mod db;
mod emulation;
mod identity;
mod models;
mod process_monitor;
mod providers;
mod sessions;

use std::{path::PathBuf, process::Command};

use models::{Game, GameDetails, LaunchResult, SteamImportResult, SteamStatus};
use process_monitor::ProcessMonitor;
use providers::steam::SteamProvider;
use tauri::Manager;
use tracing::{error, info};
use uuid::Uuid;

struct AppState {
    db_path: PathBuf,
}

#[tauri::command]
fn list_games(state: tauri::State<'_, AppState>) -> Result<Vec<Game>, String> {
    let connection = db::open(&state.db_path)?;
    db::list_games(&connection)
}

#[tauri::command]
fn get_game_details(state: tauri::State<'_, AppState>, game_id: String) -> Result<Option<GameDetails>, String> {
    let connection = db::open(&state.db_path)?;
    db::get_game_details(&connection, &game_id)
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
    let executable = executable.filter(|value| !value.trim().is_empty());
    let working_dir = working_dir.filter(|value| !value.trim().is_empty());
    let launch_args = launch_args.filter(|value| !value.trim().is_empty());
    let connection = db::open(&state.db_path)?;
    db::add_manual_game(
        &connection,
        &Uuid::new_v4().to_string(),
        title.trim(),
        platform.trim(),
        executable.as_deref(),
        working_dir.as_deref(),
        launch_args.as_deref(),
    )
}

#[tauri::command]
async fn steam_status(state: tauri::State<'_, AppState>) -> Result<SteamStatus, String> {
    let db_path = state.db_path.clone();
    tauri::async_runtime::spawn_blocking(move || {
        let connection = db::open(&db_path)?;
        let last_sync = db::get_setting(&connection, "steam.last_sync")?;
        let Some(root) = SteamProvider::detect_root() else {
            return Ok(SteamStatus { detected: false, root_path: None, library_count: 0, games_found: 0, last_sync });
        };
        let libraries = SteamProvider::library_paths(&root)?;
        let games_found = SteamProvider::scan_from_root(&root)?.len();
        Ok(SteamStatus {
            detected: true,
            root_path: Some(root.to_string_lossy().to_string()),
            library_count: libraries.len(),
            games_found,
            last_sync,
        })
    }).await.map_err(|e| e.to_string())?
}

#[tauri::command]
async fn sync_steam(state: tauri::State<'_, AppState>) -> Result<SteamImportResult, String> {
    let db_path = state.db_path.clone();
    tauri::async_runtime::spawn_blocking(move || {
        let root = SteamProvider::detect_root().ok_or_else(|| "Steam não encontrada neste computador".to_string())?;
        let libraries = SteamProvider::library_paths(&root)?;
        let installations = SteamProvider::scan_from_root(&root)?;
        let connection = db::open(&db_path)?;
        let result = db::import_installations(&connection, "steam", &installations, &root.to_string_lossy(), libraries.len())?;
        info!(games_found=result.games_found, created=result.games_created, deduplicated=result.deduplicated, "Importação Steam concluída");
        Ok(result)
    }).await.map_err(|e| e.to_string())?
}

#[cfg(windows)]
fn launch_steam(app_id: &str) -> Result<(), String> {
    let uri = format!("steam://rungameid/{app_id}");
    Command::new("cmd")
        .args(["/C", "start", "", &uri])
        .spawn()
        .map_err(|e| format!("Falha ao abrir Steam URI: {e}"))?;
    Ok(())
}

#[cfg(not(windows))]
fn launch_steam(app_id: &str) -> Result<(), String> {
    let uri = format!("steam://rungameid/{app_id}");
    Command::new("xdg-open").arg(uri).spawn().map_err(|e| e.to_string())?;
    Ok(())
}

#[tauri::command]
fn launch_game(state: tauri::State<'_, AppState>, game_id: String) -> Result<LaunchResult, String> {
    let connection = db::open(&state.db_path)?;
    if db::active_session_exists(&connection, &game_id)? {
        return Ok(LaunchResult { launched: false, already_running: true, message: "O Ludex já está acompanhando uma sessão deste jogo.".into() });
    }
    let installation = db::installation_for_launch(&connection, &game_id)?
        .ok_or_else(|| "Nenhuma instalação jogável foi encontrada para este jogo".to_string())?;
    drop(connection);

    let baseline = ProcessMonitor::snapshot_pids();
    let direct_pid = match installation.provider.as_str() {
        "steam" => {
            let app_id = installation.external_id.as_deref().ok_or_else(|| "Steam AppID ausente".to_string())?;
            launch_steam(app_id)?;
            None
        }
        "manual" => {
            let executable = installation.executable.as_deref().ok_or_else(|| "Executável não configurado".to_string())?;
            let mut command = Command::new(executable);
            if let Some(directory) = installation.working_dir.as_deref() {
                command.current_dir(directory);
            }
            if let Some(arguments) = installation.launch_args.as_deref() {
                let args = shlex::split(arguments).ok_or_else(|| "Argumentos de inicialização inválidos".to_string())?;
                command.args(args);
            }
            Some(command.spawn().map_err(|e| format!("Falha ao iniciar o jogo: {e}"))?.id())
        }
        provider => return Err(format!("O provider '{provider}' ainda não possui launcher implementado")),
    };

    info!(game_id=%game_id, provider=%installation.provider, "Launch solicitado");
    sessions::spawn_for_launch(state.db_path.clone(), installation, baseline, direct_pid);
    Ok(LaunchResult { launched: true, already_running: false, message: "Jogo iniciado. A sessão começará quando o processo real for confirmado.".into() })
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let _ = tracing_subscriber::fmt().with_target(false).try_init();
    tauri::Builder::default()
        .setup(|app| {
            let data_dir = app.path().app_data_dir()?;
            let db_path = data_dir.join("ludex.db");
            db::open(&db_path).map_err(std::io::Error::other)?;
            if let Err(error) = sessions::recover_incomplete_sessions(db_path.clone()) {
                error!(%error, "Falha ao recuperar sessões incompletas");
            }
            app.manage(AppState { db_path });
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            list_games,
            get_game_details,
            add_manual_game,
            steam_status,
            sync_steam,
            launch_game
        ])
        .run(tauri::generate_context!())
        .expect("erro fatal ao iniciar o Ludex");
}
