use std::{
    collections::{HashMap, HashSet, VecDeque},
    path::{Path, PathBuf},
};

use sysinfo::System;

pub const LAUNCH_SCORE_THRESHOLD: i32 = 70;
pub const EXTERNAL_SCORE_THRESHOLD: i32 = 85;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProcessInfo {
    pub pid: u32,
    pub ppid: Option<u32>,
    pub name: String,
    pub executable: Option<String>,
    pub command_line: String,
    pub start_time: u64,
    pub memory_bytes: u64,
}

impl ProcessInfo {
    pub fn identity_matches(&self, pid: u32, executable: &str, start_time: Option<u64>) -> bool {
        if self.pid != pid {
            return false;
        }
        let Some(actual_path) = self.executable.as_deref() else {
            return false;
        };
        if !path_eq(Path::new(actual_path), Path::new(executable)) {
            return false;
        }
        start_time.is_none_or(|expected| expected == self.start_time)
    }
}

#[derive(Debug, Clone, Default)]
pub struct ProcessSnapshot {
    pub processes: HashMap<u32, ProcessInfo>,
}

impl ProcessSnapshot {
    pub fn pids(&self) -> HashSet<u32> {
        self.processes.keys().copied().collect()
    }

    pub fn get(&self, pid: u32) -> Option<&ProcessInfo> {
        self.processes.get(&pid)
    }

    pub fn descendants(&self, root_pid: u32) -> Vec<ProcessInfo> {
        let mut result = Vec::new();
        let mut queue = VecDeque::from([root_pid]);
        let mut visited = HashSet::from([root_pid]);

        while let Some(parent) = queue.pop_front() {
            for process in self.processes.values() {
                if process.ppid == Some(parent) && visited.insert(process.pid) {
                    result.push(process.clone());
                    queue.push_back(process.pid);
                }
            }
        }
        result
    }

    pub fn ancestors(&self, pid: u32) -> Vec<ProcessInfo> {
        let mut result = Vec::new();
        let mut current = self.get(pid).and_then(|process| process.ppid);
        let mut visited = HashSet::new();

        while let Some(parent_pid) = current {
            if !visited.insert(parent_pid) {
                break;
            }
            let Some(parent) = self.get(parent_pid) else {
                break;
            };
            result.push(parent.clone());
            current = parent.ppid;
        }
        result
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ProcessRole {
    Game,
    Launcher,
    AntiCheat,
    Ignored,
}

#[derive(Debug, Clone)]
pub struct CandidateContext<'a> {
    pub install_dir: Option<&'a Path>,
    pub known_executables: &'a [PathBuf],
    pub baseline: &'a HashSet<u32>,
    pub launch_started_unix: Option<u64>,
    pub related_roots: &'a HashSet<u32>,
    pub app_id: Option<&'a str>,
    pub require_new_process: bool,
}

#[derive(Debug, Clone)]
pub struct ScoredCandidate {
    pub process: ProcessInfo,
    pub role: ProcessRole,
    pub score: i32,
    pub reasons: Vec<String>,
}

pub struct ProcessCandidateScorer;

impl ProcessCandidateScorer {
    pub fn score(
        snapshot: &ProcessSnapshot,
        process: &ProcessInfo,
        context: &CandidateContext<'_>,
    ) -> ScoredCandidate {
        let role = classify_process(process);
        let mut score = 0;
        let mut reasons = Vec::new();
        let is_new = !context.baseline.contains(&process.pid);

        if context.require_new_process && !is_new {
            score -= 120;
            reasons.push("processo já existia antes da janela de discovery (-120)".into());
        } else if is_new {
            score += 20;
            reasons.push("processo novo (+20)".into());
        }

        if let (Some(path), Some(directory)) = (process.executable.as_deref(), context.install_dir)
        {
            if path_is_inside(Path::new(path), directory) {
                score += 60;
                reasons.push("executável dentro da instalação (+60)".into());
            }
        }

        if let Some(path) = process.executable.as_deref() {
            if context
                .known_executables
                .iter()
                .any(|known| path_eq(Path::new(path), known))
            {
                score += 35;
                reasons.push("executável conhecido (+35)".into());
            }
        }

        if context.related_roots.iter().any(|root| {
            process.pid == *root
                || snapshot
                    .ancestors(process.pid)
                    .iter()
                    .any(|ancestor| ancestor.pid == *root)
        }) {
            score += 35;
            reasons.push("relacionado à árvore de launch (+35)".into());
        }

        if let Some(started) = context.launch_started_unix {
            if process.start_time.saturating_add(2) >= started {
                score += 20;
                reasons.push("criado na janela do launch (+20)".into());
            }
        }

        if let Some(app_id) = context.app_id {
            if !app_id.is_empty() && process.command_line.contains(app_id) {
                score += 15;
                reasons.push("command line relacionada ao AppID (+15)".into());
            }
        }

        if process.memory_bytes >= 50 * 1024 * 1024 {
            score += 10;
            reasons.push("processo substancial em memória (+10)".into());
        }

        match role {
            ProcessRole::Game => {}
            ProcessRole::Launcher => {
                score -= 25;
                reasons.push("launcher intermediário (-25)".into());
            }
            ProcessRole::AntiCheat => {
                score -= 75;
                reasons.push("anti-cheat/bootstrapper (-75)".into());
            }
            ProcessRole::Ignored => {
                score -= 200;
                reasons.push("processo global/auxiliar ignorado (-200)".into());
            }
        }

        ScoredCandidate {
            process: process.clone(),
            role,
            score,
            reasons,
        }
    }
}

pub struct ProcessMonitor;

impl ProcessMonitor {
    pub fn snapshot() -> ProcessSnapshot {
        let mut system = System::new_all();
        system.refresh_all();
        let processes = system
            .processes()
            .iter()
            .map(|(pid, process)| {
                let pid = pid.as_u32();
                let info = ProcessInfo {
                    pid,
                    ppid: process.parent().map(|value| value.as_u32()),
                    name: process.name().to_string_lossy().to_string(),
                    executable: process.exe().map(|path| path.to_string_lossy().to_string()),
                    command_line: process
                        .cmd()
                        .iter()
                        .map(|value| value.to_string_lossy())
                        .collect::<Vec<_>>()
                        .join(" "),
                    start_time: process.start_time(),
                    memory_bytes: process.memory(),
                };
                (pid, info)
            })
            .collect();
        ProcessSnapshot { processes }
    }

    pub fn snapshot_pids() -> HashSet<u32> {
        Self::snapshot().pids()
    }

    pub fn process_exists(pid: u32) -> bool {
        Self::snapshot().get(pid).is_some()
    }

    pub fn executable_path(pid: u32) -> Option<String> {
        Self::snapshot().get(pid)?.executable.clone()
    }

    pub fn process_identity_matches(
        pid: u32,
        expected_path: &str,
        expected_start_time: Option<u64>,
    ) -> bool {
        Self::snapshot().get(pid).is_some_and(|process| {
            process.identity_matches(pid, expected_path, expected_start_time)
        })
    }

    pub fn process_matches(pid: u32, expected_path: &str) -> bool {
        Self::process_identity_matches(pid, expected_path, None)
    }

    pub fn find_candidates(
        snapshot: &ProcessSnapshot,
        context: &CandidateContext<'_>,
    ) -> Vec<ScoredCandidate> {
        let mut candidates = snapshot
            .processes
            .values()
            .map(|process| ProcessCandidateScorer::score(snapshot, process, context))
            .filter(|candidate| candidate.role != ProcessRole::Ignored)
            .filter(|candidate| candidate.score > 0)
            .collect::<Vec<_>>();
        candidates.sort_by(|a, b| {
            b.score
                .cmp(&a.score)
                .then_with(|| a.process.pid.cmp(&b.process.pid))
        });
        candidates
    }

    pub fn related_processes(
        snapshot: &ProcessSnapshot,
        root_pid: u32,
        directory: &Path,
        session_started_unix: u64,
    ) -> Vec<ProcessInfo> {
        let descendants = snapshot
            .descendants(root_pid)
            .into_iter()
            .map(|process| process.pid)
            .collect::<HashSet<_>>();

        snapshot
            .processes
            .values()
            .filter(|process| {
                if classify_process(process) == ProcessRole::Ignored {
                    return false;
                }
                let inside = process
                    .executable
                    .as_deref()
                    .is_some_and(|path| path_is_inside(Path::new(path), directory));
                let related = process.pid == root_pid || descendants.contains(&process.pid);
                let same_launch_window =
                    process.start_time.saturating_add(3) >= session_started_unix;
                related || (inside && same_launch_window)
            })
            .cloned()
            .collect()
    }

    pub fn find_new_process_in_dir(
        directory: &Path,
        baseline: &HashSet<u32>,
    ) -> Option<TrackedProcess> {
        let snapshot = Self::snapshot();
        let roots = HashSet::new();
        let known = Vec::new();
        let context = CandidateContext {
            install_dir: Some(directory),
            known_executables: &known,
            baseline,
            launch_started_unix: None,
            related_roots: &roots,
            app_id: None,
            require_new_process: true,
        };
        Self::find_candidates(&snapshot, &context)
            .into_iter()
            .find(|candidate| candidate.role == ProcessRole::Game)
            .map(|candidate| TrackedProcess {
                pid: candidate.process.pid,
                executable: candidate.process.executable.unwrap_or_default(),
                start_time: candidate.process.start_time,
            })
    }
}

#[derive(Debug, Clone)]
pub struct TrackedProcess {
    pub pid: u32,
    pub executable: String,
    pub start_time: u64,
}

pub fn classify_process(process: &ProcessInfo) -> ProcessRole {
    let name = process.name.to_ascii_lowercase();
    let path_name = process
        .executable
        .as_deref()
        .and_then(|path| Path::new(path).file_name())
        .and_then(|value| value.to_str())
        .unwrap_or(&name)
        .to_ascii_lowercase();
    let value = if path_name.is_empty() {
        &name
    } else {
        &path_name
    };

    if matches!(
        value.as_str(),
        "steam.exe"
            | "steamwebhelper.exe"
            | "gameoverlayui.exe"
            | "crashhandler.exe"
            | "crashhandler64.exe"
            | "dxsetup.exe"
            | "vcredist_x64.exe"
            | "vcredist_x86.exe"
            | "chrome.exe"
            | "msedge.exe"
            | "firefox.exe"
    ) || value.contains("updater")
        || value.contains("installer")
        || value.contains("redistributable")
    {
        return ProcessRole::Ignored;
    }

    if value.contains("easyanticheat")
        || value.contains("battleye")
        || value.contains("beservice")
        || value.contains("beclient")
        || value.contains("start_protected_game")
    {
        return ProcessRole::AntiCheat;
    }

    if value.contains("launcher")
        || value.contains("ubisoftconnect")
        || value.contains("eadesktop")
        || value.contains("rockstar")
    {
        return ProcessRole::Launcher;
    }

    ProcessRole::Game
}

fn normalized_path(path: &Path) -> String {
    path.to_string_lossy()
        .replace('/', "\\")
        .trim_end_matches('\\')
        .to_ascii_lowercase()
}

pub fn path_eq(left: &Path, right: &Path) -> bool {
    normalized_path(left) == normalized_path(right)
}

pub fn path_is_inside(path: &Path, directory: &Path) -> bool {
    let path = normalized_path(path);
    let directory = normalized_path(directory);
    path == directory || path.starts_with(&(directory + "\\"))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn process(
        pid: u32,
        ppid: Option<u32>,
        name: &str,
        path: &str,
        start_time: u64,
    ) -> ProcessInfo {
        ProcessInfo {
            pid,
            ppid,
            name: name.into(),
            executable: Some(path.into()),
            command_line: path.into(),
            start_time,
            memory_bytes: 100 * 1024 * 1024,
        }
    }

    fn snapshot(items: Vec<ProcessInfo>) -> ProcessSnapshot {
        ProcessSnapshot {
            processes: items.into_iter().map(|item| (item.pid, item)).collect(),
        }
    }

    #[test]
    fn windows_paths_are_compared_case_insensitively() {
        assert!(path_eq(
            Path::new("C:/Games/Test/game.exe"),
            Path::new("c:\\games\\test\\GAME.exe")
        ));
    }

    #[test]
    fn process_must_be_inside_install_directory() {
        assert!(path_is_inside(
            Path::new("D:/Steam/common/Game/bin/game.exe"),
            Path::new("D:/Steam/common/Game")
        ));
        assert!(!path_is_inside(
            Path::new("D:/Steam/common/Game2/game.exe"),
            Path::new("D:/Steam/common/Game")
        ));
    }

    #[test]
    fn discovers_descendants_across_multiple_generations() {
        let tree = snapshot(vec![
            process(1, None, "A.exe", "C:/A.exe", 1),
            process(2, Some(1), "B.exe", "C:/B.exe", 2),
            process(3, Some(2), "C.exe", "C:/C.exe", 3),
        ]);
        let ids = tree
            .descendants(1)
            .into_iter()
            .map(|item| item.pid)
            .collect::<HashSet<_>>();
        assert_eq!(ids, HashSet::from([2, 3]));
        assert_eq!(
            tree.ancestors(3)
                .iter()
                .map(|item| item.pid)
                .collect::<Vec<_>>(),
            vec![2, 1]
        );
    }

    #[test]
    fn anticheat_is_not_a_game_candidate_by_itself() {
        let anti = process(
            20,
            Some(10),
            "EasyAntiCheat.exe",
            "D:/Game/EasyAntiCheat.exe",
            100,
        );
        assert_eq!(classify_process(&anti), ProcessRole::AntiCheat);
        let snap = snapshot(vec![anti]);
        let baseline = HashSet::new();
        let roots = HashSet::new();
        let known = Vec::new();
        let context = CandidateContext {
            install_dir: Some(Path::new("D:/Game")),
            known_executables: &known,
            baseline: &baseline,
            launch_started_unix: Some(100),
            related_roots: &roots,
            app_id: None,
            require_new_process: true,
        };
        let candidate = ProcessCandidateScorer::score(&snap, snap.get(20).unwrap(), &context);
        assert!(candidate.score < LAUNCH_SCORE_THRESHOLD);
    }

    #[test]
    fn old_global_steam_process_is_rejected() {
        let steam = process(1, None, "steam.exe", "C:/Steam/steam.exe", 1);
        let snap = snapshot(vec![steam]);
        let baseline = HashSet::from([1]);
        let roots = HashSet::new();
        let known = Vec::new();
        let context = CandidateContext {
            install_dir: Some(Path::new("C:/Steam/common/Game")),
            known_executables: &known,
            baseline: &baseline,
            launch_started_unix: Some(100),
            related_roots: &roots,
            app_id: Some("123"),
            require_new_process: true,
        };
        let candidate = ProcessCandidateScorer::score(&snap, snap.get(1).unwrap(), &context);
        assert_eq!(candidate.role, ProcessRole::Ignored);
        assert!(candidate.score < 0);
    }

    #[test]
    fn new_executable_inside_installation_scores_high() {
        let game = process(42, Some(1), "game.exe", "D:/Game/bin/game.exe", 101);
        let snap = snapshot(vec![game]);
        let baseline = HashSet::new();
        let roots = HashSet::new();
        let known = Vec::new();
        let context = CandidateContext {
            install_dir: Some(Path::new("D:/Game")),
            known_executables: &known,
            baseline: &baseline,
            launch_started_unix: Some(100),
            related_roots: &roots,
            app_id: None,
            require_new_process: true,
        };
        let candidate = ProcessCandidateScorer::score(&snap, snap.get(42).unwrap(), &context);
        assert_eq!(candidate.role, ProcessRole::Game);
        assert!(candidate.score >= LAUNCH_SCORE_THRESHOLD);
        assert!(candidate.score >= EXTERNAL_SCORE_THRESHOLD);
    }

    #[test]
    fn launcher_persistent_does_not_count_as_game_after_game_exits() {
        let snap = snapshot(vec![process(
            2,
            Some(1),
            "ThirdPartyLauncher.exe",
            "D:/Game/launcher.exe",
            100,
        )]);
        let related = ProcessMonitor::related_processes(&snap, 2, Path::new("D:/Game"), 100);
        assert!(related
            .iter()
            .all(|item| classify_process(item) != ProcessRole::Game));
    }

    #[test]
    fn pid_reuse_rejects_incompatible_start_identity() {
        let current = process(55, None, "game.exe", "D:/Game/game.exe", 500);
        assert!(!current.identity_matches(55, "D:/Game/game.exe", Some(100)));
        assert!(!current.identity_matches(55, "D:/Other/game.exe", Some(500)));
        assert!(current.identity_matches(55, "D:/Game/game.exe", Some(500)));
    }

    #[cfg(windows)]
    #[test]
    #[ignore]
    fn harness_sleeper() {
        let millis = std::env::var("LUDEX_HARNESS_MS")
            .ok()
            .and_then(|value| value.parse::<u64>().ok())
            .unwrap_or(500);
        std::thread::sleep(std::time::Duration::from_millis(millis));
    }

    #[cfg(windows)]
    #[test]
    #[ignore]
    fn harness_launcher() {
        use std::process::{Command, Stdio};
        let directory = std::path::PathBuf::from(std::env::var("LUDEX_HARNESS_DIR").unwrap());
        for (name, millis) in [
            ("EasyAntiCheat.exe", "900"),
            ("FakeGame.exe", "1800"),
            ("ThirdPartyLauncher.exe", "3200"),
        ] {
            Command::new(directory.join(name))
                .args([
                    "--ignored",
                    "--exact",
                    "process_monitor::tests::harness_sleeper",
                    "--nocapture",
                ])
                .env("LUDEX_HARNESS_MS", millis)
                .stdin(Stdio::null())
                .stdout(Stdio::null())
                .stderr(Stdio::null())
                .spawn()
                .unwrap();
        }
    }

    #[cfg(windows)]
    fn wait_snapshot<F>(timeout: std::time::Duration, predicate: F) -> Option<ProcessSnapshot>
    where
        F: Fn(&ProcessSnapshot) -> bool,
    {
        let deadline = std::time::Instant::now() + timeout;
        while std::time::Instant::now() < deadline {
            let snapshot = ProcessMonitor::snapshot();
            if predicate(&snapshot) {
                return Some(snapshot);
            }
            std::thread::sleep(std::time::Duration::from_millis(25));
        }
        None
    }

    #[cfg(windows)]
    #[test]
    fn fake_process_tree_survives_launcher_exit() {
        use std::{fs, process::Command, time::Duration};
        let source = std::env::current_exe().unwrap();
        let directory =
            std::env::temp_dir().join(format!("ludex-process-harness-{}", uuid::Uuid::new_v4()));
        fs::create_dir_all(&directory).unwrap();
        for name in [
            "FakeLauncher.exe",
            "EasyAntiCheat.exe",
            "FakeGame.exe",
            "ThirdPartyLauncher.exe",
        ] {
            fs::copy(&source, directory.join(name)).unwrap();
        }

        let status = Command::new(directory.join("FakeLauncher.exe"))
            .args([
                "--ignored",
                "--exact",
                "process_monitor::tests::harness_launcher",
                "--nocapture",
            ])
            .env("LUDEX_HARNESS_DIR", &directory)
            .status()
            .unwrap();
        assert!(status.success(), "o launcher fake deve sair normalmente");

        let running = wait_snapshot(Duration::from_secs(2), |snapshot| {
            let names = snapshot
                .processes
                .values()
                .map(|process| process.name.to_ascii_lowercase())
                .collect::<std::collections::HashSet<_>>();
            names.iter().any(|name| name.contains("fakegame"))
                && names.iter().any(|name| name.contains("easyanticheat"))
                && names.iter().any(|name| name.contains("thirdpartylauncher"))
        })
        .expect("filhos fake devem continuar após o launcher sair");

        let game = running
            .processes
            .values()
            .find(|process| process.name.to_ascii_lowercase().contains("fakegame"))
            .unwrap();
        let anti = running
            .processes
            .values()
            .find(|process| process.name.to_ascii_lowercase().contains("easyanticheat"))
            .unwrap();
        let persistent = running
            .processes
            .values()
            .find(|process| {
                process
                    .name
                    .to_ascii_lowercase()
                    .contains("thirdpartylauncher")
            })
            .unwrap();
        assert_eq!(classify_process(game), ProcessRole::Game);
        assert_eq!(classify_process(anti), ProcessRole::AntiCheat);
        assert_eq!(classify_process(persistent), ProcessRole::Launcher);

        let after_game = wait_snapshot(Duration::from_secs(4), |snapshot| {
            let names = snapshot
                .processes
                .values()
                .map(|process| process.name.to_ascii_lowercase())
                .collect::<Vec<_>>();
            !names.iter().any(|name| name.contains("fakegame"))
                && names.iter().any(|name| name.contains("thirdpartylauncher"))
        })
        .expect("launcher persistente deve sobreviver ao processo do jogo");
        assert!(after_game.processes.values().all(|process| {
            !process
                .name
                .to_ascii_lowercase()
                .contains("thirdpartylauncher")
                || classify_process(process) == ProcessRole::Launcher
        }));

        let _ = wait_snapshot(Duration::from_secs(4), |snapshot| {
            snapshot.processes.values().all(|process| {
                !process
                    .name
                    .to_ascii_lowercase()
                    .contains("thirdpartylauncher")
            })
        });
        let _ = fs::remove_dir_all(directory);
    }
}
