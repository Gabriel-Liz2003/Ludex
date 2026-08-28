use std::{
    collections::HashSet,
    fs,
    path::{Path, PathBuf},
};

use crate::{models::ScannedInstallation, providers::LibraryProvider};
use tracing::{debug, info, warn};

pub struct SteamProvider;

impl SteamProvider {
    pub fn detect_root() -> Option<PathBuf> {
        let mut roots = Self::registry_roots();
        roots.extend(Self::candidate_roots());
        roots
            .into_iter()
            .find(|root| root.join("steam.exe").exists())
    }

    fn candidate_roots() -> Vec<PathBuf> {
        let mut roots = Vec::new();
        if let Ok(program_files_x86) = std::env::var("PROGRAMFILES(X86)") {
            roots.push(PathBuf::from(program_files_x86).join("Steam"));
        }
        if let Ok(program_files) = std::env::var("PROGRAMFILES") {
            roots.push(PathBuf::from(program_files).join("Steam"));
        }
        if let Ok(local_app_data) = std::env::var("LOCALAPPDATA") {
            roots.push(PathBuf::from(local_app_data).join("Steam"));
        }
        roots
    }

    #[cfg(windows)]
    fn registry_roots() -> Vec<PathBuf> {
        use winreg::{
            enums::{HKEY_CURRENT_USER, HKEY_LOCAL_MACHINE},
            RegKey,
        };

        let mut roots = Vec::new();
        let hkcu = RegKey::predef(HKEY_CURRENT_USER);
        if let Ok(key) = hkcu.open_subkey("Software\\Valve\\Steam") {
            if let Ok(path) = key.get_value::<String, _>("SteamPath") {
                roots.push(PathBuf::from(path));
            }
        }

        let hklm = RegKey::predef(HKEY_LOCAL_MACHINE);
        for key_path in [
            "SOFTWARE\\WOW6432Node\\Valve\\Steam",
            "SOFTWARE\\Valve\\Steam",
        ] {
            if let Ok(key) = hklm.open_subkey(key_path) {
                if let Ok(path) = key.get_value::<String, _>("InstallPath") {
                    roots.push(PathBuf::from(path));
                }
            }
        }
        roots
    }

    #[cfg(not(windows))]
    fn registry_roots() -> Vec<PathBuf> {
        Vec::new()
    }

    pub fn library_paths(root: &Path) -> Result<Vec<PathBuf>, String> {
        let mut libraries = vec![root.to_path_buf()];
        let file = root.join("steamapps").join("libraryfolders.vdf");
        if !file.exists() {
            return Ok(libraries);
        }
        let content = fs::read_to_string(&file).map_err(|e| format!("{}: {e}", file.display()))?;
        libraries.extend(parse_libraryfolders(&content));

        let mut seen = HashSet::new();
        libraries.retain(|path| seen.insert(path.to_string_lossy().to_lowercase()));
        Ok(libraries)
    }

    pub fn scan_from_root(root: &Path) -> Result<Vec<ScannedInstallation>, String> {
        let libraries = Self::library_paths(root)?;
        info!(provider = "steam", root = %root.display(), libraries = libraries.len(), "Steam detectada");
        let mut games = Vec::new();

        for library in libraries {
            let steamapps = library.join("steamapps");
            let entries = match fs::read_dir(&steamapps) {
                Ok(entries) => entries,
                Err(error) => {
                    debug!(provider = "steam", path = %steamapps.display(), %error, "Biblioteca Steam inacessível");
                    continue;
                }
            };

            for entry in entries.flatten() {
                let path = entry.path();
                let name = entry.file_name().to_string_lossy().to_string();
                if !name.starts_with("appmanifest_") || !name.ends_with(".acf") {
                    continue;
                }
                match fs::read_to_string(&path)
                    .map_err(|e| e.to_string())
                    .and_then(|content| parse_appmanifest(&content, &library))
                {
                    Ok(Some(game)) => games.push(game),
                    Ok(None) => {
                        debug!(provider = "steam", manifest = %path.display(), "Manifest ignorado por não representar jogo normal")
                    }
                    Err(error) => {
                        warn!(provider = "steam", manifest = %path.display(), %error, "Manifest Steam inválido")
                    }
                }
            }
        }

        Ok(games)
    }
}

impl LibraryProvider for SteamProvider {
    fn id(&self) -> &'static str {
        "steam"
    }
    fn display_name(&self) -> &'static str {
        "Steam"
    }
    fn is_available(&self) -> bool {
        Self::detect_root().is_some()
    }
    fn scan(&self) -> Result<Vec<ScannedInstallation>, String> {
        let root = Self::detect_root().ok_or_else(|| "Steam não encontrada".to_string())?;
        Self::scan_from_root(&root)
    }
}

fn quoted_fields(line: &str) -> Vec<String> {
    let mut result = Vec::new();
    let mut current = String::new();
    let mut in_quote = false;
    let mut escaped = false;

    for ch in line.chars() {
        if escaped {
            current.push(ch);
            escaped = false;
            continue;
        }
        if ch == '\\' && in_quote {
            escaped = true;
            current.push(ch);
            continue;
        }
        if ch == '"' {
            if in_quote {
                result.push(current.clone());
                current.clear();
            }
            in_quote = !in_quote;
        } else if in_quote {
            current.push(ch);
        }
    }
    result
}

pub fn parse_libraryfolders(content: &str) -> Vec<PathBuf> {
    let mut paths = Vec::new();
    for line in content.lines() {
        let fields = quoted_fields(line);
        if fields.len() < 2 {
            continue;
        }
        let key = fields[0].trim();
        let value = fields[1].replace("\\\\", "\\");
        if key.eq_ignore_ascii_case("path")
            || (key.chars().all(|c| c.is_ascii_digit())
                && (value.contains(':') || value.starts_with('/')))
        {
            paths.push(PathBuf::from(value));
        }
    }
    paths
}

fn value_for(content: &str, wanted: &str) -> Option<String> {
    content.lines().find_map(|line| {
        let fields = quoted_fields(line);
        if fields.len() >= 2 && fields[0].eq_ignore_ascii_case(wanted) {
            Some(fields[1].replace("\\\\", "\\"))
        } else {
            None
        }
    })
}

fn is_non_game(name: &str, app_type: Option<&str>) -> bool {
    if app_type.is_some_and(|value| {
        matches!(
            value.to_ascii_lowercase().as_str(),
            "tool" | "config" | "demo" | "dlc"
        )
    }) {
        return true;
    }
    let lower = name.to_ascii_lowercase();
    [
        "steamworks common redistributables",
        "proton ",
        "dedicated server",
        "redistributable",
        "runtime",
        "sdk",
    ]
    .iter()
    .any(|needle| lower.contains(needle))
}

pub fn parse_appmanifest(
    content: &str,
    library: &Path,
) -> Result<Option<ScannedInstallation>, String> {
    let appid = value_for(content, "appid").ok_or_else(|| "appid ausente".to_string())?;
    let name = value_for(content, "name").ok_or_else(|| "name ausente".to_string())?;
    let installdir =
        value_for(content, "installdir").ok_or_else(|| "installdir ausente".to_string())?;
    let state_flags = value_for(content, "StateFlags")
        .and_then(|v| v.parse::<u64>().ok())
        .unwrap_or(0);
    let size_bytes = value_for(content, "SizeOnDisk").and_then(|v| v.parse::<i64>().ok());
    let last_updated = value_for(content, "LastUpdated").and_then(|v| v.parse::<i64>().ok());
    let app_type = value_for(content, "AppType");

    if is_non_game(&name, app_type.as_deref()) {
        return Ok(None);
    }

    let install_dir = library.join("steamapps").join("common").join(&installdir);
    // Steam usa bit 4 (fully installed) nos StateFlags, mas a existência da pasta também é
    // considerada porque manifests podem ficar em estados transitórios durante updates.
    let installed = (state_flags & 4) != 0 && install_dir.exists();

    Ok(Some(ScannedInstallation {
        provider: "steam".into(),
        external_id: appid,
        title: name,
        platform: "PC".into(),
        install_dir: Some(install_dir.to_string_lossy().to_string()),
        executable: None,
        installed,
        size_bytes,
        last_updated,
    }))
}

#[cfg(test)]
mod tests {
    use super::{parse_appmanifest, parse_libraryfolders};
    use std::path::Path;

    #[test]
    fn parses_modern_libraryfolders_fixture() {
        let content = include_str!("../../tests/fixtures/libraryfolders.vdf");
        let paths = parse_libraryfolders(content);
        assert_eq!(paths.len(), 2);
        assert!(paths
            .iter()
            .any(|path| path.to_string_lossy().contains("SteamLibrary")));
        assert!(paths
            .iter()
            .any(|path| path.to_string_lossy().contains("Program Files (x86)")));
    }

    #[test]
    fn parses_appmanifest_fixture() {
        let content = include_str!("../../tests/fixtures/appmanifest_1091500.acf");
        let game = parse_appmanifest(content, Path::new("C:\\SteamLibrary"))
            .unwrap()
            .unwrap();
        assert_eq!(game.external_id, "1091500");
        assert_eq!(game.title, "Cyberpunk 2077™");
        assert_eq!(game.size_bytes, Some(74239123456));
    }

    #[test]
    fn filters_common_redistributables() {
        let content = r#""AppState"
{
    "appid" "228980"
    "name" "Steamworks Common Redistributables"
    "StateFlags" "4"
    "installdir" "Steamworks Shared"
}"#;
        assert!(parse_appmanifest(content, Path::new("C:\\SteamLibrary"))
            .unwrap()
            .is_none());
    }
}
