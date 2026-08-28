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
                while i < chars.len() && !chars[i].is_whitespace() && !matches!(chars[i], '{' | '}')
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
    let mut values: HashMap<String, i64> = HashMap::new();
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
        .query_map([], |row| {
            Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?))
        })
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
