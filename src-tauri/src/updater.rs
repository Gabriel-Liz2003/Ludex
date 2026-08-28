use reqwest::blocking::Client;
use semver::Version;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    fs,
    path::{Path, PathBuf},
    process::Command,
    time::Duration,
};

const RELEASES_API: &str = "https://api.github.com/repos/Gabriel-Liz2003/Ludex/releases/latest";

#[derive(Debug, Clone, Serialize)]
pub struct UpdateInfo {
    pub available: bool,
    pub current_version: String,
    pub latest_version: Option<String>,
    pub notes: Option<String>,
    pub published_at: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
struct ReleaseAsset {
    name: String,
    browser_download_url: String,
    digest: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
struct Release {
    tag_name: String,
    body: Option<String>,
    published_at: Option<String>,
    assets: Vec<ReleaseAsset>,
}

fn client() -> Result<Client, String> {
    Client::builder()
        .timeout(Duration::from_secs(20))
        .user_agent("Ludex-Updater")
        .build()
        .map_err(|e| e.to_string())
}

fn fetch_release() -> Result<Option<Release>, String> {
    let response = client()?
        .get(RELEASES_API)
        .send()
        .map_err(|e| e.to_string())?;
    if response.status().as_u16() == 404 {
        return Ok(None);
    }
    let response = response.error_for_status().map_err(|e| e.to_string())?;
    response
        .json::<Release>()
        .map(Some)
        .map_err(|e| e.to_string())
}

fn clean_version(value: &str) -> &str {
    value.strip_prefix('v').unwrap_or(value)
}

fn windows_asset(release: &Release) -> Option<&ReleaseAsset> {
    release.assets.iter().find(|asset| {
        let name = asset.name.to_ascii_lowercase();
        name.ends_with(".exe") && name.contains("x64") && name.contains("setup")
    })
}

pub fn check() -> Result<UpdateInfo, String> {
    let current_text = env!("CARGO_PKG_VERSION").to_string();
    let current = Version::parse(&current_text).map_err(|e| e.to_string())?;
    let Some(release) = fetch_release()? else {
        return Ok(UpdateInfo {
            available: false,
            current_version: current_text,
            latest_version: None,
            notes: None,
            published_at: None,
        });
    };
    let latest_text = clean_version(&release.tag_name).to_string();
    let latest = Version::parse(&latest_text).map_err(|e| format!("Release inválida: {e}"))?;
    Ok(UpdateInfo {
        available: latest > current && windows_asset(&release).is_some(),
        current_version: current_text,
        latest_version: Some(latest_text),
        notes: release.body,
        published_at: release.published_at,
    })
}

pub fn download_latest(data_dir: &Path) -> Result<PathBuf, String> {
    let current = Version::parse(env!("CARGO_PKG_VERSION")).map_err(|e| e.to_string())?;
    let release = fetch_release()?.ok_or_else(|| "Nenhuma release publicada".to_string())?;
    let latest_text = clean_version(&release.tag_name);
    let latest = Version::parse(latest_text).map_err(|e| e.to_string())?;
    if latest <= current {
        return Err("O Ludex já está atualizado".into());
    }
    let asset = windows_asset(&release)
        .ok_or_else(|| "A release mais recente não contém instalador Windows x64".to_string())?;
    let response = client()?
        .get(&asset.browser_download_url)
        .send()
        .and_then(|r| r.error_for_status())
        .map_err(|e| e.to_string())?;
    let bytes = response.bytes().map_err(|e| e.to_string())?;
    if let Some(expected) = asset
        .digest
        .as_deref()
        .and_then(|d| d.strip_prefix("sha256:"))
    {
        let actual = format!("{:x}", Sha256::digest(&bytes));
        if !actual.eq_ignore_ascii_case(expected) {
            return Err("Falha de integridade no instalador baixado".into());
        }
    }
    let updates = data_dir.join("updates");
    fs::create_dir_all(&updates).map_err(|e| e.to_string())?;
    let path = updates.join(format!("Ludex_{latest_text}_x64-setup.exe"));
    fs::write(&path, &bytes).map_err(|e| e.to_string())?;
    Ok(path)
}

#[cfg(windows)]
pub fn launch_installer(path: &Path) -> Result<(), String> {
    Command::new(path)
        .arg("/S")
        .spawn()
        .map_err(|e| format!("Falha ao iniciar o instalador: {e}"))?;
    Ok(())
}

#[cfg(not(windows))]
pub fn launch_installer(_path: &Path) -> Result<(), String> {
    Err("Atualização automática está habilitada somente no Desktop Windows nesta versão".into())
}

#[cfg(test)]
mod tests {
    use super::clean_version;
    #[test]
    fn accepts_v_prefixed_release_versions() {
        assert_eq!(clean_version("v0.9.1"), "0.9.1");
        assert_eq!(clean_version("1.0.0"), "1.0.0");
    }
}
