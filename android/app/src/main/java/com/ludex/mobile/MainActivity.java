package com.ludex.mobile;

import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.*;
import android.content.pm.*;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.View;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.*;
import rikka.shizuku.Shizuku;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.navigation.NavigationBarView;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends AppCompatActivity {
    private enum Tab { LIBRARY, ANDROID, EMULATORS, SYNC }
    private LudexDb db;
    private RecyclerView list;
    private TextInputEditText search;
    private GameAdapter gameAdapter;
    private EmulatorAdapter emulatorAdapter;
    private View syncPanel, emptyState;
    private TextView summary, syncInfo, steamSyncInfo;
    private Tab tab=Tab.LIBRARY;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private String pendingEmulatorPackage;
    private String pendingEdenPackage;
    private File pendingUpdateApk;
    private boolean pendingGameNativeShizuku;
    private boolean pendingEdenShizuku;
    private boolean shizukuBinderReady;
    private static final int SHIZUKU_GAMENATIVE_REQUEST=4201;
    private static final int SHIZUKU_EDEN_REQUEST=4202;

    private final Shizuku.OnBinderReceivedListener shizukuBinderReceivedListener=()->{
        shizukuBinderReady=true;
        if(emulatorAdapter!=null){
            emulatorAdapter.notifyDataSetChanged();
        }
        if(pendingGameNativeShizuku){
            pendingGameNativeShizuku=false;
            try{
                if(Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED){
                    syncGameNativeWithShizuku(true);
                }else if(!Shizuku.shouldShowRequestPermissionRationale()){
                    Shizuku.requestPermission(SHIZUKU_GAMENATIVE_REQUEST);
                }
            }catch(Exception e){
                toast("Shizuku conectado, mas a autorização falhou: "+e.getMessage());
            }
        }
        if(pendingEdenShizuku){
            pendingEdenShizuku=false;
            try{
                if(Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED){
                    syncEdenWithShizuku(true);
                }else if(!Shizuku.shouldShowRequestPermissionRationale()){
                    Shizuku.requestPermission(SHIZUKU_EDEN_REQUEST);
                }
            }catch(Exception e){
                toast("Shizuku conectado, mas o Eden falhou: "+e.getMessage());
            }
        }
    };

    private final Shizuku.OnBinderDeadListener shizukuBinderDeadListener=()->{
        shizukuBinderReady=false;
        if(emulatorAdapter!=null)emulatorAdapter.notifyDataSetChanged();
    };

    private final Shizuku.OnRequestPermissionResultListener shizukuPermissionListener=(requestCode,grantResult)->{
        if(requestCode==SHIZUKU_GAMENATIVE_REQUEST){
            pendingGameNativeShizuku=false;
            if(grantResult==PackageManager.PERMISSION_GRANTED)syncGameNativeWithShizuku(true);
            else runOnUiThread(()->toast("Permissão do Shizuku negada"));
            return;
        }
        if(requestCode==SHIZUKU_EDEN_REQUEST){
            pendingEdenShizuku=false;
            if(grantResult==PackageManager.PERMISSION_GRANTED)syncEdenWithShizuku(true);
            else runOnUiThread(()->toast("Permissão do Shizuku negada"));
        }
    };

    private final ActivityResultLauncher<Uri> gameNativeTreeLauncher=registerForActivityResult(
        new ActivityResultContracts.OpenDocumentTree(), uri -> {
            if(uri==null)return;
            try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}
            catch(Exception ignored){}
            db.setSetting("gamenative.tree_uri",uri.toString());
            syncGameNativeLibrary(uri,true);
        });

    private final ActivityResultLauncher<Uri> edenTreeLauncher=registerForActivityResult(
        new ActivityResultContracts.OpenDocumentTree(), uri -> {
            String pkg=pendingEdenPackage;
            pendingEdenPackage=null;
            if(uri==null||pkg==null)return;
            try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}
            catch(Exception ignored){}
            db.setSetting("eden.tree_uri."+pkg,uri.toString());
            syncEdenLibrary(uri,pkg,true);
        });

    private final ActivityResultLauncher<Intent> importLauncher=registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(), r -> {
            if(r.getResultCode()!=RESULT_OK||r.getData()==null||r.getData().getData()==null)return;
            Uri uri=r.getData().getData();
            io.execute(() -> {
                try(InputStream in=getContentResolver().openInputStream(uri)){
                    String json=new String(readAll(in), StandardCharsets.UTF_8);
                    LudexDb.SyncResult result=db.importBundle(new JSONObject(json));
                    runOnUiThread(() -> { toast("Sync: "+result.inserted+" novos · "+result.updated+" atualizados"); refreshAsync(false); });
                }catch(Exception e){runOnUiThread(() -> toast("Falha ao importar: "+e.getMessage()));}
            });
        });

    private final ActivityResultLauncher<Intent> exportLauncher=registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(), r -> {
            if(r.getResultCode()!=RESULT_OK||r.getData()==null||r.getData().getData()==null)return;
            Uri uri=r.getData().getData();
            io.execute(() -> {
                try(OutputStream out=getContentResolver().openOutputStream(uri)){
                    out.write(db.exportBundle().toString(2).getBytes(StandardCharsets.UTF_8));
                    runOnUiThread(() -> toast("Biblioteca exportada para o PC"));
                }catch(Exception e){runOnUiThread(() -> toast("Falha ao exportar: "+e.getMessage()));}
            });
        });

    private final ActivityResultLauncher<Intent> romLauncher=registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(), r -> {
            if(r.getResultCode()==RESULT_OK&&r.getData()!=null&&r.getData().getData()!=null){
                openRom(r.getData().getData(),pendingEmulatorPackage);
            }
            pendingEmulatorPackage=null;
        });

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceivedListener);
        Shizuku.addBinderDeadListener(shizukuBinderDeadListener);
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener);
        shizukuBinderReady=Shizuku.pingBinder();
        db=new LudexDb(this);
        bindViews();
        setupUi();
        refreshAsync(true);
    }

    @Override protected void onResume(){
        super.onResume();
        shizukuBinderReady=Shizuku.pingBinder();
        if(emulatorAdapter!=null)emulatorAdapter.notifyDataSetChanged();
        if(pendingUpdateApk!=null && Build.VERSION.SDK_INT>=26 && getPackageManager().canRequestPackageInstalls()){
            File apk=pendingUpdateApk; pendingUpdateApk=null; installDownloadedUpdate(apk);
        }
    }

    @Override protected void onDestroy(){
        Shizuku.removeBinderReceivedListener(shizukuBinderReceivedListener);
        Shizuku.removeBinderDeadListener(shizukuBinderDeadListener);
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener);
        super.onDestroy();io.shutdownNow();
    }

    private void bindViews(){
        list=findViewById(R.id.game_list);
        search=findViewById(R.id.search);
        summary=findViewById(R.id.summary);
        syncPanel=findViewById(R.id.sync_panel);
        syncInfo=findViewById(R.id.sync_info);
        steamSyncInfo=findViewById(R.id.steam_sync_info);
        emptyState=findViewById(R.id.empty_state);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setItemAnimator(new DefaultItemAnimator());
        gameAdapter=new GameAdapter(this::showGame,this::launchGame);
        emulatorAdapter=new EmulatorAdapter(this::launchEmulator,this::pickRomFor,this::connectEmulatorLibrary);
        list.setAdapter(gameAdapter);
    }

    private void setupUi(){
        findViewById(R.id.refresh).setOnClickListener(v->refreshAsync(true));
        findViewById(R.id.usage_access).setOnClickListener(v->openUsageAccess());
        findViewById(R.id.sync_import).setOnClickListener(v->pickImport());
        findViewById(R.id.sync_export).setOnClickListener(v->pickExport());
        findViewById(R.id.sync_usage).setOnClickListener(v->importUsageAsync(true));
        findViewById(R.id.steam_config).setOnClickListener(v->showSteamConfig());
        findViewById(R.id.steam_sync).setOnClickListener(v->syncSteamPlaytime(true));
        findViewById(R.id.mobile_update).setOnClickListener(v->checkForAndroidUpdate());

        search.addTextChangedListener(new SimpleTextWatcher(s->renderCurrent()));
        NavigationBarView nav=findViewById(R.id.bottom_nav);
        nav.setOnItemSelectedListener(item->{
            int id=item.getItemId();
            if(id==R.id.nav_library)tab=Tab.LIBRARY;
            else if(id==R.id.nav_android)tab=Tab.ANDROID;
            else if(id==R.id.nav_emulators)tab=Tab.EMULATORS;
            else tab=Tab.SYNC;
            renderCurrent();
            return true;
        });
    }

    private void refreshAsync(boolean importUsage){
        findViewById(R.id.refresh).setEnabled(false);
        io.execute(()->{
            try{
                scanInstalledGames();
                if(importUsage&&hasUsageAccess()) importUsage();
                String gameNativeUri=db.getSetting("gamenative.tree_uri","");
                if(!gameNativeUri.isEmpty()){
                    try{
                        List<GameNativeScanner.ImportedGame> found=GameNativeScanner.scan(this,Uri.parse(gameNativeUri));
                        db.syncGameNativeGames(found);
                    }catch(Exception ignored){}
                }
                final List<LudexDb.GameRow> games=db.listGames();
                final List<EmulatorRegistry.Emulator> emulators=EmulatorRegistry.detect(this);
                runOnUiThread(()->{
                    gameAdapter.setAll(games);
                    emulatorAdapter.setAll(emulators);
                    updateSummary(games);
                    renderCurrent();
                    findViewById(R.id.refresh).setEnabled(true);
                });
            }catch(Exception e){
                runOnUiThread(()->{findViewById(R.id.refresh).setEnabled(true);toast("Atualização falhou: "+e.getMessage());});
            }
        });
    }

    private void renderCurrent(){
        boolean sync=tab==Tab.SYNC;
        syncPanel.setVisibility(sync?View.VISIBLE:View.GONE);
        list.setVisibility(sync?View.GONE:View.VISIBLE);
        search.setVisibility((tab==Tab.LIBRARY||tab==Tab.ANDROID)?View.VISIBLE:View.GONE);
        emptyState.setVisibility(View.GONE);
        if(sync){
            syncInfo.setText((hasUsageAccess()?"Acesso de uso concedido. ":"Acesso de uso pendente. ")+
                "O Android não expõe o histórico oficial do Google Play; o Ludex usa Usage Access para medir o tempo em primeiro plano dos jogos neste aparelho e sincroniza esse tempo com o PC.");
            updateSteamSyncInfo();
            return;
        }
        if(tab==Tab.EMULATORS){
            list.setAdapter(emulatorAdapter);
            emptyState.setVisibility(emulatorAdapter.getItemCount()==0?View.VISIBLE:View.GONE);
            return;
        }
        list.setAdapter(gameAdapter);
        String q=search.getText()==null?"":search.getText().toString();
        gameAdapter.filter(q,tab==Tab.ANDROID);
        emptyState.setVisibility(gameAdapter.getItemCount()==0?View.VISIBLE:View.GONE);
    }

    private void updateSummary(List<LudexDb.GameRow> games){
        int android=0,remote=0; long seconds=0;
        for(LudexDb.GameRow g:games){if("android".equals(g.source))android++;else remote++;seconds+=g.seconds;}
        summary.setText(games.size()+" jogos · "+android+" Android · "+remote+" do PC · "+format(seconds));
    }

    private void scanInstalledGames(){
        PackageManager pm=getPackageManager();
        Intent launcher=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps=pm.queryIntentActivities(launcher,PackageManager.MATCH_ALL);
        db.markAllAndroidUninstalled();
        HashSet<String> seen=new HashSet<>();
        for(ResolveInfo r:apps){
            String pkg=r.activityInfo.packageName;
            if(pkg.equals(getPackageName())||!seen.add(pkg)||EmulatorRegistry.isKnown(pkg))continue;
            ApplicationInfo ai=r.activityInfo.applicationInfo;
            if(Build.VERSION.SDK_INT>=26&&ai.category!=ApplicationInfo.CATEGORY_GAME)continue;
            String title=r.loadLabel(pm).toString();
            db.upsertAndroidGame(pkg,title,true);
        }
    }

    private boolean hasUsageAccess(){
        AppOpsManager ops=(AppOpsManager)getSystemService(APP_OPS_SERVICE);
        int mode;
        if(Build.VERSION.SDK_INT>=29) mode=ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,android.os.Process.myUid(),getPackageName());
        else mode=ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,android.os.Process.myUid(),getPackageName());
        return mode==AppOpsManager.MODE_ALLOWED;
    }

    private void openUsageAccess(){
        try{startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS,Uri.parse("package:"+getPackageName())));}
        catch(Exception e){startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));}
    }

    private void importUsageAsync(boolean notify){
        if(!hasUsageAccess()){openUsageAccess(); if(notify)toast("Conceda Acesso de uso e volte ao Ludex"); return;}
        io.execute(()->{
            try{int changed=importUsage();runOnUiThread(()->{if(notify)toast(changed+" jogos com uso atualizado");refreshAsync(false);});}
            catch(Exception e){runOnUiThread(()->toast("Falha ao ler uso: "+e.getMessage()));}
        });
    }

    private Map<String,Long> readHistoricalUsage(){
        UsageStatsManager manager=(UsageStatsManager)getSystemService(USAGE_STATS_SERVICE);
        long end=System.currentTimeMillis();
        long start=Math.max(0,end-(3L*365*24*60*60*1000));
        HashMap<String,Long> best=new HashMap<>();
        int[] intervals={UsageStatsManager.INTERVAL_YEARLY,UsageStatsManager.INTERVAL_MONTHLY,UsageStatsManager.INTERVAL_WEEKLY,UsageStatsManager.INTERVAL_DAILY};
        for(int interval:intervals){
            HashMap<String,Long> totals=new HashMap<>();
            List<UsageStats> rows=manager.queryUsageStats(interval,start,end);
            if(rows==null)continue;
            for(UsageStats s:rows){
                if(s==null||s.getPackageName()==null)continue;
                long sec=Math.max(0,s.getTotalTimeInForeground()/1000L);
                totals.put(s.getPackageName(),totals.getOrDefault(s.getPackageName(),0L)+sec);
            }
            for(Map.Entry<String,Long> x:totals.entrySet())best.put(x.getKey(),Math.max(best.getOrDefault(x.getKey(),0L),x.getValue()));
        }
        Map<String,UsageStats> aggregate=manager.queryAndAggregateUsageStats(start,end);
        if(aggregate!=null)for(Map.Entry<String,UsageStats> x:aggregate.entrySet()){
            long sec=Math.max(0,x.getValue().getTotalTimeInForeground()/1000L);
            best.put(x.getKey(),Math.max(best.getOrDefault(x.getKey(),0L),sec));
        }
        return best;
    }

    private int importUsage(){
        Map<String,Long> stats=readHistoricalUsage();
        PackageManager pm=getPackageManager();
        int changed=0;
        for(Map.Entry<String,Long> x:stats.entrySet()){
            String pkg=x.getKey();
            if(!db.hasAndroidGame(pkg)){
                try{
                    ApplicationInfo ai=pm.getApplicationInfo(pkg,0);
                    if(Build.VERSION.SDK_INT>=26&&ai.category==ApplicationInfo.CATEGORY_GAME){
                        db.upsertAndroidGame(pkg,pm.getApplicationLabel(ai).toString(),true);
                    }else continue;
                }catch(Exception ignored){continue;}
            }
            long raw=Math.max(0,x.getValue());
            db.setSetting("usage.raw."+pkg,Long.toString(raw));
            long baselineTotal=db.getSettingLong("usage.baseline.total."+pkg,-1);
            long baselineRaw=db.getSettingLong("usage.baseline.raw."+pkg,-1);
            long sec=raw;
            if(baselineTotal>=0&&baselineRaw>=0)sec=baselineTotal+Math.max(0,raw-baselineRaw);
            if(sec>0){db.setImportedPlaytime("android:"+pkg,"android-usage",sec);changed++;}
        }
        db.setSetting("usage.last_import_ms",Long.toString(System.currentTimeMillis()));
        return changed;
    }

    private void showGame(LudexDb.GameRow g){
        new MaterialAlertDialogBuilder(this)
            .setTitle(g.title)
            .setMessage(g.platform+" · "+providerLabel(g.source)+"\n"+format(g.seconds)+" registrados\nStatus: "+g.status+
                (g.packageName!=null?"\nApp: "+g.packageName:""))
            .setNeutralButton(g.favorite?"Remover favorito":"Favoritar",(d,w)->{db.setFavorite(g.id,!g.favorite);refreshAsync(false);})
            .setNegativeButton("Mais",(d,w)->showGameActions(g))
            .setPositiveButton(g.installed&&(g.packageName!=null||g.gameNative||g.eden)?"JOGAR":"Fechar",(d,w)->{if(g.installed&&(g.packageName!=null||g.gameNative||g.eden))launchGame(g);})
            .show();
    }

    private void showGameActions(LudexDb.GameRow g){
        ArrayList<String> actions=new ArrayList<>();
        actions.add("Alterar status");
        if("android".equals(g.source)&&g.packageName!=null)actions.add("Ajustar horas totais");
        new MaterialAlertDialogBuilder(this).setTitle(g.title).setItems(actions.toArray(new String[0]),(d,which)->{
            if(which==0)showStatusPicker(g);
            else calibratePlaytime(g);
        }).show();
    }

    private void showStatusPicker(LudexDb.GameRow g){
        String[] statuses={"Quero jogar","Jogando","Pausado","Concluído","100%","Abandonado"};
        new MaterialAlertDialogBuilder(this).setTitle("Status").setItems(statuses,(d,which)->{
            db.setStatus(g.id,statuses[which]);refreshAsync(false);
        }).show();
    }

    private void calibratePlaytime(LudexDb.GameRow g){
        EditText input=new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setHint("Ex.: 130");
        input.setText(String.format(Locale.ROOT,"%.1f",g.seconds/3600.0));
        int pad=(int)(20*getResources().getDisplayMetrics().density);
        FrameLayout wrap=new FrameLayout(this);wrap.setPadding(pad,0,pad,0);wrap.addView(input);
        new MaterialAlertDialogBuilder(this)
            .setTitle("Ajustar horas de "+g.title)
            .setMessage("Use o total que aparece na Play Store/Play Games. O Ludex salva esse valor como baseline e acrescenta apenas o uso novo detectado pelo Android.")
            .setView(wrap)
            .setNegativeButton("Cancelar",null)
            .setPositiveButton("Salvar",(d,w)->{
                try{
                    double hours=Double.parseDouble(input.getText().toString().replace(',','.'));
                    long total=Math.max(0,Math.round(hours*3600.0));
                    long raw=db.getSettingLong("usage.raw."+g.packageName,0);
                    db.setSetting("usage.baseline.total."+g.packageName,Long.toString(total));
                    db.setSetting("usage.baseline.raw."+g.packageName,Long.toString(raw));
                    db.replaceImportedPlaytime(g.id,"android-usage",total);
                    refreshAsync(false);
                    toast("Baseline salvo: "+format(total));
                }catch(Exception e){toast("Informe um número válido de horas");}
            }).show();
    }

    private void launchGame(LudexDb.GameRow g){
        if(g.gameNative){
            LudexDb.GameNativeLaunch launch=db.getGameNativeLaunch(g.id);
            if(launch!=null){
                try{
                    int appId=Integer.parseInt(launch.externalId);
                    String source=launch.provider==null?"STEAM":launch.provider.toUpperCase(Locale.ROOT);
                    Intent direct=new Intent("app.gamenative.LAUNCH_GAME")
                        .setClassName(GameNativeScanner.PACKAGE,"app.gamenative.MainActivity")
                        .putExtra("app_id",appId)
                        .putExtra("game_source",source)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    startActivity(direct);
                    return;
                }catch(Exception ex){
                    toast("Falha no lançamento direto; abrindo GameNative");
                }
            }
        }
        if(g.eden){
            LudexDb.EdenLaunch launch=db.getEdenLaunch(g.id);
            if(launch!=null){
                try{
                    Intent direct=new Intent(Intent.ACTION_VIEW)
                        .setData(Uri.parse(launch.uri))
                        .setClassName(launch.packageName,EdenShortcutScanner.ACTIVITY)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    startActivity(direct);
                    return;
                }catch(Exception ex){
                    toast("Falha ao abrir o jogo no Eden");
                }
            }
        }
        String pkg=g.packageName;
        if(pkg==null&&g.gameNative)pkg=GameNativeScanner.PACKAGE;
        if(pkg==null&&g.eden){
            LudexDb.EdenLaunch launch=db.getEdenLaunch(g.id);
            if(launch!=null)pkg=launch.packageName;
        }
        if(pkg==null)return;
        Intent i=getPackageManager().getLaunchIntentForPackage(pkg);
        if(i==null){toast("Este jogo não expõe uma activity de inicialização");return;}
        startActivity(i);
    }

    private void launchEmulator(EmulatorRegistry.Emulator e){
        Intent i=getPackageManager().getLaunchIntentForPackage(e.packageName);
        if(i!=null)startActivity(i);else toast("Não foi possível iniciar "+e.name);
    }

    private void connectEmulatorLibrary(EmulatorRegistry.Emulator e){
        boolean gameNative=GameNativeScanner.PACKAGE.equals(e.packageName);
        boolean eden=EdenShortcutScanner.STANDARD_PACKAGE.equals(e.packageName)||EdenShortcutScanner.OPTIMIZED_PACKAGE.equals(e.packageName);
        if(!gameNative&&!eden)return;

        if(eden){
            String saved=db.getSetting("eden.tree_uri."+e.packageName,"");
            if(saved.isEmpty()){
                pendingEdenPackage=e.packageName;
                toast("Selecione a pasta onde ficam seus jogos de Nintendo Switch");
                edenTreeLauncher.launch(null);
            }else{
                syncEdenLibrary(Uri.parse(saved),e.packageName,true);
            }
            return;
        }

        shizukuBinderReady=Shizuku.pingBinder();
        if(shizukuBinderReady){
            try{
                if(Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED){
                    syncGameNativeWithShizuku(true);
                    return;
                }
                if(!Shizuku.shouldShowRequestPermissionRationale()){
                    pendingGameNativeShizuku=true;
                    Shizuku.requestPermission(SHIZUKU_GAMENATIVE_REQUEST);
                    toast("Autorize o Ludex no Shizuku");
                    return;
                }
            }catch(Exception ex){
                toast("Shizuku conectado, mas falhou: "+ex.getMessage());
            }
        }else{
            pendingGameNativeShizuku=true;
            toast("Aguardando o binder do Shizuku…");
            new Handler(Looper.getMainLooper()).postDelayed(()->{
                if(!pendingGameNativeShizuku)return;
                if(Shizuku.pingBinder()){
                    shizukuBinderReady=true;
                    pendingGameNativeShizuku=false;
                    try{
                        if(Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED)syncGameNativeWithShizuku(true);
                        else Shizuku.requestPermission(SHIZUKU_GAMENATIVE_REQUEST);
                    }catch(Exception ex){toast("Shizuku indisponível: "+ex.getMessage());}
                }else{
                    pendingGameNativeShizuku=false;
                    showGameNativeFallback();
                }
            },2500);
            return;
        }
        showGameNativeFallback();
    }

    private void showGameNativeFallback(){
        String saved=db.getSetting("gamenative.tree_uri","");
        new MaterialAlertDialogBuilder(this)
            .setTitle("Biblioteca do GameNative")
            .setMessage("O serviço do Shizuku está ativo, mas o Ludex ainda não recebeu o binder. Feche o Ludex completamente e abra de novo com o Shizuku já iniciado. Você também pode usar a pasta pública do GameNative.")
            .setNegativeButton("Cancelar",null)
            .setNeutralButton("Escolher pasta",(d,w)->{
                if(saved.isEmpty())gameNativeTreeLauncher.launch(null);
                else syncGameNativeLibrary(Uri.parse(saved),true);
            })
            .setPositiveButton("Tentar novamente",(d,w)->{
                shizukuBinderReady=Shizuku.pingBinder();
                if(shizukuBinderReady){
                    try{
                        if(Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED)syncGameNativeWithShizuku(true);
                        else Shizuku.requestPermission(SHIZUKU_GAMENATIVE_REQUEST);
                    }catch(Exception ex){toast("Shizuku indisponível: "+ex.getMessage());}
                }else toast("Binder do Shizuku ainda não chegou ao Ludex");
            }).show();
    }

    private void syncEdenWithShizuku(boolean notify){
        String pkg=EdenShortcutScanner.STANDARD_PACKAGE;
        String saved=db.getSetting("eden.tree_uri."+pkg,"");
        if(saved.isEmpty()){
            runOnUiThread(()->toast("O Eden agora usa importação pela pasta de ROMs, como o Beacon. Abra Emuladores > Eden > Conectar biblioteca."));
            return;
        }
        syncEdenLibrary(Uri.parse(saved),pkg,notify);
    }

    private void syncEdenLibrary(Uri uri,String packageName,boolean notify){
        if(notify)toast("Lendo a biblioteca do Eden…");
        io.execute(()->{
            try{
                List<EdenLibraryScanner.ImportedGame> found=EdenLibraryScanner.scan(this,uri,packageName);
                int count=db.syncEdenGames(found);
                runOnUiThread(()->{
                    if(count>0)toast(count+" jogos do Eden encontrados");
                    else toast("Nenhuma ROM .xci/.nsp/.nca/.nro encontrada nessa pasta");
                    refreshAsync(false);
                });
            }catch(Exception e){
                runOnUiThread(()->{
                    db.setSetting("eden.tree_uri."+packageName,"");
                    toast("Não consegui ler a pasta do Eden: "+e.getMessage());
                });
            }
        });
    }

    private void syncGameNativeWithShizuku(boolean notify){
        if(notify)toast("Procurando atalhos do GameNative…");
        io.execute(()->{
            try{
                List<GameNativeShortcutScanner.ShortcutGame> shortcuts=GameNativeShortcutScanner.scan();
                if(!shortcuts.isEmpty()){
                    ArrayList<GameNativeScanner.ImportedGame> found=new ArrayList<>();
                    for(GameNativeShortcutScanner.ShortcutGame s:shortcuts)found.add(s.asImportedGame());
                    int count=db.syncGameNativeGames(found);
                    runOnUiThread(()->{
                        toast(count+" atalhos do GameNative importados");
                        refreshAsync(false);
                    });
                    return;
                }

                ShizukuGameNativeScanner.Result result=ShizukuGameNativeScanner.scan();
                int count=db.syncGameNativeGames(result.games);
                runOnUiThread(()->{
                    if(count>0){
                        toast(count+" jogos do GameNative encontrados no armazenamento");
                        refreshAsync(false);
                    }else{
                        toast("Nenhum atalho do GameNative foi encontrado. Crie os atalhos dos jogos no GameNative e tente novamente.");
                    }
                });
            }catch(Exception e){
                runOnUiThread(()->toast("Falha ao ler atalhos do GameNative: "+e.getMessage()));
            }
        });
    }

    private void syncGameNativeLibrary(Uri uri,boolean notify){
        if(notify)toast("Lendo jogos instalados no GameNative…");
        io.execute(()->{
            try{
                List<GameNativeScanner.ImportedGame> found=GameNativeScanner.scan(this,uri);
                int count=db.syncGameNativeGames(found);
                runOnUiThread(()->{
                    if(notify)toast(count+" jogos do GameNative encontrados");
                    refreshAsync(false);
                });
            }catch(Exception e){
                runOnUiThread(()->{
                    db.setSetting("gamenative.tree_uri","");
                    toast("Não consegui ler a pasta GameNative: "+e.getMessage());
                });
            }
        });
    }

    private void pickRomFor(EmulatorRegistry.Emulator e){
        pendingEmulatorPackage=e.packageName;
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
        romLauncher.launch(i);
    }

    private void openRom(Uri uri,String pkg){
        try{
            getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }catch(Exception ignored){}
        String type=getContentResolver().getType(uri);
        Intent view=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,type==null?"application/octet-stream":type)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if(pkg!=null)view.setPackage(pkg);
        try{startActivity(view);}
        catch(ActivityNotFoundException ex){
            view.setPackage(null);
            try{startActivity(Intent.createChooser(view,"Abrir ROM com emulador"));}
            catch(Exception e){toast("O emulador não declarou suporte a este tipo de ROM");}
        }
    }

    private void updateSteamSyncInfo(){
        String steamId=db.getSetting("steam.id64","");
        boolean hasKey=!SecretStore.get(this,"steam.api_key").isEmpty();
        long last=db.getSettingLong("steam.last_sync_ms",0);
        if(steamId.isEmpty()||!hasKey){
            steamSyncInfo.setText("Não configurado. Informe SteamID64 e API Key para importar o playtime dos jogos GameNative/Steam.");
            return;
        }
        String suffix=last>0?" · última sync "+new java.text.SimpleDateFormat("dd/MM HH:mm",Locale.getDefault()).format(new java.util.Date(last)):"";
        steamSyncInfo.setText("Steam configurada · "+steamId+suffix);
    }

    private void showSteamConfig(){
        int pad=(int)(20*getResources().getDisplayMetrics().density);
        LinearLayout wrap=new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(pad,8,pad,0);

        EditText steamId=new EditText(this);
        steamId.setSingleLine(true);
        steamId.setHint("SteamID64");
        steamId.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        steamId.setText(db.getSetting("steam.id64",""));
        wrap.addView(steamId,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT));

        boolean alreadyHasKey=!SecretStore.get(this,"steam.api_key").isEmpty();
        EditText apiKey=new EditText(this);
        apiKey.setSingleLine(true);
        apiKey.setHint(alreadyHasKey?"API Key (vazio = manter a atual)":"Steam Web API Key");
        apiKey.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        wrap.addView(apiKey,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView keyLink=new TextView(this);
        keyLink.setText("Obter API Key na Steam ↗");
        keyLink.setTextColor(getColor(R.color.ludex_accent));
        keyLink.setTextSize(14);
        keyLink.setPadding(0,pad/2,0,pad/2);
        keyLink.setOnClickListener(v->{
            try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://steamcommunity.com/dev/apikey")));}
            catch(Exception e){toast("Não foi possível abrir a página da Steam");}
        });
        wrap.addView(keyLink,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView note=new TextView(this);
        note.setText("A API Key fica criptografada pelo Android Keystore e não entra no arquivo de sync/export.");
        note.setTextColor(getColor(R.color.ludex_muted));
        note.setTextSize(12);
        wrap.addView(note,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT));

        new MaterialAlertDialogBuilder(this)
            .setTitle("Steam no Android")
            .setMessage("O Ludex usa o SteamID64 + GetOwnedGames para associar playtime pelo AppID dos atalhos do GameNative.")
            .setView(wrap)
            .setNegativeButton("Cancelar",null)
            .setPositiveButton("Salvar",(d,w)->{
                String id=steamId.getText()==null?"":steamId.getText().toString().trim();
                String key=apiKey.getText()==null?"":apiKey.getText().toString().trim();
                if(!id.matches("\\d{16,20}")){toast("SteamID64 inválido");return;}
                if(key.isEmpty()&&!alreadyHasKey){toast("Informe a Steam Web API Key");return;}
                try{
                    db.setSetting("steam.id64",id);
                    if(!key.isEmpty())SecretStore.put(this,"steam.api_key",key);
                    updateSteamSyncInfo();
                    toast("Steam configurada");
                    syncSteamPlaytime(true);
                }catch(Exception e){toast("Não foi possível proteger a API Key: "+e.getMessage());}
            }).show();
    }

    private void syncSteamPlaytime(boolean notify){
        String steamId=db.getSetting("steam.id64","");
        String key=SecretStore.get(this,"steam.api_key");
        if(steamId.isEmpty()||key.isEmpty()){
            if(notify)showSteamConfig();
            return;
        }
        if(notify)toast("Sincronizando horas da Steam…");
        io.execute(()->{
            try{
                Map<String,Long> owned=SteamPlaytimeClient.getOwnedPlaytime(key,steamId);
                List<LudexDb.GameNativeSteamLink> links=db.listGameNativeSteamLinks();
                int matched=0;
                for(LudexDb.GameNativeSteamLink link:links){
                    Long sec=owned.get(link.appId);
                    if(sec==null)continue;
                    db.replaceImportedPlaytime(link.gameId,"steam",sec);
                    matched++;
                }
                db.setSetting("steam.last_sync_ms",Long.toString(System.currentTimeMillis()));
                final int count=matched;
                final int total=owned.size();
                runOnUiThread(()->{
                    updateSteamSyncInfo();
                    if(notify){
                        if(total==0)toast("A Steam não retornou jogos. Confira SteamID64 e privacidade da conta.");
                        else toast(count+" jogos do GameNative receberam playtime da Steam");
                    }
                    refreshAsync(false);
                });
            }catch(Exception e){
                runOnUiThread(()->toast("Falha na Steam: "+e.getMessage()));
            }
        });
    }

    private void checkForAndroidUpdate(){
        View button=findViewById(R.id.mobile_update);button.setEnabled(false);toast("Verificando atualização…");
        io.execute(()->{
            try{
                AndroidUpdater.UpdateCheck check=AndroidUpdater.check();
                runOnUiThread(()->{
                    button.setEnabled(true);
                    if(check.update!=null){
                        String note=BuildConfig.DEBUG
                            ?"\n\nEsta instalação é DEBUG. A primeira migração para uma release assinada pode exigir desinstalar a build debug. Exporte a biblioteca antes para não perder dados."
                            :"";
                        new MaterialAlertDialogBuilder(this)
                            .setTitle("Ludex Android "+check.update.version)
                            .setMessage("Versão instalada: "+BuildConfig.VERSION_NAME+note+"\n\nBaixar, verificar SHA-256 e abrir o instalador do Android?")
                            .setNegativeButton("Agora não",null)
                            .setPositiveButton("Atualizar",(d,w)->downloadAndroidUpdate(check.update))
                            .show();
                        return;
                    }
                    if(check.releaseMissing){
                        new MaterialAlertDialogBuilder(this)
                            .setTitle("Atualização ainda não publicada")
                            .setMessage("O código no main já está na versão "+check.codeVersion+", mas ainda não existe uma release Android assinada com APK + SHA-256. O Ludex não vai fingir que está atualizado quando o pacote simplesmente não foi publicado.")
                            .setPositiveButton("Entendi",null)
                            .show();
                        return;
                    }
                    toast("Você já está na versão Android mais recente ("+BuildConfig.VERSION_NAME+")");
                });
            }catch(Exception e){runOnUiThread(()->{button.setEnabled(true);toast("Não foi possível verificar atualizações: "+e.getMessage());});}
        });
    }

    private void downloadAndroidUpdate(AndroidUpdater.UpdateInfo update){
        toast("Baixando Ludex "+update.version+"…");
        io.execute(()->{
            try{
                File apk=AndroidUpdater.downloadAndVerify(this,update);
                runOnUiThread(()->requestInstallUpdate(apk));
            }catch(Exception e){runOnUiThread(()->toast("Falha ao baixar atualização: "+e.getMessage()));}
        });
    }

    private void requestInstallUpdate(File apk){
        if(Build.VERSION.SDK_INT>=26 && !getPackageManager().canRequestPackageInstalls()){
            pendingUpdateApk=apk;
            toast("Permita que o Ludex instale atualizações e volte ao app");
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName())));
            return;
        }
        installDownloadedUpdate(apk);
    }

    private void installDownloadedUpdate(File apk){
        try{
            Uri uri=androidx.core.content.FileProvider.getUriForFile(this,getPackageName()+".fileprovider",apk);
            Intent install=new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri,"application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(install);
        }catch(Exception e){toast("Não foi possível abrir o instalador: "+e.getMessage());}
    }

    private void pickImport(){
        importLauncher.launch(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE));
    }
    private void pickExport(){
        exportLauncher.launch(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").putExtra(Intent.EXTRA_TITLE,"ludex-sync.json"));
    }

    private void bindGameArtwork(LudexDb.GameRow g,ImageView view){
        view.setTag(g.id);
        String iconPkg=g.packageName!=null?g.packageName:(g.gameNative?GameNativeScanner.PACKAGE:(g.eden?(db.getEdenLaunch(g.id)==null?null:db.getEdenLaunch(g.id).packageName):null));
        Drawable fallback=iconPkg==null?null:appIcon(iconPkg);
        view.setImageDrawable(fallback);
        view.setVisibility(fallback==null?View.INVISIBLE:View.VISIBLE);

        if(!g.gameNative)return;
        LudexDb.GameNativeLaunch launch=db.getGameNativeLaunch(g.id);
        if(launch==null)return;

        final String expectedTag=g.id;
        io.execute(()->{
            android.graphics.Bitmap bmp=GameArtworkLoader.load(this,launch.provider,launch.externalId);
            if(bmp==null)return;
            runOnUiThread(()->{
                Object tag=view.getTag();
                if(tag!=null&&expectedTag.equals(tag.toString())){
                    view.setImageBitmap(bmp);
                    view.setVisibility(View.VISIBLE);
                }
            });
        });
    }

    Drawable appIcon(String pkg){
        try{return getPackageManager().getApplicationIcon(pkg);}catch(Exception e){return null;}
    }

    private String providerLabel(String source){
        if(source==null)return "Ludex";
        switch(source){case "steam":return "Steam";case "epic":return "Epic";case "gog":return "GOG";case "android":return "Android";case "xbox":return "Xbox";default:return source;}
    }
    static String format(long sec){long h=sec/3600,m=(sec%3600)/60;return h>0?h+"h "+m+"min":m+"min";}
    private void toast(String s){Snackbar.make(findViewById(R.id.root),s,Snackbar.LENGTH_LONG).show();}
    private static byte[] readAll(InputStream in)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toByteArray();}

    interface TextChange{void onChange(String s);}
    static final class SimpleTextWatcher implements android.text.TextWatcher{
        private final TextChange c;SimpleTextWatcher(TextChange c){this.c=c;}
        public void beforeTextChanged(CharSequence s,int st,int count,int after){}
        public void onTextChanged(CharSequence s,int st,int before,int count){c.onChange(s.toString());}
        public void afterTextChanged(android.text.Editable e){}
    }

    final class GameAdapter extends RecyclerView.Adapter<GameAdapter.Holder>{
        interface GameClick{void click(LudexDb.GameRow g);}
        private final ArrayList<LudexDb.GameRow> all=new ArrayList<>(),shown=new ArrayList<>();
        private final GameClick click,launch;
        GameAdapter(GameClick click,GameClick launch){this.click=click;this.launch=launch;}
        void setAll(List<LudexDb.GameRow> x){all.clear();all.addAll(x);filter("",tab==Tab.ANDROID);}
        void filter(String query,boolean androidOnly){
            shown.clear();String q=query==null?"":query.trim().toLowerCase(Locale.ROOT);
            for(LudexDb.GameRow g:all){
                if(androidOnly&&!"android".equals(g.source))continue;
                if(!q.isEmpty()&&!g.title.toLowerCase(Locale.ROOT).contains(q))continue;
                shown.add(g);
            }
            notifyDataSetChanged();
        }
        @NonNull public Holder onCreateViewHolder(@NonNull android.view.ViewGroup p,int t){return new Holder(getLayoutInflater().inflate(R.layout.item_game,p,false));}
        public void onBindViewHolder(@NonNull Holder h,int pos){
            LudexDb.GameRow g=shown.get(pos);
            h.title.setText(g.title);h.meta.setText(g.platform+" · "+providerLabel(g.source)+(g.gameNative?" · GameNative":(g.eden?" · Eden":(g.installed?" · instalado":""))));
            h.time.setText(format(g.seconds));h.status.setText(g.favorite?"★ "+g.status:g.status);
            bindGameArtwork(g,h.icon);
            h.play.setVisibility(g.installed&&(g.packageName!=null||g.gameNative||g.eden)?View.VISIBLE:View.GONE);
            h.itemView.setOnClickListener(v->click.click(g));h.play.setOnClickListener(v->launch.click(g));
        }
        public int getItemCount(){return shown.size();}
        final class Holder extends RecyclerView.ViewHolder{
            TextView title,meta,time,status;ImageView icon;Button play;
            Holder(View v){super(v);title=v.findViewById(R.id.game_title);meta=v.findViewById(R.id.game_meta);time=v.findViewById(R.id.game_time);status=v.findViewById(R.id.game_status);icon=v.findViewById(R.id.game_icon);play=v.findViewById(R.id.game_play);}
        }
    }

    final class EmulatorAdapter extends RecyclerView.Adapter<EmulatorAdapter.Holder>{
        interface EClick{void click(EmulatorRegistry.Emulator e);}
        private final ArrayList<EmulatorRegistry.Emulator> items=new ArrayList<>();
        private final EClick launch,rom,library;
        EmulatorAdapter(EClick l,EClick r,EClick lib){launch=l;rom=r;library=lib;}
        void setAll(List<EmulatorRegistry.Emulator> x){items.clear();items.addAll(x);notifyDataSetChanged();}
        @NonNull public Holder onCreateViewHolder(@NonNull android.view.ViewGroup p,int t){return new Holder(getLayoutInflater().inflate(R.layout.item_emulator,p,false));}
        public void onBindViewHolder(@NonNull Holder h,int pos){
            EmulatorRegistry.Emulator e=items.get(pos);
            boolean gameNative=GameNativeScanner.PACKAGE.equals(e.packageName);
            boolean eden=EdenShortcutScanner.STANDARD_PACKAGE.equals(e.packageName)||EdenShortcutScanner.OPTIMIZED_PACKAGE.equals(e.packageName);
            h.name.setText(e.name);h.pkg.setText(e.packageName);h.open.setOnClickListener(v->launch.click(e));
            h.rom.setVisibility((gameNative||eden)?View.GONE:View.VISIBLE);h.rom.setOnClickListener(v->rom.click(e));
            h.library.setVisibility((gameNative||eden)?View.VISIBLE:View.GONE);
            String savedTree=db.getSetting("gamenative.tree_uri","");
            boolean binder=shizukuBinderReady||Shizuku.pingBinder();
            if(eden){
                String edenTree=db.getSetting("eden.tree_uri."+e.packageName,"");
                h.library.setText(edenTree.isEmpty()?"Conectar biblioteca":"Sincronizar jogos");
            }else h.library.setText(binder?"Importar atalhos do GameNative":(savedTree.isEmpty()?"Conectar biblioteca":"Sincronizar jogos"));
            h.library.setOnClickListener(v->library.click(e));
        }
        public int getItemCount(){return items.size();}
        final class Holder extends RecyclerView.ViewHolder{TextView name,pkg;Button open,rom,library;Holder(View v){super(v);name=v.findViewById(R.id.emu_name);pkg=v.findViewById(R.id.emu_pkg);open=v.findViewById(R.id.emu_open);rom=v.findViewById(R.id.emu_rom);library=v.findViewById(R.id.emu_library);}}
    }
}
