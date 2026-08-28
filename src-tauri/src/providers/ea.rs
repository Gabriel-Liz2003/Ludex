use crate::{models::ScannedInstallation, providers::ProviderScan};
use std::{fs, path::{Path, PathBuf}};

fn roots()->Vec<PathBuf>{
    let mut r=Vec::new(); if let Ok(p)=std::env::var("PROGRAMFILES"){r.push(PathBuf::from(p).join("EA Games"));} if let Ok(p)=std::env::var("PROGRAMFILES(X86)"){r.push(PathBuf::from(p).join("Origin Games"));} r
}
fn find_exe(dir:&Path)->Option<String>{
    let entries=fs::read_dir(dir).ok()?;
    entries.flatten().map(|e|e.path()).find(|p|p.extension().and_then(|x|x.to_str()).is_some_and(|x|x.eq_ignore_ascii_case("exe")) && !p.file_name().and_then(|x|x.to_str()).unwrap_or("").to_ascii_lowercase().contains("unins")).map(|p|p.to_string_lossy().to_string())
}
pub fn scan()->Result<ProviderScan,String>{
    let roots=roots(); let mut installations=Vec::new();
    for root in &roots { let Ok(entries)=fs::read_dir(root) else{continue}; for e in entries.flatten(){let dir=e.path();if !dir.is_dir(){continue;} let title=e.file_name().to_string_lossy().to_string(); let id=title.to_ascii_lowercase().replace(' ',"-"); installations.push(ScannedInstallation{provider:"ea".into(),external_id:id,title,platform:"PC".into(),install_dir:Some(dir.to_string_lossy().to_string()),executable:find_exe(&dir),installed:true,size_bytes:None,last_updated:None});}}
    let root=roots.into_iter().find(|p|p.exists()); Ok(ProviderScan{id:"ea",name:"EA app",root,installations,can_launch:true,message:"Detecção local conservadora em EA Games/Origin Games; executável direto quando identificado com segurança.".into()})
}
