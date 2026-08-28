use crate::{models::ScannedInstallation, providers::ProviderScan};
use std::{path::PathBuf, process::Command};

#[cfg(windows)]
pub fn scan() -> Result<ProviderScan, String> {
    let script = r#"$ErrorActionPreference='SilentlyContinue'; Get-StartApps | Where-Object { $_.AppID -like '*!*' -and ($_.Name -match 'Xbox|Minecraft|Forza|Halo|Gaming|Game') } | Select-Object Name,AppID | ConvertTo-Json -Compress"#;
    let output = Command::new("powershell.exe")
        .args(["-NoProfile", "-NonInteractive", "-Command", script])
        .output()
        .map_err(|e| e.to_string())?;
    let text = String::from_utf8_lossy(&output.stdout);
    let value: serde_json::Value = if text.trim().is_empty() {
        serde_json::json!([])
    } else {
        serde_json::from_str(&text).unwrap_or_else(|_| serde_json::json!([]))
    };
    let items: Vec<serde_json::Value> = match value {
        serde_json::Value::Array(v) => v,
        v @ serde_json::Value::Object(_) => vec![v],
        _ => Vec::new(),
    };
    let installations = items
        .into_iter()
        .filter_map(|v| {
            let title = v.get("Name")?.as_str()?.to_string();
            let id = v.get("AppID")?.as_str()?.to_string();
            Some(ScannedInstallation {
                provider: "xbox".into(),
                external_id: id,
                title,
                platform: "PC".into(),
                install_dir: None,
                executable: None,
                installed: true,
                size_bytes: None,
                last_updated: None,
            })
        })
        .collect();
    Ok(ProviderScan { id:"xbox", name:"Xbox / Microsoft Store", root:None, installations, can_launch:true,
        message:"Detecção conservadora via AUMIDs registrados no Windows; apps ambíguos podem ser adicionados manualmente.".into() })
}

#[cfg(not(windows))]
pub fn scan() -> Result<ProviderScan, String> {
    Ok(ProviderScan {
        id: "xbox",
        name: "Xbox / Microsoft Store",
        root: None,
        installations: Vec::new(),
        can_launch: false,
        message: "Disponível no Windows.".into(),
    })
}
