use rusqlite::{params, Connection};

const CREDENTIAL_MARKER: &str = "windows-credential-manager-v1";

fn setting_key(name: &str) -> String {
    format!("secret.{name}")
}

#[cfg(windows)]
fn credential_entry(name: &str) -> Result<keyring::Entry, String> {
    keyring::Entry::new("Ludex", name)
        .map_err(|e| format!("Falha ao abrir o Gerenciador de Credenciais do Windows: {e}"))
}

#[cfg(windows)]
fn store_secure(name: &str, value: &str) -> Result<(), String> {
    credential_entry(name)?
        .set_password(value)
        .map_err(|e| format!("O Windows recusou salvar a credencial com segurança: {e}"))
}

#[cfg(windows)]
fn load_secure(name: &str) -> Result<String, String> {
    credential_entry(name)?
        .get_password()
        .map_err(|e| format!("Não foi possível ler a credencial deste usuário do Windows: {e}"))
}

#[cfg(not(windows))]
fn store_secure(_name: &str, _value: &str) -> Result<(), String> {
    Err("O armazenamento protegido de credenciais está disponível no Desktop Windows".into())
}

#[cfg(not(windows))]
fn load_secure(_name: &str) -> Result<String, String> {
    Err("O armazenamento protegido de credenciais está disponível no Desktop Windows".into())
}

pub fn set(connection: &Connection, name: &str, value: &str) -> Result<(), String> {
    let key = setting_key(name);
    let value = value.trim();
    if value.is_empty() {
        connection
            .execute("DELETE FROM settings WHERE key=?1", [key])
            .map_err(|e| e.to_string())?;
        return Ok(());
    }

    // Never persist the API key itself in SQLite. Windows Credential Manager stores it
    // under the current Windows user and SQLite only keeps a non-secret marker.
    store_secure(name, value)?;
    connection
        .execute(
            "INSERT INTO settings(key,value) VALUES(?1,?2) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
            params![key, CREDENTIAL_MARKER],
        )
        .map_err(|e| e.to_string())?;
    Ok(())
}

pub fn get(connection: &Connection, name: &str) -> Result<Option<String>, String> {
    let Some(value) = crate::db::get_setting(connection, &setting_key(name))? else {
        return Ok(None);
    };

    if value == CREDENTIAL_MARKER {
        return load_secure(name).map(Some);
    }

    // 0.9.2 stored a DPAPI payload directly in SQLite. We intentionally do not attempt
    // to decrypt it through PowerShell anymore because that was the source of the
    // compatibility failure fixed in 0.9.3. Asking the user to save the key once more
    // migrates it to Windows Credential Manager safely.
    Err("Esta credencial foi salva pelo formato 0.9.2. Salve a chave novamente para migrá-la ao Gerenciador de Credenciais do Windows.".into())
}

pub fn configured(connection: &Connection, name: &str) -> Result<bool, String> {
    Ok(crate::db::get_setting(connection, &setting_key(name))?
        .as_deref()
        == Some(CREDENTIAL_MARKER))
}
