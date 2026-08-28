mod db;
mod emulation;
mod models;
mod providers;

use std::sync::Mutex;
use tauri::Manager;
use uuid::Uuid;
use models::Game;

struct AppState {
    db: Mutex<rusqlite::Connection>,
}

#[tauri::command]
fn list_games(state: tauri::State<'_, AppState>) -> Result<Vec<Game>, String> {
    let connection = state.db.lock().map_err(|_| "Banco de dados indisponível".to_string())?;
    db::list_games(&connection)
}

#[tauri::command]
fn add_manual_game(
    state: tauri::State<'_, AppState>,
    title: String,
    platform: String,
) -> Result<(), String> {
    if title.trim().is_empty() {
        return Err("O título não pode ser vazio".into());
    }
    let connection = state.db.lock().map_err(|_| "Banco de dados indisponível".to_string())?;
    db::add_manual_game(&connection, &Uuid::new_v4().to_string(), title.trim(), platform.trim())
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .setup(|app| {
            let data_dir = app.path().app_data_dir()?;
            let connection = db::open(&data_dir.join("ludex.db"))
                .map_err(std::io::Error::other)?;
            app.manage(AppState { db: Mutex::new(connection) });
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![list_games, add_manual_game])
        .run(tauri::generate_context!())
        .expect("erro fatal ao iniciar o Ludex");
}
