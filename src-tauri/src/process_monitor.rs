use std::{collections::HashSet, path::Path};

use sysinfo::{Pid, System};

#[derive(Debug, Clone)]
pub struct TrackedProcess {
    pub pid: u32,
    pub executable: String,
}

pub struct ProcessMonitor;

impl ProcessMonitor {
    pub fn snapshot_pids() -> HashSet<u32> {
        let system = System::new_all();
        system.processes().keys().map(|pid| pid.as_u32()).collect()
    }

    pub fn process_matches(pid: u32, expected_path: &str) -> bool {
        let mut system = System::new_all();
        system.refresh_all();
        let Some(process) = system.process(Pid::from_u32(pid)) else { return false; };
        let Some(path) = process.exe() else { return false; };
        path_eq(path, Path::new(expected_path))
    }

    pub fn find_new_process_in_dir(directory: &Path, baseline: &HashSet<u32>) -> Option<TrackedProcess> {
        let mut system = System::new_all();
        system.refresh_all();
        system.processes().iter().find_map(|(pid, process)| {
            let pid = pid.as_u32();
            if baseline.contains(&pid) {
                return None;
            }
            let path = process.exe()?;
            if is_ignored_executable(path) || !path_is_inside(path, directory) {
                return None;
            }
            Some(TrackedProcess { pid, executable: path.to_string_lossy().to_string() })
        })
    }
}

fn normalized_path(path: &Path) -> String {
    path.to_string_lossy().replace('/', "\\").trim_end_matches('\\').to_ascii_lowercase()
}

fn path_eq(left: &Path, right: &Path) -> bool {
    normalized_path(left) == normalized_path(right)
}

fn path_is_inside(path: &Path, directory: &Path) -> bool {
    let path = normalized_path(path);
    let directory = normalized_path(directory);
    path == directory || path.starts_with(&(directory + "\\"))
}

fn is_ignored_executable(path: &Path) -> bool {
    let name = path.file_name().and_then(|v| v.to_str()).unwrap_or_default().to_ascii_lowercase();
    matches!(name.as_str(),
        "steam.exe" | "steamwebhelper.exe" | "gameoverlayui.exe" | "crashhandler.exe" |
        "crashhandler64.exe" | "dxsetup.exe" | "vcredist_x64.exe" | "vcredist_x86.exe")
}

#[cfg(test)]
mod tests {
    use std::path::Path;
    use super::{path_eq, path_is_inside};

    #[test]
    fn windows_paths_are_compared_case_insensitively() {
        assert!(path_eq(Path::new("C:/Games/Test/game.exe"), Path::new("c:\\games\\test\\GAME.exe")));
    }

    #[test]
    fn process_must_be_inside_install_directory() {
        assert!(path_is_inside(Path::new("D:/Steam/common/Game/bin/game.exe"), Path::new("D:/Steam/common/Game")));
        assert!(!path_is_inside(Path::new("D:/Steam/common/Game2/game.exe"), Path::new("D:/Steam/common/Game")));
    }
}
