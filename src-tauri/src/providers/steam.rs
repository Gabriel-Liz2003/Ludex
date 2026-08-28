use crate::{
    models::ScannedInstallation,
    providers::{LibraryProvider, ProviderScan},
};
use std::{
    collections::HashSet,
    fs,
    path::{Path, PathBuf},
};
use tracing::{debug, info, warn};

pub struct SteamProvider;
impl SteamProvider {
    pub fn detect_root() -> Option<PathBuf> {
        let mut roots = Self::registry_roots();
        roots.extend(Self::candidate_roots());
        roots.into_iter().find(|r| r.join("steam.exe").exists())
    }
    fn candidate_roots() -> Vec<PathBuf> {
        let mut r = Vec::new();
        if let Ok(p) = std::env::var("PROGRAMFILES(X86)") {
            r.push(PathBuf::from(p).join("Steam"));
        }
        if let Ok(p) = std::env::var("PROGRAMFILES") {
            r.push(PathBuf::from(p).join("Steam"));
        }
        if let Ok(p) = std::env::var("LOCALAPPDATA") {
            r.push(PathBuf::from(p).join("Steam"));
        }
        r
    }
    #[cfg(windows)]
    fn registry_roots() -> Vec<PathBuf> {
        use winreg::{
            enums::{HKEY_CURRENT_USER, HKEY_LOCAL_MACHINE},
            RegKey,
        };
        let mut r = Vec::new();
        let hkcu = RegKey::predef(HKEY_CURRENT_USER);
        if let Ok(k) = hkcu.open_subkey("Software\\Valve\\Steam") {
            if let Ok(p) = k.get_value::<String, _>("SteamPath") {
                r.push(PathBuf::from(p));
            }
        }
        let hklm = RegKey::predef(HKEY_LOCAL_MACHINE);
        for kp in [
            "SOFTWARE\\WOW6432Node\\Valve\\Steam",
            "SOFTWARE\\Valve\\Steam",
        ] {
            if let Ok(k) = hklm.open_subkey(kp) {
                if let Ok(p) = k.get_value::<String, _>("InstallPath") {
                    r.push(PathBuf::from(p));
                }
            }
        }
        r
    }
    #[cfg(not(windows))]
    fn registry_roots() -> Vec<PathBuf> {
        Vec::new()
    }
    pub fn library_paths(root: &Path) -> Result<Vec<PathBuf>, String> {
        let mut l = vec![root.to_path_buf()];
        let f = root.join("steamapps").join("libraryfolders.vdf");
        if f.exists() {
            let c = fs::read_to_string(&f).map_err(|e| format!("{}: {e}", f.display()))?;
            l.extend(parse_libraryfolders(&c));
        }
        let mut seen = HashSet::new();
        l.retain(|p| seen.insert(p.to_string_lossy().to_lowercase()));
        Ok(l)
    }
    pub fn scan_from_root(root: &Path) -> Result<Vec<ScannedInstallation>, String> {
        let libraries = Self::library_paths(root)?;
        info!(provider="steam",root=%root.display(),libraries=libraries.len(),"Steam detectada");
        let mut games = Vec::new();
        for library in libraries {
            let sa = library.join("steamapps");
            let entries = match fs::read_dir(&sa) {
                Ok(e) => e,
                Err(error) => {
                    debug!(provider="steam",path=%sa.display(),%error,"Biblioteca Steam inacessível");
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
                    .and_then(|c| parse_appmanifest(&c, &library))
                {
                    Ok(Some(g)) => games.push(g),
                    Ok(None) => {}
                    Err(error) => {
                        warn!(provider="steam",manifest=%path.display(),%error,"Manifest Steam inválido")
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
        let r = Self::detect_root().ok_or_else(|| "Steam não encontrada".to_string())?;
        Self::scan_from_root(&r)
    }
}

pub fn scan() -> Result<ProviderScan, String> {
    let root = SteamProvider::detect_root();
    let installations = match root.as_ref() {
        Some(r) => SteamProvider::scan_from_root(r)?,
        None => Vec::new(),
    };
    Ok(ProviderScan {
        id: "steam",
        name: "Steam",
        root,
        installations,
        can_launch: true,
        message: "libraryfolders.vdf + appmanifest_*.acf locais.".into(),
    })
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
        let f = quoted_fields(line);
        if f.len() < 2 {
            continue;
        }
        let key = f[0].trim();
        let value = f[1].replace("\\\\", "\\");
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
        let f = quoted_fields(line);
        if f.len() >= 2 && f[0].eq_ignore_ascii_case(wanted) {
            Some(f[1].replace("\\\\", "\\"))
        } else {
            None
        }
    })
}
fn is_non_game(name: &str, app_type: Option<&str>) -> bool {
    if app_type.is_some_and(|v| {
        matches!(
            v.to_ascii_lowercase().as_str(),
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
    .any(|n| lower.contains(n))
}
pub fn parse_appmanifest(
    content: &str,
    library: &Path,
) -> Result<Option<ScannedInstallation>, String> {
    let appid = value_for(content, "appid").ok_or_else(|| "appid ausente".to_string())?;
    let name = value_for(content, "name").ok_or_else(|| "name ausente".to_string())?;
    let installdir =
        value_for(content, "installdir").ok_or_else(|| "installdir ausente".to_string())?;
    let state = value_for(content, "StateFlags")
        .and_then(|v| v.parse::<u64>().ok())
        .unwrap_or(0);
    let size = value_for(content, "SizeOnDisk").and_then(|v| v.parse::<i64>().ok());
    let updated = value_for(content, "LastUpdated").and_then(|v| v.parse::<i64>().ok());
    let app_type = value_for(content, "AppType");
    if is_non_game(&name, app_type.as_deref()) {
        return Ok(None);
    }
    let dir = library.join("steamapps").join("common").join(&installdir);
    let installed = (state & 4) != 0 && dir.exists();
    Ok(Some(ScannedInstallation {
        provider: "steam".into(),
        external_id: appid,
        title: name,
        platform: "PC".into(),
        install_dir: Some(dir.to_string_lossy().to_string()),
        executable: None,
        installed,
        size_bytes: size,
        last_updated: updated,
    }))
}
#[cfg(test)]
mod tests {
    use super::{parse_appmanifest, parse_libraryfolders};
    use std::path::Path;
    #[test]
    fn parses_modern_libraryfolders_fixture() {
        let c = include_str!("../../tests/fixtures/libraryfolders.vdf");
        let p = parse_libraryfolders(c);
        assert_eq!(p.len(), 2);
        assert!(p
            .iter()
            .any(|x| x.to_string_lossy().contains("SteamLibrary")));
    }
    #[test]
    fn parses_appmanifest_fixture() {
        let c = include_str!("../../tests/fixtures/appmanifest_1091500.acf");
        let g = parse_appmanifest(c, Path::new("C:\\SteamLibrary"))
            .unwrap()
            .unwrap();
        assert_eq!(g.external_id, "1091500");
        assert_eq!(g.title, "Cyberpunk 2077™");
    }
    #[test]
    fn filters_common_redistributables() {
        let c = r#""AppState"
{
    "appid" "228980"
    "name" "Steamworks Common Redistributables"
    "StateFlags" "4"
    "installdir" "Steamworks Shared"
}"#;
        assert!(parse_appmanifest(c, Path::new("C:\\SteamLibrary"))
            .unwrap()
            .is_none());
    }
}
