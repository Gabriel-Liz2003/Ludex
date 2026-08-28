use crate::{models::ScannedInstallation, providers::ProviderScan};
use std::{fs, path::PathBuf};

fn roots() -> Vec<PathBuf> {
    let mut r = Vec::new();
    for var in ["PROGRAMFILES", "PROGRAMFILES(X86)"] {
        if let Ok(p) = std::env::var(var) {
            r.push(PathBuf::from(p));
        }
    }
    r
}
pub fn scan() -> Result<ProviderScan, String> {
    let mut installations = Vec::new();
    // Fallback deliberadamente conservador: reconhece instalações que expõem .build.info,
    // sem manter uma tabela frágil de jogos/produtos Battle.net.
    for root in roots() {
        let Ok(entries) = fs::read_dir(&root) else {
            continue;
        };
        for e in entries.flatten() {
            let dir = e.path();
            if !dir.is_dir() || !dir.join(".build.info").exists() {
                continue;
            }
            let title = e.file_name().to_string_lossy().to_string();
            let id = title.to_ascii_lowercase().replace(' ', "-");
            installations.push(ScannedInstallation {
                provider: "battlenet".into(),
                external_id: id,
                title,
                platform: "PC".into(),
                install_dir: Some(dir.to_string_lossy().to_string()),
                executable: None,
                installed: true,
                size_bytes: None,
                last_updated: None,
            });
        }
    }
    Ok(ProviderScan{id:"battlenet",name:"Battle.net",root:None,installations,can_launch:false,message:"Instalações com .build.info são importadas sem hardcode; launch requer product code confiável e oferece fallback manual.".into()})
}
