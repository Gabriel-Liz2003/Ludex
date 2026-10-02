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

    private enum LibraryFilter {
        ALL("all","Todas"),
        ANDROID("android","Android"),
        STEAM("steam","Steam"),
        NINTENDO("nintendo","Nintendo"),
        EPIC("epic","Epic"),
        GOG("gog","GOG"),
        XBOX("xbox","Xbox"),
        EMULATED("emulated","Emulados"),
        OTHER("other","Outros");

        final String id,label;
        LibraryFilter(String id,String label){this.id=id;this.label=label;}
        static LibraryFilter fromId(String id){
            for(LibraryFilter value:values())if(value.id.equals(id))return value;
            return ALL;
        }
    }

    private static final class DisplayGame {
        final String key;
        final ArrayList<LudexDb.GameRow> variants=new ArrayList<>();
        String title="",status="Quero jogar";
        long seconds,firstActivityAt,acquiredAt,firstSeenAt;
        boolean favorite;
        LudexDb.GameRow primary;

        DisplayGame(String key){this.key=key;}
    }
    private LudexDb db;
    private RecyclerView list;
    private TextInputEditText search;
    private GameAdapter gameAdapter;
    private EmulatorAdapter emulatorAdapter;
    private View syncPanel, emptyState;
    private TextView summary, syncInfo, steamSyncInfo, epicSyncInfo, nintendoSyncInfo, artworkInfo;
    private Tab tab=Tab.LIBRARY;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final ExecutorService remoteSyncIo=Executors.newFixedThreadPool(3);
    private final ExecutorService artworkIo=Executors.newFixedThreadPool(4);
    private final android.util.LruCache<String,android.graphics.Bitmap> artworkMemory=
        new android.util.LruCache<String,android.graphics.Bitmap>(24*1024){
            @Override protected int sizeOf(String key,android.graphics.Bitmap value){
                try{return Math.max(1,value.getAllocationByteCount()/1024);}
                catch(Exception e){return Math.max(1,value.getByteCount()/1024);}
            }
        };
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
    private LibraryFilter libraryFilter=LibraryFilter.ALL;
    private String pendingManualArtworkUri="";
    private TextView pendingManualArtworkInfo;
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

    private final ActivityResultLauncher<String[]> manualArtworkLauncher=registerForActivityResult(
        new ActivityResultContracts.OpenDocument(), uri -> {
            if(uri==null)return;
            try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}
            catch(Exception ignored){}
            pendingManualArtworkUri=uri.toString();
            if(pendingManualArtworkInfo!=null)pendingManualArtworkInfo.setText("Capa selecionada");
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
        super.onDestroy();
        io.shutdownNow();
        remoteSyncIo.shutdownNow();
        artworkIo.shutdownNow();
        artworkMemory.evictAll();
    }

    private void bindViews(){
        list=findViewById(R.id.game_list);
        search=findViewById(R.id.search);
        summary=findViewById(R.id.summary);
        syncPanel=findViewById(R.id.sync_panel);
        syncInfo=findViewById(R.id.sync_info);
        steamSyncInfo=findViewById(R.id.steam_sync_info);
        epicSyncInfo=findViewById(R.id.epic_sync_info);
        nintendoSyncInfo=findViewById(R.id.nintendo_sync_info);
        artworkInfo=findViewById(R.id.artwork_info);
        emptyState=findViewById(R.id.empty_state);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setItemAnimator(new DefaultItemAnimator());
        gameAdapter=new GameAdapter(this::showGame,game->{
            LudexDb.GameRow launch=launchVariant(game);
            if(launch!=null)launchGame(launch);
        });
        emulatorAdapter=new EmulatorAdapter(this::launchEmulator,this::pickRomFor,this::connectEmulatorLibrary);
        list.setAdapter(gameAdapter);
    }

    private void setupUi(){
        sortByPlaytime="playtime".equals(db.getSetting("library.sort","title"));
        libraryFilter=LibraryFilter.fromId(db.getSetting("library.filter","all"));
        updateSortButton();
        updateLibraryFilterButton();
        findViewById(R.id.refresh).setOnClickListener(v->refreshAllPlaytimeAsync());
        findViewById(R.id.sort_playtime).setOnClickListener(v->{
            sortByPlaytime=!sortByPlaytime;
            db.setSetting("library.sort",sortByPlaytime?"playtime":"title");
            updateSortButton();
            renderCurrent();
        });
        findViewById(R.id.library_filter).setOnClickListener(v->showLibraryFilter());
        findViewById(R.id.manual_add).setOnClickListener(v->showManualGameDialog(null));
        findViewById(R.id.usage_access).setOnClickListener(v->openUsageAccess());
        findViewById(R.id.sync_import).setOnClickListener(v->pickImport());
        findViewById(R.id.sync_export).setOnClickListener(v->pickExport());
        findViewById(R.id.sync_usage).setOnClickListener(v->importUsageAsync(true));
        findViewById(R.id.steam_config).setOnClickListener(v->showSteamConfig());
        findViewById(R.id.steam_sync).setOnClickListener(v->syncSteamPlaytime(true));
        findViewById(R.id.epic_connect).setOnClickListener(v->showEpicConnect());
        findViewById(R.id.epic_sync).setOnClickListener(v->syncEpicLibrary(true));
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

    private static final String[] MANUAL_PLATFORM_LABELS={"Nintendo Switch","PC","Xbox","PlayStation","Outro"};
    private static final String[] MANUAL_PLATFORM_KEYS={"nintendo","pc","xbox","playstation","other"};

    private static int manualPlatformIndex(String key){
        if(key==null)return 0;
        for(int i=0;i<MANUAL_PLATFORM_KEYS.length;i++)if(MANUAL_PLATFORM_KEYS[i].equalsIgnoreCase(key))return i;
        return 0;
    }

    private void showManualGameDialog(LudexDb.GameRow existing){
        LudexDb.ManualInfo existingInfo=existing==null?null:db.getManualInfo(existing.id);
        pendingManualArtworkUri=existingInfo==null?"":existingInfo.imageUri;

        int pad=(int)(20*getResources().getDisplayMetrics().density);
        LinearLayout wrap=new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(pad,8,pad,0);

        EditText title=new EditText(this);
        title.setHint("Nome do jogo");
        title.setSingleLine(true);
        if(existing!=null)title.setText(existing.title);
        wrap.addView(title,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView platformLabel=new TextView(this);
        platformLabel.setText("Plataforma");
        platformLabel.setTextColor(getColor(R.color.ludex_muted));
        platformLabel.setPadding(0,pad/2,0,0);
        wrap.addView(platformLabel);

        Spinner platform=new Spinner(this);
        ArrayAdapter<String> platformAdapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,MANUAL_PLATFORM_LABELS);
        platformAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        platform.setAdapter(platformAdapter);
        platform.setSelection(manualPlatformIndex(existingInfo==null?null:existingInfo.platformKey));
        wrap.addView(platform,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT));

        EditText hours=new EditText(this);
        hours.setHint("Horas jogadas (ex.: 24,5)");
        hours.setSingleLine(true);
        hours.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        if(existing!=null)hours.setText(String.format(Locale.ROOT,"%.2f",existing.seconds/3600.0).replaceAll("0+$","").replaceAll("\\.$",""));
        wrap.addView(hours,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT));

        EditText firstPlayed=new EditText(this);
        firstPlayed.setHint("Primeira vez jogado (dd/MM/aaaa) · opcional");
        firstPlayed.setSingleLine(true);
        long firstAt=existingInfo==null?0:existingInfo.firstPlayedAt;
        if(firstAt<=0&&existing!=null)firstAt=existing.firstActivityAt;
        if(firstAt>0)firstPlayed.setText(formatDate(firstAt));
        wrap.addView(firstPlayed,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT));

        com.google.android.material.button.MaterialButton cover=new com.google.android.material.button.MaterialButton(this);
        cover.setText(pendingManualArtworkUri.isBlank()?"Escolher capa (opcional)":"Trocar capa");
        cover.setOnClickListener(v->manualArtworkLauncher.launch(new String[]{"image/*"}));
        LinearLayout.LayoutParams coverParams=new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT);
        coverParams.topMargin=pad/2;
        wrap.addView(cover,coverParams);

        pendingManualArtworkInfo=new TextView(this);
        pendingManualArtworkInfo.setText(pendingManualArtworkUri.isBlank()?"Nenhuma capa selecionada":"Capa selecionada");
        pendingManualArtworkInfo.setTextColor(getColor(R.color.ludex_muted));
        pendingManualArtworkInfo.setTextSize(12);
        wrap.addView(pendingManualArtworkInfo,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView note=new TextView(this);
        note.setText("Entradas manuais participam da deduplicação. Um jogo de Switch manual, por exemplo, soma suas horas com Steam/Epic do mesmo título.");
        note.setTextColor(getColor(R.color.ludex_muted));
        note.setTextSize(12);
        note.setPadding(0,pad/2,0,0);
        wrap.addView(note,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT));

        androidx.appcompat.app.AlertDialog dialog=new MaterialAlertDialogBuilder(this)
            .setTitle(existing==null?"Adicionar jogo manualmente":"Editar entrada manual")
            .setView(wrap)
            .setNegativeButton("Cancelar",null)
            .setPositiveButton("Salvar",null)
            .create();

        dialog.setOnShowListener(ignored->dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String gameTitle=title.getText()==null?"":title.getText().toString().trim();
            if(gameTitle.isBlank()){title.setError("Informe o nome do jogo");return;}

            String rawHours=hours.getText()==null?"":hours.getText().toString().trim().replace(',','.');
            double totalHours;
            try{totalHours=Double.parseDouble(rawHours);}
            catch(Exception e){hours.setError("Informe as horas jogadas");return;}
            if(totalHours<=0){hours.setError("Use um valor maior que zero");return;}
            long seconds=Math.max(60,Math.round(totalHours*3600.0));

            long firstPlayedAt=0;
            String dateText=firstPlayed.getText()==null?"":firstPlayed.getText().toString().trim();
            if(!dateText.isBlank()){
                try{
                    java.text.SimpleDateFormat fmt=new java.text.SimpleDateFormat("dd/MM/yyyy",Locale.getDefault());
                    fmt.setLenient(false);
                    java.util.Date parsed=fmt.parse(dateText);
                    if(parsed!=null)firstPlayedAt=parsed.getTime();
                }catch(Exception e){firstPlayed.setError("Use dd/MM/aaaa");return;}
            }

            int platformIndex=Math.max(0,platform.getSelectedItemPosition());
            String platformName=MANUAL_PLATFORM_LABELS[platformIndex];
            String platformKey=MANUAL_PLATFORM_KEYS[platformIndex];

            try{
                if(existing==null)db.addManualGame(gameTitle,platformName,platformKey,seconds,firstPlayedAt,pendingManualArtworkUri);
                else db.updateManualGame(existing.id,gameTitle,platformName,platformKey,seconds,firstPlayedAt,pendingManualArtworkUri);
                dialog.dismiss();
                pendingManualArtworkInfo=null;
                pendingManualArtworkUri="";
                refreshAsync(false);
                toast(existing==null?"Jogo adicionado":"Entrada manual atualizada");
            }catch(Exception e){
                toast("Não foi possível salvar: "+e.getMessage());
            }
        }));
        dialog.setOnDismissListener(ignored->pendingManualArtworkInfo=null);
        dialog.show();
    }

    private void updateSortButton(){
        Button sort=findViewById(R.id.sort_playtime);
        sort.setText(sortByPlaytime?"Horas ↓":"A–Z");
    }

    private void updateLibraryFilterButton(){
        Button filter=findViewById(R.id.library_filter);
        filter.setText("Biblioteca: "+libraryFilter.label);
    }

    private void showLibraryFilter(){
        LibraryFilter[] filters=LibraryFilter.values();
        String[] labels=new String[filters.length];
        int selected=0;
        for(int i=0;i<filters.length;i++){
            labels[i]=filters[i].label;
            if(filters[i]==libraryFilter)selected=i;
        }
        new MaterialAlertDialogBuilder(this)
            .setTitle("Biblioteca")
            .setSingleChoiceItems(labels,selected,(dialog,which)->{
                libraryFilter=filters[which];
                db.setSetting("library.filter",libraryFilter.id);
                updateLibraryFilterButton();
                dialog.dismiss();
                renderCurrent();
            })
            .setNegativeButton("Cancelar",null)
            .show();
    }

    private void refreshAllPlaytimeAsync(){
        View refresh=findViewById(R.id.refresh);
        refresh.setEnabled(false);
        toast("Atualizando biblioteca e horas…");
        io.execute(()->{
            List<String> updated=Collections.synchronizedList(new ArrayList<>());
            List<String> failed=Collections.synchronizedList(new ArrayList<>());
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

                ArrayList<Future<?>> remoteTasks=new ArrayList<>();

                String steamId=db.getSetting("steam.id64","");
                String steamKey=SecretStore.get(this,"steam.api_key");
                String steamFamilyToken=SecretStore.get(this,"steam.family_token");
                if(!steamId.isEmpty()&&(!steamKey.isEmpty()||!steamFamilyToken.isEmpty())){
                    remoteTasks.add(remoteSyncIo.submit(()->{
                        try{
                            SteamSyncResult steamResult=syncSteamLibraryNow(steamId,steamKey,steamFamilyToken);
                            updated.add(steamResult.familyGames>0?"Steam + Família":"Steam");
                            if(steamResult.familyError!=null&&!steamResult.familyError.isBlank())failed.add("Família Steam");
                        }catch(Exception e){failed.add("Steam");}
                    }));
                }

                if(!SecretStore.get(this,"epic.refresh_token").isEmpty()){
                    remoteTasks.add(remoteSyncIo.submit(()->{
                        try{
                            syncEpicLibraryNow();
                            updated.add("Epic");
                        }catch(Exception e){failed.add("Epic");}
                    }));
                }

                String nintendoSession=SecretStore.get(this,"nintendo.session_token");
                if(!nintendoSession.isEmpty()){
                    remoteTasks.add(remoteSyncIo.submit(()->{
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
                    }));
                }

                // Eden é local/Shizuku: execute enquanto as APIs remotas trabalham em paralelo.
                if(Shizuku.pingBinder()){
                    int edenMatched=0;
                    edenMatched+=importEdenPlaytime(EdenShortcutScanner.STANDARD_PACKAGE);
                    edenMatched+=importEdenPlaytime(EdenShortcutScanner.OPTIMIZED_PACKAGE);
                    if(edenMatched>0)updated.add("Eden");
                }

                for(Future<?> task:remoteTasks){
                    try{task.get();}
                    catch(Exception ignored){}
                }

                final List<LudexDb.GameRow> games=db.listGames();
                final List<EmulatorRegistry.Emulator> emulators=EmulatorRegistry.detect(this);
                runOnUiThread(()->{
                    gameAdapter.setAll(games);
                    emulatorAdapter.setAll(emulators);
                    updateSummary(games);
                    updateSteamSyncInfo();
                    updateEpicSyncInfo();
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
        boolean gamesTab=tab==Tab.LIBRARY||tab==Tab.ANDROID||tab==Tab.EMULATED;
        findViewById(R.id.library_controls).setVisibility(gamesTab?View.VISIBLE:View.GONE);
        emptyState.setVisibility(View.GONE);
        if(sync){
            syncInfo.setText((hasUsageAccess()?"Acesso de uso concedido. ":"Acesso de uso pendente. ")+
                "O Android não expõe o histórico oficial do Google Play; o Ludex usa Usage Access para medir o tempo em primeiro plano dos jogos neste aparelho e sincroniza esse tempo com o PC.");
            updateSteamSyncInfo();
            updateEpicSyncInfo();
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
        List<DisplayGame> grouped=buildDisplayGames(games);
        int played=0;long seconds=0;
        for(DisplayGame game:grouped){
            if(game.seconds<=0)continue;
            played++;
            seconds+=game.seconds;
        }
        summary.setText(played+" jogos com horas · "+format(seconds)+" no total");
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

    private static String canonicalGameTitle(String raw){
        if(raw==null)return "";
        String x=java.text.Normalizer.normalize(raw,java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+","");
        return x.toLowerCase(Locale.ROOT)
            .replace("&","and")
            .replaceAll("[^\\p{L}\\p{N}]+","")
            .trim();
    }

    private static int titleQuality(String title){
        if(title==null)return 0;
        int spaces=0;
        for(int i=0;i<title.length();i++)if(Character.isWhitespace(title.charAt(i)))spaces++;
        return spaces*20+title.length();
    }

    private static boolean isLaunchable(LudexDb.GameRow g){
        return g!=null&&g.installed&&(g.packageName!=null||g.gameNative||g.emulated);
    }

    private static int primaryScore(LudexDb.GameRow g){
        int score=titleQuality(g.title);
        if(isLaunchable(g))score+=1000;
        if(g.installed)score+=200;
        if(g.steam||g.nintendo)score+=50;
        return score;
    }

    private List<DisplayGame> buildDisplayGames(List<LudexDb.GameRow> rows){
        LinkedHashMap<String,DisplayGame> grouped=new LinkedHashMap<>();
        for(LudexDb.GameRow row:rows){
            String key=canonicalGameTitle(row.title);
            if(key.isEmpty())key="id:"+row.id;
            DisplayGame game=grouped.computeIfAbsent(key,DisplayGame::new);
            game.variants.add(row);
            game.seconds+=Math.max(0,row.seconds);
            game.favorite|=row.favorite;
            game.firstActivityAt=minPositiveTime(game.firstActivityAt,row.firstActivityAt);
            game.acquiredAt=minPositiveTime(game.acquiredAt,row.acquiredAt);
            game.firstSeenAt=minPositiveTime(game.firstSeenAt,row.firstSeenAt);
            if(game.title.isEmpty()||titleQuality(row.title)>titleQuality(game.title))game.title=row.title;
            if(game.primary==null||primaryScore(row)>primaryScore(game.primary))game.primary=row;
        }
        for(DisplayGame game:grouped.values()){
            if(game.primary!=null)game.status=game.primary.status;
        }
        return new ArrayList<>(grouped.values());
    }

    private static long minPositiveTime(long a,long b){
        if(a<=0)return Math.max(0,b);
        if(b<=0)return a;
        return Math.min(a,b);
    }

    private static String formatDate(long atMs){
        if(atMs<=0)return "";
        return new java.text.SimpleDateFormat("dd/MM/yyyy",Locale.getDefault()).format(new java.util.Date(atMs));
    }

    private static String gameDateLabel(DisplayGame game){
        if(game.firstActivityAt>0)return "1ª atividade: "+formatDate(game.firstActivityAt);
        if(game.acquiredAt>0)return "Na biblioteca desde: "+formatDate(game.acquiredAt);
        if(game.firstSeenAt>0)return "No Ludex desde: "+formatDate(game.firstSeenAt);
        return "";
    }

    private static String sourceKey(LudexDb.GameRow g){
        if(g==null)return "";
        if(g.steam)return "steam";
        if(g.nintendo)return "nintendo";
        if(g.epic)return "epic";
        if("android".equalsIgnoreCase(g.source))return "android";
        String provider=g.linkedProvider==null?"":g.linkedProvider.toLowerCase(Locale.ROOT);
        if(!provider.isBlank())return provider;
        String source=g.source==null?"":g.source.toLowerCase(Locale.ROOT);
        if("steam-family".equals(source))return "steam";
        if(source.startsWith("nintendo-"))return "nintendo";
        if(source.startsWith("xbox-"))return "xbox";
        if(source.startsWith("playstation-"))return "playstation";
        if(source.startsWith("pc-"))return "pc";
        if(source.startsWith("other-"))return "other";
        if("eden".equals(source)||"emulator".equals(source))return "emulated";
        return source;
    }

    private boolean rowMatchesLibrary(LudexDb.GameRow g,LibraryFilter filter){
        if(filter==LibraryFilter.ALL)return true;
        String source=sourceKey(g);
        switch(filter){
            case ANDROID:return "android".equals(source);
            case STEAM:return g.steam||"steam".equals(source);
            case NINTENDO:return g.nintendo||"nintendo".equals(source);
            case EPIC:return g.epic||"epic".equals(source);
            case GOG:return "gog".equals(source);
            case XBOX:return "xbox".equals(source)||"microsoft".equals(source);
            case EMULATED:return g.emulated;
            case OTHER:
                return !g.steam&&!g.nintendo&&!g.epic&&!g.emulated
                    && !"android".equals(source)&&!"steam".equals(source)&&!"nintendo".equals(source)
                    && !"epic".equals(source)&&!"gog".equals(source)&&!"xbox".equals(source)&&!"microsoft".equals(source);
            default:return true;
        }
    }

    private boolean rowHasPlaytimeForFilter(LudexDb.GameRow row,LibraryFilter filter){
        switch(filter){
            case ALL:return row.seconds>0;
            case ANDROID:return row.androidSeconds>0;
            case STEAM:return row.steamSeconds>0;
            case NINTENDO:return row.nintendoSeconds>0;
            case EMULATED:return row.emulatedSeconds>0;
            case EPIC:return row.epicSeconds>0;
            case GOG:return row.otherSeconds>0&&"gog".equals(sourceKey(row));
            case XBOX:{
                String source=sourceKey(row);
                return row.otherSeconds>0&&("xbox".equals(source)||"microsoft".equals(source));
            }
            case OTHER:
                return row.otherSeconds>0&&!rowMatchesLibrary(row,LibraryFilter.EPIC)
                    &&!rowMatchesLibrary(row,LibraryFilter.GOG)&&!rowMatchesLibrary(row,LibraryFilter.XBOX);
            default:return false;
        }
    }

    private boolean groupMatchesLibrary(DisplayGame game,LibraryFilter filter){
        if(filter==LibraryFilter.ALL)return game.seconds>0;
        for(LudexDb.GameRow row:game.variants){
            if(rowHasPlaytimeForFilter(row,filter))return true;
        }
        return false;
    }

    private boolean groupMatchesTab(DisplayGame game,Tab mode){
        if(mode!=Tab.LIBRARY&&mode!=Tab.ANDROID&&mode!=Tab.EMULATED)return true;
        for(LudexDb.GameRow row:game.variants){
            if(mode==Tab.ANDROID&&row.androidSeconds>0)return true;
            if(mode==Tab.EMULATED&&row.emulatedSeconds>0)return true;
            if(mode==Tab.LIBRARY&&(row.androidSeconds>0||row.steamSeconds>0||row.nintendoSeconds>0||row.epicSeconds>0||row.otherSeconds>0))return true;
        }
        return false;
    }

    private String rowLibraryLabel(LudexDb.GameRow row){
        if(row.steam)return "Steam";
        if(row.nintendo)return "Nintendo";
        String source=sourceKey(row);
        switch(source){
            case "android":return "Android";
            case "steam":return "Steam";
            case "nintendo":return "Nintendo";
            case "epic":return "Epic";
            case "gog":return "GOG";
            case "xbox":case "microsoft":return "Xbox";
            case "playstation":return "PlayStation";
            case "pc":return "PC";
            case "other":return "Manual";
            case "emulated":return "Emulado";
            default:return providerLabel(row.source);
        }
    }

    private String librariesLabel(DisplayGame game){
        LinkedHashSet<String> labels=new LinkedHashSet<>();
        for(LudexDb.GameRow row:game.variants){
            if(row.androidSeconds>0)labels.add("Android");
            if(row.steamSeconds>0)labels.add("Steam");
            if(row.nintendoSeconds>0)labels.add("Nintendo");
            if(row.epicSeconds>0)labels.add("Epic");
            if(row.emulatedSeconds>0)labels.add("Emulado");
            if(row.otherSeconds>0)labels.add(rowLibraryLabel(row));
        }
        if(labels.isEmpty()&&game.primary!=null)labels.add(rowLibraryLabel(game.primary));
        return String.join(" + ",labels);
    }

    private static void addPlaytime(Map<String,Long> totals,String label,long seconds){
        if(seconds<=0)return;
        totals.put(label,totals.getOrDefault(label,0L)+seconds);
    }

    private String playtimeBreakdown(DisplayGame game){
        LinkedHashMap<String,Long> totals=new LinkedHashMap<>();
        for(LudexDb.GameRow row:game.variants){
            addPlaytime(totals,"Android",row.androidSeconds);
            addPlaytime(totals,"Steam",row.steamSeconds);
            addPlaytime(totals,"Nintendo",row.nintendoSeconds);
            addPlaytime(totals,"Epic",row.epicSeconds);
            addPlaytime(totals,"Emulado",row.emulatedSeconds);
            addPlaytime(totals,rowLibraryLabel(row),row.otherSeconds);
        }
        StringBuilder out=new StringBuilder();
        for(Map.Entry<String,Long> entry:totals.entrySet()){
            if(out.length()>0)out.append("\n");
            out.append(entry.getKey()).append(": ").append(format(entry.getValue()));
        }
        return out.toString();
    }

    private LudexDb.GameRow androidVariant(DisplayGame game){
        for(LudexDb.GameRow row:game.variants){
            if("android".equalsIgnoreCase(row.source)&&row.packageName!=null)return row;
        }
        return null;
    }

    private LudexDb.GameRow manualVariant(DisplayGame game){
        for(LudexDb.GameRow row:game.variants){
            if(db.isManualGame(row.id))return row;
        }
        return null;
    }

    private LudexDb.GameRow artworkVariant(DisplayGame game){
        LudexDb.GameRow best=game.primary;
        int bestScore=-1;
        for(LudexDb.GameRow row:game.variants){
            int score=0;
            if("android".equalsIgnoreCase(row.source)&&row.packageName!=null)score+=500;
            LudexDb.ManualInfo manual=db.getManualInfo(row.id);
            if(manual!=null&&!manual.imageUri.isBlank())score+=450;
            if(row.steam)score+=400;
            if(row.epic)score+=350;
            if(row.nintendo)score+=300;
            if(row.gameNative)score+=200;
            if(row.emulated)score+=100;
            if(row.installed)score+=50;
            if(score>bestScore){best=row;bestScore=score;}
        }
        return best;
    }

    private LudexDb.GameRow launchVariant(DisplayGame game){
        LudexDb.GameRow best=null;int bestScore=-1;
        for(LudexDb.GameRow row:game.variants){
            if(!isLaunchable(row))continue;
            int score=10;
            if(rowMatchesLibrary(row,libraryFilter))score+=200;
            if(tab==Tab.ANDROID&&"android".equalsIgnoreCase(row.source))score+=150;
            if(tab==Tab.EMULATED&&row.emulated)score+=150;
            if("android".equalsIgnoreCase(row.source))score+=30;
            if(score>bestScore){best=row;bestScore=score;}
        }
        return best;
    }

    private void showGame(DisplayGame game){
        LudexDb.GameRow launch=launchVariant(game);
        String breakdown=playtimeBreakdown(game);
        String message=librariesLabel(game)+"\nTotal: "+format(game.seconds);
        String date=gameDateLabel(game);
        if(!date.isBlank())message+="\n"+date;
        if(!breakdown.isBlank())message+="\n\n"+breakdown;
        message+="\n\nStatus: "+game.status;
        new MaterialAlertDialogBuilder(this)
            .setTitle(game.title)
            .setMessage(message)
            .setNeutralButton(game.favorite?"Remover favorito":"Favoritar",(d,w)->{
                for(LudexDb.GameRow row:game.variants)db.setFavorite(row.id,!game.favorite);
                refreshAsync(false);
            })
            .setNegativeButton("Mais",(d,w)->showGameActions(game))
            .setPositiveButton(launch!=null?"JOGAR":"Fechar",(d,w)->{if(launch!=null)launchGame(launch);})
            .show();
    }

    private void showGameActions(DisplayGame game){
        ArrayList<String> actions=new ArrayList<>();
        actions.add("Alterar status");
        LudexDb.GameRow android=androidVariant(game);
        LudexDb.GameRow manual=manualVariant(game);
        if(android!=null)actions.add("Ajustar horas Android");
        if(manual!=null){
            actions.add("Editar entrada manual");
            actions.add("Remover entrada manual");
        }
        actions.add("Excluir da lista");
        new MaterialAlertDialogBuilder(this).setTitle(game.title).setItems(actions.toArray(new String[0]),(d,which)->{
            String action=actions.get(which);
            if("Alterar status".equals(action))showStatusPicker(game);
            else if("Ajustar horas Android".equals(action)&&android!=null)calibratePlaytime(android);
            else if("Editar entrada manual".equals(action)&&manual!=null)showManualGameDialog(manual);
            else if("Remover entrada manual".equals(action)&&manual!=null)confirmDeleteManualGame(game,manual);
            else if("Excluir da lista".equals(action))confirmHideGame(game);
        }).show();
    }

    private void confirmDeleteManualGame(DisplayGame game,LudexDb.GameRow manual){
        new MaterialAlertDialogBuilder(this)
            .setTitle("Remover entrada manual?")
            .setMessage("Remove apenas as horas e dados adicionados manualmente. Outras versões de "+game.title+" continuam na biblioteca.")
            .setNegativeButton("Cancelar",null)
            .setPositiveButton("Remover",(d,w)->{
                db.deleteManualGame(manual.id);
                refreshAsync(false);
                toast("Entrada manual removida");
            }).show();
    }

    private void confirmHideGame(DisplayGame game){
        new MaterialAlertDialogBuilder(this)
            .setTitle("Excluir "+game.title+" da lista?")
            .setMessage("Todas as cópias agrupadas serão ocultadas no Ludex. Apps, ROMs, saves, horas e arquivos originais não serão apagados.")
            .setNegativeButton("Cancelar",null)
            .setPositiveButton("Excluir",(d,w)->{
                for(LudexDb.GameRow row:game.variants)db.hideGame(row.id);
                refreshAsync(false);
                Snackbar.make(findViewById(R.id.root),game.title+" removido da lista",Snackbar.LENGTH_LONG)
                    .setAction("DESFAZER",v->{
                        for(LudexDb.GameRow row:game.variants)db.unhideGame(row.id);
                        refreshAsync(false);
                    })
                    .show();
            }).show();
    }

    private void showStatusPicker(DisplayGame game){
        String[] statuses={"Quero jogar","Jogando","Pausado","Concluído","100%","Abandonado"};
        new MaterialAlertDialogBuilder(this).setTitle("Status").setItems(statuses,(d,which)->{
            for(LudexDb.GameRow row:game.variants)db.setStatus(row.id,statuses[which]);
            refreshAsync(false);
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
        db.markGameOpened(g.id,System.currentTimeMillis());
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
        boolean hasFamily=!SecretStore.get(this,"steam.family_token").isEmpty();
        long last=db.getSettingLong("steam.last_sync_ms",0);
        if(steamId.isEmpty()||(!hasKey&&!hasFamily)){
            steamSyncInfo.setText("Não configurado. Informe SteamID64 e conecte a Steam para importar biblioteca e horas.");
            return;
        }
        String suffix=last>0?" · última sync "+new java.text.SimpleDateFormat("dd/MM HH:mm",Locale.getDefault()).format(new java.util.Date(last)):"";
        String family=hasFamily?" · Família Steam ativa":"";
        steamSyncInfo.setText("Steam configurada · "+steamId+family+suffix);
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
        keyLink.setPadding(0,pad/3,0,pad/2);
        keyLink.setOnClickListener(v->{
            try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://steamcommunity.com/dev/apikey")));}
            catch(Exception e){toast("Não foi possível abrir a página da Steam");}
        });
        wrap.addView(keyLink,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT));

        boolean alreadyHasFamily=!SecretStore.get(this,"steam.family_token").isEmpty();
        EditText familyToken=new EditText(this);
        familyToken.setSingleLine(false);
        familyToken.setMinLines(2);
        familyToken.setMaxLines(4);
        familyToken.setHint(alreadyHasFamily?"Token Família (vazio = manter o atual)":"webapi_token da sessão Steam (opcional)");
        familyToken.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        wrap.addView(familyToken,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView familyLink=new TextView(this);
        familyLink.setText("Obter token da Família Steam ↗");
        familyLink.setTextColor(getColor(R.color.ludex_accent));
        familyLink.setTextSize(14);
        familyLink.setPadding(0,pad/3,0,pad/2);
        familyLink.setOnClickListener(v->{
            try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://store.steampowered.com/pointssummary/ajaxgetasyncconfig")));}
            catch(Exception e){toast("Não foi possível abrir a página da Steam");}
        });
        wrap.addView(familyLink,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView note=new TextView(this);
        note.setText("A API Key e o token ficam criptografados pelo Android Keystore. A biblioteca própria usa a API oficial. Família Steam usa endpoints não documentados e o token pode precisar ser renovado.");
        note.setTextColor(getColor(R.color.ludex_muted));
        note.setTextSize(12);
        wrap.addView(note,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT));

        new MaterialAlertDialogBuilder(this)
            .setTitle("Steam no Android")
            .setMessage("Importa sua biblioteca Steam completa com horas. Se você configurar o token da Família Steam, também entram os jogos compartilhados e o seu tempo jogado neles.")
            .setView(wrap)
            .setNegativeButton("Cancelar",null)
            .setPositiveButton("Salvar",(d,w)->{
                String id=steamId.getText()==null?"":steamId.getText().toString().trim();
                String key=apiKey.getText()==null?"":apiKey.getText().toString().trim();
                String familyRaw=familyToken.getText()==null?"":familyToken.getText().toString().trim();
                if(!id.matches("\\d{16,20}")){toast("SteamID64 inválido");return;}
                if(key.isEmpty()&&!alreadyHasKey&&familyRaw.isEmpty()&&!alreadyHasFamily){
                    toast("Informe uma API Key ou o token da sessão Steam");
                    return;
                }
                try{
                    db.setSetting("steam.id64",id);
                    if(!key.isEmpty())SecretStore.put(this,"steam.api_key",key);
                    if(!familyRaw.isEmpty()){
                        String clean=SteamPlaytimeClient.extractAccessToken(familyRaw);
                        SecretStore.put(this,"steam.family_token",clean);
                    }
                    updateSteamSyncInfo();
                    toast("Steam configurada");
                    syncSteamPlaytime(true);
                }catch(Exception e){toast("Não foi possível salvar a configuração Steam: "+e.getMessage());}
            }).show();
    }

    private static final class SteamSyncResult{
        final int imported,total,familyGames;
        final String familyError;
        SteamSyncResult(int imported,int total,int familyGames,String familyError){
            this.imported=imported;this.total=total;this.familyGames=familyGames;this.familyError=familyError;
        }
    }

    private SteamSyncResult syncSteamLibraryNow(String steamId,String key,String familyToken) throws Exception {
        LinkedHashMap<String,SteamPlaytimeClient.LibraryGame> merged=new LinkedHashMap<>();
        int familyGames=0;
        String familyError=null;

        if(familyToken!=null&&!familyToken.isEmpty()){
            try{
                SteamPlaytimeClient.FamilyLibrary family=SteamPlaytimeClient.getFamilyLibrary(familyToken,steamId);
                db.setSetting("steam.family_group_id",family.familyGroupId);
                for(SteamPlaytimeClient.LibraryGame game:family.games){
                    merged.put(game.appId,game);
                    if(game.familyShared)familyGames++;
                }
            }catch(Exception e){
                familyError=e.getMessage()==null?"Falha na Família Steam":e.getMessage();
                if(key==null||key.isEmpty())throw e;
            }
        }

        if(key!=null&&!key.isEmpty()){
            List<SteamPlaytimeClient.LibraryGame> owned=SteamPlaytimeClient.getOwnedLibrary(key,steamId);
            for(SteamPlaytimeClient.LibraryGame game:owned)merged.put(game.appId,game);
        }

        int imported=db.syncSteamLibrary(new ArrayList<>(merged.values()));
        db.setSetting("steam.last_sync_ms",Long.toString(System.currentTimeMillis()));
        db.setSetting("steam.last_library_count",Integer.toString(merged.size()));
        db.setSetting("steam.last_family_count",Integer.toString(familyGames));
        return new SteamSyncResult(imported,merged.size(),familyGames,familyError);
    }

    private void syncSteamPlaytime(boolean notify){
        String steamId=db.getSetting("steam.id64","");
        String key=SecretStore.get(this,"steam.api_key");
        String familyToken=SecretStore.get(this,"steam.family_token");
        if(steamId.isEmpty()||(key.isEmpty()&&familyToken.isEmpty())){
            if(notify)showSteamConfig();
            return;
        }
        if(notify)toast("Sincronizando biblioteca Steam…");
        io.execute(()->{
            try{
                SteamSyncResult result=syncSteamLibraryNow(steamId,key,familyToken);
                runOnUiThread(()->{
                    updateSteamSyncInfo();
                    if(notify){
                        String msg=result.total+" jogos Steam sincronizados";
                        if(result.familyGames>0)msg+=" · "+result.familyGames+" da família";
                        if(result.familyError!=null&&!result.familyError.isBlank())msg+=" · Família falhou";
                        toast(msg);
                    }
                    refreshAsync(false);
                });
            }catch(Exception e){
                runOnUiThread(()->toast("Falha na Steam: "+e.getMessage()));
            }
        });
    }

    private void updateEpicSyncInfo(){
        boolean connected=!SecretStore.get(this,"epic.refresh_token").isEmpty();
        long last=db.getSettingLong("epic.last_sync_ms",0);
        if(!connected){
            epicSyncInfo.setText("Conta Epic não conectada. Conecte para importar biblioteca e horas jogadas.");
            return;
        }
        String name=db.getSetting("epic.display_name","");
        String suffix=last>0?" · última sync "+new java.text.SimpleDateFormat("dd/MM HH:mm",Locale.getDefault()).format(new java.util.Date(last)):"";
        String who=name.isBlank()?"Conta Epic conectada":"Epic conectada · "+name;
        long unresolved=db.getSettingLong("epic.last_unresolved_played_count",0);
        String unresolvedInfo=unresolved>0?" · "+unresolved+" artifacts legados sem metadata":"";
        epicSyncInfo.setText(who+suffix+unresolvedInfo);
    }

    private void saveEpicCredentials(EpicLibraryClient.Credentials credentials) throws Exception {
        SecretStore.put(this,"epic.access_token",credentials.accessToken);
        SecretStore.put(this,"epic.refresh_token",credentials.refreshToken);
        db.setSetting("epic.account_id",credentials.accountId);
        db.setSetting("epic.display_name",credentials.displayName);
        db.setSetting("epic.expires_at_ms",Long.toString(credentials.expiresAtMs));
    }

    private void clearEpicCredentials(){
        SecretStore.remove(this,"epic.access_token");
        SecretStore.remove(this,"epic.refresh_token");
        db.setSetting("epic.account_id","");
        db.setSetting("epic.display_name","");
        db.setSetting("epic.expires_at_ms","0");
        updateEpicSyncInfo();
    }

    private EpicLibraryClient.Credentials validEpicCredentials() throws Exception {
        String access=SecretStore.get(this,"epic.access_token");
        String refresh=SecretStore.get(this,"epic.refresh_token");
        String account=db.getSetting("epic.account_id","");
        String display=db.getSetting("epic.display_name","");
        long expires=db.getSettingLong("epic.expires_at_ms",0);
        if(refresh.isEmpty())throw new IllegalStateException("Conta Epic não conectada");

        if(!access.isEmpty()&&!account.isEmpty()&&expires>System.currentTimeMillis()+5*60*1000L){
            return new EpicLibraryClient.Credentials(access,refresh,account,display,expires);
        }

        EpicLibraryClient.Credentials renewed=EpicLibraryClient.refresh(refresh);
        saveEpicCredentials(renewed);
        return renewed;
    }

    private static final class EpicSyncResult{
        final int total,played,unresolvedPlayed,recoveredFromLauncher;
        EpicSyncResult(int total,int played,int unresolvedPlayed,int recoveredFromLauncher){
            this.total=total;this.played=played;this.unresolvedPlayed=unresolvedPlayed;this.recoveredFromLauncher=recoveredFromLauncher;
        }
    }

    private EpicSyncResult syncEpicLibraryNow() throws Exception {
        EpicLibraryClient.Credentials credentials=validEpicCredentials();
        List<EpicLibraryClient.LibraryGame> library=EpicLibraryClient.getOwnedLibrary(credentials,db.getEpicMetadataCache());
        int played=0,unresolvedPlayed=0,recoveredFromLauncher=0;
        for(EpicLibraryClient.LibraryGame game:library){
            if(game.seconds>0){
                played++;
                if(!EpicLibraryClient.isReadableTitle(game.title))unresolvedPlayed++;
                if(game.launcherFallback&&EpicLibraryClient.isReadableTitle(game.title))recoveredFromLauncher++;
            }
        }
        db.syncEpicLibrary(library);
        db.setSetting("epic.last_sync_ms",Long.toString(System.currentTimeMillis()));
        db.setSetting("epic.last_library_count",Integer.toString(library.size()));
        db.setSetting("epic.last_played_count",Integer.toString(played));
        db.setSetting("epic.last_unresolved_played_count",Integer.toString(unresolvedPlayed));
        db.setSetting("epic.last_launcher_recovered_count",Integer.toString(recoveredFromLauncher));
        return new EpicSyncResult(library.size(),played,unresolvedPlayed,recoveredFromLauncher);
    }

    private void syncEpicLibrary(boolean notify){
        if(SecretStore.get(this,"epic.refresh_token").isEmpty()){
            if(notify)showEpicConnect();
            return;
        }
        if(notify)toast("Sincronizando biblioteca Epic…");
        io.execute(()->{
            try{
                EpicSyncResult result=syncEpicLibraryNow();
                runOnUiThread(()->{
                    updateEpicSyncInfo();
                    if(notify){
                        String msg=result.total+" jogos Epic sincronizados · "+result.played+" com horas";
                        if(result.recoveredFromLauncher>0)msg+=" · "+result.recoveredFromLauncher+" recuperados via Launcher";
                        if(result.unresolvedPlayed>0)msg+=" · "+result.unresolvedPlayed+" artifacts legados ignorados";
                        toast(msg);
                    }
                    refreshAsync(false);
                });
            }catch(Exception e){
                String message=e.getMessage()==null?"erro desconhecido":e.getMessage();
                runOnUiThread(()->{
                    updateEpicSyncInfo();
                    toast("Falha na Epic: "+message);
                });
            }
        });
    }

    private void showEpicConnect(){
        boolean connected=!SecretStore.get(this,"epic.refresh_token").isEmpty();
        if(connected){
            String name=db.getSetting("epic.display_name","");
            new MaterialAlertDialogBuilder(this)
                .setTitle("Epic Games")
                .setMessage((name.isBlank()?"Conta conectada":"Conectado como "+name)+
                    ". O Ludex importa sua biblioteca e o playtime registrado pela Epic.")
                .setNegativeButton("Fechar",null)
                .setNeutralButton("Desconectar",(d,w)->{
                    clearEpicCredentials();
                    toast("Conta Epic desconectada");
                })
                .setPositiveButton("Sincronizar",(d,w)->syncEpicLibrary(true))
                .show();
            return;
        }

        int pad=(int)(20*getResources().getDisplayMetrics().density);
        LinearLayout wrap=new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(pad,8,pad,0);

        TextView openLogin=new TextView(this);
        openLogin.setText("1. Abrir login oficial da Epic ↗");
        openLogin.setTextColor(getColor(R.color.ludex_accent));
        openLogin.setTextSize(15);
        openLogin.setPadding(0,pad/3,0,pad/2);
        openLogin.setOnClickListener(v->{
            try{
                startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(EpicLibraryClient.loginUrl())));
                toast("Depois do login, copie o JSON ou authorizationCode e volte ao Ludex");
            }catch(Exception e){toast("Não foi possível abrir o login da Epic");}
        });
        wrap.addView(openLogin,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT));

        EditText code=new EditText(this);
        code.setSingleLine(false);
        code.setMinLines(3);
        code.setMaxLines(6);
        code.setHint("2. Cole o JSON, URL ou authorizationCode");
        code.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        wrap.addView(code,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView note=new TextView(this);
        note.setText("O login acontece no site oficial da Epic. O Ludex não recebe sua senha. Access/refresh tokens ficam criptografados pelo Android Keystore.");
        note.setTextColor(getColor(R.color.ludex_muted));
        note.setTextSize(12);
        note.setPadding(0,pad/2,0,0);
        wrap.addView(note,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT));

        new MaterialAlertDialogBuilder(this)
            .setTitle("Conectar Epic Games")
            .setMessage("Faça login no navegador. A página de retorno mostra um authorizationCode; você pode colar o JSON inteiro.")
            .setView(wrap)
            .setNegativeButton("Cancelar",null)
            .setPositiveButton("Conectar",(d,w)->{
                String raw=code.getText()==null?"":code.getText().toString().trim();
                if(raw.isEmpty()){toast("Cole o authorizationCode da Epic");return;}
                connectEpic(raw);
            })
            .show();
    }

    private void connectEpic(String rawCode){
        toast("Conectando à Epic…");
        io.execute(()->{
            try{
                EpicLibraryClient.Credentials credentials=EpicLibraryClient.exchangeAuthorizationCode(rawCode);
                saveEpicCredentials(credentials);
                EpicSyncResult result=syncEpicLibraryNow();
                runOnUiThread(()->{
                    updateEpicSyncInfo();
                    toast("Epic conectada · "+result.total+" jogos sincronizados");
                    refreshAsync(false);
                });
            }catch(Exception e){
                String message=e.getMessage()==null?"erro desconhecido":e.getMessage();
                runOnUiThread(()->toast("Falha ao conectar Epic: "+message));
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
        LudexDb.ManualInfo manualInfo=db.getManualInfo(g.id);
        if(manualInfo!=null&&!manualInfo.imageUri.isBlank()){
            loadArtworkAsync("manual:"+manualInfo.imageUri,view,expectedTag,()->loadManualArtwork(manualInfo.imageUri));
            return;
        }
        LudexDb.SteamInfo steamInfo=db.getSteamInfo(g.id);
        if(steamInfo!=null&&!g.gameNative){
            loadArtworkAsync("steam:"+steamInfo.appId,view,expectedTag,()->GameArtworkLoader.load(this,"steam",steamInfo.appId));
            return;
        }
        LudexDb.EpicInfo epicInfo=db.getEpicInfo(g.id);
        if(epicInfo!=null&&!epicInfo.imageUrl.isBlank()){
            loadArtworkAsync("epic:"+epicInfo.appName,view,expectedTag,()->EpicArtworkLoader.load(this,epicInfo.appName,epicInfo.imageUrl));
            return;
        }
        if("nintendo".equals(g.source)){
            LudexDb.NintendoInfo n=db.getNintendoInfo(g.id);
            if(n!=null&&!n.imageUrl.isBlank()){
                loadArtworkAsync("nintendo:"+n.titleId,view,expectedTag,()->NintendoArtworkLoader.load(this,n.titleId,n.imageUrl));
            }
            return;
        }
        if(g.gameNative){
            LudexDb.GameNativeLaunch launch=db.getGameNativeLaunch(g.id);
            if(launch==null)return;
            loadArtworkAsync("gamenative:"+launch.provider+":"+launch.externalId,view,expectedTag,
                ()->GameArtworkLoader.load(this,launch.provider,launch.externalId));
            return;
        }
        if(g.emulated){
            String pkg=null;
            if(g.eden){LudexDb.EdenLaunch e=db.getEdenLaunch(g.id);if(e!=null)pkg=e.packageName;}
            else {LudexDb.EmulatorLaunch e=db.getEmulatorLaunch(g.id);if(e!=null)pkg=e.packageName;}
            EmulatorRegistry.Emulator em=EmulatorRegistry.get(pkg);
            String libretro=em==null?null:em.libretroSystem;
            String key=SecretStore.get(this,"steamgriddb.api_key");
            String cacheKey="emulated:"+g.platform+":"+String.valueOf(libretro)+":"+g.title;
            loadArtworkAsync(cacheKey,view,expectedTag,()->EmulatedArtworkLoader.load(this,g.platform,libretro,g.title,key));
        }
    }

    private void loadArtworkAsync(String cacheKey,ImageView view,String expectedTag,Callable<android.graphics.Bitmap> loader){
        android.graphics.Bitmap cached=artworkMemory.get(cacheKey);
        if(cached!=null){
            applyArtwork(view,expectedTag,cached);
            return;
        }
        artworkIo.execute(()->{
            try{
                android.graphics.Bitmap bmp=loader.call();
                if(bmp==null)return;
                artworkMemory.put(cacheKey,bmp);
                runOnUiThread(()->applyArtwork(view,expectedTag,bmp));
            }catch(Exception ignored){}
        });
    }

    private android.graphics.Bitmap loadManualArtwork(String uriText){
        if(uriText==null||uriText.isBlank())return null;
        try(InputStream in=getContentResolver().openInputStream(Uri.parse(uriText))){
            if(in==null)return null;
            return android.graphics.BitmapFactory.decodeStream(in);
        }catch(Exception e){return null;}
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
        switch(source){case "steam":return "Steam";case "steam-family":return "Steam Família";case "epic":return "Epic";case "gog":return "GOG";case "android":return "Android";case "xbox":return "Xbox";case "eden":return "Eden";case "emulator":return "Emulado";case "nintendo":return "Nintendo";case "nintendo-manual":return "Nintendo";case "xbox-manual":return "Xbox";case "playstation-manual":return "PlayStation";case "pc-manual":return "PC";case "other-manual":return "Manual";default:return source;}
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
        interface GameClick{void click(DisplayGame g);}
        private final ArrayList<DisplayGame> all=new ArrayList<>(),shown=new ArrayList<>();
        private final GameClick click,launch;
        GameAdapter(GameClick click,GameClick launch){this.click=click;this.launch=launch;}
        void setAll(List<LudexDb.GameRow> x){all.clear();all.addAll(buildDisplayGames(x));filter("",tab);}
        void filter(String query,Tab mode){
            shown.clear();String q=query==null?"":query.trim().toLowerCase(Locale.ROOT);
            for(DisplayGame g:all){
                if(g.seconds<=0)continue;
                if(!groupMatchesTab(g,mode))continue;
                if(!groupMatchesLibrary(g,libraryFilter))continue;
                if(!q.isEmpty()&&!g.title.toLowerCase(Locale.ROOT).contains(q)&&!librariesLabel(g).toLowerCase(Locale.ROOT).contains(q))continue;
                shown.add(g);
            }
            if(sortByPlaytime){
                shown.sort(Comparator.comparingLong((DisplayGame g)->g.seconds).reversed()
                    .thenComparing(g->g.title,String.CASE_INSENSITIVE_ORDER));
            }else{
                shown.sort(Comparator.comparing(g->g.title,String.CASE_INSENSITIVE_ORDER));
            }
            notifyDataSetChanged();
        }
        @NonNull public Holder onCreateViewHolder(@NonNull android.view.ViewGroup p,int t){return new Holder(getLayoutInflater().inflate(R.layout.item_game,p,false));}
        public void onBindViewHolder(@NonNull Holder h,int pos){
            DisplayGame g=shown.get(pos);
            LudexDb.GameRow artwork=artworkVariant(g);
            LudexDb.GameRow launchable=launchVariant(g);
            h.title.setText(g.title);
            h.meta.setText(librariesLabel(g)+(g.variants.size()>1?" · "+g.variants.size()+" fontes":""));
            String date=gameDateLabel(g);
            h.date.setText(date);
            h.date.setVisibility(date.isBlank()?View.GONE:View.VISIBLE);
            h.time.setText(format(g.seconds));
            h.status.setText(g.favorite?"★ "+g.status:g.status);
            if(artwork!=null)bindGameArtwork(artwork,h.icon);
            else h.icon.setVisibility(View.INVISIBLE);
            h.play.setVisibility(launchable!=null?View.VISIBLE:View.GONE);
            h.itemView.setOnClickListener(v->click.click(g));
            h.play.setOnClickListener(v->launch.click(g));
        }
        public int getItemCount(){return shown.size();}
        final class Holder extends RecyclerView.ViewHolder{
            TextView title,meta,date,time,status;ImageView icon;Button play;
            Holder(View v){super(v);title=v.findViewById(R.id.game_title);meta=v.findViewById(R.id.game_meta);date=v.findViewById(R.id.game_date);time=v.findViewById(R.id.game_time);status=v.findViewById(R.id.game_status);icon=v.findViewById(R.id.game_icon);play=v.findViewById(R.id.game_play);}
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
