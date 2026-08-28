use std::{fs, path::Path};
use rusqlite::{params, Connection};
use crate::models::Game;

pub fn open(path: &Path) -> Result<Connection, String> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).map_err(|e| e.to_string())?;
    }
    let connection = Connection::open(path).map_err(|e| e.to_string())?;
    connection.execute_batch(
        "PRAGMA journal_mode=WAL;
         PRAGMA foreign_keys=ON;
         CREATE TABLE IF NOT EXISTS games (
           id TEXT PRIMARY KEY,
           title TEXT NOT NULL,
           platform TEXT NOT NULL,
           source TEXT NOT NULL,
           executable TEXT,
           favorite INTEGER NOT NULL DEFAULT 0,
           status TEXT NOT NULL DEFAULT 'Quero jogar',
           total_seconds INTEGER NOT NULL DEFAULT 0,
           created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
           updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
         );
         CREATE INDEX IF NOT EXISTS idx_games_title ON games(title COLLATE NOCASE);
         CREATE INDEX IF NOT EXISTS idx_games_platform ON games(platform);
         CREATE INDEX IF NOT EXISTS idx_games_source ON games(source);
         CREATE TABLE IF NOT EXISTS installations (
           id TEXT PRIMARY KEY,
           game_id TEXT NOT NULL REFERENCES games(id) ON DELETE CASCADE,
           source TEXT NOT NULL,
           executable TEXT,
           launch_args TEXT,
           installed INTEGER NOT NULL DEFAULT 1,
           UNIQUE(game_id, source, executable)
         );
         CREATE TABLE IF NOT EXISTS play_sessions (
           id TEXT PRIMARY KEY,
           game_id TEXT NOT NULL REFERENCES games(id) ON DELETE CASCADE,
           started_at TEXT NOT NULL,
           ended_at TEXT,
           duration_seconds INTEGER NOT NULL DEFAULT 0,
           device TEXT NOT NULL
         );
         CREATE INDEX IF NOT EXISTS idx_sessions_game_started ON play_sessions(game_id, started_at DESC);
         CREATE TABLE IF NOT EXISTS collections (
           id TEXT PRIMARY KEY,
           name TEXT NOT NULL UNIQUE
         );
         CREATE TABLE IF NOT EXISTS collection_games (
           collection_id TEXT NOT NULL REFERENCES collections(id) ON DELETE CASCADE,
           game_id TEXT NOT NULL REFERENCES games(id) ON DELETE CASCADE,
           PRIMARY KEY(collection_id, game_id)
         );
         CREATE TABLE IF NOT EXISTS emulators (
           id TEXT PRIMARY KEY,
           name TEXT NOT NULL,
           platform TEXT NOT NULL,
           executable TEXT NOT NULL,
           arguments_template TEXT NOT NULL,
           rom_directory TEXT,
           bios_directory TEXT,
           saves_directory TEXT
         );
         CREATE TABLE IF NOT EXISTS roms (
           id TEXT PRIMARY KEY,
           game_id TEXT NOT NULL REFERENCES games(id) ON DELETE CASCADE,
           platform TEXT NOT NULL,
           path TEXT NOT NULL UNIQUE,
           emulator_id TEXT REFERENCES emulators(id)
         );
         CREATE TABLE IF NOT EXISTS settings (
           key TEXT PRIMARY KEY,
           value TEXT NOT NULL
         );"
    ).map_err(|e| e.to_string())?;
    Ok(connection)
}

pub fn list_games(connection: &Connection) -> Result<Vec<Game>, String> {
    let mut statement = connection.prepare(
        "SELECT id, title, platform, source, executable, favorite, status, total_seconds
         FROM games ORDER BY favorite DESC, title COLLATE NOCASE"
    ).map_err(|e| e.to_string())?;

    let rows = statement.query_map([], |row| {
        Ok(Game {
            id: row.get(0)?,
            title: row.get(1)?,
            platform: row.get(2)?,
            source: row.get(3)?,
            executable: row.get(4)?,
            favorite: row.get::<_, i64>(5)? != 0,
            status: row.get(6)?,
            total_seconds: row.get(7)?,
        })
    }).map_err(|e| e.to_string())?;

    rows.collect::<Result<Vec<_>, _>>().map_err(|e| e.to_string())
}

pub fn add_manual_game(connection: &Connection, id: &str, title: &str, platform: &str) -> Result<(), String> {
    connection.execute(
        "INSERT INTO games (id, title, platform, source) VALUES (?1, ?2, ?3, 'manual')",
        params![id, title, platform],
    ).map_err(|e| e.to_string())?;
    Ok(())
}
