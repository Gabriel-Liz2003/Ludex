use crate::{models::ScannedInstallation, providers::ProviderScan};
use serde::Deserialize;
use std::{
    fs,
    path::{Path, PathBuf},
};

#[derive(Debug, Deserialize)]
#[serde(rename_all = "PascalCase")]
struct EpicManifest {
    app_name: Option<String>,
    display_name: Option<String>,
    install_location: Option<String>,
    launch_executable: Option<String>,
    catalog_namespace: Option<String>,
    catalog_item_id: Option<String>,
    #[serde(default)]
    b_is_incomplete_install: bool,
}

fn manifests_dir() -> Option<PathBuf> {
    std::env::var("PROGRAMDATA")
        .ok()
        .map(PathBuf::from)
        .map(|p| {
            p.join("Epic")
                .join("EpicGamesLauncher")
                .join("Data")
                .join("Manifests")
        })
}

fn launcher_root() -> Option<PathBuf> {
    let mut roots = Vec::new();
    if let Ok(p) = std::env::var("PROGRAMFILES(X86)") {
        roots.push(PathBuf::from(p).join("Epic Games").join("Launcher"));
    }
    if let Ok(p) = std::env::var("PROGRAMFILES") {
        roots.push(PathBuf::from(p).join("Epic Games").join("Launcher"));
    }
    roots.into_iter().find(|p| p.exists())
}

fn parse_manifest(content: &str) -> Result<Option<ScannedInstallation>, String> {
    let m: EpicManifest = serde_json::from_str(content).map_err(|e| e.to_string())?;
    let app = m
        .app_name
        .filter(|s| !s.trim().is_empty())
        .ok_or_else(|| "AppName ausente".to_string())?;
    let title = m.display_name.unwrap_or_else(|| app.clone());
    let install = m.install_location.filter(|s| !s.trim().is_empty());
    let executable = match (
        &install,
        m.launch_executable.filter(|s| !s.trim().is_empty()),
    ) {
        (Some(dir), Some(exe)) => Some(Path::new(dir).join(exe).to_string_lossy().to_string()),
        _ => None,
    };
    let external_id = match (m.catalog_namespace, m.catalog_item_id) {
        (Some(ns), Some(cat)) if !ns.is_empty() && !cat.is_empty() => format!("{ns}:{cat}:{app}"),
        _ => app,
    };
    Ok(Some(ScannedInstallation {
        provider: "epic".into(),
        external_id,
        title,
        platform: "PC".into(),
        install_dir: install.clone(),
        executable,
        installed: !m.b_is_incomplete_install
            && install.as_deref().is_some_and(|p| Path::new(p).exists()),
        size_bytes: None,
        last_updated: None,
    }))
}

pub fn scan() -> Result<ProviderScan, String> {
    let dir = manifests_dir();
    let mut installations = Vec::new();
    if let Some(path) = dir.as_ref().filter(|p| p.exists()) {
        for entry in fs::read_dir(path).map_err(|e| e.to_string())?.flatten() {
            let p = entry.path();
            if p.extension()
                .and_then(|s| s.to_str())
                .is_some_and(|s| s.eq_ignore_ascii_case("item"))
            {
                if let Ok(content) = fs::read_to_string(&p) {
                    if let Ok(Some(item)) = parse_manifest(&content) {
                        installations.push(item);
                    }
                }
            }
        }
    }
    let root = launcher_root().or(dir.clone());
    Ok(ProviderScan {
        id: "epic",
        name: "Epic Games Store",
        root,
        installations,
        can_launch: true,
        message: "Importação baseada nos manifests locais oficiais do Epic Games Launcher.".into(),
    })
}

#[cfg(test)]
mod tests {
    use super::parse_manifest;
    #[test]
    fn parses_epic_item_manifest() {
        let json = r#"{"AppName":"Fortnite","DisplayName":"Fortnite","InstallLocation":"Z:\\NoSuchGame","LaunchExecutable":"FortniteGame\\Binaries\\Win64\\FortniteClient-Win64-Shipping.exe","CatalogNamespace":"fn","CatalogItemId":"cat","bIsIncompleteInstall":false}"#;
        let item = parse_manifest(json).unwrap().unwrap();
        assert_eq!(item.external_id, "fn:cat:Fortnite");
        assert!(item.executable.unwrap().contains("FortniteClient"));
    }
}
