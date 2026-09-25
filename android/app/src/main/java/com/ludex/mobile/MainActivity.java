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
    private enum Tab { LIBRARY, ANDROID, EMULATED, EMULATORS, SYNC }
    private LudexDb db;
    private RecyclerView list;
    private TextInputEditText search;
    private GameAdapter gameAdapter;
    private EmulatorAdapter emulatorAdapter;
    private View syncPanel, emptyState;
    private TextView summary, syncInfo, steamSyncInfo, nintendoSyncInfo, artworkInfo;
    private Tab tab=Tab.LIBRARY;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private String pendingEmulatorPackage;
    private String pendingEdenPackage;
    private String pendingLibraryEmulatorPackage;
    private String activeEmulatedGameId,activeEmulatorPackage;
    private long activeEmulatedStartedAt;
    private File pendingUpdateApk;
    private boolean pendingGameNativeShizuku;
    private boolean pendingEdenShizuku;
    private boolean shizukuBinderReady;
    private boolean sortByPlaytime;
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

    private final ActivityResultLauncher<Uri> emulatorTreeLauncher=registerForActivityResult(
        new ActivityResultContracts.OpenDocumentTree(), uri -> {
            String pkg=pendingLibraryEmulatorPackage;
            pendingLibraryEmulatorPackage=null;
            if(uri==null||pkg==null)return;
            try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}
            catch(Exception ignored){}
            db.setSetting("emulator.tree_uri."+pkg,uri.toString());
            EmulatorRegistry.Emulator e=EmulatorRegistry.get(pkg);
            if(e!=null)syncGenericEmulatorLibrary(uri,e,true);
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
        if(activeEmulatedGameId!=null&&activeEmulatedStartedAt>0){
            long end=System.currentTimeMillis();
            String finishedGameId=activeEmulatedGameId;
            String finishedPackage=activeEmulatorPackage;
            db.recordSession(finishedGameId,finishedPackage,activeEmulatedStartedAt,end,"emulator");
            activeEmulatedGameId=null;activeEmulatorPackage=null;activeEmulatedStartedAt=0;
            if(EdenShortcutScanner.STANDARD_PACKAGE.equals(finishedPackage)||EdenShortcutScanner.OPTIMIZED_PACKAGE.equals(finishedPackage)){
                io.execute(()->{
                    try{
                        String programId=EdenPlaytimeScanner.readLatestProgramId();
                        if(!programId.isBlank())db.setEdenProgramId(finishedGameId,programId);
                    }catch(Exception ignored){}
                    importEdenPlaytime(finishedPackage);
                    runOnUiThread(()->refreshAsync(false));
                });
            }else refreshAsync(false);
        }
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
        nintendoSyncInfo=findViewById(R.id.nintendo_sync_info);
        artworkInfo=findViewById(R.id.artwork_info);
        emptyState=findViewById(R.id.empty_state);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setItemAnimator(new DefaultItemAnimator());
        gameAdapter=new GameAdapter(this::showGame,this::launchGame);
        emulatorAdapter=new EmulatorAdapter(this::launchEmulator,this::pickRomFor,this::connectEmulatorLibrary);
        list.setAdapter(gameAdapter);
    }

    private void setupUi(){
        sortByPlaytime="playtime".equals(db.getSetting("library.sort","title"));
        updateSortButton();
        findViewById(R.id.refresh).setOnClickListener(v->refreshAllPlaytimeAsync());
        findViewById(R.id.sort_playtime).setOnClickListener(v->{
            sortByPlaytime=!sortByPlaytime;
            db.setSetting("library.sort",sortByPlaytime?"playtime":"title");
            updateSortButton();
            renderCurrent();
        });
        findViewById(R.id.usage_access).setOnClickListener(v->openUsageAccess());
        findViewById(R.id.sync_import).setOnClickListener(v->pickImport());
        findViewById(R.id.sync_export).setOnClickListener(v->pickExport());
        findViewById(R.id.sync_usage).setOnClickListener(v->importUsageAsync(true));
        findViewById(R.id.steam_config).setOnClickListener(v->showSteamConfig());
        findViewById(R.id.steam_sync).setOnClickListener(v->syncSteamPlaytime(true));
        findViewById(R.id.nintendo_connect).setOnClickListener(v->showNintendoConnect());
        findViewById(R.id.nintendo_sync).setOnClickListener(v->syncNintendoPlaytime(true));
        findViewById(R.id.artwork_config).setOnClickListener(v->showArtworkConfig());
        findViewById(R.id.mobile_update).setOnClickListener(v->checkForAndroidUpdate());

        search.addTextChangedListener(new SimpleTextWatcher(s->renderCurrent()));
        NavigationBarView nav=findViewById(R.id.bottom_nav);
        nav.setOnItemSelectedListener(item->{
            int id=item.getItemId();
            if(id==R.id.nav_library)tab=Tab.LIBRARY;
            else if(id==R.id.nav_android)tab=Tab.ANDROID;
            else if(id==R.id.nav_emulated)tab=Tab.EMULATED;
            else if(id==R.id.nav_emulators)tab=Tab.EMULATORS;
            else tab=Tab.SYNC;
            renderCurrent();
            return true;
        });
    }

    private void updateSortButton(){
        Button sort=findViewById(R.id.sort_playtime);
        sort.setText(sortByPlaytime?"Horas ↓":"A–Z");
    }

    private void refreshAllPlaytimeAsync(){
        View refresh=findViewById(R.id.refresh);
        refresh.setEnabled(false);
        toast("Atualizando biblioteca e horas…");
        io.execute(()->{
            ArrayList<String> updated=new ArrayList<>();
            ArrayList<String> failed=new ArrayList<>();
            try{
                scanInstalledGames();

                if(hasUsageAccess()){
                    try{
                        importUsage();
                        updated.add("Android");
                    }catch(Exception e){failed.add("Android");}
                }

                boolean gameNativeScanned=false;
                if(Shizuku.pingBinder()){
                    try{
                        if(Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED){
                            List<GameNativeShortcutScanner.ShortcutGame> shortcuts=GameNativeShortcutScanner.scan();
                            if(!shortcuts.isEmpty()){
                                ArrayList<GameNativeScanner.ImportedGame> found=new ArrayList<>();
                                for(GameNativeShortcutScanner.ShortcutGame s:shortcuts)found.add(s.asImportedGame());
                                db.syncGameNativeGames(found);
                                gameNativeScanned=true;
                            }
                        }
                    }catch(Exception ignored){}
                }
                if(!gameNativeScanned){
                    String gameNativeUri=db.getSetting("gamenative.tree_uri","");
                    if(!gameNativeUri.isEmpty()){
                        try{
                            List<GameNativeScanner.ImportedGame> found=GameNativeScanner.scan(this,Uri.parse(gameNativeUri));
                            db.syncGameNativeGames(found);
                        }catch(Exception ignored){}
                    }
                }

                String steamId=db.getSetting("steam.id64","");
                String steamKey=SecretStore.get(this,"steam.api_key");
                if(!steamId.isEmpty()&&!steamKey.isEmpty()){
                    try{
                        syncSteamPlaytimeNow(steamId,steamKey);
                        updated.add("Steam");
                    }catch(Exception e){failed.add("Steam");}
                }

                if(Shizuku.pingBinder()){
                    int edenMatched=0;
                    edenMatched+=importEdenPlaytime(EdenShortcutScanner.STANDARD_PACKAGE);
                    edenMatched+=importEdenPlaytime(EdenShortcutScanner.OPTIMIZED_PACKAGE);
                    if(edenMatched>0)updated.add("Eden");
                }

                String nintendoSession=SecretStore.get(this,"nintendo.session_token");
                if(!nintendoSession.isEmpty()){
                    try{
                        syncNintendoPlaytimeNow(nintendoSession);
                        updated.add("Nintendo");
                    }catch(Exception e){
                        String msg=e.getMessage()==null?"":e.getMessage();
                        if(msg.contains("401")||msg.contains("403")||msg.contains("invalid_grant")){
                            SecretStore.remove(this,"nintendo.session_token");
                        }
                        failed.add("Nintendo");
                    }
                }

                final List<LudexDb.GameRow> games=db.listGames();
                final List<EmulatorRegistry.Emulator> emulators=EmulatorRegistry.detect(this);
                runOnUiThread(()->{
                    gameAdapter.setAll(games);
                    emulatorAdapter.setAll(emulators);
                    updateSummary(games);
                    updateSteamSyncInfo();
                    updateNintendoSyncInfo();
                    renderCurrent();
                    refresh.setEnabled(true);

                    String message=updated.isEmpty()
                        ?"Biblioteca atualizada. Nenhuma fonte externa de horas configurada."
                        :"Horas atualizadas: "+String.join(" · ",updated);
                    if(!failed.isEmpty())message+=" · falhou: "+String.join(", ",failed);
                    toast(message);
                });
            }catch(Exception e){
                runOnUiThread(()->{
                    refresh.setEnabled(true);
                    toast("Atualização falhou: "+e.getMessage());
                });
            }
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
        search.setVisibility((tab==Tab.LIBRARY||tab==Tab.ANDROID||tab==Tab.EMULATED)?View.VISIBLE:View.GONE);
        emptyState.setVisibility(View.GONE);
        if(sync){
            syncInfo.setText((hasUsageAccess()?"Acesso de uso concedido. ":"Acesso de uso pendente. ")+
                "O Android não expõe o histórico oficial do Google Play; o Ludex usa Usage Access para medir o tempo em primeiro plano dos jogos neste aparelho e sincroniza esse tempo com o PC.");
            updateSteamSyncInfo();
            updateNintendoSyncInfo();
            updateArtworkInfo();
            return;
        }
        if(tab==Tab.EMULATORS){
            list.setAdapter(emulatorAdapter);
            emptyState.setVisibility(emulatorAdapter.getItemCount()==0?View.VISIBLE:View.GONE);
            return;
        }
        list.setAdapter(gameAdapter);
        String q=search.getText()==null?"":search.getText().toString();
        gameAdapter.filter(q,tab);
        emptyState.setVisibility(gameAdapter.getItemCount()==0?View.VISIBLE:View.GONE);
    }

    private void updateSummary(List<LudexDb.GameRow> games){
        int android=0,emulated=0,other=0; long seconds=0;
        for(LudexDb.GameRow g:games){
            if("android".equals(g.source))android++;
            else if(g.emulated)emulated++;
            else other++;
            seconds+=g.seconds;
        }
        summary.setText(games.size()+" jogos · "+android+" Android · "+emulated+" emulados · "+other+" outros · "+format(seconds));
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
            .setPositiveButton(g.installed&&(g.packageName!=null||g.gameNative||g.emulated)?"JOGAR":"Fechar",(d,w)->{if(g.installed&&(g.packageName!=null||g.gameNative||g.emulated))launchGame(g);})
            .show();
    }

    private void showGameActions(LudexDb.GameRow g){
        ArrayList<String> actions=new ArrayList<>();
        actions.add("Alterar status");
        if("android".equals(g.source)&&g.packageName!=null)actions.add("Ajustar horas totais");
        actions.add("Excluir da lista");
        new MaterialAlertDialogBuilder(this).setTitle(g.title).setItems(actions.toArray(new String[0]),(d,which)->{
            String action=actions.get(which);
            if("Alterar status".equals(action))showStatusPicker(g);
            else if("Ajustar horas totais".equals(action))calibratePlaytime(g);
            else if("Excluir da lista".equals(action))confirmHideGame(g);
        }).show();
    }

    private void confirmHideGame(LudexDb.GameRow g){
        new MaterialAlertDialogBuilder(this)
            .setTitle("Excluir "+g.title+" da lista?")
            .setMessage("Isso só remove o jogo da biblioteca do Ludex. O app, ROM, save, horas e arquivos originais não serão apagados. Se ele for encontrado novamente em uma sincronização, continuará oculto.")
            .setNegativeButton("Cancelar",null)
            .setPositiveButton("Excluir",(d,w)->{
                db.hideGame(g.id);
                refreshAsync(false);
                Snackbar.make(findViewById(R.id.root),g.title+" removido da lista",Snackbar.LENGTH_LONG)
                    .setAction("DESFAZER",v->{db.unhideGame(g.id);refreshAsync(false);})
                    .show();
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
                }catch(Exception ex){toast("Falha no lançamento direto; abrindo GameNative");}
            }
        }

        if(g.eden){
            LudexDb.EdenLaunch launch=db.getEdenLaunch(g.id);
            if(launch!=null){
                try{
                    markEmulatedLaunch(g.id,launch.packageName);
                    Intent direct=new Intent(Intent.ACTION_VIEW)
                        .setData(Uri.parse(launch.uri))
                        .setClassName(launch.packageName,EdenShortcutScanner.ACTIVITY)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    startActivity(direct);
                    return;
                }catch(Exception ex){clearEmulatedLaunch();toast("Falha ao abrir o jogo no Eden");}
            }
        }

        if(g.emulated){
            LudexDb.EmulatorLaunch launch=db.getEmulatorLaunch(g.id);
            if(launch!=null){
                try{
                    markEmulatedLaunch(g.id,launch.packageName);
                    Intent direct=new Intent(Intent.ACTION_VIEW)
                        .setData(Uri.parse(launch.uri))
                        .setPackage(launch.packageName)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(direct);
                    return;
                }catch(Exception ex){clearEmulatedLaunch();toast("Falha ao abrir o jogo no emulador");}
            }
        }

        String pkg=g.packageName;
        if(pkg==null&&g.gameNative)pkg=GameNativeScanner.PACKAGE;
        if(pkg==null)return;
        Intent i=getPackageManager().getLaunchIntentForPackage(pkg);
        if(i==null){toast("Este jogo não expõe uma activity de inicialização");return;}
        startActivity(i);
    }

    private void markEmulatedLaunch(String gameId,String packageName){
        activeEmulatedGameId=gameId;activeEmulatorPackage=packageName;activeEmulatedStartedAt=System.currentTimeMillis();
    }
    private void clearEmulatedLaunch(){activeEmulatedGameId=null;activeEmulatorPackage=null;activeEmulatedStartedAt=0;}

    private void launchEmulator(EmulatorRegistry.Emulator e){
        Intent i=getPackageManager().getLaunchIntentForPackage(e.packageName);
        if(i!=null)startActivity(i);else toast("Não foi possível iniciar "+e.name);
    }

    private void connectEmulatorLibrary(EmulatorRegistry.Emulator e){
        boolean gameNative=GameNativeScanner.PACKAGE.equals(e.packageName);
        boolean eden=EdenShortcutScanner.STANDARD_PACKAGE.equals(e.packageName)||EdenShortcutScanner.OPTIMIZED_PACKAGE.equals(e.packageName);
        if(gameNative){
            shizukuBinderReady=Shizuku.pingBinder();
            if(shizukuBinderReady){
                try{
                    if(Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED){syncGameNativeWithShizuku(true);return;}
                    if(!Shizuku.shouldShowRequestPermissionRationale()){
                        pendingGameNativeShizuku=true;Shizuku.requestPermission(SHIZUKU_GAMENATIVE_REQUEST);toast("Autorize o Ludex no Shizuku");return;
                    }
                }catch(Exception ex){toast("Shizuku conectado, mas falhou: "+ex.getMessage());}
            }
            showGameNativeFallback();return;
        }

        if(e.isMultiSystem()){
            toast("RetroArch é multi-system: conecte bibliotecas por console em uma etapa futura.");
            return;
        }

        String key=(eden?"eden.tree_uri.":"emulator.tree_uri.")+e.packageName;
        String saved=db.getSetting(key,"");
        if(saved.isEmpty()){
            if(eden)pendingEdenPackage=e.packageName;else pendingLibraryEmulatorPackage=e.packageName;
            toast("Selecione a pasta de jogos de "+e.platform);
            if(eden)edenTreeLauncher.launch(null);else emulatorTreeLauncher.launch(null);
        }else{
            if(eden)syncEdenLibrary(Uri.parse(saved),e.packageName,true);
            else syncGenericEmulatorLibrary(Uri.parse(saved),e,true);
        }
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
                importEdenPlaytime(packageName);
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

    private void syncGenericEmulatorLibrary(Uri uri,EmulatorRegistry.Emulator emulator,boolean notify){
        if(notify)toast("Lendo "+emulator.platform+"…");
        io.execute(()->{
            try{
                List<EmulatorLibraryScanner.ImportedGame> found=EmulatorLibraryScanner.scan(this,uri,emulator);
                int count=db.syncEmulatorGames(emulator,found);
                runOnUiThread(()->{
                    if(count>0)toast(count+" jogos de "+emulator.platform+" encontrados");
                    else toast("Nenhuma ROM compatível encontrada nessa pasta");
                    refreshAsync(false);
                });
            }catch(Exception e){
                runOnUiThread(()->{
                    db.setSetting("emulator.tree_uri."+emulator.packageName,"");
                    toast("Não consegui ler a biblioteca: "+e.getMessage());
                });
            }
        });
    }

    private int importEdenPlaytime(String packageName){
        if(!Shizuku.pingBinder())return 0;
        try{
            Map<String,Long> times=EdenPlaytimeScanner.read(packageName);
            Map<String,String> links=db.listEdenProgramIds(packageName);
            int matched=0;
            for(Map.Entry<String,String> link:links.entrySet()){
                Long sec=times.get(link.getValue());
                if(sec!=null){
                    db.replaceImportedPlaytime(link.getKey(),"eden",sec);
                    matched++;
                }
            }
            return matched;
        }catch(Exception ignored){return 0;}
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

    private static final class SteamSyncResult{
        final int matched,total;
        SteamSyncResult(int matched,int total){this.matched=matched;this.total=total;}
    }

    private SteamSyncResult syncSteamPlaytimeNow(String steamId,String key) throws Exception {
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
        return new SteamSyncResult(matched,owned.size());
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
                SteamSyncResult result=syncSteamPlaytimeNow(steamId,key);
                runOnUiThread(()->{
                    updateSteamSyncInfo();
                    if(notify){
                        if(result.total==0)toast("A Steam não retornou jogos. Confira SteamID64 e privacidade da conta.");
                        else toast(result.matched+" jogos do GameNative receberam playtime da Steam");
                    }
                    refreshAsync(false);
                });
            }catch(Exception e){
                runOnUiThread(()->toast("Falha na Steam: "+e.getMessage()));
            }
        });
    }

    private void updateNintendoSyncInfo(){
        boolean connected=!SecretStore.get(this,"nintendo.session_token").isEmpty();
        long last=db.getSettingLong("nintendo.last_sync_ms",0);
        if(!connected){
            boolean pending=!db.getSetting("nintendo.login.verifier","").isEmpty();
            nintendoSyncInfo.setText(pending
                ?"Login iniciado. Conclua o acesso da Nintendo e cole a URL de retorno."
                :"Conta Nintendo não conectada. O Ludex pode importar seu histórico oficial de atividade e horas por jogo.");
            return;
        }
        String suffix=last>0?" · última sync "+new java.text.SimpleDateFormat("dd/MM HH:mm",Locale.getDefault()).format(new java.util.Date(last)):"";
        nintendoSyncInfo.setText("Conta Nintendo conectada"+suffix);
    }

    private void showNintendoConnect(){
        boolean connected=!SecretStore.get(this,"nintendo.session_token").isEmpty();
        boolean pending=!db.getSetting("nintendo.login.verifier","").isEmpty();
        MaterialAlertDialogBuilder b=new MaterialAlertDialogBuilder(this)
            .setTitle("Conta Nintendo")
            .setMessage(connected
                ?"A sessão da Nintendo está salva de forma criptografada neste aparelho. O Ludex usa uma API não documentada do app Nintendo/My Nintendo para ler somente seu Play Activity."
                :"O login abre no site oficial accounts.nintendo.com. O Ludex nunca recebe sua senha. Depois do login, copie a URL de retorno que começa com npf5c38e31cd085304b://auth# e cole no Ludex.");
        if(connected){
            b.setNegativeButton("Fechar",null)
             .setNeutralButton("Desconectar",(d,w)->{
                 SecretStore.remove(this,"nintendo.session_token");
                 db.setSetting("nintendo.login.verifier","");
                 db.setSetting("nintendo.login.state","");
                 updateNintendoSyncInfo();
                 toast("Conta Nintendo desconectada");
             })
             .setPositiveButton("Sincronizar",(d,w)->syncNintendoPlaytime(true));
        }else{
            b.setNegativeButton("Cancelar",null)
             .setNeutralButton(pending?"Colar retorno":"Ajuda",(d,w)->{
                 if(pending)showNintendoCallbackDialog();
                 else new MaterialAlertDialogBuilder(this)
                     .setTitle("Como conectar")
                     .setMessage("1. Toque em Entrar com Nintendo.\n2. Faça login no site oficial.\n3. No botão final, copie o endereço do link se o navegador não conseguir abrir o app.\n4. Volte ao Ludex > Conta Nintendo > Colar retorno.\n\nA URL deve começar com npf5c38e31cd085304b://auth#.")
                     .setPositiveButton("Entendi",null).show();
             })
             .setPositiveButton("Entrar com Nintendo",(d,w)->startNintendoLogin());
        }
        b.show();
    }

    private void startNintendoLogin(){
        try{
            NintendoPlayActivityClient.LoginRequest req=NintendoPlayActivityClient.newLoginRequest();
            db.setSetting("nintendo.login.verifier",req.codeVerifier);
            db.setSetting("nintendo.login.state",req.state);
            updateNintendoSyncInfo();
            startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(req.url)));
            toast("Após concluir o login, copie a URL de retorno e volte ao Ludex");
        }catch(Exception e){toast("Não foi possível iniciar o login Nintendo: "+e.getMessage());}
    }

    private void showNintendoCallbackDialog(){
        EditText input=new EditText(this);
        input.setSingleLine(false);
        input.setMinLines(3);
        input.setHint("npf5c38e31cd085304b://auth#session_token_code=...");
        int pad=(int)(20*getResources().getDisplayMetrics().density);
        FrameLayout wrap=new FrameLayout(this);wrap.setPadding(pad,0,pad,0);wrap.addView(input);
        new MaterialAlertDialogBuilder(this)
            .setTitle("Concluir login Nintendo")
            .setMessage("Cole a URL completa de retorno gerada após autorizar sua Conta Nintendo.")
            .setView(wrap)
            .setNegativeButton("Cancelar",null)
            .setNeutralButton("Abrir login novamente",(d,w)->startNintendoLogin())
            .setPositiveButton("Conectar",(d,w)->{
                String callback=input.getText()==null?"":input.getText().toString().trim();
                if(callback.isEmpty()){toast("Cole a URL de retorno");return;}
                finishNintendoLogin(callback);
            }).show();
    }

    private void finishNintendoLogin(String callback){
        String verifier=db.getSetting("nintendo.login.verifier","");
        String expectedState=db.getSetting("nintendo.login.state","");
        if(verifier.isEmpty()){toast("Inicie o login Nintendo novamente");return;}
        toast("Validando Conta Nintendo…");
        io.execute(()->{
            try{
                NintendoPlayActivityClient.Callback parsed=NintendoPlayActivityClient.parseCallback(callback);
                if(!expectedState.isEmpty()&&!expectedState.equals(parsed.state))throw new SecurityException("state do login não confere");
                String session=NintendoPlayActivityClient.exchangeSessionToken(parsed.code,verifier);
                SecretStore.put(this,"nintendo.session_token",session);
                db.setSetting("nintendo.login.verifier","");
                db.setSetting("nintendo.login.state","");
                runOnUiThread(()->{
                    updateNintendoSyncInfo();
                    toast("Conta Nintendo conectada");
                    syncNintendoPlaytime(true);
                });
            }catch(Exception e){
                runOnUiThread(()->toast("Falha no login Nintendo: "+e.getMessage()));
            }
        });
    }

    private int syncNintendoPlaytimeNow(String session) throws Exception {
        List<NintendoPlayActivityClient.Title> titles=NintendoPlayActivityClient.getPlayHistory(session,"pt-BR");
        int count=db.syncNintendoPlayHistory(titles);
        db.setSetting("nintendo.last_sync_ms",Long.toString(System.currentTimeMillis()));
        return count;
    }

    private void syncNintendoPlaytime(boolean notify){
        String session=SecretStore.get(this,"nintendo.session_token");
        if(session.isEmpty()){
            if(notify)showNintendoConnect();
            return;
        }
        if(notify)toast("Sincronizando atividade da Nintendo…");
        io.execute(()->{
            try{
                int count=syncNintendoPlaytimeNow(session);
                runOnUiThread(()->{
                    updateNintendoSyncInfo();
                    if(notify)toast(count+" jogos da Conta Nintendo sincronizados");
                    refreshAsync(false);
                });
            }catch(Exception e){
                String msg=e.getMessage()==null?"erro desconhecido":e.getMessage();
                if(msg.contains("401")||msg.contains("403")||msg.contains("invalid_grant")){
                    SecretStore.remove(this,"nintendo.session_token");
                }
                runOnUiThread(()->{
                    updateNintendoSyncInfo();
                    toast("Falha na Nintendo: "+msg);
                });
            }
        });
    }

    private void updateArtworkInfo(){
        boolean has=!SecretStore.get(this,"steamgriddb.api_key").isEmpty();
        artworkInfo.setText(has
            ?"Libretro + SteamGridDB configurados. Capas modernas e retrô serão buscadas e armazenadas em cache."
            :"Libretro será usado automaticamente. Configure SteamGridDB para capas de Switch e outros sistemas modernos.");
    }

    private void showArtworkConfig(){
        int pad=(int)(20*getResources().getDisplayMetrics().density);
        LinearLayout wrap=new LinearLayout(this);wrap.setOrientation(LinearLayout.VERTICAL);wrap.setPadding(pad,8,pad,0);
        boolean has=!SecretStore.get(this,"steamgriddb.api_key").isEmpty();
        EditText key=new EditText(this);key.setSingleLine(true);
        key.setHint(has?"API Key (vazio = manter a atual)":"SteamGridDB API Key");
        key.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        wrap.addView(key,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView link=new TextView(this);link.setText("Obter API Key no SteamGridDB ↗");link.setTextColor(getColor(R.color.ludex_accent));link.setTextSize(14);link.setPadding(0,pad/2,0,pad/2);
        link.setOnClickListener(v->{try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://www.steamgriddb.com/profile/preferences/api")));}catch(Exception e){toast("Não foi possível abrir o SteamGridDB");}});
        wrap.addView(link,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT));
        new MaterialAlertDialogBuilder(this)
            .setTitle("Capas de jogos emulados")
            .setMessage("Sistemas retrô usam Libretro sem chave. SteamGridDB serve como fallback, inclusive para Nintendo Switch.")
            .setView(wrap).setNegativeButton("Cancelar",null)
            .setNeutralButton("Remover chave",(d,w)->{SecretStore.remove(this,"steamgriddb.api_key");updateArtworkInfo();})
            .setPositiveButton("Salvar",(d,w)->{
                String value=key.getText()==null?"":key.getText().toString().trim();
                try{if(!value.isEmpty())SecretStore.put(this,"steamgriddb.api_key",value);updateArtworkInfo();gameAdapter.notifyDataSetChanged();}
                catch(Exception e){toast("Não foi possível proteger a chave: "+e.getMessage());}
            }).show();
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
        String iconPkg=g.packageName;
        if(iconPkg==null&&g.gameNative)iconPkg=GameNativeScanner.PACKAGE;
        if(iconPkg==null&&g.eden){
            LudexDb.EdenLaunch e=db.getEdenLaunch(g.id);if(e!=null)iconPkg=e.packageName;
        }
        if(iconPkg==null&&g.emulated){
            LudexDb.EmulatorLaunch e=db.getEmulatorLaunch(g.id);if(e!=null)iconPkg=e.packageName;
        }
        Drawable fallback=iconPkg==null?null:appIcon(iconPkg);
        view.setImageDrawable(fallback);view.setVisibility(fallback==null?View.INVISIBLE:View.VISIBLE);

        final String expectedTag=g.id;
        if("nintendo".equals(g.source)){
            LudexDb.NintendoInfo n=db.getNintendoInfo(g.id);
            if(n!=null&&!n.imageUrl.isBlank()){
                io.execute(()->{
                    android.graphics.Bitmap bmp=NintendoArtworkLoader.load(this,n.titleId,n.imageUrl);
                    if(bmp==null)return;
                    runOnUiThread(()->applyArtwork(view,expectedTag,bmp));
                });
            }
            return;
        }
        if(g.gameNative){
            LudexDb.GameNativeLaunch launch=db.getGameNativeLaunch(g.id);
            if(launch==null)return;
            io.execute(()->{
                android.graphics.Bitmap bmp=GameArtworkLoader.load(this,launch.provider,launch.externalId);
                if(bmp==null)return;
                runOnUiThread(()->applyArtwork(view,expectedTag,bmp));
            });
            return;
        }
        if(g.emulated){
            String pkg=null;
            if(g.eden){LudexDb.EdenLaunch e=db.getEdenLaunch(g.id);if(e!=null)pkg=e.packageName;}
            else {LudexDb.EmulatorLaunch e=db.getEmulatorLaunch(g.id);if(e!=null)pkg=e.packageName;}
            EmulatorRegistry.Emulator em=EmulatorRegistry.get(pkg);
            String libretro=em==null?null:em.libretroSystem;
            String key=SecretStore.get(this,"steamgriddb.api_key");
            io.execute(()->{
                android.graphics.Bitmap bmp=EmulatedArtworkLoader.load(this,g.platform,libretro,g.title,key);
                if(bmp==null)return;
                runOnUiThread(()->applyArtwork(view,expectedTag,bmp));
            });
        }
    }

    private void applyArtwork(ImageView view,String expectedTag,android.graphics.Bitmap bmp){
        Object tag=view.getTag();
        if(tag!=null&&expectedTag.equals(tag.toString())){view.setImageBitmap(bmp);view.setVisibility(View.VISIBLE);}
    }

    Drawable appIcon(String pkg){
        try{return getPackageManager().getApplicationIcon(pkg);}catch(Exception e){return null;}
    }

    private String providerLabel(String source){
        if(source==null)return "Ludex";
        switch(source){case "steam":return "Steam";case "epic":return "Epic";case "gog":return "GOG";case "android":return "Android";case "xbox":return "Xbox";case "eden":return "Eden";case "emulator":return "Emulado";case "nintendo":return "Nintendo";default:return source;}
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
        void setAll(List<LudexDb.GameRow> x){all.clear();all.addAll(x);filter("",tab);}
        void filter(String query,Tab mode){
            shown.clear();String q=query==null?"":query.trim().toLowerCase(Locale.ROOT);
            for(LudexDb.GameRow g:all){
                if(mode==Tab.ANDROID&&!"android".equals(g.source))continue;
                if(mode==Tab.EMULATED&&!g.emulated)continue;
                if(mode==Tab.LIBRARY&&g.emulated)continue;
                if(!q.isEmpty()&&!g.title.toLowerCase(Locale.ROOT).contains(q))continue;
                shown.add(g);
            }
            if(sortByPlaytime){
                shown.sort(Comparator.comparingLong((LudexDb.GameRow g)->g.seconds).reversed()
                    .thenComparing(g->g.title,String.CASE_INSENSITIVE_ORDER));
            }else{
                shown.sort(Comparator.comparing(g->g.title,String.CASE_INSENSITIVE_ORDER));
            }
            notifyDataSetChanged();
        }
        @NonNull public Holder onCreateViewHolder(@NonNull android.view.ViewGroup p,int t){return new Holder(getLayoutInflater().inflate(R.layout.item_game,p,false));}
        public void onBindViewHolder(@NonNull Holder h,int pos){
            LudexDb.GameRow g=shown.get(pos);
            h.title.setText(g.title);h.meta.setText(g.platform+" · "+providerLabel(g.source)+(g.gameNative?" · GameNative":(g.eden?" · Eden":(g.installed?" · instalado":""))));
            h.time.setText(format(g.seconds));h.status.setText(g.favorite?"★ "+g.status:g.status);
            bindGameArtwork(g,h.icon);
            h.play.setVisibility(g.installed&&(g.packageName!=null||g.gameNative||g.emulated)?View.VISIBLE:View.GONE);
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
            h.name.setText(e.name);h.pkg.setText(e.platform+" · "+e.packageName);h.open.setOnClickListener(v->launch.click(e));
            h.rom.setVisibility(gameNative?View.GONE:View.VISIBLE);h.rom.setOnClickListener(v->rom.click(e));
            h.library.setVisibility((gameNative||!e.extensions.isEmpty())?View.VISIBLE:View.GONE);
            String savedTree=db.getSetting("gamenative.tree_uri","");
            boolean binder=shizukuBinderReady||Shizuku.pingBinder();
            if(eden){
                String edenTree=db.getSetting("eden.tree_uri."+e.packageName,"");
                h.library.setText(edenTree.isEmpty()?"Conectar biblioteca":"Sincronizar jogos");
            }else if(gameNative)h.library.setText(binder?"Importar atalhos do GameNative":(savedTree.isEmpty()?"Conectar biblioteca":"Sincronizar jogos"));
            else{
                String tree=db.getSetting("emulator.tree_uri."+e.packageName,"");
                h.library.setText(tree.isEmpty()?"Conectar biblioteca":"Sincronizar jogos");
            }
            h.library.setOnClickListener(v->library.click(e));
        }
        public int getItemCount(){return items.size();}
        final class Holder extends RecyclerView.ViewHolder{TextView name,pkg;Button open,rom,library;Holder(View v){super(v);name=v.findViewById(R.id.emu_name);pkg=v.findViewById(R.id.emu_pkg);open=v.findViewById(R.id.emu_open);rom=v.findViewById(R.id.emu_rom);library=v.findViewById(R.id.emu_library);}}
    }
}
