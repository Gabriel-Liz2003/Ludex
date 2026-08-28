use crate::models::ScannedInstallation;
use std::path::PathBuf;

pub mod battlenet;
pub mod ea;
pub mod epic;
pub mod gog;
pub mod microsoft;
pub mod steam;
pub mod ubisoft;

pub trait LibraryProvider: Send + Sync {
    fn id(&self) -> &'static str;
    fn display_name(&self) -> &'static str;
    fn is_available(&self) -> bool;
    fn scan(&self) -> Result<Vec<ScannedInstallation>, String>;
}

#[derive(Debug, Clone)]
pub struct ProviderScan {
    pub id: &'static str,
    pub name: &'static str,
    pub root: Option<PathBuf>,
    pub installations: Vec<ScannedInstallation>,
    pub can_launch: bool,
    pub message: String,
}

pub fn scan_provider(id: &str) -> Result<ProviderScan, String> {
    match id {
        "steam" => steam::scan(),
        "epic" => epic::scan(),
        "gog" => gog::scan(),
        "xbox" => microsoft::scan(),
        "ea" => ea::scan(),
        "ubisoft" => ubisoft::scan(),
        "battlenet" => battlenet::scan(),
        _ => Err(format!("Provider desconhecido: {id}")),
    }
}

pub const PROVIDER_IDS: &[&str] = &["steam", "epic", "gog", "xbox", "ea", "ubisoft", "battlenet"];
