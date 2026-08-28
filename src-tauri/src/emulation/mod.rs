use crate::models::EmulatorConfig;
use std::path::Path;

pub trait EmulatorAdapter: Send + Sync {
    fn config(&self) -> &EmulatorConfig;
    fn supports(&self, platform: &str, rom: &Path) -> bool;
    fn build_command(&self, rom: &Path) -> Result<(String, Vec<String>), String>;
}

pub fn render_arguments(
    template: &str,
    rom: &Path,
    core: Option<&str>,
) -> Result<Vec<String>, String> {
    let rom = rom
        .to_str()
        .ok_or_else(|| "Caminho de ROM inválido".to_string())?;
    let args =
        shlex::split(template).ok_or_else(|| "Template de argumentos inválido".to_string())?;
    Ok(args
        .into_iter()
        .map(|a| {
            a.replace("{rom}", rom)
                .replace("{core}", core.unwrap_or(""))
        })
        .filter(|a| !a.is_empty())
        .collect())
}

pub fn preset(name: &str) -> Option<(&'static str, &'static str, &'static str)> {
    match name.to_ascii_lowercase().as_str() {
        "retroarch" => Some((
            "Multi",
            "-L {core} {rom}",
            "nes,snes,n64,z64,v64,gba,gbc,nds",
        )),
        "dolphin" => Some(("GameCube/Wii", "-b -e {rom}", "iso,rvz,wbfs")),
        "pcsx2" => Some(("PlayStation 2", "{rom}", "iso,chd")),
        "rpcs3" => Some(("PlayStation 3", "{rom}", "")),
        "ppsspp" => Some(("PSP", "{rom}", "iso,cso,pbp")),
        "duckstation" => Some(("PlayStation", "{rom}", "cue,chd,pbp")),
        "cemu" => Some(("Wii U", "-g {rom}", "wua,wud,wux,rpx")),
        "ryujinx" => Some(("Nintendo Switch", "{rom}", "nsp,xci")),
        "melonds" => Some(("Nintendo DS", "{rom}", "nds")),
        "mgba" => Some(("Game Boy Advance", "{rom}", "gba,gbc,gb")),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn preserves_rom_paths_with_spaces() {
        let p = Path::new("C:/ROMs/My Game.iso");
        let a = render_arguments("-b -e \"{rom}\"", p, None).unwrap();
        assert_eq!(a[2], "C:/ROMs/My Game.iso");
    }
}
