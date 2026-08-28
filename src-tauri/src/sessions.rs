use std::{collections::HashSet, path::{Path, PathBuf}, thread, time::Duration};

use chrono::{DateTime, Utc};
use rusqlite::{params, Connection};
use tracing::{info, warn};
use uuid::Uuid;

use crate::{db, models::Installation, process_monitor::{ProcessMonitor, TrackedProcess}};

const DETECTION_ATTEMPTS: usize = 45;
const DETECTION_INTERVAL: Duration = Duration::from_secs(2);
const HEARTBEAT_INTERVAL: Duration = Duration::from_secs(5);
const MAX_RECOVERY_SECONDS: i64 = 18 * 60 * 60;

pub fn duration_between(started_at: &str, ended_at: &str) -> i64 {
    let Ok(start) = DateTime::parse_from_rfc3339(started_at) else { return 0; };
    let Ok(end) = DateTime::parse_from_rfc3339(ended_at) else { return 0; };
    (end - start).num_seconds().max(0)
}

fn safe_recovery_duration(started_at: &str, last_seen_at: Option<&str>) -> i64 {
    let Some(last_seen_at) = last_seen_at else { return 0; };
    duration_between(started_at, last_seen_at).min(MAX_RECOVERY_SECONDS)
}

fn open_db(path: &Path) -> Result<Connection, String> { db::open(path) }

fn create_session(db_path: &Path, installation: &Installation, process: &TrackedProcess) -> Result<String, String> {
    let connection = open_db(db_path)?;
    let id = Uuid::new_v4().to_string();
    let now = Utc::now().to_rfc3339();
    connection.execute(
        "INSERT INTO play_sessions(id, game_id, installation_id, started_at, duration_seconds, device, provider, process_id, process_path, last_seen_at)
         VALUES (?1, ?2, ?3, ?4, 0, 'desktop', ?5, ?6, ?7, ?4)",
        params![id, installation.game_id, installation.id, now, installation.provider, process.pid as i64, process.executable],
    ).map_err(|e| e.to_string())?;
    info!(game_id=%installation.game_id, installation_id=%installation.id, session_id=%id, pid=process.pid, "Sessão iniciada");
    Ok(id)
}

fn heartbeat(db_path: &Path, session_id: &str, process: &TrackedProcess) -> Result<(), String> {
    let connection = open_db(db_path)?;
    connection.execute(
        "UPDATE play_sessions SET last_seen_at=?1, process_id=?2, process_path=?3 WHERE id=?4 AND ended_at IS NULL",
        params![Utc::now().to_rfc3339(), process.pid as i64, process.executable, session_id],
    ).map_err(|e| e.to_string())?;
    Ok(())
}

fn finish_session(db_path: &Path, session_id: &str) -> Result<(), String> {
    let connection = open_db(db_path)?;
    let started_at: String = connection.query_row("SELECT started_at FROM play_sessions WHERE id=?1", [session_id], |row| row.get(0)).map_err(|e| e.to_string())?;
    let ended_at = Utc::now().to_rfc3339();
    let duration = duration_between(&started_at, &ended_at);
    connection.execute(
        "UPDATE play_sessions SET ended_at=?1, last_seen_at=?1, duration_seconds=?2 WHERE id=?3 AND ended_at IS NULL",
        params![ended_at, duration, session_id],
    ).map_err(|e| e.to_string())?;
    info!(session_id, duration_seconds=duration, "Sessão encerrada");
    Ok(())
}

fn install_directory(installation: &Installation) -> Option<PathBuf> {
    installation.install_dir.as_ref().map(PathBuf::from).or_else(|| {
        installation.executable.as_ref().and_then(|exe| Path::new(exe).parent().map(Path::to_path_buf))
    })
}

pub fn spawn_for_launch(db_path: PathBuf, installation: Installation, baseline: HashSet<u32>, direct_pid: Option<u32>) {
    thread::spawn(move || {
        let Some(directory) = install_directory(&installation) else {
            warn!(installation_id=%installation.id, "Não foi possível determinar diretório para monitorar");
            return;
        };

        let process = if let Some(pid) = direct_pid {
            let executable = installation.executable.clone().unwrap_or_default();
            let mut confirmed = None;
            for _ in 0..10 {
                if ProcessMonitor::process_matches(pid, &executable) {
                    confirmed = Some(TrackedProcess { pid, executable: executable.clone() });
                    break;
                }
                thread::sleep(Duration::from_millis(250));
            }
            confirmed
        } else {
            let mut found = None;
            for _ in 0..DETECTION_ATTEMPTS {
                if let Some(process) = ProcessMonitor::find_new_process_in_dir(&directory, &baseline) {
                    found = Some(process);
                    break;
                }
                thread::sleep(DETECTION_INTERVAL);
            }
            found
        };

        let Some(mut process) = process else {
            warn!(game_id=%installation.game_id, installation_id=%installation.id, "Launch ocorreu, mas nenhum processo confiável do jogo foi detectado");
            return;
        };

        let Ok(session_id) = create_session(&db_path, &installation, &process) else { return; };
        let mut misses = 0u8;
        loop {
            thread::sleep(HEARTBEAT_INTERVAL);
            if let Some(next) = ProcessMonitor::find_new_process_in_dir(&directory, &baseline) {
                process = next;
                misses = 0;
                let _ = heartbeat(&db_path, &session_id, &process);
            } else {
                misses += 1;
                if misses >= 2 {
                    let _ = finish_session(&db_path, &session_id);
                    break;
                }
            }
        }
    });
}

pub fn recover_incomplete_sessions(db_path: PathBuf) -> Result<(), String> {
    let connection = open_db(&db_path)?;
    let mut statement = connection.prepare(
        "SELECT id, started_at, last_seen_at, process_id, process_path FROM play_sessions WHERE ended_at IS NULL"
    ).map_err(|e| e.to_string())?;
    let rows = statement.query_map([], |row| Ok((
        row.get::<_, String>(0)?, row.get::<_, String>(1)?, row.get::<_, Option<String>>(2)?,
        row.get::<_, Option<i64>>(3)?, row.get::<_, Option<String>>(4)?
    ))).map_err(|e| e.to_string())?;
    let sessions = rows.collect::<Result<Vec<_>, _>>().map_err(|e| e.to_string())?;
    drop(statement);
    drop(connection);

    for (id, started_at, last_seen_at, process_id, process_path) in sessions {
        if let (Some(pid), Some(path)) = (process_id.map(|v| v as u32), process_path.clone()) {
            if ProcessMonitor::process_matches(pid, &path) {
                let path_clone = db_path.clone();
                thread::spawn(move || {
                    info!(session_id=%id, pid, "Sessão incompleta recuperada; processo ainda está ativo");
                    loop {
                        thread::sleep(HEARTBEAT_INTERVAL);
                        if ProcessMonitor::process_matches(pid, &path) {
                            let tracked = TrackedProcess { pid, executable: path.clone() };
                            let _ = heartbeat(&path_clone, &id, &tracked);
                        } else {
                            let _ = finish_session(&path_clone, &id);
                            break;
                        }
                    }
                });
                continue;
            }
        }

        let connection = open_db(&db_path)?;
        let duration = safe_recovery_duration(&started_at, last_seen_at.as_deref());
        let ended_at = last_seen_at.unwrap_or_else(|| started_at.clone());
        connection.execute(
            "UPDATE play_sessions SET ended_at=?1, duration_seconds=?2, recovered=1 WHERE id=?3 AND ended_at IS NULL",
            params![ended_at, duration, id],
        ).map_err(|e| e.to_string())?;
        info!(session_id=%id, duration_seconds=duration, "Sessão incompleta encerrada com segurança");
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::{duration_between, safe_recovery_duration};

    #[test]
    fn calculates_session_duration() {
        assert_eq!(duration_between("2026-08-28T10:00:00+00:00", "2026-08-28T11:30:05+00:00"), 5405);
    }

    #[test]
    fn rejects_negative_duration() {
        assert_eq!(duration_between("2026-08-28T12:00:00+00:00", "2026-08-28T11:00:00+00:00"), 0);
    }

    #[test]
    fn incomplete_session_uses_last_heartbeat_and_caps_corruption() {
        assert_eq!(safe_recovery_duration("2026-08-01T00:00:00+00:00", Some("2026-08-28T00:00:00+00:00")), 18 * 60 * 60);
        assert_eq!(safe_recovery_duration("2026-08-28T10:00:00+00:00", None), 0);
    }
}
