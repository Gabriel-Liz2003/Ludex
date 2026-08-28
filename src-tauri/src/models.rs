use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Game {
    pub id: String,
    pub title: String,
    pub platform: String,
    pub source: String,
    pub executable: Option<String>,
    pub favorite: bool,
    pub status: String,
    pub total_seconds: i64,
    pub installed: bool,
    pub providers: Vec<String>,
    pub active: bool,
    pub last_played_at: Option<String>,
    pub session_count: i64,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Installation {
    pub id: String,
    pub game_id: String,
    pub provider: String,
    pub external_id: Option<String>,
    pub executable: Option<String>,
    pub install_dir: Option<String>,
    pub working_dir: Option<String>,
    pub launch_args: Option<String>,
    pub installed: bool,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PlaySession {
    pub id: String,
    pub game_id: String,
    pub installation_id: Option<String>,
    pub started_at: String,
    pub ended_at: Option<String>,
    pub duration_seconds: i64,
    pub device: String,
    pub provider: Option<String>,
    pub process_id: Option<u32>,
    pub process_path: Option<String>,
    pub recovered: bool,
}
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct GameStats {
    pub total_seconds: i64,
    pub last_14_seconds: i64,
    pub last_30_seconds: i64,
    pub session_count: i64,
    pub average_session_seconds: i64,
    pub last_played_at: Option<String>,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GameDetails {
    pub game: Game,
    pub stats: GameStats,
    pub installations: Vec<Installation>,
    pub recent_sessions: Vec<PlaySession>,
}
#[derive(Debug, Clone)]
pub struct ScannedInstallation {
    pub provider: String,
    pub external_id: String,
    pub title: String,
    pub platform: String,
    pub install_dir: Option<String>,
    pub executable: Option<String>,
    pub installed: bool,
    pub size_bytes: Option<i64>,
    pub last_updated: Option<i64>,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProviderStatus {
    pub id: String,
    pub name: String,
    pub detected: bool,
    pub root_path: Option<String>,
    pub games_found: usize,
    pub last_sync: Option<String>,
    pub message: String,
    pub can_launch: bool,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProviderImportResult {
    pub provider: String,
    pub root_path: String,
    pub games_found: usize,
    pub games_created: usize,
    pub installations_upserted: usize,
    pub deduplicated: usize,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SteamStatus {
    pub detected: bool,
    pub root_path: Option<String>,
    pub library_count: usize,
    pub games_found: usize,
    pub last_sync: Option<String>,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SteamImportResult {
    pub root_path: String,
    pub library_count: usize,
    pub games_found: usize,
    pub games_created: usize,
    pub installations_upserted: usize,
    pub deduplicated: usize,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LaunchResult {
    pub launched: bool,
    pub already_running: bool,
    pub message: String,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EmulatorConfig {
    pub id: String,
    pub name: String,
    pub platform: String,
    pub executable: String,
    pub arguments_template: String,
    pub rom_directory: Option<String>,
    pub bios_directory: Option<String>,
    pub saves_directory: Option<String>,
}
