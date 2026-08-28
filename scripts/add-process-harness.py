from pathlib import Path

p = Path('src-tauri/src/process_monitor.rs')
t = p.read_text(encoding='utf-8')
if 'fake_process_tree_survives_launcher_exit' not in t:
    block = r'''

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
        let directory = std::env::temp_dir().join(format!("ludex-process-harness-{}", uuid::Uuid::new_v4()));
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
            .find(|process| process.name.to_ascii_lowercase().contains("thirdpartylauncher"))
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
            !process.name.to_ascii_lowercase().contains("thirdpartylauncher")
                || classify_process(process) == ProcessRole::Launcher
        }));

        let _ = wait_snapshot(Duration::from_secs(4), |snapshot| {
            snapshot.processes.values().all(|process| {
                !process.name.to_ascii_lowercase().contains("thirdpartylauncher")
            })
        });
        let _ = fs::remove_dir_all(directory);
    }
'''
    pos = t.rfind('\n}')
    if pos < 0:
        raise SystemExit('test module closing brace not found')
    t = t[:pos] + block + t[pos:]
p.write_text(t, encoding='utf-8')
