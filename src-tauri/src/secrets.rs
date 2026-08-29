use base64::{engine::general_purpose::STANDARD, Engine as _};
use rusqlite::{params, Connection};

const CREDENTIAL_MANAGER_MARKER: &str = "windows-credential-manager-v1";

fn setting_key(name: &str) -> String {
    format!("secret.{name}")
}

#[cfg(windows)]
mod native_dpapi {
    use super::*;
    use std::{ffi::c_void, ptr};

    const CRYPTPROTECT_UI_FORBIDDEN: u32 = 0x1;

    #[repr(C)]
    struct DataBlob {
        cb_data: u32,
        pb_data: *mut u8,
    }

    #[link(name = "Crypt32")]
    extern "system" {
        fn CryptProtectData(
            data_in: *mut DataBlob,
            description: *const u16,
            optional_entropy: *mut DataBlob,
            reserved: *mut c_void,
            prompt: *mut c_void,
            flags: u32,
            data_out: *mut DataBlob,
        ) -> i32;
        fn CryptUnprotectData(
            data_in: *mut DataBlob,
            description: *mut *mut u16,
            optional_entropy: *mut DataBlob,
            reserved: *mut c_void,
            prompt: *mut c_void,
            flags: u32,
            data_out: *mut DataBlob,
        ) -> i32;
    }

    #[link(name = "Kernel32")]
    extern "system" {
        fn LocalFree(memory: *mut c_void) -> *mut c_void;
    }

    pub fn protect(value: &str) -> Result<String, String> {
        let mut bytes = value.as_bytes().to_vec();
        let mut input = DataBlob {
            cb_data: bytes.len() as u32,
            pb_data: bytes.as_mut_ptr(),
        };
        let mut output = DataBlob {
            cb_data: 0,
            pb_data: ptr::null_mut(),
        };

        let ok = unsafe {
            CryptProtectData(
                &mut input,
                ptr::null(),
                ptr::null_mut(),
                ptr::null_mut(),
                ptr::null_mut(),
                CRYPTPROTECT_UI_FORBIDDEN,
                &mut output,
            )
        };
        if ok == 0 {
            return Err(format!(
                "O Windows não conseguiu proteger a credencial: {}",
                std::io::Error::last_os_error()
            ));
        }

        let protected = unsafe {
            let slice = std::slice::from_raw_parts(output.pb_data, output.cb_data as usize);
            let encoded = STANDARD.encode(slice);
            LocalFree(output.pb_data.cast());
            encoded
        };
        Ok(protected)
    }

    pub fn unprotect(value: &str) -> Result<String, String> {
        let mut bytes = STANDARD
            .decode(value)
            .map_err(|_| "Credencial protegida inválida".to_string())?;
        let mut input = DataBlob {
            cb_data: bytes.len() as u32,
            pb_data: bytes.as_mut_ptr(),
        };
        let mut output = DataBlob {
            cb_data: 0,
            pb_data: ptr::null_mut(),
        };

        let ok = unsafe {
            CryptUnprotectData(
                &mut input,
                ptr::null_mut(),
                ptr::null_mut(),
                ptr::null_mut(),
                ptr::null_mut(),
                CRYPTPROTECT_UI_FORBIDDEN,
                &mut output,
            )
        };
        if ok == 0 {
            return Err(format!(
                "O Windows não conseguiu desbloquear a credencial deste usuário: {}",
                std::io::Error::last_os_error()
            ));
        }

        let plain = unsafe {
            let slice = std::slice::from_raw_parts(output.pb_data, output.cb_data as usize);
            let text = String::from_utf8(slice.to_vec()).map_err(|e| e.to_string());
            LocalFree(output.pb_data.cast());
            text?
        };
        Ok(plain)
    }
}

#[cfg(windows)]
fn protect(value: &str) -> Result<String, String> {
    native_dpapi::protect(value)
}

#[cfg(windows)]
fn unprotect(value: &str) -> Result<String, String> {
    native_dpapi::unprotect(value)
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
    let value = value.trim();
    if value.is_empty() {
        connection
            .execute("DELETE FROM settings WHERE key=?1", [key])
            .map_err(|e| e.to_string())?;
        return Ok(());
    }

    let encrypted = protect(value)?;
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

    // 0.9.3 could leave a SQLite marker even if Windows Credential Manager later
    // failed to return the entry. Treat that stale state as unconfigured instead of
    // breaking the settings/store pages. Saving the key again migrates it to DPAPI.
    if value == CREDENTIAL_MANAGER_MARKER {
        return Ok(None);
    }

    unprotect(&value).map(Some)
}

pub fn configured(connection: &Connection, name: &str) -> Result<bool, String> {
    let Some(value) = crate::db::get_setting(connection, &setting_key(name))? else {
        return Ok(false);
    };
    if value == CREDENTIAL_MANAGER_MARKER {
        return Ok(false);
    }
    Ok(unprotect(&value).is_ok())
}
