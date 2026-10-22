use rusqlite::Connection;
use serde::Serialize;
use std::collections::{BTreeMap, HashMap};

#[derive(Debug, Clone, Serialize)]
pub struct LibrarySource {
    pub game_id: String,
    pub source: String,
    pub seconds: i64,
    pub private: bool,
}

#[derive(Default)]
struct Totals {
    imported: i64,
    tracked: i64,
    private: bool,
}

/// A single normalized library identity is shared by sessions, historical
/// playtime and installations. Never add imported history to a tracked total
/// for the same provider: they may be snapshots of overlapping playtime.
pub fn normalize_source(raw: &str) -> String {
    let source = raw.trim().to_ascii_lowercase();
    if source.starts_with("nintendo-") { return "nintendo".into(); }
    if source.starts_with("psn-") || source.starts_with("playstation-") { return "playstation".into(); }
    if source.starts_with("steam-") { return "steam".into(); }
    if source.starts_with("epic-") { return "epic".into(); }
    if source.starts_with("eden-") || source.starts_with("emulator-") { return "emulation".into(); }
    if source.starts_with("android-renpy") || source == "renpy-save" { return "renpy".into(); }
    if source.starts_with("android-") { return "android".into(); }
    if source.starts_with("riot-") { return "riot".into(); }
    match source.as_str() {
        "riot" | "riot-wolgg" | "riot-trackergg" | "riot-tracker" => "riot".into(),
        "renpy" | "android-renpy-save" | "android-renpy" => "renpy".into(),
        "psn" | "playstation" | "playstation-manual" => "playstation".into(),
        "nintendo" | "nintendo-manual" | "switch" => "nintendo".into(),
        "steam" | "steam-family" | "steam-import" => "steam".into(),
        "xbox" | "xbox-manual" | "microsoft" | "gamepass" => "xbox".into(),
        "android" | "android-usage" | "android-usagestats" | "android-save" => "android".into(),
        "epic" | "epic-games" => "epic".into(),
        "pc-manual" | "other-manual" | "manual" => "manual".into(),
        "emulator" | "emulation" | "eden" => "emulation".into(),
        "" | "sync" | "local" => "local".into(),
        _ => source,
    }
}

/// Includes sources with no time, so unplayed library items remain filterable.
/// Ren'Py playtime is marked private; it is excluded by default in the UI.
pub fn list(c: &Connection) -> Result<Vec<LibrarySource>, String> {
    let mut totals: BTreeMap<(String, String), Totals> = BTreeMap::new();
    {
        let mut q = c.prepare("SELECT id,source FROM games").map_err(|e| e.to_string())?;
        let iter = q.query_map([], |r| Ok((r.get::<_,String>(0)?,r.get::<_,String>(1)?)))
            .map_err(|e| e.to_string())?;
        for item in iter {
            let (id, source) = item.map_err(|e| e.to_string())?;
            let normalized = normalize_source(&source);
            if normalized != "local" {
                totals.entry((id, normalized.clone())).or_default().private |= normalized == "renpy";
            }
        }
    }
    {
        let mut q = c.prepare("SELECT game_id,provider FROM installations").map_err(|e| e.to_string())?;
        let iter = q.query_map([], |r| Ok((r.get::<_,String>(0)?,r.get::<_,String>(1)?)))
            .map_err(|e| e.to_string())?;
        for item in iter {
            let (id, source) = item.map_err(|e| e.to_string())?;
            let normalized = normalize_source(&source);
            totals.entry((id, normalized.clone())).or_default().private |= normalized == "renpy";
        }
    }
    {
        let mut q = c.prepare("SELECT game_id,provider,seconds FROM imported_playtime")
            .map_err(|e| e.to_string())?;
        let iter = q.query_map([], |r| Ok((r.get::<_,String>(0)?,r.get::<_,String>(1)?,r.get::<_,i64>(2)?)))
            .map_err(|e| e.to_string())?;
        for item in iter {
            let (id, source, seconds) = item.map_err(|e| e.to_string())?;
            let normalized = normalize_source(&source);
            let entry = totals.entry((id, normalized.clone())).or_default();
            entry.imported = entry.imported.saturating_add(seconds.max(0));
            entry.private |= normalized == "renpy";
        }
    }
    {
        let mut q = c.prepare(
            "SELECT game_id,COALESCE(provider,''),COALESCE(SUM(duration_seconds),0)
             FROM play_sessions WHERE ended_at IS NOT NULL GROUP BY game_id,provider",
        ).map_err(|e| e.to_string())?;
        let iter = q.query_map([], |r| Ok((r.get::<_,String>(0)?,r.get::<_,String>(1)?,r.get::<_,i64>(2)?)))
            .map_err(|e| e.to_string())?;
        for item in iter {
            let (id, source, seconds) = item.map_err(|e| e.to_string())?;
            let normalized = normalize_source(&source);
            let entry = totals.entry((id, normalized.clone())).or_default();
            entry.tracked = entry.tracked.saturating_add(seconds.max(0));
            entry.private |= normalized == "renpy";
        }
    }
    Ok(totals.into_iter().map(|((game_id, source), total)| LibrarySource {
        game_id,
        private: total.private,
        source,
        seconds: total.imported.max(total.tracked),
    }).collect())
}

pub fn game_totals(sources: &[LibrarySource]) -> HashMap<String, i64> {
    let mut result = HashMap::new();
    for item in sources {
        let entry = result.entry(item.game_id.clone()).or_insert(0_i64);
        *entry = entry.saturating_add(item.seconds);
    }
    result
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{db, product};
    #[test]
    fn separates_platforms_without_double_counting_snapshots() {
        let conn = db::open(&std::env::temp_dir().join(format!("ludex-library-sources-{}.db",uuid::Uuid::new_v4()))).unwrap();
        product::migrate(&conn).unwrap();
        conn.execute("INSERT INTO games (id,title,platform,source) VALUES ('g1','Example','PC','steam')",[]).unwrap();
        conn.execute("INSERT INTO imported_playtime (game_id,provider,seconds) VALUES ('g1','steam',7200),('g1','riot-wolgg',18000)",[]).unwrap();
        conn.execute("INSERT INTO play_sessions (id,game_id,started_at,ended_at,duration_seconds,device,provider)
                      VALUES ('s1','g1','2026-01-01','2026-01-02',3600,'Windows','steam')",[]).unwrap();
        let items = list(&conn).unwrap();
        assert_eq!(items.iter().find(|i|i.source=="steam").unwrap().seconds, 7200);
        assert_eq!(items.iter().find(|i|i.source=="riot").unwrap().seconds, 18000);
        assert_eq!(game_totals(&items)["g1"], 25200);
    }
    #[test]
    fn renpy_is_private_and_riot_variants_are_one_source(){
        assert_eq!(normalize_source("android-renpy-save"),"renpy");
        assert_eq!(normalize_source("riot-trackergg"),"riot");
        assert_eq!(normalize_source("steam-family"),"steam");
        assert_eq!(normalize_source("nintendo-account"),"nintendo");
        assert_eq!(normalize_source("renpy-save"),"renpy");
    }
}
