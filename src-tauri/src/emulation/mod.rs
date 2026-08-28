use crate::models::EmulatorConfig;
use std::path::Path;

pub trait EmulatorAdapter: Send + Sync {
    fn config(&self) -> &EmulatorConfig;
    fn supports(&self, platform: &str, rom: &Path) -> bool;
    fn build_command(&self, rom: &Path) -> Result<(String, Vec<String>), String>;
}

pub fn render_arguments(template: &str, rom: &Path) -> Result<Vec<String>, String> {
    let rom = rom
        .to_str()
        .ok_or_else(|| "Caminho de ROM inválido".to_string())?;
    let rendered = template.replace("{rom}", rom);
    Ok(rendered
        .split_whitespace()
        .map(ToString::to_string)
        .collect())
}
