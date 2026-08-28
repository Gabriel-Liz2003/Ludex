use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct MetadataRecord {
    pub game_id: String,
    pub description: Option<String>,
    pub developer: Option<String>,
    pub publisher: Option<String>,
    pub release_date: Option<String>,
    pub genres: Option<String>,
    pub cover: Option<String>,
    pub hero: Option<String>,
    pub source: String,
    pub manual: bool,
    pub updated_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CollectionRecord {
    pub id: String,
    pub name: String,
    pub kind: String,
    pub filter_json: Option<String>,
    pub game_count: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EmulatorRecord {
    pub id: String,
    pub name: String,
    pub platform: String,
    pub executable: String,
    pub arguments_template: String,
    pub rom_directory: Option<String>,
    pub bios_directory: Option<String>,
    pub saves_directory: Option<String>,
    pub extensions: String,
    pub core: Option<String>,
    pub enabled: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RomRecord {
    pub id: String,
    pub game_id: String,
    pub title: String,
    pub platform: String,
    pub path: String,
    pub emulator_id: Option<String>,
    pub hash_sha256: Option<String>,
    pub size_bytes: Option<i64>,
    pub launch_args: Option<String>,
    pub core: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct TimeBucket { pub label: String, pub seconds: i64 }

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct NamedTime { pub name: String, pub seconds: i64 }

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct LibraryStats {
    pub library_games: i64,
    pub installed_games: i64,
    pub never_played: i64,
    pub tracked_seconds: i64,
    pub imported_seconds: i64,
    pub last_14_seconds: i64,
    pub last_30_seconds: i64,
    pub average_daily_seconds_30d: i64,
    pub average_weekly_seconds_12w: i64,
    pub by_provider: Vec<NamedTime>,
    pub by_platform: Vec<NamedTime>,
    pub top_games: Vec<NamedTime>,
    pub monthly: Vec<TimeBucket>,
    pub yearly: Vec<TimeBucket>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DiagnosticItem {
    pub level: String,
    pub area: String,
    pub message: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RomScanResult {
    pub scanned_files: usize,
    pub imported: usize,
    pub duplicates: usize,
    pub ignored: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SyncSummary { pub inserted: usize, pub updated: usize, pub skipped: usize }

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BackupEnvelope {
    pub format: String,
    pub version: u32,
    pub exported_at: String,
    pub data: serde_json::Value,
}
