use crate::{models::ScannedInstallation, providers::ProviderScan};
use serde_json::Value;
use std::{
    fs,
    path::{Path, PathBuf},
};

fn candidate_roots() -> Vec<PathBuf> {
    let mut roots = Vec::new();
    if let Ok(p) = std::env::var("PROGRAMFILES(X86)") {
        roots.push(PathBuf::from(p).join("GOG Galaxy").join("Games"));
    }
    if let Ok(p) = std::env::var("PROGRAMFILES") {
        roots.push(PathBuf::from(p).join("GOG Galaxy").join("Games"));
    }
    roots
}

fn primary_task(info: &Value, install_dir: &Path) -> Option<PathBuf> {
    info.get("playTasks")?.as_array()?.iter().find_map(|task| {
        let primary = task
            .get("isPrimary")
            .and_then(Value::as_bool)
            .unwrap_or(false);
        let path = task.get("path").and_then(Value::as_str)?;
        primary.then(|| install_dir.join(path))
    })
}

fn scan_folder(root: &Path, out: &mut Vec<ScannedInstallation>) {
    let Ok(children) = fs::read_dir(root) else {
        return;
    };
    for child in children.flatten() {
        let dir = child.path();
        if !dir.is_dir() {
            continue;
        }
        let Ok(files) = fs::read_dir(&dir) else {
            continue;
        };
        for file in files.flatten() {
            let path = file.path();
            let name = file.file_name().to_string_lossy().to_string();
            if !name.starts_with("goggame-") || !name.ends_with(".info") {
                continue;
            }
            let Ok(content) = fs::read_to_string(&path) else {
                continue;
            };
            let Ok(value) = serde_json::from_str::<Value>(&content) else {
                continue;
            };
            let id = value
                .get("gameId")
                .and_then(Value::as_str)
                .map(str::to_string)
                .unwrap_or_else(|| {
                    name.trim_start_matches("goggame-")
                        .trim_end_matches(".info")
                        .to_string()
                });
            let title = value
                .get("name")
                .and_then(Value::as_str)
                .unwrap_or_else(|| child.file_name().to_str().unwrap_or("GOG Game"))
                .to_string();
            let exe = primary_task(&value, &dir)
                .filter(|p| p.exists())
                .map(|p| p.to_string_lossy().to_string());
            out.push(ScannedInstallation {
                provider: "gog".into(),
                external_id: id,
                title,
                platform: "PC".into(),
                install_dir: Some(dir.to_string_lossy().to_string()),
                executable: exe,
                installed: true,
                size_bytes: None,
                last_updated: None,
            });
            break;
        }
    }
}

pub fn scan() -> Result<ProviderScan, String> {
    let roots = candidate_roots();
    let mut installations = Vec::new();
    for root in &roots {
        if root.exists() {
            scan_folder(root, &mut installations);
        }
    }
    let root = roots.into_iter().find(|p| p.exists());
    Ok(ProviderScan {
        id: "gog",
        name: "GOG",
        root,
        installations,
        can_launch: true,
        message:
            "Jogos DRM-free são iniciados diretamente pelo playTask primário de goggame-*.info."
                .into(),
    })
}

#[cfg(test)]
mod tests {
    use super::primary_task;
    use serde_json::json;
    use std::path::Path;
    #[test]
    fn chooses_primary_gog_play_task() {
        let v = json!({"playTasks":[{"isPrimary":false,"path":"setup.exe"},{"isPrimary":true,"path":"bin/game.exe"}]});
        assert!(primary_task(&v, Path::new("C:/GOG/Game"))
            .unwrap()
            .ends_with("bin/game.exe"));
    }
}
