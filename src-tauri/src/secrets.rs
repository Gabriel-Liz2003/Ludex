use base64::{engine::general_purpose::STANDARD, Engine as _};
use rusqlite::{params, Connection};
use std::process::Command;

fn setting_key(name: &str) -> String {
    format!("secret.{name}")
}

#[cfg(windows)]
fn protect(value: &str) -> Result<String, String> {
    let input = STANDARD.encode(value.as_bytes());
    let script = format!(
        "$b=[Convert]::FromBase64String('{}');$e=[Security.Cryptography.ProtectedData]::Protect($b,$null,[Security.Cryptography.DataProtectionScope]::CurrentUser);[Convert]::ToBase64String($e)",
        input
    );
    let output = Command::new("powershell.exe")
        .args(["-NoProfile", "-NonInteractive", "-Command", &script])
        .output()
        .map_err(|e| format!("Falha ao abrir o armazenamento seguro do Windows: {e}"))?;
    if !output.status.success() {
        return Err("O Windows recusou proteger a credencial".into());
    }
    String::from_utf8(output.stdout)
        .map(|v| v.trim().to_string())
        .map_err(|e| e.to_string())
}

#[cfg(windows)]
fn unprotect(value: &str) -> Result<String, String> {
    if !value.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '+' | '/' | '=')) {
        return Err("Credencial protegida inválida".into());
    }
    let script = format!(
        "$b=[Convert]::FromBase64String('{}');$d=[Security.Cryptography.ProtectedData]::Unprotect($b,$null,[Security.Cryptography.DataProtectionScope]::CurrentUser);[Text.Encoding]::UTF8.GetString($d)",
        value
    );
    let output = Command::new("powershell.exe")
        .args(["-NoProfile", "-NonInteractive", "-Command", &script])
        .output()
        .map_err(|e| format!("Falha ao abrir o armazenamento seguro do Windows: {e}"))?;
    if !output.status.success() {
        return Err("Não foi possível desbloquear a credencial para este usuário do Windows".into());
    }
    String::from_utf8(output.stdout)
        .map(|v| v.trim().to_string())
        .map_err(|e| e.to_string())
}

#[cfg(not(windows))]
fn protect(_value: &str) -> Result<String, String> {
    Err("O armazenamento protegido de credenciais está disponível no Desktop Windows".into())
}

#[cfg(not(windows))]
fn unprotect(_value: &str) -> Result<String, String> {
    Err("O armazenamento protegido de credenciais está disponível no Desktop Windows".into())
}

pub fn set(connection: &Connection, name: &str, value: &str) -> Result<(), String> {
    let key = setting_key(name);
    if value.trim().is_empty() {
        connection
            .execute("DELETE FROM settings WHERE key=?1", [key])
            .map_err(|e| e.to_string())?;
        return Ok(());
    }
    let encrypted = protect(value.trim())?;
    connection
        .execute(
            "INSERT INTO settings(key,value) VALUES(?1,?2) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
            params![key, encrypted],
        )
        .map_err(|e| e.to_string())?;
    Ok(())
}

pub fn get(connection: &Connection, name: &str) -> Result<Option<String>, String> {
    let Some(value) = crate::db::get_setting(connection, &setting_key(name))? else {
        return Ok(None);
    };
    unprotect(&value).map(Some)
}

pub fn configured(connection: &Connection, name: &str) -> Result<bool, String> {
    Ok(crate::db::get_setting(connection, &setting_key(name))?.is_some())
}
