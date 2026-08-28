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
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PlaySession {
    pub id: String,
    pub game_id: String,
    pub started_at: String,
    pub ended_at: Option<String>,
    pub duration_seconds: i64,
    pub device: String,
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
