use std::{fs, path::Path};

use chrono::Utc;
use rusqlite::{params, Connection, OptionalExtension};
use uuid::Uuid;

use crate::{
    identity::normalize_title,
    models::{
        Game, GameDetails, GameStats, Installation, PlaySession, ScannedInstallation,
        SteamImportResult,
    },
};

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
           normalized_title TEXT NOT NULL DEFAULT '',
           created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
           updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
         );
         CREATE INDEX IF NOT EXISTS idx_games_title ON games(title COLLATE NOCASE);
         CREATE INDEX IF NOT EXISTS idx_games_normalized_title ON games(normalized_title);
         CREATE INDEX IF NOT EXISTS idx_games_platform ON games(platform);
         CREATE INDEX IF NOT EXISTS idx_games_source ON games(source);
         CREATE TABLE IF NOT EXISTS installations (
           id TEXT PRIMARY KEY,
           game_id TEXT NOT NULL REFERENCES games(id) ON DELETE CASCADE,
           source TEXT NOT NULL,
           provider TEXT NOT NULL DEFAULT 'manual',
           external_id TEXT,
           executable TEXT,
           install_dir TEXT,
           working_dir TEXT,
           launch_args TEXT,
           installed INTEGER NOT NULL DEFAULT 1,
           size_bytes INTEGER,
           last_updated INTEGER,
           updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
           UNIQUE(game_id, source, executable)
         );
         CREATE INDEX IF NOT EXISTS idx_installations_game ON installations(game_id);
         CREATE INDEX IF NOT EXISTS idx_installations_provider ON installations(provider);
         CREATE UNIQUE INDEX IF NOT EXISTS idx_installations_provider_external ON installations(provider, external_id) WHERE external_id IS NOT NULL;
         CREATE TABLE IF NOT EXISTS external_ids (
           game_id TEXT NOT NULL REFERENCES games(id) ON DELETE CASCADE,
           provider TEXT NOT NULL,
           external_id TEXT NOT NULL,
           PRIMARY KEY(provider, external_id)
         );
         CREATE INDEX IF NOT EXISTS idx_external_ids_game ON external_ids(game_id);
         CREATE TABLE IF NOT EXISTS play_sessions (
           id TEXT PRIMARY KEY,
           game_id TEXT NOT NULL REFERENCES games(id) ON DELETE CASCADE,
           installation_id TEXT REFERENCES installations(id) ON DELETE SET NULL,
           started_at TEXT NOT NULL,
           ended_at TEXT,
           duration_seconds INTEGER NOT NULL DEFAULT 0,
           device TEXT NOT NULL,
           provider TEXT,
           process_id INTEGER,
           process_path TEXT,
           last_seen_at TEXT,
           recovered INTEGER NOT NULL DEFAULT 0
         );
         CREATE INDEX IF NOT EXISTS idx_sessions_game_started ON play_sessions(game_id, started_at DESC);
         CREATE INDEX IF NOT EXISTS idx_sessions_installation ON play_sessions(installation_id);
         CREATE INDEX IF NOT EXISTS idx_sessions_started ON play_sessions(started_at DESC);
         CREATE TABLE IF NOT EXISTS collections (id TEXT PRIMARY KEY, name TEXT NOT NULL UNIQUE);
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
         CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL);"
    ).map_err(|e| e.to_string())?;

    migrate_existing(&connection)?;
    backfill_normalized_titles(&connection)?;
    Ok(connection)
}

fn column_exists(connection: &Connection, table: &str, column: &str) -> Result<bool, String> {
    let mut statement = connection
        .prepare(&format!("PRAGMA table_info({table})"))
        .map_err(|e| e.to_string())?;
    let names = statement
        .query_map([], |row| row.get::<_, String>(1))
        .map_err(|e| e.to_string())?;
    for name in names {
        if name.map_err(|e| e.to_string())? == column {
            return Ok(true);
        }
    }
    Ok(false)
}

fn ensure_column(
    connection: &Connection,
    table: &str,
    column: &str,
    definition: &str,
) -> Result<(), String> {
    if !column_exists(connection, table, column)? {
        connection
            .execute_batch(&format!(
                "ALTER TABLE {table} ADD COLUMN {column} {definition};"
            ))
            .map_err(|e| e.to_string())?;
    }
    Ok(())
}

fn migrate_existing(connection: &Connection) -> Result<(), String> {
    ensure_column(
        connection,
        "games",
        "normalized_title",
        "TEXT NOT NULL DEFAULT ''",
    )?;
    for (name, definition) in [
        ("provider", "TEXT NOT NULL DEFAULT 'manual'"),
        ("external_id", "TEXT"),
        ("install_dir", "TEXT"),
        ("working_dir", "TEXT"),
        ("size_bytes", "INTEGER"),
        ("last_updated", "INTEGER"),
        ("updated_at", "TEXT"),
    ] {
        ensure_column(connection, "installations", name, definition)?;
    }
    for (name, definition) in [
        (
            "installation_id",
            "TEXT REFERENCES installations(id) ON DELETE SET NULL",
        ),
        ("provider", "TEXT"),
        ("process_id", "INTEGER"),
        ("process_path", "TEXT"),
        ("last_seen_at", "TEXT"),
        ("recovered", "INTEGER NOT NULL DEFAULT 0"),
    ] {
        ensure_column(connection, "play_sessions", name, definition)?;
    }

    connection.execute_batch(
        "CREATE INDEX IF NOT EXISTS idx_games_normalized_title ON games(normalized_title);
         CREATE INDEX IF NOT EXISTS idx_installations_game ON installations(game_id);
         CREATE INDEX IF NOT EXISTS idx_installations_provider ON installations(provider);
         CREATE UNIQUE INDEX IF NOT EXISTS idx_installations_provider_external ON installations(provider, external_id) WHERE external_id IS NOT NULL;
         CREATE INDEX IF NOT EXISTS idx_sessions_installation ON play_sessions(installation_id);
         CREATE INDEX IF NOT EXISTS idx_sessions_started ON play_sessions(started_at DESC);
         CREATE TABLE IF NOT EXISTS external_ids (
           game_id TEXT NOT NULL REFERENCES games(id) ON DELETE CASCADE,
           provider TEXT NOT NULL,
           external_id TEXT NOT NULL,
           PRIMARY KEY(provider, external_id)
         );
         CREATE INDEX IF NOT EXISTS idx_external_ids_game ON external_ids(game_id);
         UPDATE installations SET updated_at=CURRENT_TIMESTAMP WHERE updated_at IS NULL;"
    ).map_err(|e| e.to_string())?;
    Ok(())
}

fn backfill_normalized_titles(connection: &Connection) -> Result<(), String> {
    let mut statement = connection
        .prepare("SELECT id, title FROM games WHERE normalized_title = ''")
        .map_err(|e| e.to_string())?;
    let rows = statement
        .query_map([], |row| {
            Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?))
        })
        .map_err(|e| e.to_string())?;
    let mut updates = Vec::new();
    for row in rows {
        updates.push(row.map_err(|e| e.to_string())?);
    }
    drop(statement);
    for (id, title) in updates {
        connection
            .execute(
                "UPDATE games SET normalized_title=?1 WHERE id=?2",
                params![normalize_title(&title), id],
            )
            .map_err(|e| e.to_string())?;
    }
    Ok(())
}

fn row_to_game(row: &rusqlite::Row<'_>) -> rusqlite::Result<Game> {
    let providers: String = row.get(9)?;
    Ok(Game {
        id: row.get(0)?,
        title: row.get(1)?,
        platform: row.get(2)?,
        source: row.get(3)?,
        executable: row.get(4)?,
        favorite: row.get::<_, i64>(5)? != 0,
        status: row.get(6)?,
        total_seconds: row.get(7)?,
        installed: row.get::<_, i64>(8)? != 0,
        providers: providers
            .split(',')
            .filter(|v| !v.is_empty())
            .map(str::to_string)
            .collect(),
        active: row.get::<_, i64>(10)? != 0,
        last_played_at: row.get(11)?,
        session_count: row.get(12)?,
    })
}

const GAME_SELECT: &str = "
SELECT g.id, g.title, g.platform, g.source,
       COALESCE((SELECT executable FROM installations i WHERE i.game_id=g.id AND i.installed=1 AND i.executable IS NOT NULL LIMIT 1), g.executable),
       g.favorite, g.status,
       MAX(
         COALESCE((SELECT SUM(duration_seconds) FROM play_sessions ps WHERE ps.game_id=g.id AND ps.ended_at IS NOT NULL), 0),
         COALESCE((SELECT SUM(seconds) FROM imported_playtime ip WHERE ip.game_id=g.id), 0)
       ),
       EXISTS(SELECT 1 FROM installations i WHERE i.game_id=g.id AND i.installed=1),
       COALESCE((SELECT GROUP_CONCAT(provider) FROM (SELECT DISTINCT provider FROM installations i2 WHERE i2.game_id=g.id)), ''),
       EXISTS(SELECT 1 FROM play_sessions aps WHERE aps.game_id=g.id AND aps.ended_at IS NULL),
       (SELECT MAX(COALESCE(ended_at, started_at)) FROM play_sessions lps WHERE lps.game_id=g.id),
       (SELECT COUNT(*) FROM play_sessions cps WHERE cps.game_id=g.id AND cps.ended_at IS NOT NULL)
FROM games g";

pub fn list_games(connection: &Connection) -> Result<Vec<Game>, String> {
    let sql = format!("{GAME_SELECT} ORDER BY g.favorite DESC, g.title COLLATE NOCASE");
    let mut statement = connection.prepare(&sql).map_err(|e| e.to_string())?;
    let rows = statement
        .query_map([], row_to_game)
        .map_err(|e| e.to_string())?;
    rows.collect::<Result<Vec<_>, _>>()
        .map_err(|e| e.to_string())
}

pub fn add_manual_game(
    connection: &Connection,
    id: &str,
    title: &str,
    platform: &str,
    executable: Option<&str>,
    working_dir: Option<&str>,
    launch_args: Option<&str>,
) -> Result<(), String> {
    let normalized = normalize_title(title);
    connection.execute(
        "INSERT INTO games (id, title, normalized_title, platform, source, executable) VALUES (?1, ?2, ?3, ?4, 'manual', ?5)",
        params![id, title, normalized, platform, executable],
    ).map_err(|e| e.to_string())?;
    if executable.is_some() {
        connection.execute(
            "INSERT INTO installations (id, game_id, source, provider, executable, working_dir, launch_args, installed) VALUES (?1, ?2, 'manual', 'manual', ?3, ?4, ?5, 1)",
            params![format!("manual:{id}"), id, executable, working_dir, launch_args],
        ).map_err(|e| e.to_string())?;
    }
    Ok(())
}

fn matching_game(
    connection: &Connection,
    provider: &str,
    external_id: &str,
    title: &str,
) -> Result<(Option<String>, bool), String> {
    if let Some(id) = connection
        .query_row(
            "SELECT game_id FROM external_ids WHERE provider=?1 AND external_id=?2",
            params![provider, external_id],
            |row| row.get(0),
        )
        .optional()
        .map_err(|e| e.to_string())?
    {
        return Ok((Some(id), true));
    }

    let normalized = normalize_title(title);
    let mut statement = connection
        .prepare("SELECT id FROM games WHERE normalized_title=?1 LIMIT 2")
        .map_err(|e| e.to_string())?;
    let rows = statement
        .query_map([normalized], |row| row.get::<_, String>(0))
        .map_err(|e| e.to_string())?;
    let matches = rows
        .collect::<Result<Vec<_>, _>>()
        .map_err(|e| e.to_string())?;
    if matches.len() == 1 {
        Ok((Some(matches[0].clone()), true))
    } else {
        Ok((None, false))
    }
}

pub fn import_installations(
    connection: &Connection,
    provider: &str,
    installations: &[ScannedInstallation],
    root_path: &str,
    library_count: usize,
) -> Result<SteamImportResult, String> {
    connection
        .execute(
            "UPDATE installations SET installed=0 WHERE provider=?1",
            [provider],
        )
        .map_err(|e| e.to_string())?;
    let mut created = 0usize;
    let mut deduplicated = 0usize;

    for item in installations {
        let (existing, matched) =
            matching_game(connection, provider, &item.external_id, &item.title)?;
        let game_id = if let Some(id) = existing {
            if matched {
                deduplicated += 1;
            }
            id
        } else {
            created += 1;
            let id = Uuid::new_v4().to_string();
            connection.execute(
                "INSERT INTO games (id, title, normalized_title, platform, source) VALUES (?1, ?2, ?3, ?4, ?5)",
                params![id, item.title, normalize_title(&item.title), item.platform, provider],
            ).map_err(|e| e.to_string())?;
            id
        };

        connection
            .execute(
                "INSERT INTO external_ids(game_id, provider, external_id) VALUES (?1, ?2, ?3)
             ON CONFLICT(provider, external_id) DO UPDATE SET game_id=excluded.game_id",
                params![game_id, provider, item.external_id],
            )
            .map_err(|e| e.to_string())?;

        let installation_id = format!("{provider}:{}", item.external_id);
        connection.execute(
            "INSERT INTO installations(id, game_id, source, provider, external_id, executable, install_dir, installed, size_bytes, last_updated, updated_at)
             VALUES (?1, ?2, ?3, ?3, ?4, ?5, ?6, ?7, ?8, ?9, CURRENT_TIMESTAMP)
             ON CONFLICT(id) DO UPDATE SET game_id=excluded.game_id, provider=excluded.provider, external_id=excluded.external_id,
               executable=excluded.executable, install_dir=excluded.install_dir, installed=excluded.installed,
               size_bytes=excluded.size_bytes, last_updated=excluded.last_updated, updated_at=CURRENT_TIMESTAMP",
            params![installation_id, game_id, provider, item.external_id, item.executable, item.install_dir, item.installed as i64, item.size_bytes, item.last_updated],
        ).map_err(|e| e.to_string())?;
    }

    let now = Utc::now().to_rfc3339();
    let sync_key = format!("{provider}.last_sync");
    connection.execute(
        "INSERT INTO settings(key, value) VALUES (?1, ?2) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
        params![sync_key, now],
    ).map_err(|e| e.to_string())?;

    Ok(SteamImportResult {
        root_path: root_path.to_string(),
        library_count,
        games_found: installations.len(),
        games_created: created,
        installations_upserted: installations.len(),
        deduplicated,
    })
}

pub fn get_setting(connection: &Connection, key: &str) -> Result<Option<String>, String> {
    connection
        .query_row("SELECT value FROM settings WHERE key=?1", [key], |row| {
            row.get(0)
        })
        .optional()
        .map_err(|e| e.to_string())
}

pub fn installation_for_launch(
    connection: &Connection,
    game_id: &str,
) -> Result<Option<Installation>, String> {
    connection.query_row(
        "SELECT id, game_id, provider, external_id, executable, install_dir, working_dir, launch_args, installed
         FROM installations WHERE game_id=?1 AND installed=1 ORDER BY CASE provider WHEN 'steam' THEN 0 WHEN 'manual' THEN 1 ELSE 2 END LIMIT 1",
        [game_id],
        |row| Ok(Installation {
            id: row.get(0)?, game_id: row.get(1)?, provider: row.get(2)?, external_id: row.get(3)?, executable: row.get(4)?,
            install_dir: row.get(5)?, working_dir: row.get(6)?, launch_args: row.get(7)?, installed: row.get::<_, i64>(8)? != 0,
        })
    ).optional().map_err(|e| e.to_string())
}

pub fn active_session_exists(connection: &Connection, game_id: &str) -> Result<bool, String> {
    connection
        .query_row(
            "SELECT EXISTS(SELECT 1 FROM play_sessions WHERE game_id=?1 AND ended_at IS NULL)",
            [game_id],
            |row| row.get::<_, i64>(0),
        )
        .map(|value| value != 0)
        .map_err(|e| e.to_string())
}

pub fn get_game_details(
    connection: &Connection,
    game_id: &str,
) -> Result<Option<GameDetails>, String> {
    let sql = format!("{GAME_SELECT} WHERE g.id=?1");
    let game = connection
        .query_row(&sql, [game_id], row_to_game)
        .optional()
        .map_err(|e| e.to_string())?;
    let Some(game) = game else {
        return Ok(None);
    };

    let mut stats = connection.query_row(
        "SELECT COALESCE(SUM(CASE WHEN ended_at IS NOT NULL THEN duration_seconds ELSE 0 END),0),
                COALESCE(SUM(CASE WHEN ended_at IS NOT NULL AND datetime(started_at) >= datetime('now','-14 days') THEN duration_seconds ELSE 0 END),0),
                COALESCE(SUM(CASE WHEN ended_at IS NOT NULL AND datetime(started_at) >= datetime('now','-30 days') THEN duration_seconds ELSE 0 END),0),
                COUNT(CASE WHEN ended_at IS NOT NULL THEN 1 END),
                COALESCE(AVG(CASE WHEN ended_at IS NOT NULL THEN duration_seconds END),0),
                MAX(COALESCE(ended_at, started_at))
         FROM play_sessions WHERE game_id=?1",
        [game_id],
        |row| Ok(GameStats {
            total_seconds: row.get(0)?, last_14_seconds: row.get(1)?, last_30_seconds: row.get(2)?, session_count: row.get(3)?,
            average_session_seconds: row.get::<_, f64>(4)? as i64, last_played_at: row.get(5)?,
        })
    ).map_err(|e| e.to_string())?;
    let imported_total: i64 = connection
        .query_row(
            "SELECT COALESCE(SUM(seconds),0) FROM imported_playtime WHERE game_id=?1",
            [game_id],
            |row| row.get(0),
        )
        .map_err(|e| e.to_string())?;
    stats.total_seconds = stats.total_seconds.max(imported_total);

    let mut installation_stmt = connection.prepare(
        "SELECT id, game_id, provider, external_id, executable, install_dir, working_dir, launch_args, installed FROM installations WHERE game_id=?1 ORDER BY installed DESC, provider"
    ).map_err(|e| e.to_string())?;
    let installations = installation_stmt
        .query_map([game_id], |row| {
            Ok(Installation {
                id: row.get(0)?,
                game_id: row.get(1)?,
                provider: row.get(2)?,
                external_id: row.get(3)?,
                executable: row.get(4)?,
                install_dir: row.get(5)?,
                working_dir: row.get(6)?,
                launch_args: row.get(7)?,
                installed: row.get::<_, i64>(8)? != 0,
            })
        })
        .map_err(|e| e.to_string())?
        .collect::<Result<Vec<_>, _>>()
        .map_err(|e| e.to_string())?;

    let mut session_stmt = connection.prepare(
        "SELECT id, game_id, installation_id, started_at, ended_at, duration_seconds, device, provider, process_id, process_path, recovered
         FROM play_sessions WHERE game_id=?1 ORDER BY started_at DESC LIMIT 8"
    ).map_err(|e| e.to_string())?;
    let recent_sessions = session_stmt
        .query_map([game_id], |row| {
            Ok(PlaySession {
                id: row.get(0)?,
                game_id: row.get(1)?,
                installation_id: row.get(2)?,
                started_at: row.get(3)?,
                ended_at: row.get(4)?,
                duration_seconds: row.get(5)?,
                device: row.get(6)?,
                provider: row.get(7)?,
                process_id: row.get::<_, Option<i64>>(8)?.map(|v| v as u32),
                process_path: row.get(9)?,
                recovered: row.get::<_, i64>(10)? != 0,
            })
        })
        .map_err(|e| e.to_string())?
        .collect::<Result<Vec<_>, _>>()
        .map_err(|e| e.to_string())?;

    Ok(Some(GameDetails {
        game,
        stats,
        installations,
        recent_sessions,
    }))
}

#[cfg(test)]
mod import_tests {
    use super::{add_manual_game, import_installations, list_games, open};
    use crate::models::ScannedInstallation;
    use std::fs;
    use uuid::Uuid;

    fn temp_db() -> std::path::PathBuf {
        std::env::temp_dir().join(format!("ludex-test-{}.db", Uuid::new_v4()))
    }

    fn steam_game(external_id: &str, title: &str) -> ScannedInstallation {
        ScannedInstallation {
            provider: "steam".into(),
            external_id: external_id.into(),
            title: title.into(),
            platform: "PC".into(),
            install_dir: Some("C:\\Games\\Cyberpunk 2077".into()),
            executable: None,
            installed: true,
            size_bytes: Some(100),
            last_updated: Some(1),
        }
    }

    #[test]
    fn steam_reimport_is_idempotent() {
        let path = temp_db();
        let connection = open(&path).unwrap();
        let item = steam_game("1091500", "Cyberpunk 2077™");
        import_installations(
            &connection,
            "steam",
            std::slice::from_ref(&item),
            "C:\\Steam",
            1,
        )
        .unwrap();
        import_installations(
            &connection,
            "steam",
            std::slice::from_ref(&item),
            "C:\\Steam",
            1,
        )
        .unwrap();

        assert_eq!(list_games(&connection).unwrap().len(), 1);
        let count: i64 = connection
            .query_row(
                "SELECT COUNT(*) FROM installations WHERE provider='steam'",
                [],
                |row| row.get(0),
            )
            .unwrap();
        assert_eq!(count, 1);
        drop(connection);
        let _ = fs::remove_file(path);
    }

    #[test]
    fn unique_normalized_title_deduplicates_manual_identity() {
        let path = temp_db();
        let connection = open(&path).unwrap();
        add_manual_game(
            &connection,
            "manual-cp",
            "Cyberpunk 2077",
            "PC",
            None,
            None,
            None,
        )
        .unwrap();

        let result = import_installations(
            &connection,
            "steam",
            &[steam_game("1091500", "Cyberpunk 2077™")],
            "C:\\Steam",
            1,
        )
        .unwrap();

        assert_eq!(result.games_created, 0);
        assert_eq!(list_games(&connection).unwrap().len(), 1);
        drop(connection);
        let _ = fs::remove_file(path);
    }
}
