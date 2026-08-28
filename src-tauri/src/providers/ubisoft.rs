use crate::{models::ScannedInstallation, providers::ProviderScan};
use std::path::PathBuf;

#[cfg(windows)]
pub fn scan() -> Result<ProviderScan, String> {
    use winreg::{enums::HKEY_LOCAL_MACHINE, RegKey};
    let hklm=RegKey::predef(HKEY_LOCAL_MACHINE);
    let base=hklm.open_subkey("SOFTWARE\\WOW6432Node\\Ubisoft\\Launcher\\Installs").or_else(|_| hklm.open_subkey("SOFTWARE\\Ubisoft\\Launcher\\Installs"));
    let mut installations=Vec::new();
    if let Ok(base)=base {
        for id in base.enum_keys().flatten() {
            if let Ok(key)=base.open_subkey(&id) {
                let dir: String=key.get_value("InstallDir").unwrap_or_default();
                if dir.is_empty(){continue;}
                let title=PathBuf::from(&dir).file_name().and_then(|s|s.to_str()).unwrap_or("Ubisoft Game").to_string();
                installations.push(ScannedInstallation{provider:"ubisoft".into(),external_id:id,title,platform:"PC".into(),install_dir:Some(dir),executable:None,installed:true,size_bytes:None,last_updated:None});
            }
        }
    }
    Ok(ProviderScan{id:"ubisoft",name:"Ubisoft Connect",root:None,installations,can_launch:true,message:"Detecção pelo Registro oficial do Ubisoft Connect; launch via uplay:// com o ID registrado.".into()})
}
#[cfg(not(windows))]
pub fn scan()->Result<ProviderScan,String>{Ok(ProviderScan{id:"ubisoft",name:"Ubisoft Connect",root:None,installations:Vec::new(),can_launch:false,message:"Disponível no Windows.".into()})}
