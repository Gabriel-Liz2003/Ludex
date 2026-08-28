use std::path::PathBuf;
use crate::{models::Game, providers::LibraryProvider};

pub struct SteamProvider;

impl SteamProvider {
    fn candidate_roots() -> Vec<PathBuf> {
        let mut roots = Vec::new();
        if let Ok(program_files_x86) = std::env::var("PROGRAMFILES(X86)") {
            roots.push(PathBuf::from(program_files_x86).join("Steam"));
        }
        if let Ok(program_files) = std::env::var("PROGRAMFILES") {
            roots.push(PathBuf::from(program_files).join("Steam"));
        }
        roots
    }
}

impl LibraryProvider for SteamProvider {
    fn id(&self) -> &'static str { "steam" }
    fn display_name(&self) -> &'static str { "Steam" }
    fn is_available(&self) -> bool {
        Self::candidate_roots().iter().any(|root| root.join("steam.exe").exists())
    }
    fn scan(&self) -> Result<Vec<Game>, String> {
        // A leitura de libraryfolders.vdf + appmanifest_*.acf será adicionada no próximo incremento.
        // Retornar vazio é intencional: não inventamos biblioteca quando a Steam não foi parseada.
        Ok(Vec::new())
    }
}
