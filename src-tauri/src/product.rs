use crate::{db, identity::normalize_title, models::ScannedInstallation, product_models::*};
use chrono::Utc;
use rusqlite::{params, Connection, OptionalExtension};
use sha2::{Digest, Sha256};
use std::{
    fs,
    io::Read,
    path::{Path, PathBuf},
};
use uuid::Uuid;

pub const STATUSES: &[&str] = &[
    "Quero jogar",
    "Jogando",
    "Pausado",
    "Concluído",
    "100%",
    "Abandonado",
];

pub fn migrate(c: &Connection) -> Result<(), String> {
    c.execute_batch(r#"
      CREATE TABLE IF NOT EXISTS schema_migrations(version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP);
      INSERT OR IGNORE INTO schema_migrations(version) VALUES(1);
      CREATE TABLE IF NOT EXISTS game_metadata(
        game_id TEXT PRIMARY KEY REFERENCES games(id) ON DELETE CASCADE,
        description TEXT, developer TEXT, publisher TEXT, release_date TEXT, genres TEXT,
        cover TEXT, hero TEXT, source TEXT NOT NULL DEFAULT 'manual', manual INTEGER NOT NULL DEFAULT 0,
        updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP);
      CREATE TABLE IF NOT EXISTS imported_playtime(
        game_id TEXT NOT NULL REFERENCES games(id) ON DELETE CASCADE, provider TEXT NOT NULL,
        seconds INTEGER NOT NULL DEFAULT 0, updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY(game_id,provider));
      CREATE TABLE IF NOT EXISTS achievements(
        id TEXT PRIMARY KEY, game_id TEXT NOT NULL REFERENCES games(id) ON DELETE CASCADE, provider TEXT NOT NULL,
        external_id TEXT, name TEXT NOT NULL, description TEXT, unlocked INTEGER NOT NULL DEFAULT 0,
        unlocked_at TEXT, icon TEXT, progress_current REAL, progress_target REAL, rarity REAL,
        UNIQUE(game_id,provider,external_id));
      CREATE TABLE IF NOT EXISTS sync_conflicts(
        id TEXT PRIMARY KEY, entity_type TEXT NOT NULL, entity_id TEXT NOT NULL, local_json TEXT NOT NULL,
        remote_json TEXT NOT NULL, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, resolved_at TEXT);
      CREATE TABLE IF NOT EXISTS save_backups(
        id TEXT PRIMARY KEY, emulator_id TEXT, source_path TEXT NOT NULL, backup_path TEXT NOT NULL,
        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP);
      CREATE INDEX IF NOT EXISTS idx_metadata_updated ON game_metadata(updated_at);
      CREATE INDEX IF NOT EXISTS idx_imported_playtime_game ON imported_playtime(game_id);
      CREATE INDEX IF NOT EXISTS idx_achievements_game ON achievements(game_id);
    "#).map_err(|e|e.to_string())?;
    ensure_column(c, "collections", "kind", "TEXT NOT NULL DEFAULT 'manual'")?;
    ensure_column(c, "collections", "filter_json", "TEXT")?;
    ensure_column(c, "collections", "updated_at", "TEXT")?;
    c.execute(
        "UPDATE collections SET updated_at=CURRENT_TIMESTAMP WHERE updated_at IS NULL",
        [],
    )
    .map_err(|e| e.to_string())?;
    ensure_column(c, "emulators", "extensions", "TEXT NOT NULL DEFAULT ''")?;
    ensure_column(c, "emulators", "core", "TEXT")?;
    ensure_column(c, "emulators", "enabled", "INTEGER NOT NULL DEFAULT 1")?;
    ensure_column(c, "roms", "hash_sha256", "TEXT")?;
    ensure_column(c, "roms", "size_bytes", "INTEGER")?;
    ensure_column(c, "roms", "launch_args", "TEXT")?;
    ensure_column(c, "roms", "core", "TEXT")?;
    ensure_column(c, "roms", "updated_at", "TEXT")?;
    c.execute(
        "UPDATE roms SET updated_at=CURRENT_TIMESTAMP WHERE updated_at IS NULL",
        [],
    )
    .map_err(|e| e.to_string())?;
    c.execute_batch("CREATE UNIQUE INDEX IF NOT EXISTS idx_rom_hash ON roms(hash_sha256) WHERE hash_sha256 IS NOT NULL; INSERT OR IGNORE INTO schema_migrations(version) VALUES(2); CREATE INDEX IF NOT EXISTS idx_games_updated ON games(updated_at); CREATE INDEX IF NOT EXISTS idx_sessions_started ON play_sessions(started_at); CREATE INDEX IF NOT EXISTS idx_installations_provider_installed ON installations(provider,installed); INSERT OR IGNORE INTO schema_migrations(version) VALUES(3);").map_err(|e|e.to_string())?;
    Ok(())
}
fn column_exists(c: &Connection, t: &str, col: &str) -> Result<bool, String> {
    let mut s = c
        .prepare(&format!("PRAGMA table_info({t})"))
        .map_err(|e| e.to_string())?;
    let mut rows = s.query([]).map_err(|e| e.to_string())?;
    while let Some(r) = rows.next().map_err(|e| e.to_string())? {
        let n: String = r.get(1).map_err(|e| e.to_string())?;
        if n == col {
            return Ok(true);
        }
    }
    Ok(false)
}
fn ensure_column(c: &Connection, t: &str, col: &str, def: &str) -> Result<(), String> {
    if !column_exists(c, t, col)? {
        c.execute_batch(&format!("ALTER TABLE {t} ADD COLUMN {col} {def};"))
            .map_err(|e| e.to_string())?;
    }
    Ok(())
}

pub fn sync_provider(
    c: &Connection,
    provider: &str,
    items: &[ScannedInstallation],
    root: &str,
) -> Result<crate::models::ProviderImportResult, String> {
    let r = db::import_installations(c, provider, items, root, 1)?;
    let key = format!("{provider}.last_sync");
    let now = Utc::now().to_rfc3339();
    c.execute("INSERT INTO settings(key,value) VALUES(?1,?2) ON CONFLICT(key) DO UPDATE SET value=excluded.value",params![key,now]).map_err(|e|e.to_string())?;
    Ok(crate::models::ProviderImportResult {
        provider: provider.into(),
        root_path: root.into(),
        games_found: r.games_found,
        games_created: r.games_created,
        installations_upserted: r.installations_upserted,
        deduplicated: r.deduplicated,
    })
}

pub fn set_favorite(c: &Connection, id: &str, value: bool) -> Result<(), String> {
    c.execute(
        "UPDATE games SET favorite=?1,updated_at=CURRENT_TIMESTAMP WHERE id=?2",
        params![value as i64, id],
    )
    .map_err(|e| e.to_string())?;
    Ok(())
}
pub fn set_status(c: &Connection, id: &str, status: &str) -> Result<(), String> {
    if !STATUSES.contains(&status) {
        return Err("Status inválido".into());
    }
    c.execute(
        "UPDATE games SET status=?1,updated_at=CURRENT_TIMESTAMP WHERE id=?2",
        params![status, id],
    )
    .map_err(|e| e.to_string())?;
    Ok(())
}

pub fn update_metadata(c: &Connection, m: &MetadataRecord) -> Result<(), String> {
    c.execute(r#"INSERT INTO game_metadata(game_id,description,developer,publisher,release_date,genres,cover,hero,source,manual,updated_at)
 VALUES(?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,CURRENT_TIMESTAMP)
 ON CONFLICT(game_id) DO UPDATE SET description=excluded.description,developer=excluded.developer,publisher=excluded.publisher,
 release_date=excluded.release_date,genres=excluded.genres,cover=excluded.cover,hero=excluded.hero,source=excluded.source,manual=excluded.manual,updated_at=CURRENT_TIMESTAMP"#,
 params![m.game_id,m.description,m.developer,m.publisher,m.release_date,m.genres,m.cover,m.hero,m.source,m.manual as i64]).map_err(|e|e.to_string())?;
    Ok(())
}
pub fn metadata(c: &Connection, game_id: &str) -> Result<Option<MetadataRecord>, String> {
    c.query_row("SELECT game_id,description,developer,publisher,release_date,genres,cover,hero,source,manual,updated_at FROM game_metadata WHERE game_id=?1",[game_id],|r|Ok(MetadataRecord{game_id:r.get(0)?,description:r.get(1)?,developer:r.get(2)?,publisher:r.get(3)?,release_date:r.get(4)?,genres:r.get(5)?,cover:r.get(6)?,hero:r.get(7)?,source:r.get(8)?,manual:r.get::<_,i64>(9)?!=0,updated_at:r.get(10)?})).optional().map_err(|e|e.to_string())
}

pub fn create_collection(c: &Connection, name: &str) -> Result<String, String> {
    let id = Uuid::new_v4().to_string();
    c.execute(
        "INSERT INTO collections(id,name,kind) VALUES(?1,?2,'manual')",
        params![id, name.trim()],
    )
    .map_err(|e| e.to_string())?;
    Ok(id)
}
pub fn rename_collection(c: &Connection, id: &str, name: &str) -> Result<(), String> {
    c.execute(
        "UPDATE collections SET name=?1,updated_at=CURRENT_TIMESTAMP WHERE id=?2",
        params![name.trim(), id],
    )
    .map_err(|e| e.to_string())?;
    Ok(())
}
pub fn delete_collection(c: &Connection, id: &str) -> Result<(), String> {
    c.execute("DELETE FROM collections WHERE id=?1", [id])
        .map_err(|e| e.to_string())?;
    Ok(())
}
pub fn set_collection_game(c: &Connection, col: &str, game: &str, add: bool) -> Result<(), String> {
    if add {
        c.execute(
            "INSERT OR IGNORE INTO collection_games(collection_id,game_id) VALUES(?1,?2)",
            params![col, game],
        )
    } else {
        c.execute(
            "DELETE FROM collection_games WHERE collection_id=?1 AND game_id=?2",
            params![col, game],
        )
    }
    .map_err(|e| e.to_string())?;
    Ok(())
}
pub fn collections(c: &Connection) -> Result<Vec<CollectionRecord>, String> {
    let mut s=c.prepare("SELECT c.id,c.name,c.kind,c.filter_json,COUNT(cg.game_id) FROM collections c LEFT JOIN collection_games cg ON cg.collection_id=c.id GROUP BY c.id ORDER BY c.name COLLATE NOCASE").map_err(|e|e.to_string())?;
    let result = s
        .query_map([], |r| {
            Ok(CollectionRecord {
                id: r.get(0)?,
                name: r.get(1)?,
                kind: r.get(2)?,
                filter_json: r.get(3)?,
                game_count: r.get(4)?,
            })
        })
        .map_err(|e| e.to_string())?
        .collect::<Result<Vec<_>, _>>()
        .map_err(|e| e.to_string());
    drop(s);
    result
}

pub fn collection_memberships(
    c: &Connection,
    game_id: &str,
) -> Result<Vec<CollectionMembership>, String> {
    let mut s=c.prepare("SELECT c.id,c.name,EXISTS(SELECT 1 FROM collection_games cg WHERE cg.collection_id=c.id AND cg.game_id=?1) FROM collections c ORDER BY c.name COLLATE NOCASE").map_err(|e|e.to_string())?;
    let result = s
        .query_map([game_id], |r| {
            Ok(CollectionMembership {
                id: r.get(0)?,
                name: r.get(1)?,
                included: r.get::<_, i64>(2)? != 0,
            })
        })
        .map_err(|e| e.to_string())?
        .collect::<Result<Vec<_>, _>>()
        .map_err(|e| e.to_string());
    result
}

pub fn merge_games(c: &mut Connection, target: &str, source: &str) -> Result<(), String> {
    if target == source {
        return Ok(());
    }
    let tx = c.transaction().map_err(|e| e.to_string())?;
    for sql in [
        "UPDATE installations SET game_id=?1 WHERE game_id=?2",
        "UPDATE external_ids SET game_id=?1 WHERE game_id=?2",
        "UPDATE play_sessions SET game_id=?1 WHERE game_id=?2",
        "UPDATE roms SET game_id=?1 WHERE game_id=?2",
        "UPDATE achievements SET game_id=?1 WHERE game_id=?2",
    ] {
        tx.execute(sql, params![target, source])
            .map_err(|e| e.to_string())?;
    }
    tx.execute("INSERT OR IGNORE INTO collection_games(collection_id,game_id) SELECT collection_id,?1 FROM collection_games WHERE game_id=?2",params![target,source]).map_err(|e|e.to_string())?;
    tx.execute("DELETE FROM games WHERE id=?1", [source])
        .map_err(|e| e.to_string())?;
    tx.commit().map_err(|e| e.to_string())?;
    Ok(())
}
pub fn split_installation(c: &mut Connection, installation_id: &str) -> Result<String, String> {
    let (old,title,platform):(String,String,String)=c.query_row("SELECT i.game_id,g.title,g.platform FROM installations i JOIN games g ON g.id=i.game_id WHERE i.id=?1",[installation_id],|r|Ok((r.get(0)?,r.get(1)?,r.get(2)?))).map_err(|e|e.to_string())?;
    let id = Uuid::new_v4().to_string();
    let tx = c.transaction().map_err(|e| e.to_string())?;
    tx.execute(
        "INSERT INTO games(id,title,normalized_title,platform,source) VALUES(?1,?2,?3,?4,'manual')",
        params![id, title, normalize_title(&title), platform],
    )
    .map_err(|e| e.to_string())?;
    tx.execute(
        "UPDATE installations SET game_id=?1 WHERE id=?2",
        params![id, installation_id],
    )
    .map_err(|e| e.to_string())?;
    tx.execute("UPDATE external_ids SET game_id=?1 WHERE provider=(SELECT provider FROM installations WHERE id=?2) AND external_id=(SELECT external_id FROM installations WHERE id=?2)",params![id,installation_id]).map_err(|e|e.to_string())?;
    tx.commit().map_err(|e| e.to_string())?;
    let _ = old;
    Ok(id)
}

pub fn upsert_emulator(c: &Connection, e: &EmulatorRecord) -> Result<(), String> {
    c.execute(r#"INSERT INTO emulators(id,name,platform,executable,arguments_template,rom_directory,bios_directory,saves_directory,extensions,core,enabled)
VALUES(?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11) ON CONFLICT(id) DO UPDATE SET name=excluded.name,platform=excluded.platform,executable=excluded.executable,arguments_template=excluded.arguments_template,rom_directory=excluded.rom_directory,bios_directory=excluded.bios_directory,saves_directory=excluded.saves_directory,extensions=excluded.extensions,core=excluded.core,enabled=excluded.enabled"#,params![e.id,e.name,e.platform,e.executable,e.arguments_template,e.rom_directory,e.bios_directory,e.saves_directory,e.extensions,e.core,e.enabled as i64]).map_err(|x|x.to_string())?;
    Ok(())
}
pub fn emulators(c: &Connection) -> Result<Vec<EmulatorRecord>, String> {
    let mut s=c.prepare("SELECT id,name,platform,executable,arguments_template,rom_directory,bios_directory,saves_directory,extensions,core,enabled FROM emulators ORDER BY platform,name").map_err(|e|e.to_string())?;
    let result = s
        .query_map([], |r| {
            Ok(EmulatorRecord {
                id: r.get(0)?,
                name: r.get(1)?,
                platform: r.get(2)?,
                executable: r.get(3)?,
                arguments_template: r.get(4)?,
                rom_directory: r.get(5)?,
                bios_directory: r.get(6)?,
                saves_directory: r.get(7)?,
                extensions: r.get(8)?,
                core: r.get(9)?,
                enabled: r.get::<_, i64>(10)? != 0,
            })
        })
        .map_err(|e| e.to_string())?
        .collect::<Result<Vec<_>, _>>()
        .map_err(|e| e.to_string());
    drop(s);
    result
}

fn allowed_extension(ext: &str) -> bool {
    matches!(
        ext,
        "iso"
            | "chd"
            | "cue"
            | "rvz"
            | "wbfs"
            | "gba"
            | "gbc"
            | "nds"
            | "3ds"
            | "nsp"
            | "xci"
            | "nes"
            | "snes"
            | "n64"
            | "z64"
            | "v64"
            | "pbp"
    )
}
fn collect_files(dir: &Path, recursive: bool, out: &mut Vec<PathBuf>) {
    let Ok(entries) = fs::read_dir(dir) else {
        return;
    };
    for e in entries.flatten() {
        let p = e.path();
        if p.is_dir() && recursive {
            collect_files(&p, true, out)
        } else if p.is_file() {
            out.push(p)
        }
    }
}
fn sha256_file(path: &Path) -> Result<String, String> {
    let mut f = fs::File::open(path).map_err(|e| e.to_string())?;
    let mut h = Sha256::new();
    let mut b = [0u8; 1024 * 128];
    loop {
        let n = f.read(&mut b).map_err(|e| e.to_string())?;
        if n == 0 {
            break;
        }
        h.update(&b[..n]);
    }
    Ok(format!("{:x}", h.finalize()))
}
pub fn scan_roms(
    c: &Connection,
    folder: &str,
    platform: &str,
    emulator_id: Option<&str>,
    recursive: bool,
) -> Result<RomScanResult, String> {
    let root = Path::new(folder);
    if !root.is_dir() {
        return Err("Pasta de ROMs não encontrada".into());
    }
    let mut files = Vec::new();
    collect_files(root, recursive, &mut files);
    let mut r = RomScanResult {
        scanned_files: files.len(),
        imported: 0,
        duplicates: 0,
        ignored: 0,
    };
    for p in files {
        let ext = p
            .extension()
            .and_then(|s| s.to_str())
            .unwrap_or("")
            .to_ascii_lowercase();
        if !allowed_extension(&ext) || ext == "bin" {
            r.ignored += 1;
            continue;
        }
        let hash = sha256_file(&p)?;
        let exists: i64 = c
            .query_row(
                "SELECT EXISTS(SELECT 1 FROM roms WHERE hash_sha256=?1 OR path=?2)",
                params![hash, p.to_string_lossy()],
                |x| x.get(0),
            )
            .map_err(|e| e.to_string())?;
        if exists != 0 {
            r.duplicates += 1;
            continue;
        }
        let title = p
            .file_stem()
            .and_then(|s| s.to_str())
            .unwrap_or("ROM")
            .replace(['_', '.'], " ");
        let game_id = Uuid::new_v4().to_string();
        let rom_id = Uuid::new_v4().to_string();
        c.execute("INSERT INTO games(id,title,normalized_title,platform,source) VALUES(?1,?2,?3,?4,'emulation')",params![game_id,title,normalize_title(&title),platform]).map_err(|e|e.to_string())?;
        let size = fs::metadata(&p).ok().map(|m| m.len() as i64);
        c.execute("INSERT INTO roms(id,game_id,platform,path,emulator_id,hash_sha256,size_bytes) VALUES(?1,?2,?3,?4,?5,?6,?7)",params![rom_id,game_id,platform,p.to_string_lossy(),emulator_id,hash,size]).map_err(|e|e.to_string())?;
        r.imported += 1;
    }
    Ok(r)
}
pub fn roms(c: &Connection) -> Result<Vec<RomRecord>, String> {
    let mut s=c.prepare("SELECT r.id,r.game_id,g.title,r.platform,r.path,r.emulator_id,r.hash_sha256,r.size_bytes,r.launch_args,r.core FROM roms r JOIN games g ON g.id=r.game_id ORDER BY g.title").map_err(|e|e.to_string())?;
    let result = s
        .query_map([], |r| {
            Ok(RomRecord {
                id: r.get(0)?,
                game_id: r.get(1)?,
                title: r.get(2)?,
                platform: r.get(3)?,
                path: r.get(4)?,
                emulator_id: r.get(5)?,
                hash_sha256: r.get(6)?,
                size_bytes: r.get(7)?,
                launch_args: r.get(8)?,
                core: r.get(9)?,
            })
        })
        .map_err(|e| e.to_string())?
        .collect::<Result<Vec<_>, _>>()
        .map_err(|e| e.to_string());
    drop(s);
    result
}

fn named_times(c: &Connection, sql: &str) -> Result<Vec<NamedTime>, String> {
    let mut s = c.prepare(sql).map_err(|e| e.to_string())?;
    let result = s
        .query_map([], |r| {
            Ok(NamedTime {
                name: r.get(0)?,
                seconds: r.get(1)?,
            })
        })
        .map_err(|e| e.to_string())?
        .collect::<Result<Vec<_>, _>>()
        .map_err(|e| e.to_string());
    drop(s);
    result
}
fn buckets(c: &Connection, sql: &str) -> Result<Vec<TimeBucket>, String> {
    let mut s = c.prepare(sql).map_err(|e| e.to_string())?;
    let result = s
        .query_map([], |r| {
            Ok(TimeBucket {
                label: r.get(0)?,
                seconds: r.get(1)?,
            })
        })
        .map_err(|e| e.to_string())?
        .collect::<Result<Vec<_>, _>>()
        .map_err(|e| e.to_string());
    drop(s);
    result
}
pub fn library_stats(c: &Connection) -> Result<LibraryStats, String> {
    let q = |sql: &str| {
        c.query_row(sql, [], |r| r.get::<_, i64>(0))
            .map_err(|e| e.to_string())
    };
    let tracked = q(
        "SELECT COALESCE(SUM(duration_seconds),0) FROM play_sessions WHERE ended_at IS NOT NULL",
    )?;
    Ok(LibraryStats{library_games:q("SELECT COUNT(*) FROM games")?,installed_games:q("SELECT COUNT(DISTINCT game_id) FROM installations WHERE installed=1")?,never_played:q("SELECT COUNT(*) FROM games g WHERE NOT EXISTS(SELECT 1 FROM play_sessions p WHERE p.game_id=g.id AND p.ended_at IS NOT NULL) AND COALESCE((SELECT SUM(seconds) FROM imported_playtime ip WHERE ip.game_id=g.id),0)=0")?,tracked_seconds:tracked,imported_seconds:q("SELECT COALESCE(SUM(seconds),0) FROM imported_playtime")?,last_14_seconds:q("SELECT COALESCE(SUM(duration_seconds),0) FROM play_sessions WHERE ended_at IS NOT NULL AND datetime(started_at)>=datetime('now','-14 days')")?,last_30_seconds:q("SELECT COALESCE(SUM(duration_seconds),0) FROM play_sessions WHERE ended_at IS NOT NULL AND datetime(started_at)>=datetime('now','-30 days')")?,average_daily_seconds_30d:q("SELECT COALESCE(SUM(duration_seconds),0)/30 FROM play_sessions WHERE ended_at IS NOT NULL AND datetime(started_at)>=datetime('now','-30 days')")?,average_weekly_seconds_12w:q("SELECT COALESCE(SUM(duration_seconds),0)/12 FROM play_sessions WHERE ended_at IS NOT NULL AND datetime(started_at)>=datetime('now','-84 days')")?,by_provider:named_times(c,"SELECT COALESCE(provider,'local'),SUM(duration_seconds) FROM play_sessions WHERE ended_at IS NOT NULL GROUP BY COALESCE(provider,'local') ORDER BY 2 DESC")?,by_platform:named_times(c,"SELECT g.platform,SUM(p.duration_seconds) FROM play_sessions p JOIN games g ON g.id=p.game_id WHERE p.ended_at IS NOT NULL GROUP BY g.platform ORDER BY 2 DESC")?,top_games:named_times(c,"SELECT g.title,MAX(COALESCE((SELECT SUM(duration_seconds) FROM play_sessions p WHERE p.game_id=g.id AND p.ended_at IS NOT NULL),0),COALESCE((SELECT SUM(seconds) FROM imported_playtime ip WHERE ip.game_id=g.id),0)) AS total FROM games g ORDER BY total DESC LIMIT 10")?,monthly:buckets(c,"SELECT strftime('%Y-%m',started_at),SUM(duration_seconds) FROM play_sessions WHERE ended_at IS NOT NULL GROUP BY 1 ORDER BY 1 DESC LIMIT 24")?,yearly:buckets(c,"SELECT strftime('%Y',started_at),SUM(duration_seconds) FROM play_sessions WHERE ended_at IS NOT NULL GROUP BY 1 ORDER BY 1")?})
}

pub fn backup_json(c: &Connection) -> Result<String, String> {
    let table = |sql: &str| -> Result<serde_json::Value, String> {
        serde_json::from_str(&query_json(c, sql)?).map_err(|e| e.to_string())
    };
    let data = serde_json::json!({
      "games":table("SELECT id,title,platform,source,favorite,status,normalized_title,created_at,updated_at FROM games")?,
      "installations":table("SELECT id,game_id,source,provider,external_id,executable,install_dir,working_dir,launch_args,installed,updated_at FROM installations")?,
      "sessions":table("SELECT id,game_id,installation_id,started_at,ended_at,duration_seconds,device,provider,recovered FROM play_sessions WHERE ended_at IS NOT NULL")?,
      "metadata":table("SELECT game_id,description,developer,publisher,release_date,genres,cover,hero,source,manual,updated_at FROM game_metadata")?,
      "collections":table("SELECT id,name,kind,filter_json,updated_at FROM collections")?,
      "collection_games":table("SELECT collection_id,game_id FROM collection_games")?,
      "imported_playtime":table("SELECT game_id,provider,seconds,updated_at FROM imported_playtime")?,
      "achievements":table("SELECT id,game_id,provider,external_id,name,description,unlocked,unlocked_at,icon,progress_current,progress_target,rarity FROM achievements")?,
      "emulators":table("SELECT id,name,platform,executable,arguments_template,rom_directory,bios_directory,saves_directory,extensions,core,enabled FROM emulators")?,
      "roms":table("SELECT id,game_id,platform,path,emulator_id,hash_sha256,size_bytes,launch_args,core,updated_at FROM roms")?
    });
    serde_json::to_string_pretty(&BackupEnvelope {
        format: "ludex-backup".into(),
        version: 1,
        exported_at: Utc::now().to_rfc3339(),
        data,
    })
    .map_err(|e| e.to_string())
}

fn query_json(c: &Connection, sql: &str) -> Result<String, String> {
    let mut s = c.prepare(sql).map_err(|e| e.to_string())?;
    let names = s
        .column_names()
        .iter()
        .map(|x| x.to_string())
        .collect::<Vec<_>>();
    let mut rows = s.query([]).map_err(|e| e.to_string())?;
    let mut out = Vec::new();
    while let Some(r) = rows.next().map_err(|e| e.to_string())? {
        let mut m = serde_json::Map::new();
        for (i, n) in names.iter().enumerate() {
            let v = r.get_ref(i).map_err(|e| e.to_string())?;
            let j = match v {
                rusqlite::types::ValueRef::Null => serde_json::Value::Null,
                rusqlite::types::ValueRef::Integer(x) => x.into(),
                rusqlite::types::ValueRef::Real(x) => serde_json::json!(x),
                rusqlite::types::ValueRef::Text(x) => String::from_utf8_lossy(x).to_string().into(),
                rusqlite::types::ValueRef::Blob(_) => serde_json::Value::Null,
            };
            m.insert(n.clone(), j);
        }
        out.push(serde_json::Value::Object(m));
    }
    serde_json::to_string(&out).map_err(|e| e.to_string())
}

fn json_str<'a>(v: &'a serde_json::Value, key: &str) -> Option<&'a str> {
    v.get(key).and_then(|x| x.as_str())
}
fn ms_rfc3339(ms: i64) -> Option<String> {
    chrono::DateTime::<Utc>::from_timestamp_millis(ms).map(|d| d.to_rfc3339())
}
fn remote_is_newer(local: Option<String>, remote: Option<&str>) -> bool {
    match (local, remote) {
        (None, _) => true,
        (Some(_), None) => false,
        (Some(l), Some(r)) => r > l.as_str(),
    }
}

pub fn import_sync_json(c: &mut Connection, json: &str) -> Result<SyncSummary, String> {
    let root: serde_json::Value =
        serde_json::from_str(json).map_err(|e| format!("Sync inválido: {e}"))?;
    let format = json_str(&root, "format").unwrap_or("");
    let version = root.get("version").and_then(|v| v.as_u64()).unwrap_or(0);
    if version != 1 || !(format == "ludex-backup" || format == "ludex-mobile-sync") {
        return Err("Formato/versão de sync não suportado".into());
    }
    let data = if format == "ludex-backup" {
        root.get("data").cloned().unwrap_or_default()
    } else {
        root.clone()
    };
    let mut out = SyncSummary {
        inserted: 0,
        updated: 0,
        skipped: 0,
    };
    let tx = c.transaction().map_err(|e| e.to_string())?;
    if let Some(items) = data.get("games").and_then(|v| v.as_array()) {
        for g in items {
            let id = json_str(g, "id").unwrap_or("");
            let title = json_str(g, "title").unwrap_or("");
            if id.is_empty() || title.is_empty() {
                out.skipped += 1;
                continue;
            }
            let local: Option<String> = tx
                .query_row("SELECT updated_at FROM games WHERE id=?1", [id], |r| {
                    r.get(0)
                })
                .optional()
                .map_err(|e| e.to_string())?;
            let remote_owned = g
                .get("updated_at_ms")
                .and_then(|v| v.as_i64())
                .and_then(ms_rfc3339);
            let remote = json_str(g, "updated_at").or(remote_owned.as_deref());
            if local.is_none() {
                tx.execute("INSERT INTO games(id,title,platform,source,favorite,status,normalized_title,created_at,updated_at) VALUES(?1,?2,?3,?4,?5,?6,?7,CURRENT_TIMESTAMP,COALESCE(?8,CURRENT_TIMESTAMP))",params![id,title,json_str(g,"platform").unwrap_or("PC"),json_str(g,"source").unwrap_or("sync"),g.get("favorite").and_then(|v|v.as_i64()).unwrap_or(0),json_str(g,"status").unwrap_or("Quero jogar"),normalize_title(title),remote]).map_err(|e|e.to_string())?;
                out.inserted += 1
            } else if remote_is_newer(local.clone(), remote) {
                tx.execute("UPDATE games SET title=?1,platform=?2,favorite=?3,status=?4,updated_at=COALESCE(?5,updated_at) WHERE id=?6",params![title,json_str(g,"platform").unwrap_or("PC"),g.get("favorite").and_then(|v|v.as_i64()).unwrap_or(0),json_str(g,"status").unwrap_or("Quero jogar"),remote,id]).map_err(|e|e.to_string())?;
                out.updated += 1
            } else {
                out.skipped += 1
            }
        }
    }
    if let Some(items) = data.get("installations").and_then(|v| v.as_array()) {
        for x in items {
            let id = json_str(x, "id").unwrap_or("");
            let game = json_str(x, "game_id").unwrap_or("");
            if id.is_empty() || game.is_empty() {
                continue;
            }
            tx.execute("INSERT OR IGNORE INTO installations(id,game_id,source,provider,external_id,executable,install_dir,working_dir,launch_args,installed,updated_at) VALUES(?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,COALESCE(?11,CURRENT_TIMESTAMP))",params![id,game,json_str(x,"source").unwrap_or("sync"),json_str(x,"provider").unwrap_or("sync"),json_str(x,"external_id"),json_str(x,"executable"),json_str(x,"install_dir"),json_str(x,"working_dir"),json_str(x,"launch_args"),x.get("installed").and_then(|v|v.as_i64()).unwrap_or(0),json_str(x,"updated_at")]).map_err(|e|e.to_string())?;
        }
    }
    if let Some(items) = data.get("sessions").and_then(|v| v.as_array()) {
        for s in items {
            let id = json_str(s, "id").unwrap_or("");
            let game = json_str(s, "game_id").unwrap_or("");
            if id.is_empty() || game.is_empty() {
                continue;
            }
            let start_owned = s
                .get("started_at_ms")
                .and_then(|v| v.as_i64())
                .and_then(ms_rfc3339);
            let end_owned = s
                .get("ended_at_ms")
                .and_then(|v| v.as_i64())
                .and_then(ms_rfc3339);
            let start = json_str(s, "started_at").or(start_owned.as_deref());
            let end = json_str(s, "ended_at").or(end_owned.as_deref());
            if let (Some(st), Some(en)) = (start, end) {
                tx.execute("INSERT OR IGNORE INTO play_sessions(id,game_id,installation_id,started_at,ended_at,duration_seconds,device,provider,recovered) VALUES(?1,?2,?3,?4,?5,?6,?7,?8,?9)",params![id,game,json_str(s,"installation_id"),st,en,s.get("duration_seconds").and_then(|v|v.as_i64()).unwrap_or(0),json_str(s,"device").unwrap_or("sync"),json_str(s,"provider").unwrap_or("sync"),s.get("recovered").and_then(|v|v.as_i64()).unwrap_or(0)]).map_err(|e|e.to_string())?;
            }
        }
    }
    if let Some(items) = data.get("metadata").and_then(|v| v.as_array()) {
        for m in items {
            let game = json_str(m, "game_id").unwrap_or("");
            if game.is_empty() {
                continue;
            }
            let manual = m.get("manual").and_then(|v| v.as_i64()).unwrap_or(0);
            let existing_manual: i64 = tx
                .query_row(
                    "SELECT COALESCE((SELECT manual FROM game_metadata WHERE game_id=?1),0)",
                    [game],
                    |r| r.get(0),
                )
                .map_err(|e| e.to_string())?;
            if existing_manual != 0 && manual == 0 {
                continue;
            }
            tx.execute("INSERT INTO game_metadata(game_id,description,developer,publisher,release_date,genres,cover,hero,source,manual,updated_at) VALUES(?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,COALESCE(?11,CURRENT_TIMESTAMP)) ON CONFLICT(game_id) DO UPDATE SET description=excluded.description,developer=excluded.developer,publisher=excluded.publisher,release_date=excluded.release_date,genres=excluded.genres,cover=excluded.cover,hero=excluded.hero,source=excluded.source,manual=excluded.manual,updated_at=excluded.updated_at",params![game,json_str(m,"description"),json_str(m,"developer"),json_str(m,"publisher"),json_str(m,"release_date"),json_str(m,"genres"),json_str(m,"cover"),json_str(m,"hero"),json_str(m,"source").unwrap_or("sync"),manual,json_str(m,"updated_at")]).map_err(|e|e.to_string())?;
        }
    }
    if let Some(items) = data.get("collections").and_then(|v| v.as_array()) {
        for x in items {
            if let (Some(id), Some(name)) = (json_str(x, "id"), json_str(x, "name")) {
                tx.execute("INSERT OR IGNORE INTO collections(id,name,kind,filter_json,updated_at) VALUES(?1,?2,?3,?4,COALESCE(?5,CURRENT_TIMESTAMP))",params![id,name,json_str(x,"kind").unwrap_or("manual"),json_str(x,"filter_json"),json_str(x,"updated_at")]).map_err(|e|e.to_string())?;
            }
        }
    }
    if let Some(items) = data.get("collection_games").and_then(|v| v.as_array()) {
        for x in items {
            if let (Some(col), Some(game)) = (json_str(x, "collection_id"), json_str(x, "game_id"))
            {
                tx.execute(
                    "INSERT OR IGNORE INTO collection_games(collection_id,game_id) VALUES(?1,?2)",
                    params![col, game],
                )
                .map_err(|e| e.to_string())?;
            }
        }
    }
    if let Some(items) = data.get("imported_playtime").and_then(|v| v.as_array()) {
        for x in items {
            if let (Some(game), Some(provider)) = (json_str(x, "game_id"), json_str(x, "provider"))
            {
                tx.execute("INSERT INTO imported_playtime(game_id,provider,seconds,updated_at) VALUES(?1,?2,?3,COALESCE(?4,CURRENT_TIMESTAMP)) ON CONFLICT(game_id,provider) DO UPDATE SET seconds=MAX(imported_playtime.seconds,excluded.seconds),updated_at=excluded.updated_at",params![game,provider,x.get("seconds").and_then(|v|v.as_i64()).unwrap_or(0),json_str(x,"updated_at")]).map_err(|e|e.to_string())?;
            }
        }
    }
    tx.commit().map_err(|e| e.to_string())?;
    Ok(out)
}

fn copy_tree(src: &Path, dst: &Path) -> Result<(), String> {
    if src.is_file() {
        if let Some(p) = dst.parent() {
            fs::create_dir_all(p).map_err(|e| e.to_string())?
        }
        fs::copy(src, dst).map_err(|e| e.to_string())?;
        return Ok(());
    }
    fs::create_dir_all(dst).map_err(|e| e.to_string())?;
    for e in fs::read_dir(src).map_err(|e| e.to_string())?.flatten() {
        let p = e.path();
        copy_tree(&p, &dst.join(e.file_name()))?
    }
    Ok(())
}
pub fn backup_save_path(
    c: &Connection,
    source: &str,
    backup_root: &Path,
    emulator_id: Option<&str>,
) -> Result<SaveBackupRecord, String> {
    let src = Path::new(source);
    if !src.exists() {
        return Err("Caminho de save não encontrado".into());
    }
    fs::create_dir_all(backup_root).map_err(|e| e.to_string())?;
    let id = Uuid::new_v4().to_string();
    let name = src.file_name().and_then(|x| x.to_str()).unwrap_or("save");
    let dst = backup_root.join(format!("{}-{}", Utc::now().format("%Y%m%d-%H%M%S"), name));
    copy_tree(src, &dst)?;
    c.execute(
        "INSERT INTO save_backups(id,emulator_id,source_path,backup_path) VALUES(?1,?2,?3,?4)",
        params![id, emulator_id, source, dst.to_string_lossy()],
    )
    .map_err(|e| e.to_string())?;
    let created_at = c
        .query_row(
            "SELECT created_at FROM save_backups WHERE id=?1",
            [&id],
            |r| r.get(0),
        )
        .map_err(|e| e.to_string())?;
    Ok(SaveBackupRecord {
        id,
        emulator_id: emulator_id.map(str::to_string),
        source_path: source.into(),
        backup_path: dst.to_string_lossy().to_string(),
        created_at,
    })
}
pub fn restore_save_backup(c: &Connection, id: &str) -> Result<(), String> {
    let (src, dst): (String, String) = c
        .query_row(
            "SELECT backup_path,source_path FROM save_backups WHERE id=?1",
            [id],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )
        .map_err(|e| e.to_string())?;
    let backup = Path::new(&src);
    let target = Path::new(&dst);
    if !backup.exists() {
        return Err("Backup não existe mais".into());
    }
    if target.exists() {
        return Err("Restauração recusada porque o destino já existe. Faça backup/remova explicitamente o destino antes de restaurar.".into());
    }
    copy_tree(backup, target)
}
pub fn save_backups(c: &Connection) -> Result<Vec<SaveBackupRecord>, String> {
    let mut s=c.prepare("SELECT id,emulator_id,source_path,backup_path,created_at FROM save_backups ORDER BY created_at DESC").map_err(|e|e.to_string())?;
    let result = s
        .query_map([], |r| {
            Ok(SaveBackupRecord {
                id: r.get(0)?,
                emulator_id: r.get(1)?,
                source_path: r.get(2)?,
                backup_path: r.get(3)?,
                created_at: r.get(4)?,
            })
        })
        .map_err(|e| e.to_string())?
        .collect::<Result<Vec<_>, _>>()
        .map_err(|e| e.to_string());
    result
}

pub fn diagnostics(c: &Connection) -> Result<Vec<DiagnosticItem>, String> {
    let mut out = Vec::new();
    let version: i64 = c
        .query_row(
            "SELECT COALESCE(MAX(version),0) FROM schema_migrations",
            [],
            |r| r.get(0),
        )
        .map_err(|e| e.to_string())?;
    out.push(DiagnosticItem {
        level: "info".into(),
        area: "database".into(),
        message: format!("Schema version {version}; foreign_keys e WAL ativos por conexão."),
    });
    let open: i64 = c
        .query_row(
            "SELECT COUNT(*) FROM play_sessions WHERE ended_at IS NULL",
            [],
            |r| r.get(0),
        )
        .map_err(|e| e.to_string())?;
    out.push(DiagnosticItem {
        level: if open > 0 { "info" } else { "ok" }.into(),
        area: "sessions".into(),
        message: format!("{open} sessão(ões) ativa(s)."),
    });
    Ok(out)
}

pub fn create_db_backup(db_path: &Path, backup_dir: &Path) -> Result<String, String> {
    fs::create_dir_all(backup_dir).map_err(|e| e.to_string())?;
    let dest = backup_dir.join(format!("ludex-{}.db", Utc::now().format("%Y%m%d-%H%M%S")));
    let c = Connection::open(db_path).map_err(|e| e.to_string())?;
    let escaped = dest.to_string_lossy().replace('\'', "''");
    c.execute_batch(&format!("VACUUM INTO '{escaped}'"))
        .map_err(|e| e.to_string())?;
    Ok(dest.to_string_lossy().to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn migration_is_idempotent() {
        let c = Connection::open_in_memory().unwrap();
        c.execute_batch("CREATE TABLE games(id TEXT PRIMARY KEY,title TEXT NOT NULL,platform TEXT NOT NULL,source TEXT NOT NULL,executable TEXT,favorite INTEGER NOT NULL DEFAULT 0,status TEXT NOT NULL DEFAULT 'Quero jogar',total_seconds INTEGER NOT NULL DEFAULT 0,normalized_title TEXT NOT NULL DEFAULT '',created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP);CREATE TABLE installations(id TEXT PRIMARY KEY,game_id TEXT NOT NULL,source TEXT NOT NULL,provider TEXT NOT NULL DEFAULT 'manual',external_id TEXT,executable TEXT,install_dir TEXT,working_dir TEXT,launch_args TEXT,installed INTEGER NOT NULL DEFAULT 1,updated_at TEXT);CREATE TABLE play_sessions(id TEXT PRIMARY KEY,game_id TEXT NOT NULL,started_at TEXT NOT NULL,duration_seconds INTEGER NOT NULL DEFAULT 0,device TEXT NOT NULL);CREATE TABLE collections(id TEXT PRIMARY KEY,name TEXT NOT NULL UNIQUE);CREATE TABLE collection_games(collection_id TEXT,game_id TEXT);CREATE TABLE emulators(id TEXT PRIMARY KEY,name TEXT NOT NULL,platform TEXT NOT NULL,executable TEXT NOT NULL,arguments_template TEXT NOT NULL,rom_directory TEXT,bios_directory TEXT,saves_directory TEXT);CREATE TABLE roms(id TEXT PRIMARY KEY,game_id TEXT NOT NULL,platform TEXT NOT NULL,path TEXT NOT NULL UNIQUE,emulator_id TEXT);CREATE TABLE settings(key TEXT PRIMARY KEY,value TEXT NOT NULL);").unwrap();
        migrate(&c).unwrap();
        migrate(&c).unwrap();
        let v: i64 = c
            .query_row("SELECT MAX(version) FROM schema_migrations", [], |r| {
                r.get(0)
            })
            .unwrap();
        assert_eq!(v, 3);
    }
    #[test]
    fn handles_five_thousand_games() {
        let mut c = Connection::open_in_memory().unwrap();
        c.execute_batch("CREATE TABLE games(id TEXT PRIMARY KEY,title TEXT NOT NULL,platform TEXT NOT NULL,source TEXT NOT NULL,executable TEXT,favorite INTEGER NOT NULL DEFAULT 0,status TEXT NOT NULL DEFAULT 'Quero jogar',total_seconds INTEGER NOT NULL DEFAULT 0,normalized_title TEXT NOT NULL DEFAULT '',created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP); CREATE INDEX idx_games_title ON games(title);").unwrap();
        let tx = c.transaction().unwrap();
        for i in 0..5000 {
            tx.execute("INSERT INTO games(id,title,platform,source,normalized_title) VALUES(?1,?2,'PC','synthetic',?2)",params![format!("g{i}"),format!("Game {i:04}")]).unwrap();
        }
        tx.commit().unwrap();
        let count: i64 = c
            .query_row(
                "SELECT COUNT(*) FROM games WHERE title LIKE 'Game 4%'",
                [],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(count, 1000);
    }
}
