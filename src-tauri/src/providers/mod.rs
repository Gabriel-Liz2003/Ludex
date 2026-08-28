use crate::models::ScannedInstallation;

pub mod steam;

pub trait LibraryProvider: Send + Sync {
    fn id(&self) -> &'static str;
    fn display_name(&self) -> &'static str;
    fn is_available(&self) -> bool;
    fn scan(&self) -> Result<Vec<ScannedInstallation>, String>;
}
