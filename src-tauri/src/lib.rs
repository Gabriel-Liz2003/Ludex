mod db;
mod emulation;
mod identity;
mod metadata;
mod models;
mod process_monitor;
mod product;
mod product_models;
mod providers;
mod sessions;

use models::{
    Game, GameDetails, Installation, LaunchResult, ProviderImportResult, ProviderStatus,
    SteamImportResult, SteamStatus,
};
use process_monitor::ProcessMonitor;
use product_models::{
    CollectionMembership, CollectionRecord, DiagnosticItem, EmulatorRecord, LibraryStats,
    MetadataRecord, RomRecord, RomScanResult, SaveBackupRecord, SyncSummary,
};
use providers::steam::SteamProvider;
use std::{
    path::{Path, PathBuf},
    process::Command,
};
use tauri::Manager;
use tracing::error;
use uuid::Uuid;

struct AppState {
    db_path: PathBuf,
    data_dir: PathBuf,
}
fn open_product(path: &Path) -> Result<rusqlite::Connection, String> {
    let c = db::open(path)?;
    product::migrate(&c)?;
    Ok(c)
}

#[tauri::command]
fn list_games(state: tauri::State<'_, AppState>) -> Result<Vec<Game>, String> {
    db::list_games(&open_product(&state.db_path)?)
}
#[tauri::command]
fn get_game_details(
    state: tauri::State<'_, AppState>,
    game_id: String,
) -> Result<Option<GameDetails>, String> {
    db::get_game_details(&open_product(&state.db_path)?, &game_id)
}
#[tauri::command]
fn add_manual_game(
    state: tauri::State<'_, AppState>,
    title: String,
    platform: String,
    executable: Option<String>,
    working_dir: Option<String>,
    launch_args: Option<String>,
) -> Result<(), String> {
    if title.trim().is_empty() {
        return Err("O título não pode ser vazio".into());
    }
    let c = open_product(&state.db_path)?;
    db::add_manual_game(
        &c,
        &Uuid::new_v4().to_string(),
        title.trim(),
        platform.trim(),
        executable.as_deref().filter(|v| !v.trim().is_empty()),
        working_dir.as_deref().filter(|v| !v.trim().is_empty()),
        launch_args.as_deref().filter(|v| !v.trim().is_empty()),
    )
}

#[tauri::command]
async fn provider_statuses(
    state: tauri::State<'_, AppState>,
) -> Result<Vec<ProviderStatus>, String> {
    let path = state.db_path.clone();
    tauri::async_runtime::spawn_blocking(move || {
        let c = open_product(&path)?;
        let mut out = Vec::new();
        for id in providers::PROVIDER_IDS {
            match providers::scan_provider(id) {
                Ok(s) => {
                    let last = db::get_setting(&c, &format!("{id}.last_sync"))?;
                    out.push(ProviderStatus {
                        id: s.id.into(),
                        name: s.name.into(),
                        detected: s.root.is_some() || !s.installations.is_empty(),
                        root_path: s.root.map(|p| p.to_string_lossy().to_string()),
                        games_found: s.installations.len(),
                        last_sync: last,
                        message: s.message,
                        can_launch: s.can_launch,
                    });
                }
                Err(e) => out.push(ProviderStatus {
                    id: (*id).into(),
                    name: (*id).into(),
                    detected: false,
                    root_path: None,
                    games_found: 0,
                    last_sync: None,
                    message: e,
                    can_launch: false,
                }),
            }
        }
        Ok(out)
    })
    .await
    .map_err(|e| e.to_string())?
}
#[tauri::command]
async fn sync_provider(
    state: tauri::State<'_, AppState>,
    provider: String,
) -> Result<ProviderImportResult, String> {
    let path = state.db_path.clone();
    tauri::async_runtime::spawn_blocking(move || {
        let scan = providers::scan_provider(&provider)?;
        let root = scan
            .root
            .as_ref()
            .map(|p| p.to_string_lossy().to_string())
            .unwrap_or_default();
        let c = open_product(&path)?;
        let result = product::sync_provider(&c, &provider, &scan.installations, &root)?;
        if provider == "steam" {
            let _ = metadata::refresh_local_metadata(&c);
        }
        Ok(result)
    })
    .await
    .map_err(|e| e.to_string())?
}

#[tauri::command]
async fn steam_status(state: tauri::State<'_, AppState>) -> Result<SteamStatus, String> {
    let path = state.db_path.clone();
    tauri::async_runtime::spawn_blocking(move || {
        let c = open_product(&path)?;
        let last = db::get_setting(&c, "steam.last_sync")?;
        let Some(root) = SteamProvider::detect_root() else {
            return Ok(SteamStatus {
                detected: false,
                root_path: None,
                library_count: 0,
                games_found: 0,
                last_sync: last,
            });
        };
        let libraries = SteamProvider::library_paths(&root)?;
        let games = SteamProvider::scan_from_root(&root)?.len();
        Ok(SteamStatus {
            detected: true,
            root_path: Some(root.to_string_lossy().to_string()),
            library_count: libraries.len(),
            games_found: games,
            last_sync: last,
        })
    })
    .await
    .map_err(|e| e.to_string())?
}
#[tauri::command]
async fn sync_steam(state: tauri::State<'_, AppState>) -> Result<SteamImportResult, String> {
    let path = state.db_path.clone();
    tauri::async_runtime::spawn_blocking(move||{let root=SteamProvider::detect_root().ok_or_else(||"Steam não encontrada".to_string())?;let libraries=SteamProvider::library_paths(&root)?;let items=SteamProvider::scan_from_root(&root)?;let c=open_product(&path)?;let r=db::import_installations(&c,"steam",&items,&root.to_string_lossy(),libraries.len())?;c.execute("INSERT INTO settings(key,value) VALUES('steam.last_sync',CURRENT_TIMESTAMP) ON CONFLICT(key) DO UPDATE SET value=excluded.value",[]).map_err(|e|e.to_string())?;Ok(r)}).await.map_err(|e|e.to_string())?
}

#[cfg(windows)]
fn open_uri(uri: &str) -> Result<(), String> {
    Command::new("explorer.exe")
        .arg(uri)
        .spawn()
        .map_err(|e| format!("Falha ao abrir URI: {e}"))?;
    Ok(())
}
#[cfg(not(windows))]
fn open_uri(uri: &str) -> Result<(), String> {
    Command::new("xdg-open")
        .arg(uri)
        .spawn()
        .map_err(|e| e.to_string())?;
    Ok(())
}
fn percent_triplet(value: &str) -> String {
    value
        .chars()
        .map(|c| match c {
            ':' => "%3A".to_string(),
            ' ' => "%20".to_string(),
            c if c.is_ascii_alphanumeric() || "-_.~".contains(c) => c.to_string(),
            c => format!("%{:02X}", c as u32),
        })
        .collect::<Vec<_>>()
        .join("")
}
fn spawn_direct(i: &Installation) -> Result<u32, String> {
    let exe = i
        .executable
        .as_deref()
        .ok_or_else(|| "Executável não configurado para esta instalação".to_string())?;
    if !Path::new(exe).is_file() {
        return Err(format!("Executável não encontrado: {exe}"));
    }
    let mut cmd = Command::new(exe);
    if let Some(d) = i.working_dir.as_deref().or(i.install_dir.as_deref()) {
        if Path::new(d).is_dir() {
            cmd.current_dir(d);
        }
    }
    if let Some(a) = i.launch_args.as_deref() {
        cmd.args(shlex::split(a).ok_or_else(|| "Argumentos inválidos".to_string())?);
    }
    Ok(cmd
        .spawn()
        .map_err(|e| format!("Falha ao iniciar jogo: {e}"))?
        .id())
}
fn launch_installation(i: &Installation) -> Result<Option<u32>, String> {
    match i.provider.as_str(){
 "steam"=>{let id=i.external_id.as_deref().ok_or("Steam AppID ausente")?;if !id.chars().all(|c|c.is_ascii_digit()){return Err("Steam AppID inválido".into())}open_uri(&format!("steam://rungameid/{id}"))?;Ok(None)},
 "epic"=>{let id=i.external_id.as_deref().ok_or("Epic artifact ausente")?;open_uri(&format!("com.epicgames.launcher://apps/{}?action=launch&silent=true",percent_triplet(id)))?;Ok(None)},
 "xbox"=>{let id=i.external_id.as_deref().ok_or("AUMID ausente")?;#[cfg(windows)]{Command::new("explorer.exe").arg(format!("shell:AppsFolder\\{id}")).spawn().map_err(|e|e.to_string())?;Ok(None)}#[cfg(not(windows))]{Err("Xbox/MS Store disponível somente no Windows".into())}},
 "ubisoft"=>{let id=i.external_id.as_deref().ok_or("Ubisoft game id ausente")?;if !id.chars().all(|c|c.is_ascii_digit()){return Err("Ubisoft game id inválido".into())}open_uri(&format!("uplay://launch/{id}/0"))?;Ok(None)},
 "manual"|"gog"|"ea"=>spawn_direct(i).map(Some),
 "battlenet"=>Err("Battle.net foi importado, mas o product code não é exposto de forma estável pelos dados locais detectados. Configure um executável/atalho manual para launch.".into()),
 "emulation"=>spawn_direct(i).map(Some),
 p=>Err(format!("Provider '{p}' não possui estratégia de launch segura"))}
}

fn choose_installation(
    c: &rusqlite::Connection,
    game_id: &str,
    installation_id: Option<&str>,
) -> Result<Installation, String> {
    if let Some(id) = installation_id {
        return c.query_row("SELECT id,game_id,provider,external_id,executable,install_dir,working_dir,launch_args,installed FROM installations WHERE id=?1 AND game_id=?2",rusqlite::params![id,game_id],|r|Ok(Installation{id:r.get(0)?,game_id:r.get(1)?,provider:r.get(2)?,external_id:r.get(3)?,executable:r.get(4)?,install_dir:r.get(5)?,working_dir:r.get(6)?,launch_args:r.get(7)?,installed:r.get::<_,i64>(8)?!=0})).map_err(|e|e.to_string());
    }
    db::installation_for_launch(c, game_id)?
        .ok_or_else(|| "Nenhuma instalação jogável encontrada".into())
}
fn launch_internal(
    path: &Path,
    game_id: &str,
    installation_id: Option<&str>,
) -> Result<LaunchResult, String> {
    let c = open_product(path)?;
    if db::active_session_exists(&c, game_id)? {
        return Ok(LaunchResult {
            launched: false,
            already_running: true,
            message: "O Ludex já acompanha este jogo.".into(),
        });
    }
    let i = choose_installation(&c, game_id, installation_id)?;
    if !i.installed {
        return Err("Esta instalação não está disponível".into());
    }
    drop(c);
    let baseline = ProcessMonitor::snapshot_pids();
    let direct = launch_installation(&i)?;
    sessions::spawn_for_launch(path.to_path_buf(), i, baseline, direct);
    Ok(LaunchResult {
        launched: true,
        already_running: false,
        message: "Jogo iniciado; a sessão começa após confirmação do processo.".into(),
    })
}
#[tauri::command]
fn launch_game(state: tauri::State<'_, AppState>, game_id: String) -> Result<LaunchResult, String> {
    launch_internal(&state.db_path, &game_id, None)
}
#[tauri::command]
fn launch_game_installation(
    state: tauri::State<'_, AppState>,
    game_id: String,
    installation_id: String,
) -> Result<LaunchResult, String> {
    launch_internal(&state.db_path, &game_id, Some(&installation_id))
}

#[tauri::command]
fn set_favorite(
    state: tauri::State<'_, AppState>,
    game_id: String,
    value: bool,
) -> Result<(), String> {
    product::set_favorite(&open_product(&state.db_path)?, &game_id, value)
}
#[tauri::command]
fn set_game_status(
    state: tauri::State<'_, AppState>,
    game_id: String,
    status: String,
) -> Result<(), String> {
    product::set_status(&open_product(&state.db_path)?, &game_id, &status)
}
#[tauri::command]
fn get_metadata(
    state: tauri::State<'_, AppState>,
    game_id: String,
) -> Result<Option<MetadataRecord>, String> {
    product::metadata(&open_product(&state.db_path)?, &game_id)
}
#[tauri::command]
fn save_metadata(
    state: tauri::State<'_, AppState>,
    metadata: MetadataRecord,
) -> Result<(), String> {
    product::update_metadata(&open_product(&state.db_path)?, &metadata)
}
#[tauri::command]
fn collection_memberships(
    state: tauri::State<'_, AppState>,
    game_id: String,
) -> Result<Vec<CollectionMembership>, String> {
    product::collection_memberships(&open_product(&state.db_path)?, &game_id)
}

#[tauri::command]
fn list_collections(state: tauri::State<'_, AppState>) -> Result<Vec<CollectionRecord>, String> {
    product::collections(&open_product(&state.db_path)?)
}
#[tauri::command]
fn create_collection(state: tauri::State<'_, AppState>, name: String) -> Result<String, String> {
    if name.trim().is_empty() {
        return Err("Nome vazio".into());
    }
    product::create_collection(&open_product(&state.db_path)?, &name)
}
#[tauri::command]
fn rename_collection(
    state: tauri::State<'_, AppState>,
    collection_id: String,
    name: String,
) -> Result<(), String> {
    product::rename_collection(&open_product(&state.db_path)?, &collection_id, &name)
}
#[tauri::command]
fn delete_collection(
    state: tauri::State<'_, AppState>,
    collection_id: String,
) -> Result<(), String> {
    product::delete_collection(&open_product(&state.db_path)?, &collection_id)
}
#[tauri::command]
fn set_collection_game(
    state: tauri::State<'_, AppState>,
    collection_id: String,
    game_id: String,
    add: bool,
) -> Result<(), String> {
    product::set_collection_game(
        &open_product(&state.db_path)?,
        &collection_id,
        &game_id,
        add,
    )
}
#[tauri::command]
fn merge_games(
    state: tauri::State<'_, AppState>,
    target_id: String,
    source_id: String,
) -> Result<(), String> {
    let mut c = open_product(&state.db_path)?;
    product::merge_games(&mut c, &target_id, &source_id)
}
#[tauri::command]
fn split_installation(
    state: tauri::State<'_, AppState>,
    installation_id: String,
) -> Result<String, String> {
    let mut c = open_product(&state.db_path)?;
    product::split_installation(&mut c, &installation_id)
}

#[tauri::command]
fn list_emulators(state: tauri::State<'_, AppState>) -> Result<Vec<EmulatorRecord>, String> {
    product::emulators(&open_product(&state.db_path)?)
}
#[tauri::command]
fn save_emulator(
    state: tauri::State<'_, AppState>,
    emulator: EmulatorRecord,
) -> Result<(), String> {
    if !Path::new(&emulator.executable).is_file() {
        return Err("Executável do emulador não encontrado".into());
    }
    product::upsert_emulator(&open_product(&state.db_path)?, &emulator)
}
#[tauri::command]
fn emulator_preset(name: String) -> Option<(String, String, String)> {
    emulation::preset(&name).map(|(a, b, c)| (a.into(), b.into(), c.into()))
}
#[tauri::command]
fn scan_roms(
    state: tauri::State<'_, AppState>,
    folder: String,
    platform: String,
    emulator_id: Option<String>,
    recursive: bool,
) -> Result<RomScanResult, String> {
    product::scan_roms(
        &open_product(&state.db_path)?,
        &folder,
        &platform,
        emulator_id.as_deref(),
        recursive,
    )
}
#[tauri::command]
fn list_roms(state: tauri::State<'_, AppState>) -> Result<Vec<RomRecord>, String> {
    product::roms(&open_product(&state.db_path)?)
}
#[tauri::command]
fn launch_rom(state: tauri::State<'_, AppState>, rom_id: String) -> Result<LaunchResult, String> {
    let c = open_product(&state.db_path)?;
    let rom = product::roms(&c)?
        .into_iter()
        .find(|r| r.id == rom_id)
        .ok_or("ROM não encontrada")?;
    if !Path::new(&rom.path).is_file() {
        return Err("Arquivo da ROM não existe".into());
    }
    let emu_id = rom.emulator_id.clone().ok_or("Nenhum emulador associado")?;
    let emu = product::emulators(&c)?
        .into_iter()
        .find(|e| e.id == emu_id)
        .ok_or("Emulador não encontrado")?;
    let args = emulation::render_arguments(
        rom.launch_args
            .as_deref()
            .unwrap_or(&emu.arguments_template),
        Path::new(&rom.path),
        rom.core.as_deref().or(emu.core.as_deref()),
    )?;
    let iid = format!("emulation:{}", rom.id);
    c.execute("INSERT INTO installations(id,game_id,source,provider,external_id,executable,install_dir,working_dir,launch_args,installed) VALUES(?1,?2,'emulation','emulation',?3,?4,?5,?5,?6,1) ON CONFLICT(id) DO UPDATE SET executable=excluded.executable,launch_args=excluded.launch_args,installed=1",rusqlite::params![iid,rom.game_id,rom.id,emu.executable,Path::new(&emu.executable).parent().map(|p|p.to_string_lossy().to_string()),shlex::try_join(args.iter().map(String::as_str)).map_err(|e|e.to_string())?]).map_err(|e|e.to_string())?;
    drop(c);
    launch_internal(&state.db_path, &rom.game_id, Some(&iid))
}

#[tauri::command]
fn library_stats(state: tauri::State<'_, AppState>) -> Result<LibraryStats, String> {
    product::library_stats(&open_product(&state.db_path)?)
}
#[tauri::command]
fn export_backup_json(state: tauri::State<'_, AppState>) -> Result<String, String> {
    product::backup_json(&open_product(&state.db_path)?)
}
#[tauri::command]
fn import_sync_json(
    state: tauri::State<'_, AppState>,
    json: String,
) -> Result<SyncSummary, String> {
    let mut c = open_product(&state.db_path)?;
    product::import_sync_json(&mut c, &json)
}
#[tauri::command]
fn backup_database(state: tauri::State<'_, AppState>) -> Result<String, String> {
    product::create_db_backup(&state.db_path, &state.data_dir.join("backups"))
}
#[tauri::command]
fn refresh_local_metadata(state: tauri::State<'_, AppState>) -> Result<usize, String> {
    metadata::refresh_local_metadata(&open_product(&state.db_path)?)
}
#[tauri::command]
fn backup_save_path(
    state: tauri::State<'_, AppState>,
    source: String,
    emulator_id: Option<String>,
) -> Result<SaveBackupRecord, String> {
    product::backup_save_path(
        &open_product(&state.db_path)?,
        &source,
        &state.data_dir.join("save-backups"),
        emulator_id.as_deref(),
    )
}
#[tauri::command]
fn list_save_backups(state: tauri::State<'_, AppState>) -> Result<Vec<SaveBackupRecord>, String> {
    product::save_backups(&open_product(&state.db_path)?)
}
#[tauri::command]
fn restore_save_backup(state: tauri::State<'_, AppState>, backup_id: String) -> Result<(), String> {
    product::restore_save_backup(&open_product(&state.db_path)?, &backup_id)
}

#[tauri::command]
fn diagnostics(state: tauri::State<'_, AppState>) -> Result<Vec<DiagnosticItem>, String> {
    product::diagnostics(&open_product(&state.db_path)?)
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let _ = tracing_subscriber::fmt().with_target(false).try_init();
    let result = tauri::Builder::default()
        .setup(|app| {
            let data = app.path().app_data_dir()?;
            let path = data.join("ludex.db");
            open_product(&path).map_err(std::io::Error::other)?;
            if let Err(e) = sessions::recover_incomplete_sessions(path.clone()) {
                error!(%e,"Falha ao recuperar sessões")
            };
            sessions::spawn_external_detector(path.clone());
            app.manage(AppState {
                db_path: path,
                data_dir: data,
            });
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            list_games,
            get_game_details,
            add_manual_game,
            provider_statuses,
            sync_provider,
            steam_status,
            sync_steam,
            launch_game,
            launch_game_installation,
            set_favorite,
            set_game_status,
            get_metadata,
            save_metadata,
            collection_memberships,
            list_collections,
            create_collection,
            rename_collection,
            delete_collection,
            set_collection_game,
            merge_games,
            split_installation,
            list_emulators,
            save_emulator,
            emulator_preset,
            scan_roms,
            list_roms,
            launch_rom,
            library_stats,
            export_backup_json,
            import_sync_json,
            backup_save_path,
            list_save_backups,
            restore_save_backup,
            refresh_local_metadata,
            backup_database,
            diagnostics
        ])
        .run(tauri::generate_context!());
    if let Err(e) = result {
        eprintln!("Ludex encerrou com erro: {e}");
    }
}
