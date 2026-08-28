from pathlib import Path
import re
import textwrap

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def write(path: str, content: str) -> None:
    p = ROOT / path
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(content, encoding="utf-8")


def replace_once(path: str, old: str, new: str) -> None:
    content = read(path)
    if content.count(old) != 1:
        raise SystemExit(f"expected exactly one match in {path}: {old[:80]!r}, got {content.count(old)}")
    write(path, content.replace(old, new, 1))


# Version bump. This is the first build that can update itself; users on 0.9.0 need one final manual install.
replace_once("package.json", '"version": "0.9.0"', '"version": "0.9.1"')
replace_once("src-tauri/Cargo.toml", 'version = "0.9.0"', 'version = "0.9.1"')
replace_once("src-tauri/tauri.conf.json", '"version": "0.9.0"', '"version": "0.9.1"')
replace_once("android/app/build.gradle", "versionCode 9\n        versionName '0.9.0'", "versionCode 10\n        versionName '0.9.1'")

# Dependencies for a small, signed-origin GitHub release updater and safe local image data URLs.
cargo = read("src-tauri/Cargo.toml")
if 'reqwest = ' not in cargo:
    cargo = cargo.replace('sha2 = "0.10"\n', 'sha2 = "0.10"\nbase64 = "0.22"\nsemver = "1"\nreqwest = { version = "0.12", default-features = false, features = ["blocking", "json", "rustls-tls"] }\n')
write("src-tauri/Cargo.toml", cargo)

steam_data = r'''
use base64::{engine::general_purpose::STANDARD, Engine as _};
use rusqlite::{params, Connection, OptionalExtension};
use std::{
    collections::HashMap,
    fs,
    path::{Path, PathBuf},
    time::SystemTime,
};

use crate::{metadata, providers::steam::SteamProvider};

#[derive(Debug, Clone, PartialEq, Eq)]
enum Token {
    Text(String),
    Open,
    Close,
}

fn tokenize_vdf(content: &str) -> Vec<Token> {
    let chars: Vec<char> = content.chars().collect();
    let mut out = Vec::new();
    let mut i = 0usize;
    while i < chars.len() {
        match chars[i] {
            c if c.is_whitespace() => i += 1,
            '{' => {
                out.push(Token::Open);
                i += 1;
            }
            '}' => {
                out.push(Token::Close);
                i += 1;
            }
            '/' if i + 1 < chars.len() && chars[i + 1] == '/' => {
                i += 2;
                while i < chars.len() && chars[i] != '\n' {
                    i += 1;
                }
            }
            '"' => {
                i += 1;
                let mut value = String::new();
                while i < chars.len() {
                    match chars[i] {
                        '\\' if i + 1 < chars.len() => {
                            let next = chars[i + 1];
                            if matches!(next, '\\' | '"') {
                                value.push(next);
                                i += 2;
                            } else {
                                value.push('\\');
                                i += 1;
                            }
                        }
                        '"' => {
                            i += 1;
                            break;
                        }
                        c => {
                            value.push(c);
                            i += 1;
                        }
                    }
                }
                out.push(Token::Text(value));
            }
            _ => {
                let start = i;
                while i < chars.len()
                    && !chars[i].is_whitespace()
                    && !matches!(chars[i], '{' | '}')
                {
                    i += 1;
                }
                out.push(Token::Text(chars[start..i].iter().collect()));
            }
        }
    }
    out
}

fn is_apps_path(stack: &[String]) -> Option<&str> {
    if stack.len() < 5 {
        return None;
    }
    let n = stack.len();
    let tail = &stack[n - 5..n - 1];
    let expected = ["software", "valve", "steam", "apps"];
    if tail
        .iter()
        .zip(expected)
        .all(|(actual, wanted)| actual.eq_ignore_ascii_case(wanted))
    {
        Some(stack[n - 1].as_str())
    } else {
        None
    }
}

pub fn parse_local_playtime(content: &str) -> HashMap<String, i64> {
    let tokens = tokenize_vdf(content);
    let mut stack: Vec<String> = Vec::new();
    let mut values = HashMap::new();
    let mut i = 0usize;
    while i < tokens.len() {
        match &tokens[i] {
            Token::Text(key) if matches!(tokens.get(i + 1), Some(Token::Open)) => {
                stack.push(key.clone());
                i += 2;
            }
            Token::Text(key) => {
                if let Some(Token::Text(value)) = tokens.get(i + 1) {
                    if key.eq_ignore_ascii_case("Playtime") {
                        if let Some(app_id) = is_apps_path(&stack) {
                            if app_id.chars().all(|c| c.is_ascii_digit()) {
                                if let Ok(minutes) = value.parse::<i64>() {
                                    values
                                        .entry(app_id.to_string())
                                        .and_modify(|v| *v = (*v).max(minutes))
                                        .or_insert(minutes.max(0));
                                }
                            }
                        }
                    }
                    i += 2;
                } else {
                    i += 1;
                }
            }
            Token::Close => {
                let _ = stack.pop();
                i += 1;
            }
            Token::Open => i += 1,
        }
    }
    values
}

fn active_localconfig(root: &Path) -> Option<PathBuf> {
    let userdata = root.join("userdata");
    let entries = fs::read_dir(userdata).ok()?;
    let mut candidates: Vec<(SystemTime, PathBuf)> = Vec::new();
    for entry in entries.flatten() {
        let account = entry.file_name().to_string_lossy().to_string();
        if !account.chars().all(|c| c.is_ascii_digit()) {
            continue;
        }
        let path = entry.path().join("config").join("localconfig.vdf");
        if !path.is_file() {
            continue;
        }
        let modified = fs::metadata(&path)
            .and_then(|m| m.modified())
            .unwrap_or(SystemTime::UNIX_EPOCH);
        candidates.push((modified, path));
    }
    candidates.sort_by_key(|(modified, _)| *modified);
    candidates.pop().map(|(_, path)| path)
}

fn import_playtime(connection: &Connection, root: &Path) -> Result<usize, String> {
    let Some(config) = active_localconfig(root) else {
        return Ok(0);
    };
    let content = fs::read_to_string(&config)
        .map_err(|e| format!("Falha ao ler {}: {e}", config.display()))?;
    let playtime = parse_local_playtime(&content);
    let mut updated = 0usize;
    for (app_id, minutes) in playtime {
        let game_id: Option<String> = connection
            .query_row(
                "SELECT game_id FROM external_ids WHERE provider='steam' AND external_id=?1",
                [&app_id],
                |row| row.get(0),
            )
            .optional()
            .map_err(|e| e.to_string())?;
        let Some(game_id) = game_id else {
            continue;
        };
        connection
            .execute(
                "INSERT INTO imported_playtime(game_id,provider,seconds,updated_at)
                 VALUES(?1,'steam',?2,CURRENT_TIMESTAMP)
                 ON CONFLICT(game_id,provider) DO UPDATE SET seconds=excluded.seconds,updated_at=CURRENT_TIMESTAMP",
                params![game_id, minutes.saturating_mul(60)],
            )
            .map_err(|e| e.to_string())?;
        updated += 1;
    }
    Ok(updated)
}

fn ensure_artwork_fallbacks(connection: &Connection) -> Result<usize, String> {
    let mut statement = connection
        .prepare("SELECT game_id,external_id FROM external_ids WHERE provider='steam'")
        .map_err(|e| e.to_string())?;
    let rows = statement
        .query_map([], |row| Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?)))
        .map_err(|e| e.to_string())?
        .collect::<Result<Vec<_>, _>>()
        .map_err(|e| e.to_string())?;
    drop(statement);
    let mut updated = 0usize;
    for (game_id, app_id) in rows {
        if !app_id.chars().all(|c| c.is_ascii_digit()) {
            continue;
        }
        let cover = format!(
            "https://shared.steamstatic.com/store_item_assets/steam/apps/{app_id}/library_600x900.jpg"
        );
        let hero = format!(
            "https://shared.steamstatic.com/store_item_assets/steam/apps/{app_id}/library_hero.jpg"
        );
        connection
            .execute(
                "INSERT INTO game_metadata(game_id,cover,hero,source,manual,updated_at)
                 VALUES(?1,?2,?3,'steam-cdn',0,CURRENT_TIMESTAMP)
                 ON CONFLICT(game_id) DO UPDATE SET
                   cover=CASE WHEN game_metadata.manual=0 AND (game_metadata.cover IS NULL OR trim(game_metadata.cover)='') THEN excluded.cover ELSE game_metadata.cover END,
                   hero=CASE WHEN game_metadata.manual=0 AND (game_metadata.hero IS NULL OR trim(game_metadata.hero)='') THEN excluded.hero ELSE game_metadata.hero END,
                   source=CASE WHEN game_metadata.manual=0 AND (game_metadata.cover IS NULL OR trim(game_metadata.cover)='') THEN excluded.source ELSE game_metadata.source END,
                   updated_at=CASE WHEN game_metadata.manual=0 THEN CURRENT_TIMESTAMP ELSE game_metadata.updated_at END",
                params![game_id, cover, hero],
            )
            .map_err(|e| e.to_string())?;
        updated += 1;
    }
    Ok(updated)
}

pub fn sync(connection: &Connection) -> Result<(usize, usize), String> {
    let Some(root) = SteamProvider::detect_root() else {
        return Ok((0, 0));
    };
    let _ = metadata::refresh_local_metadata(connection)?;
    let covers = ensure_artwork_fallbacks(connection)?;
    let playtime = import_playtime(connection, &root)?;
    Ok((covers, playtime))
}

pub fn load_local_artwork(path: &str) -> Result<String, String> {
    let p = Path::new(path);
    if !p.is_file() {
        return Err("Imagem local não encontrada".into());
    }
    let metadata = fs::metadata(p).map_err(|e| e.to_string())?;
    if metadata.len() > 20 * 1024 * 1024 {
        return Err("Imagem local excede 20 MB".into());
    }
    let extension = p
        .extension()
        .and_then(|value| value.to_str())
        .unwrap_or("")
        .to_ascii_lowercase();
    let mime = match extension.as_str() {
        "jpg" | "jpeg" => "image/jpeg",
        "png" => "image/png",
        "webp" => "image/webp",
        "gif" => "image/gif",
        _ => return Err("Formato de imagem local não suportado".into()),
    };
    let bytes = fs::read(p).map_err(|e| e.to_string())?;
    Ok(format!("data:{mime};base64,{}", STANDARD.encode(bytes)))
}

#[cfg(test)]
mod tests {
    use super::parse_local_playtime;

    #[test]
    fn parses_playtime_minutes_from_steam_localconfig() {
        let vdf = r#"
        "UserLocalConfigStore"
        {
          "Software"
          {
            "Valve"
            {
              "Steam"
              {
                "apps"
                {
                  "1091500"
                  {
                    "LastPlayed" "1750000000"
                    "Playtime2wks" "120"
                    "Playtime" "4321"
                  }
                  "431960" { "Playtime" "987" }
                }
              }
            }
          }
        }
        "#;
        let p = parse_local_playtime(vdf);
        assert_eq!(p.get("1091500"), Some(&4321));
        assert_eq!(p.get("431960"), Some(&987));
    }

    #[test]
    fn ignores_non_app_playtime_keys() {
        let vdf = r#""other" { "123" { "Playtime" "999" } }"#;
        assert!(parse_local_playtime(vdf).is_empty());
    }
}
'''.lstrip()
write("src-tauri/src/steam_data.rs", steam_data)

updater = r'''
use reqwest::blocking::Client;
use semver::Version;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{fs, path::{Path, PathBuf}, process::Command, time::Duration};

const RELEASES_API: &str = "https://api.github.com/repos/Gabriel-Liz2003/Ludex/releases/latest";

#[derive(Debug, Clone, Serialize)]
pub struct UpdateInfo {
    pub available: bool,
    pub current_version: String,
    pub latest_version: Option<String>,
    pub notes: Option<String>,
    pub published_at: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
struct ReleaseAsset {
    name: String,
    browser_download_url: String,
    digest: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
struct Release {
    tag_name: String,
    body: Option<String>,
    published_at: Option<String>,
    assets: Vec<ReleaseAsset>,
}

fn client() -> Result<Client, String> {
    Client::builder()
        .timeout(Duration::from_secs(20))
        .user_agent("Ludex-Updater")
        .build()
        .map_err(|e| e.to_string())
}

fn fetch_release() -> Result<Option<Release>, String> {
    let response = client()?.get(RELEASES_API).send().map_err(|e| e.to_string())?;
    if response.status().as_u16() == 404 {
        return Ok(None);
    }
    let response = response.error_for_status().map_err(|e| e.to_string())?;
    response.json::<Release>().map(Some).map_err(|e| e.to_string())
}

fn clean_version(value: &str) -> &str {
    value.strip_prefix('v').unwrap_or(value)
}

fn windows_asset(release: &Release) -> Option<&ReleaseAsset> {
    release.assets.iter().find(|asset| {
        let name = asset.name.to_ascii_lowercase();
        name.ends_with(".exe") && name.contains("x64") && name.contains("setup")
    })
}

pub fn check() -> Result<UpdateInfo, String> {
    let current_text = env!("CARGO_PKG_VERSION").to_string();
    let current = Version::parse(&current_text).map_err(|e| e.to_string())?;
    let Some(release) = fetch_release()? else {
        return Ok(UpdateInfo {
            available: false,
            current_version: current_text,
            latest_version: None,
            notes: None,
            published_at: None,
        });
    };
    let latest_text = clean_version(&release.tag_name).to_string();
    let latest = Version::parse(&latest_text).map_err(|e| format!("Release inválida: {e}"))?;
    Ok(UpdateInfo {
        available: latest > current && windows_asset(&release).is_some(),
        current_version: current_text,
        latest_version: Some(latest_text),
        notes: release.body,
        published_at: release.published_at,
    })
}

pub fn download_latest(data_dir: &Path) -> Result<PathBuf, String> {
    let current = Version::parse(env!("CARGO_PKG_VERSION")).map_err(|e| e.to_string())?;
    let release = fetch_release()?.ok_or_else(|| "Nenhuma release publicada".to_string())?;
    let latest_text = clean_version(&release.tag_name);
    let latest = Version::parse(latest_text).map_err(|e| e.to_string())?;
    if latest <= current {
        return Err("O Ludex já está atualizado".into());
    }
    let asset = windows_asset(&release)
        .ok_or_else(|| "A release mais recente não contém instalador Windows x64".to_string())?;
    let response = client()?
        .get(&asset.browser_download_url)
        .send()
        .and_then(|r| r.error_for_status())
        .map_err(|e| e.to_string())?;
    let bytes = response.bytes().map_err(|e| e.to_string())?;
    if let Some(expected) = asset.digest.as_deref().and_then(|d| d.strip_prefix("sha256:")) {
        let actual = format!("{:x}", Sha256::digest(&bytes));
        if !actual.eq_ignore_ascii_case(expected) {
            return Err("Falha de integridade no instalador baixado".into());
        }
    }
    let updates = data_dir.join("updates");
    fs::create_dir_all(&updates).map_err(|e| e.to_string())?;
    let path = updates.join(format!("Ludex_{latest_text}_x64-setup.exe"));
    fs::write(&path, &bytes).map_err(|e| e.to_string())?;
    Ok(path)
}

#[cfg(windows)]
pub fn launch_installer(path: &Path) -> Result<(), String> {
    Command::new(path)
        .arg("/S")
        .spawn()
        .map_err(|e| format!("Falha ao iniciar o instalador: {e}"))?;
    Ok(())
}

#[cfg(not(windows))]
pub fn launch_installer(_path: &Path) -> Result<(), String> {
    Err("Atualização automática está habilitada somente no Desktop Windows nesta versão".into())
}

#[cfg(test)]
mod tests {
    use super::clean_version;
    #[test]
    fn accepts_v_prefixed_release_versions() {
        assert_eq!(clean_version("v0.9.1"), "0.9.1");
        assert_eq!(clean_version("1.0.0"), "1.0.0");
    }
}
'''.lstrip()
write("src-tauri/src/updater.rs", updater)

# DB totals: historical provider playtime is authoritative when greater than locally tracked sessions.
db = read("src-tauri/src/db.rs")
old_total = "       COALESCE((SELECT SUM(duration_seconds) FROM play_sessions ps WHERE ps.game_id=g.id AND ps.ended_at IS NOT NULL), 0),\n"
new_total = "       MAX(\n         COALESCE((SELECT SUM(duration_seconds) FROM play_sessions ps WHERE ps.game_id=g.id AND ps.ended_at IS NOT NULL), 0),\n         COALESCE((SELECT SUM(seconds) FROM imported_playtime ip WHERE ip.game_id=g.id), 0)\n       ),\n"
if db.count(old_total) != 1:
    raise SystemExit("GAME_SELECT total expression changed")
db = db.replace(old_total, new_total, 1)
db = db.replace("    let stats = connection.query_row(\n", "    let mut stats = connection.query_row(\n", 1)
needle = "    ).map_err(|e| e.to_string())?;\n\n    let mut installation_stmt = connection.prepare(\n"
replacement = "    ).map_err(|e| e.to_string())?;\n    let imported_total: i64 = connection\n        .query_row(\n            \"SELECT COALESCE(SUM(seconds),0) FROM imported_playtime WHERE game_id=?1\",\n            [game_id],\n            |row| row.get(0),\n        )\n        .map_err(|e| e.to_string())?;\n    stats.total_seconds = stats.total_seconds.max(imported_total);\n\n    let mut installation_stmt = connection.prepare(\n"
if db.count(needle) != 1:
    raise SystemExit("details stats insertion point changed")
db = db.replace(needle, replacement, 1)
write("src-tauri/src/db.rs", db)

# Stats should not call imported Steam games 'never played', and top games should include historical time.
product = read("src-tauri/src/product.rs")
product = product.replace(
    'never_played:q("SELECT COUNT(*) FROM games g WHERE NOT EXISTS(SELECT 1 FROM play_sessions p WHERE p.game_id=g.id AND p.ended_at IS NOT NULL)")?',
    'never_played:q("SELECT COUNT(*) FROM games g WHERE NOT EXISTS(SELECT 1 FROM play_sessions p WHERE p.game_id=g.id AND p.ended_at IS NOT NULL) AND COALESCE((SELECT SUM(seconds) FROM imported_playtime ip WHERE ip.game_id=g.id),0)=0")?',
    1,
)
old_top = 'top_games:named_times(c,"SELECT g.title,SUM(p.duration_seconds) FROM play_sessions p JOIN games g ON g.id=p.game_id WHERE p.ended_at IS NOT NULL GROUP BY g.id ORDER BY 2 DESC LIMIT 10")?'
new_top = 'top_games:named_times(c,"SELECT g.title,MAX(COALESCE((SELECT SUM(duration_seconds) FROM play_sessions p WHERE p.game_id=g.id AND p.ended_at IS NOT NULL),0),COALESCE((SELECT SUM(seconds) FROM imported_playtime ip WHERE ip.game_id=g.id),0)) AS total FROM games g ORDER BY total DESC LIMIT 10")?'
if product.count(old_top) != 1:
    raise SystemExit("top_games expression changed")
product = product.replace(old_top, new_top, 1)
write("src-tauri/src/product.rs", product)

# Wire Steam enrichment + updater commands into Tauri.
lib = read("src-tauri/src/lib.rs")
lib = lib.replace("mod sessions;\n", "mod sessions;\nmod steam_data;\nmod updater;\n", 1)
old_sync = '''        if provider == "steam" {\n            let _ = metadata::refresh_local_metadata(&c);\n        }\n'''
new_sync = '''        if provider == "steam" {\n            let _ = steam_data::sync(&c)?;\n        }\n'''
if lib.count(old_sync) != 1:
    raise SystemExit("generic Steam sync block changed")
lib = lib.replace(old_sync, new_sync, 1)
old_legacy = 'let r=db::import_installations(&c,"steam",&items,&root.to_string_lossy(),libraries.len())?;c.execute("INSERT INTO settings(key,value) VALUES(\'steam.last_sync\',CURRENT_TIMESTAMP) ON CONFLICT(key) DO UPDATE SET value=excluded.value",[]).map_err(|e|e.to_string())?;Ok(r)'
new_legacy = 'let r=db::import_installations(&c,"steam",&items,&root.to_string_lossy(),libraries.len())?;let _=steam_data::sync(&c)?;c.execute("INSERT INTO settings(key,value) VALUES(\'steam.last_sync\',CURRENT_TIMESTAMP) ON CONFLICT(key) DO UPDATE SET value=excluded.value",[]).map_err(|e|e.to_string())?;Ok(r)'
if lib.count(old_legacy) != 1:
    raise SystemExit("legacy Steam sync expression changed")
lib = lib.replace(old_legacy, new_legacy, 1)
commands = r'''

#[tauri::command]
fn load_local_artwork(path: String) -> Result<String, String> {
    steam_data::load_local_artwork(&path)
}

#[tauri::command]
async fn check_for_updates() -> Result<updater::UpdateInfo, String> {
    tauri::async_runtime::spawn_blocking(updater::check)
        .await
        .map_err(|e| e.to_string())?
}

#[tauri::command]
async fn install_update(
    app: tauri::AppHandle,
    state: tauri::State<'_, AppState>,
) -> Result<(), String> {
    let data_dir = state.data_dir.clone();
    let installer = tauri::async_runtime::spawn_blocking(move || updater::download_latest(&data_dir))
        .await
        .map_err(|e| e.to_string())??;
    updater::launch_installer(&installer)?;
    app.exit(0);
    Ok(())
}
'''
marker = "\n#[cfg_attr(mobile, tauri::mobile_entry_point)]\npub fn run() {"
if lib.count(marker) != 1:
    raise SystemExit("run marker changed")
lib = lib.replace(marker, commands + marker, 1)
handler_marker = "            diagnostics\n        ])"
handler_new = "            diagnostics,\n            load_local_artwork,\n            check_for_updates,\n            install_update\n        ])"
if lib.count(handler_marker) != 1:
    raise SystemExit("invoke handler tail changed")
lib = lib.replace(handler_marker, handler_new, 1)
write("src-tauri/src/lib.rs", lib)

# Frontend: backend-served local artwork, Steam historical time, update panel + background Steam sync.
main = read("src/main.ts")
main = main.replace("import { convertFileSrc, invoke } from '@tauri-apps/api/core';", "import { invoke } from '@tauri-apps/api/core';", 1)
main = main.replace(
    "type SaveBackup={id:string;emulator_id:string|null;source_path:string;backup_path:string;created_at:string};",
    "type SaveBackup={id:string;emulator_id:string|null;source_path:string;backup_path:string;created_at:string};\ntype UpdateInfo={available:boolean;current_version:string;latest_version:string|null;notes:string|null;published_at:string|null};",
    1,
)
main = main.replace("if(filter==='never')v=v.filter(g=>g.session_count===0);", "if(filter==='never')v=v.filter(g=>g.total_seconds===0);", 1)
old_art = "function artworkSrc(value:string){return /^(https?:|data:|blob:)/i.test(value)?value:convertFileSrc(value)}\nfunction hydrateArtwork(){const observer=new IntersectionObserver(entries=>{for(const entry of entries){if(!entry.isIntersecting)continue;const node=entry.target as HTMLElement;observer.unobserve(node);const id=node.dataset.coverId;if(!id)continue;void invoke<Metadata|null>('get_metadata',{gameId:id}).then(meta=>{if(!meta?.cover)return;const img=document.createElement('img');img.loading='lazy';img.alt='';img.src=artworkSrc(meta.cover);img.onerror=()=>img.remove();node.prepend(img);const initialsNode=node.querySelector('span');if(initialsNode)(initialsNode as HTMLElement).style.display='none'}).catch(()=>{})}},{rootMargin:'300px'});document.querySelectorAll<HTMLElement>('[data-cover-id]').forEach(node=>observer.observe(node))}"
new_art = "async function artworkSrc(value:string){if(/^(https?:|data:|blob:)/i.test(value))return value;return invoke<string>('load_local_artwork',{path:value})}\nfunction hydrateArtwork(){const observer=new IntersectionObserver(entries=>{for(const entry of entries){if(!entry.isIntersecting)continue;const node=entry.target as HTMLElement;observer.unobserve(node);const id=node.dataset.coverId;if(!id)continue;void invoke<Metadata|null>('get_metadata',{gameId:id}).then(async meta=>{if(!meta?.cover)return;const img=document.createElement('img');img.loading='lazy';img.alt='';img.onload=()=>{const initialsNode=node.querySelector('span');if(initialsNode)(initialsNode as HTMLElement).style.display='none'};img.onerror=()=>img.remove();img.src=await artworkSrc(meta.cover);node.prepend(img)}).catch(()=>{})}},{rootMargin:'300px'});document.querySelectorAll<HTMLElement>('[data-cover-id]').forEach(node=>observer.observe(node))}"
if main.count(old_art) != 1:
    raise SystemExit("artwork block changed")
main = main.replace(old_art, new_art, 1)
settings_pattern = re.compile(r"async function renderSettings\(\)\{.*?\}\nasync function renderDiagnostics", re.S)
match = settings_pattern.search(main)
if not match:
    raise SystemExit("settings function not found")
new_settings = r'''async function renderSettings(){title('Configurações');const ps=await invoke<Provider[]>('provider_statuses');let update:UpdateInfo|null=null;try{update=await invoke<UpdateInfo>('check_for_updates')}catch{}document.querySelector('#content')!.innerHTML=`<section class="page"><h2>Atualizações</h2><div class="settings-panels"><article><div class="provider-head"><div><h3>Ludex ${esc(update?.current_version||'')}</h3><p>${update?.available?`Versão ${esc(update.latest_version||'')} disponível.`:'Você está na versão mais recente publicada.'}</p></div><span class="pill ${update?.available?'':'ok'}">${update?.available?'Atualização disponível':'Atualizado'}</span></div>${update?.notes?`<p class="muted">${esc(update.notes.slice(0,500))}</p>`:''}<button id="app-update" class="${update?.available?'primary':'ghost'}">${update?.available?'Baixar e instalar':'Verificar novamente'}</button><small>As próximas versões são baixadas pelo próprio Ludex a partir das Releases oficiais do GitHub.</small></article></div><h2>Providers</h2><div class="provider-grid">${ps.map(p=>`<article><div class="provider-head"><h3>${esc(p.name)}</h3><span class="pill ${p.detected?'ok':''}">${p.detected?'Detectado':'Não detectado'}</span></div><p>${esc(p.message)}</p><small>${esc(p.root_path||'Sem caminho local exposto')}</small><div class="provider-foot"><span>${p.games_found} jogo(s) · sync ${date(p.last_sync)}</span><button data-sync="${p.id}" class="ghost">Sincronizar</button></div></article>`).join('')}</div><h2>Dados e sincronização</h2><div class="settings-panels"><article><h3>Sync por arquivo</h3><p>Exporta um bundle JSON versionado para mover entre Desktop e Android. IDs de sessão evitam duplicidade.</p><div><button id="export-json" class="primary">Exportar JSON</button><button id="import-json" class="ghost">Importar JSON</button></div><textarea id="sync-json" placeholder="O JSON exportado aparece aqui. Cole um bundle para importar."></textarea></article><article><h3>Backup do banco</h3><p>Cria uma cópia SQLite consistente na pasta local de backups do Ludex.</p><button id="db-backup" class="ghost">Criar backup</button><p id="backup-path" class="muted"></p></article></div><h2>Privacidade</h2><p class="muted">Biblioteca, sessões e metadata permanecem locais. Não há telemetria obrigatória nem servidor proprietário.</p></section>`;document.querySelectorAll<HTMLElement>('[data-sync]').forEach(b=>b.onclick=async()=>{b.textContent='Sincronizando…';try{const r=await invoke<any>('sync_provider',{provider:b.dataset.sync});toast(`${r.games_found} encontrados · ${r.games_created} novos.`);await refresh();void renderSettings()}catch(e){toast(String(e),true);b.textContent='Sincronizar'}});const updateButton=document.querySelector<HTMLButtonElement>('#app-update');if(updateButton)updateButton.onclick=async()=>{if(!update?.available){updateButton.textContent='Verificando…';void renderSettings();return}if(!confirm(`Atualizar o Ludex para ${update.latest_version}? O app será fechado durante a instalação.`))return;updateButton.disabled=true;updateButton.textContent='Baixando atualização…';try{await invoke('install_update')}catch(e){updateButton.disabled=false;updateButton.textContent='Baixar e instalar';toast(String(e),true)}};(document.querySelector('#export-json') as HTMLButtonElement).onclick=async()=>{(document.querySelector('#sync-json') as HTMLTextAreaElement).value=await invoke('export_backup_json')};(document.querySelector('#import-json') as HTMLButtonElement).onclick=async()=>{const json=(document.querySelector('#sync-json') as HTMLTextAreaElement).value;if(!json.trim())return;try{const r=await invoke<any>('import_sync_json',{json});toast(`${r.inserted} registros importados.`);await refresh()}catch(e){toast(String(e),true)}};(document.querySelector('#db-backup') as HTMLButtonElement).onclick=async()=>{try{const p=await invoke<string>('backup_database');document.querySelector('#backup-path')!.textContent=p;toast('Backup criado.')}catch(e){toast(String(e),true)}}}
async function renderDiagnostics'''
main = settings_pattern.sub(new_settings, main, count=1)
old_boot = "shell();void refresh();window.setInterval(()=>{if(['library','recent','favorites','installed'].includes(view))void refresh(true)},5000);"
new_boot = "async function bootstrap(){shell();await refresh();try{await invoke('sync_provider',{provider:'steam'});await refresh(true)}catch{}window.setTimeout(async()=>{try{const u=await invoke<UpdateInfo>('check_for_updates');if(u.available)toast(`Ludex ${u.latest_version} disponível em Configurações.`)}catch{}},1800)}\nvoid bootstrap();window.setInterval(()=>{if(['library','recent','favorites','installed'].includes(view))void refresh(true)},5000);"
if main.count(old_boot) != 1:
    raise SystemExit("bootstrap tail changed")
main = main.replace(old_boot, new_boot, 1)
write("src/main.ts", main)

release_workflow = r'''name: release

on:
  push:
    branches: [main]

permissions:
  contents: write

jobs:
  windows-release:
    runs-on: windows-latest
    steps:
      - uses: actions/checkout@v4
      - id: version
        shell: pwsh
        run: |
          $version = (Get-Content src-tauri/tauri.conf.json | ConvertFrom-Json).version
          "version=$version" >> $env:GITHUB_OUTPUT
      - id: existing
        shell: pwsh
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          gh release view "v${{ steps.version.outputs.version }}" *> $null
          if ($LASTEXITCODE -eq 0) { "exists=true" >> $env:GITHUB_OUTPUT } else { "exists=false" >> $env:GITHUB_OUTPUT }
      - if: steps.existing.outputs.exists != 'true'
        uses: actions/setup-node@v4
        with:
          node-version: 22
      - if: steps.existing.outputs.exists != 'true'
        uses: dtolnay/rust-toolchain@stable
      - if: steps.existing.outputs.exists != 'true'
        run: npm install
      - if: steps.existing.outputs.exists != 'true'
        run: npm run tauri -- build --bundles nsis
      - if: steps.existing.outputs.exists != 'true'
        shell: pwsh
        run: |
          $exe = Get-ChildItem src-tauri/target/release/bundle/nsis/*setup.exe | Select-Object -First 1
          $hash = (Get-FileHash $exe.FullName -Algorithm SHA256).Hash.ToLower()
          "$hash  $($exe.Name)" | Set-Content "$($exe.FullName).sha256" -Encoding ascii
      - if: steps.existing.outputs.exists != 'true'
        uses: softprops/action-gh-release@v2
        with:
          tag_name: v${{ steps.version.outputs.version }}
          name: Ludex v${{ steps.version.outputs.version }}
          target_commitish: ${{ github.sha }}
          generate_release_notes: true
          make_latest: true
          files: |
            src-tauri/target/release/bundle/nsis/*setup.exe
            src-tauri/target/release/bundle/nsis/*setup.exe.sha256
'''
write(".github/workflows/release.yml", release_workflow)

# README note about updater and Steam enrichment.
readme = read("README.md")
append = """

## Atualizações Desktop

A partir da versão 0.9.1, o Desktop Windows consulta as Releases oficiais deste repositório, baixa o instalador da versão mais recente dentro do próprio Ludex e inicia a atualização. O workflow `release.yml` publica automaticamente uma nova release quando `main` recebe uma versão ainda não publicada.

A versão 0.9.1 também enriquece a importação Steam sem exigir chave de API: o Ludex lê o `localconfig.vdf` da conta Steam local mais recentemente usada para importar `Playtime` e usa primeiro o artwork local do cache, com fallback para o CDN oficial da Steam.
"""
if "## Atualizações Desktop" not in readme:
    write("README.md", readme.rstrip() + append + "\n")

# Remove this one-shot machinery from the generated commit.
for rel in ["scripts/apply-steam-artwork-playtime-updater.py", ".github/workflows/steam-artwork-playtime-updater-once.yml"]:
    p = ROOT / rel
    if p.exists():
        p.unlink()
