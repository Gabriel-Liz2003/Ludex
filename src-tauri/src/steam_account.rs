use crate::{identity::normalize_title, providers::steam::SteamProvider, secrets};
use reqwest::blocking::Client;
use rusqlite::{params, Connection, OptionalExtension};
use serde::{Deserialize, Serialize};
use std::{fs, path::Path};
use uuid::Uuid;

#[derive(Debug, Clone, Serialize)]
pub struct SteamAccountStatus {
    pub steam_id: Option<String>,
    pub api_key_configured: bool,
    pub last_sync: Option<String>,
    pub owned_games: usize,
}

#[derive(Debug, Clone, Serialize)]
pub struct SteamAccountSyncResult {
    pub steam_id: String,
    pub games_found: usize,
    pub games_created: usize,
    pub games_updated: usize,
    pub playtimes_updated: usize,
}

#[derive(Debug, Deserialize)]
struct OwnedGamesEnvelope {
    response: OwnedGamesResponse,
}

#[derive(Debug, Deserialize)]
struct OwnedGamesResponse {
    #[serde(default)]
    games: Vec<OwnedGame>,
}

#[derive(Debug, Deserialize)]
struct OwnedGame {
    appid: u64,
    #[serde(default)]
    name: String,
    #[serde(default)]
    playtime_forever: i64,
}

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
            '"' => {
                i += 1;
                let mut value = String::new();
                while i < chars.len() {
                    match chars[i] {
                        '\\' if i + 1 < chars.len() && matches!(chars[i + 1], '\\' | '"') => {
                            value.push(chars[i + 1]);
                            i += 2;
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
                while i < chars.len() && !chars[i].is_whitespace() && !matches!(chars[i], '{' | '}') {
                    i += 1;
                }
                out.push(Token::Text(chars[start..i].iter().collect()));
            }
        }
    }
    out
}

pub fn parse_loginusers_most_recent(content: &str) -> Option<String> {
    let tokens = tokenize_vdf(content);
    let mut stack: Vec<String> = Vec::new();
    let mut most_recent: Option<String> = None;
    let mut first_id: Option<String> = None;
    let mut i = 0usize;
    while i < tokens.len() {
        match &tokens[i] {
            Token::Text(key) if matches!(tokens.get(i + 1), Some(Token::Open)) => {
                stack.push(key.clone());
                if key.len() == 17 && key.chars().all(|c| c.is_ascii_digit()) && first_id.is_none() {
                    first_id = Some(key.clone());
                }
                i += 2;
            }
            Token::Text(key) => {
                if let Some(Token::Text(value)) = tokens.get(i + 1) {
                    if key.eq_ignore_ascii_case("MostRecent") && value == "1" {
                        if let Some(id) = stack.iter().rev().find(|v| v.len() == 17 && v.chars().all(|c| c.is_ascii_digit())) {
                            most_recent = Some(id.clone());
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
    most_recent.or(first_id)
}

pub fn detect_steam_id(root: &Path) -> Option<String> {
    let path = root.join("config").join("loginusers.vdf");
    fs::read_to_string(path)
        .ok()
        .and_then(|content| parse_loginusers_most_recent(&content))
}

pub fn status(connection: &Connection) -> Result<SteamAccountStatus, String> {
    let steam_id = SteamProvider::detect_root().as_deref().and_then(detect_steam_id);
    let owned_games: i64 = connection
        .query_row(
            "SELECT COUNT(*) FROM external_ids WHERE provider='steam'",
            [],
            |row| row.get(0),
        )
        .map_err(|e| e.to_string())?;
    Ok(SteamAccountStatus {
        steam_id,
        api_key_configured: secrets::configured(connection, "steam.web_api_key")?,
        last_sync: crate::db::get_setting(connection, "steam.account_last_sync")?,
        owned_games: owned_games.max(0) as usize,
    })
}

pub fn save_api_key(connection: &Connection, key: &str) -> Result<(), String> {
    if !key.trim().is_empty() && !key.trim().chars().all(|c| c.is_ascii_hexdigit()) {
        return Err("A Steam Web API key deve conter somente caracteres hexadecimais".into());
    }
    secrets::set(connection, "steam.web_api_key", key)
}

fn find_game(connection: &Connection, app_id: &str, title: &str) -> Result<Option<String>, String> {
    if let Some(id) = connection
        .query_row(
            "SELECT game_id FROM external_ids WHERE provider='steam' AND external_id=?1",
            [app_id],
            |row| row.get(0),
        )
        .optional()
        .map_err(|e| e.to_string())?
    {
        return Ok(Some(id));
    }
    let normalized = normalize_title(title);
    let mut statement = connection
        .prepare("SELECT id FROM games WHERE normalized_title=?1 LIMIT 2")
        .map_err(|e| e.to_string())?;
    let matches = statement
        .query_map([normalized], |row| row.get::<_, String>(0))
        .map_err(|e| e.to_string())?
        .collect::<Result<Vec<_>, _>>()
        .map_err(|e| e.to_string())?;
    Ok((matches.len() == 1).then(|| matches[0].clone()))
}

fn upsert_owned_game(connection: &Connection, game: &OwnedGame) -> Result<(bool, bool), String> {
    let app_id = game.appid.to_string();
    let title = game.name.trim();
    if title.is_empty() {
        return Ok((false, false));
    }
    let existing = find_game(connection, &app_id, title)?;
    let created = existing.is_none();
    let game_id = existing.unwrap_or_else(|| Uuid::new_v4().to_string());
    if created {
        connection
            .execute(
                "INSERT INTO games(id,title,normalized_title,platform,source) VALUES(?1,?2,?3,'PC','steam')",
                params![game_id, title, normalize_title(title)],
            )
            .map_err(|e| e.to_string())?;
    }
    connection
        .execute(
            "INSERT INTO external_ids(game_id,provider,external_id) VALUES(?1,'steam',?2)
             ON CONFLICT(provider,external_id) DO UPDATE SET game_id=excluded.game_id",
            params![game_id, app_id],
        )
        .map_err(|e| e.to_string())?;
    connection
        .execute(
            "INSERT INTO installations(id,game_id,source,provider,external_id,installed,updated_at)
             VALUES(?1,?2,'steam','steam',?3,0,CURRENT_TIMESTAMP)
             ON CONFLICT(id) DO UPDATE SET game_id=excluded.game_id,source='steam',provider='steam',external_id=excluded.external_id,updated_at=CURRENT_TIMESTAMP",
            params![format!("steam:{app_id}"), game_id, app_id],
        )
        .map_err(|e| e.to_string())?;
    connection
        .execute(
            "INSERT INTO imported_playtime(game_id,provider,seconds,updated_at)
             VALUES(?1,'steam',?2,CURRENT_TIMESTAMP)
             ON CONFLICT(game_id,provider) DO UPDATE SET seconds=excluded.seconds,updated_at=CURRENT_TIMESTAMP",
            params![game_id, game.playtime_forever.max(0).saturating_mul(60)],
        )
        .map_err(|e| e.to_string())?;
    connection
        .execute(
            "INSERT INTO game_metadata(game_id,cover,hero,source,manual,updated_at)
             VALUES(?1,?2,?3,'steam-account',0,CURRENT_TIMESTAMP)
             ON CONFLICT(game_id) DO UPDATE SET
               cover=CASE WHEN game_metadata.manual=0 AND (game_metadata.cover IS NULL OR trim(game_metadata.cover)='' OR game_metadata.source IN ('steam-cdn','steam-account')) THEN excluded.cover ELSE game_metadata.cover END,
               hero=CASE WHEN game_metadata.manual=0 AND (game_metadata.hero IS NULL OR trim(game_metadata.hero)='' OR game_metadata.source IN ('steam-cdn','steam-account')) THEN excluded.hero ELSE game_metadata.hero END,
               source=CASE WHEN game_metadata.manual=0 AND game_metadata.source IN ('steam-cdn','steam-account') THEN excluded.source ELSE game_metadata.source END,
               updated_at=CASE WHEN game_metadata.manual=0 THEN CURRENT_TIMESTAMP ELSE game_metadata.updated_at END",
            params![game_id, format!("steam-artwork:{app_id}"), format!("steam-artwork-hero:{app_id}")],
        )
        .map_err(|e| e.to_string())?;
    Ok((created, true))
}

pub fn sync_owned_games(connection: &Connection) -> Result<SteamAccountSyncResult, String> {
    let root = SteamProvider::detect_root().ok_or_else(|| "Steam não encontrada neste PC".to_string())?;
    let steam_id = detect_steam_id(&root).ok_or_else(|| "Não foi possível detectar a conta Steam ativa em config/loginusers.vdf".to_string())?;
    let key = secrets::get(connection, "steam.web_api_key")?
        .ok_or_else(|| "Configure sua Steam Web API key em Configurações → Steam".to_string())?;
    let client = Client::builder()
        .timeout(std::time::Duration::from_secs(25))
        .user_agent("Ludex/0.9.2")
        .build()
        .map_err(|e| e.to_string())?;
    let response = client
        .get("https://api.steampowered.com/IPlayerService/GetOwnedGames/v0001/")
        .query(&[
            ("key", key.as_str()),
            ("steamid", steam_id.as_str()),
            ("format", "json"),
            ("include_appinfo", "true"),
            ("include_played_free_games", "true"),
        ])
        .send()
        .map_err(|e| format!("Falha ao consultar sua biblioteca Steam: {e}"))?;
    if !response.status().is_success() {
        return Err(format!(
            "A Steam Web API respondeu HTTP {}. Confira a chave e a conta Steam.",
            response.status()
        ));
    }
    let payload: OwnedGamesEnvelope = response
        .json()
        .map_err(|e| format!("Resposta inválida da Steam Web API: {e}"))?;
    let mut created = 0usize;
    let mut updated = 0usize;
    let mut playtimes = 0usize;
    for game in &payload.response.games {
        let (was_created, touched) = upsert_owned_game(connection, game)?;
        created += usize::from(was_created);
        updated += usize::from(touched);
        playtimes += usize::from(touched);
    }
    connection
        .execute(
            "INSERT INTO settings(key,value) VALUES('steam.account_last_sync',CURRENT_TIMESTAMP)
             ON CONFLICT(key) DO UPDATE SET value=excluded.value",
            [],
        )
        .map_err(|e| e.to_string())?;
    Ok(SteamAccountSyncResult {
        steam_id,
        games_found: payload.response.games.len(),
        games_created: created,
        games_updated: updated,
        playtimes_updated: playtimes,
    })
}

#[cfg(test)]
mod tests {
    use super::parse_loginusers_most_recent;

    #[test]
    fn finds_most_recent_steam_id() {
        let vdf = r#"
        "users"
        {
          "76561198000000001" { "AccountName" "old" "MostRecent" "0" }
          "76561198000000002" { "AccountName" "current" "MostRecent" "1" }
        }
        "#;
        assert_eq!(parse_loginusers_most_recent(vdf).as_deref(), Some("76561198000000002"));
    }
}
