use std::{
    collections::{HashMap, HashSet},
    path::{Path, PathBuf},
    sync::{Mutex, OnceLock},
    thread,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

use chrono::{DateTime, Utc};
use rusqlite::{params, Connection, OptionalExtension};
use tracing::{debug, info, warn};
use uuid::Uuid;

use crate::{
    db,
    models::Installation,
    process_monitor::{
        classify_process, CandidateContext, ProcessInfo, ProcessMonitor, ProcessRole,
        EXTERNAL_SCORE_THRESHOLD, LAUNCH_SCORE_THRESHOLD,
    },
};

const DISCOVERY_TIMEOUT: Duration = Duration::from_secs(12);
const DISCOVERY_INTERVAL: Duration = Duration::from_millis(500);
const STABILIZATION_DELAY: Duration = Duration::from_secs(2);
const EXTERNAL_SCAN_INTERVAL: Duration = Duration::from_secs(8);
const EXTERNAL_CONFIRM_DELAY: Duration = Duration::from_secs(3);
const HEARTBEAT_INTERVAL: Duration = Duration::from_secs(4);
const MAX_RECOVERY_SECONDS: i64 = 18 * 60 * 60;
const OLD_SESSION_RECOVERY_FRESHNESS_SECONDS: i64 = 30;

static LAUNCH_INTENTS: OnceLock<Mutex<HashMap<String, Instant>>> = OnceLock::new();

fn launch_intents() -> &'static Mutex<HashMap<String, Instant>> {
    LAUNCH_INTENTS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn register_launch_intent(installation_id: &str) {
    if let Ok(mut intents) = launch_intents().lock() {
        intents.insert(
            installation_id.to_string(),
            Instant::now() + Duration::from_secs(30),
        );
    }
}

fn has_launch_intent(installation_id: &str) -> bool {
    let Ok(mut intents) = launch_intents().lock() else {
        return false;
    };
    let now = Instant::now();
    intents.retain(|_, expires| *expires > now);
    intents.contains_key(installation_id)
}

fn clear_launch_intent(installation_id: &str) {
    if let Ok(mut intents) = launch_intents().lock() {
        intents.remove(installation_id);
    }
}

fn unix_now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}

pub fn duration_between(started_at: &str, ended_at: &str) -> i64 {
    let Ok(start) = DateTime::parse_from_rfc3339(started_at) else {
        return 0;
    };
    let Ok(end) = DateTime::parse_from_rfc3339(ended_at) else {
        return 0;
    };
    (end - start).num_seconds().max(0)
}

fn safe_recovery_duration(started_at: &str, last_seen_at: Option<&str>) -> i64 {
    let Some(last_seen_at) = last_seen_at else {
        return 0;
    };
    duration_between(started_at, last_seen_at).min(MAX_RECOVERY_SECONDS)
}

fn heartbeat_is_recent(last_seen_at: Option<&str>) -> bool {
    let Some(last_seen_at) = last_seen_at else {
        return false;
    };
    let Ok(last_seen) = DateTime::parse_from_rfc3339(last_seen_at) else {
        return false;
    };
    (Utc::now() - last_seen.with_timezone(&Utc))
        .num_seconds()
        .abs()
        <= OLD_SESSION_RECOVERY_FRESHNESS_SECONDS
}

fn open_db(path: &Path) -> Result<Connection, String> {
    let connection = db::open(path)?;
    ensure_runtime_schema(&connection)?;
    Ok(connection)
}

fn ensure_runtime_schema(connection: &Connection) -> Result<(), String> {
    connection
        .execute_batch(
            "CREATE TABLE IF NOT EXISTS session_process_state (
               session_id TEXT PRIMARY KEY REFERENCES play_sessions(id) ON DELETE CASCADE,
               source TEXT NOT NULL,
               root_pid INTEGER,
               process_start_time INTEGER,
               process_path TEXT,
               discovery_metadata TEXT,
               updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
             );
             CREATE TABLE IF NOT EXISTS session_process_members (
               session_id TEXT NOT NULL REFERENCES play_sessions(id) ON DELETE CASCADE,
               pid INTEGER NOT NULL,
               process_start_time INTEGER NOT NULL,
               executable TEXT,
               role TEXT NOT NULL,
               last_seen_at TEXT NOT NULL,
               PRIMARY KEY(session_id, pid, process_start_time)
             );
             CREATE INDEX IF NOT EXISTS idx_session_process_members_session ON session_process_members(session_id);",
        )
        .map_err(|e| e.to_string())
}

fn installation_directory(installation: &Installation) -> Option<PathBuf> {
    installation
        .install_dir
        .as_ref()
        .map(PathBuf::from)
        .or_else(|| {
            installation
                .executable
                .as_ref()
                .and_then(|exe| Path::new(exe).parent().map(Path::to_path_buf))
        })
}

fn role_name(process: &ProcessInfo) -> &'static str {
    match classify_process(process) {
        ProcessRole::Game => "game",
        ProcessRole::Launcher => "launcher",
        ProcessRole::AntiCheat => "anticheat",
        ProcessRole::Ignored => "ignored",
    }
}

fn persist_members(
    db_path: &Path,
    session_id: &str,
    members: &[ProcessInfo],
) -> Result<(), String> {
    let connection = open_db(db_path)?;
    let now = Utc::now().to_rfc3339();
    for member in members {
        connection
            .execute(
                "INSERT INTO session_process_members(session_id, pid, process_start_time, executable, role, last_seen_at)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6)
                 ON CONFLICT(session_id, pid, process_start_time) DO UPDATE SET executable=excluded.executable, role=excluded.role, last_seen_at=excluded.last_seen_at",
                params![
                    session_id,
                    member.pid as i64,
                    member.start_time as i64,
                    member.executable,
                    role_name(member),
                    now
                ],
            )
            .map_err(|e| e.to_string())?;
    }
    Ok(())
}

fn create_session(
    db_path: &Path,
    installation: &Installation,
    process: &ProcessInfo,
    source: &str,
    started_at: &str,
    discovery_metadata: &str,
) -> Result<Option<String>, String> {
    let connection = open_db(db_path)?;
    connection
        .execute_batch("BEGIN IMMEDIATE;")
        .map_err(|e| e.to_string())?;

    let duplicate = connection
        .query_row(
            "SELECT id FROM play_sessions WHERE installation_id=?1 AND ended_at IS NULL LIMIT 1",
            [&installation.id],
            |row| row.get::<_, String>(0),
        )
        .optional()
        .map_err(|e| e.to_string())?;
    if duplicate.is_some() {
        let _ = connection.execute_batch("ROLLBACK;");
        debug!(event="candidate_rejected", installation_id=%installation.id, reason="active_session_exists", "Sessão duplicada evitada");
        return Ok(None);
    }

    let id = Uuid::new_v4().to_string();
    let insert = connection.execute(
        "INSERT INTO play_sessions(id, game_id, installation_id, started_at, duration_seconds, device, provider, process_id, process_path, last_seen_at)
         VALUES (?1, ?2, ?3, ?4, 0, 'desktop', ?5, ?6, ?7, ?4)",
        params![id, installation.game_id, installation.id, started_at, installation.provider, process.pid as i64, process.executable],
    );
    if let Err(error) = insert {
        let _ = connection.execute_batch("ROLLBACK;");
        return Err(error.to_string());
    }
    connection.execute(
        "INSERT INTO session_process_state(session_id, source, root_pid, process_start_time, process_path, discovery_metadata, updated_at)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, CURRENT_TIMESTAMP)",
        params![id, source, process.pid as i64, process.start_time as i64, process.executable, discovery_metadata],
    ).map_err(|e| e.to_string())?;
    connection
        .execute_batch("COMMIT;")
        .map_err(|e| e.to_string())?;

    info!(event="session_started", game_id=%installation.game_id, installation_id=%installation.id, session_id=%id, pid=process.pid, source, "Sessão confirmada");
    Ok(Some(id))
}

fn heartbeat(db_path: &Path, session_id: &str, process: &ProcessInfo) -> Result<(), String> {
    let connection = open_db(db_path)?;
    connection.execute(
        "UPDATE play_sessions SET last_seen_at=?1, process_id=?2, process_path=?3 WHERE id=?4 AND ended_at IS NULL",
        params![Utc::now().to_rfc3339(), process.pid as i64, process.executable, session_id],
    ).map_err(|e| e.to_string())?;
    connection.execute(
        "UPDATE session_process_state SET root_pid=?1, process_start_time=?2, process_path=?3, updated_at=CURRENT_TIMESTAMP WHERE session_id=?4",
        params![process.pid as i64, process.start_time as i64, process.executable, session_id],
    ).map_err(|e| e.to_string())?;
    Ok(())
}

fn finish_session(db_path: &Path, session_id: &str) -> Result<(), String> {
    let connection = open_db(db_path)?;
    let started_at: String = connection
        .query_row(
            "SELECT started_at FROM play_sessions WHERE id=?1",
            [session_id],
            |row| row.get(0),
        )
        .map_err(|e| e.to_string())?;
    let ended_at = Utc::now().to_rfc3339();
    let duration = duration_between(&started_at, &ended_at);
    let changed = connection.execute(
        "UPDATE play_sessions SET ended_at=?1, last_seen_at=?1, duration_seconds=?2 WHERE id=?3 AND ended_at IS NULL",
        params![ended_at, duration, session_id],
    ).map_err(|e| e.to_string())?;
    if changed > 0 {
        info!(
            event = "session_finished",
            session_id,
            duration_seconds = duration,
            "Sessão encerrada"
        );
    }
    Ok(())
}

fn has_legitimate_game_process(members: &[ProcessInfo]) -> bool {
    members
        .iter()
        .any(|process| classify_process(process) == ProcessRole::Game)
}

fn track_session(
    db_path: PathBuf,
    installation: Installation,
    session_id: String,
    root: ProcessInfo,
    session_started_unix: u64,
) {
    let Some(directory) = installation_directory(&installation) else {
        let _ = finish_session(&db_path, &session_id);
        return;
    };
    let mut root = root;
    let mut misses = 0u8;

    loop {
        thread::sleep(HEARTBEAT_INTERVAL);
        let snapshot = ProcessMonitor::snapshot();
        let members = ProcessMonitor::related_processes(
            &snapshot,
            root.pid,
            &directory,
            session_started_unix,
        );
        let games = members
            .iter()
            .filter(|process| classify_process(process) == ProcessRole::Game)
            .collect::<Vec<_>>();

        if let Some(primary) = games
            .iter()
            .max_by_key(|process| process.memory_bytes)
            .copied()
        {
            if primary.pid != root.pid {
                info!(event="process_child_detected", session_id=%session_id, old_pid=root.pid, new_pid=primary.pid, "Processo principal transferido/expandido");
                root = primary.clone();
            }
            misses = 0;
            let _ = heartbeat(&db_path, &session_id, &root);
            let _ = persist_members(&db_path, &session_id, &members);
            continue;
        }

        misses += 1;
        if !members.is_empty() {
            debug!(event="process_exited", session_id=%session_id, remaining=members.len(), misses, "Somente launcher/anti-cheat permanece; aguardando confirmação de término");
        }
        if misses >= 2 {
            let _ = finish_session(&db_path, &session_id);
            clear_launch_intent(&installation.id);
            break;
        }
    }
}

fn discover_for_launch(
    installation: &Installation,
    baseline: &HashSet<u32>,
    direct_pid: Option<u32>,
    launch_started_unix: u64,
) -> Option<(ProcessInfo, String)> {
    let directory = installation_directory(installation)?;
    let known_executables = installation
        .executable
        .as_ref()
        .map(|value| vec![PathBuf::from(value)])
        .unwrap_or_default();
    let related_roots = direct_pid.into_iter().collect::<HashSet<_>>();
    let started = Instant::now();
    let mut best_seen: Option<(ProcessInfo, i32, String)> = None;

    while started.elapsed() < DISCOVERY_TIMEOUT {
        let snapshot = ProcessMonitor::snapshot();
        debug!(event="process_snapshot", installation_id=%installation.id, processes=snapshot.processes.len(), "Snapshot durante discovery");
        let context = CandidateContext {
            install_dir: Some(&directory),
            known_executables: &known_executables,
            baseline,
            launch_started_unix: Some(launch_started_unix),
            related_roots: &related_roots,
            app_id: installation.external_id.as_deref(),
            require_new_process: direct_pid.is_none(),
        };
        for candidate in ProcessMonitor::find_candidates(&snapshot, &context) {
            debug!(event="candidate_score", pid=candidate.process.pid, score=candidate.score, role=?candidate.role, reasons=?candidate.reasons, "Candidato avaliado");
            if candidate.role != ProcessRole::Game || candidate.score < LAUNCH_SCORE_THRESHOLD {
                continue;
            }
            let metadata = format!(
                "score={}; reasons={}",
                candidate.score,
                candidate.reasons.join(" | ")
            );
            if best_seen
                .as_ref()
                .is_none_or(|(_, score, _)| candidate.score > *score)
            {
                best_seen = Some((candidate.process, candidate.score, metadata));
            }
        }

        if started.elapsed() >= STABILIZATION_DELAY {
            if let Some((process, _, metadata)) = best_seen.take() {
                let current = ProcessMonitor::snapshot();
                if current.get(process.pid).is_some_and(|value| {
                    value.identity_matches(
                        process.pid,
                        process.executable.as_deref().unwrap_or_default(),
                        Some(process.start_time),
                    )
                }) {
                    info!(event="process_selected", installation_id=%installation.id, pid=process.pid, "Processo principal confirmado após estabilização");
                    return Some((process, metadata));
                }
            }
        }
        thread::sleep(DISCOVERY_INTERVAL);
    }
    None
}

pub fn spawn_for_launch(
    db_path: PathBuf,
    installation: Installation,
    baseline: HashSet<u32>,
    direct_pid: Option<u32>,
) {
    register_launch_intent(&installation.id);
    thread::spawn(move || {
        let launch_started = Utc::now().to_rfc3339();
        let launch_started_unix = unix_now();
        info!(event="launch_requested", installation_id=%installation.id, provider=%installation.provider, "Discovery iniciado");

        let Some((process, metadata)) =
            discover_for_launch(&installation, &baseline, direct_pid, launch_started_unix)
        else {
            clear_launch_intent(&installation.id);
            warn!(event="candidate_rejected", game_id=%installation.game_id, installation_id=%installation.id, reason="no_confident_candidate", "Launch ocorreu, mas nenhum processo confiável do jogo foi detectado");
            return;
        };

        let Ok(Some(session_id)) = create_session(
            &db_path,
            &installation,
            &process,
            "ludex_launch",
            &launch_started,
            &metadata,
        ) else {
            clear_launch_intent(&installation.id);
            return;
        };
        let _ = persist_members(&db_path, &session_id, std::slice::from_ref(&process));
        track_session(
            db_path,
            installation,
            session_id,
            process,
            launch_started_unix,
        );
    });
}

fn load_steam_installations(db_path: &Path) -> Result<Vec<Installation>, String> {
    let connection = open_db(db_path)?;
    let mut statement = connection.prepare(
        "SELECT id, game_id, provider, external_id, executable, install_dir, working_dir, launch_args, installed
         FROM installations WHERE provider='steam' AND installed=1 AND install_dir IS NOT NULL",
    ).map_err(|e| e.to_string())?;
    let rows = statement
        .query_map([], |row| {
            Ok(Installation {
                id: row.get(0)?,
                game_id: row.get(1)?,
                provider: row.get(2)?,
                external_id: row.get(3)?,
                executable: row.get(4)?,
                install_dir: row.get(5)?,
                working_dir: row.get(6)?,
                launch_args: row.get(7)?,
                installed: row.get::<_, i64>(8)? != 0,
            })
        })
        .map_err(|e| e.to_string())?;
    rows.collect::<Result<Vec<_>, _>>()
        .map_err(|e| e.to_string())
}

fn spawn_external_confirmation(
    db_path: PathBuf,
    installation: Installation,
    candidate: ProcessInfo,
    metadata: String,
) {
    thread::spawn(move || {
        thread::sleep(EXTERNAL_CONFIRM_DELAY);
        if has_launch_intent(&installation.id) {
            return;
        }
        let snapshot = ProcessMonitor::snapshot();
        let Some(current) = snapshot.get(candidate.pid).cloned() else {
            return;
        };
        let Some(path) = candidate.executable.as_deref() else {
            return;
        };
        if !current.identity_matches(candidate.pid, path, Some(candidate.start_time))
            || classify_process(&current) != ProcessRole::Game
        {
            return;
        }

        let started_at = Utc::now().to_rfc3339();
        let started_unix = current.start_time;
        match create_session(
            &db_path,
            &installation,
            &current,
            "external_detection",
            &started_at,
            &metadata,
        ) {
            Ok(Some(session_id)) => {
                info!(event="external_game_detected", game_id=%installation.game_id, installation_id=%installation.id, pid=current.pid, "Jogo Steam iniciado externamente detectado");
                let _ = persist_members(&db_path, &session_id, std::slice::from_ref(&current));
                track_session(db_path, installation, session_id, current, started_unix);
            }
            Ok(None) => {}
            Err(error) => warn!(%error, "Falha ao criar sessão externa"),
        }
    });
}

pub fn spawn_external_detector(db_path: PathBuf) {
    thread::spawn(move || {
        let mut previous = ProcessMonitor::snapshot();
        loop {
            thread::sleep(EXTERNAL_SCAN_INTERVAL);
            let current = ProcessMonitor::snapshot();
            let previous_pids = previous.pids();
            let new_processes = current
                .processes
                .values()
                .filter(|process| !previous_pids.contains(&process.pid))
                .cloned()
                .collect::<Vec<_>>();

            if !new_processes.is_empty() {
                if let Ok(installations) = load_steam_installations(&db_path) {
                    for installation in installations {
                        if has_launch_intent(&installation.id) {
                            continue;
                        }
                        let Some(directory) = installation_directory(&installation) else {
                            continue;
                        };
                        let known = installation
                            .executable
                            .as_ref()
                            .map(|value| vec![PathBuf::from(value)])
                            .unwrap_or_default();
                        let roots = HashSet::new();
                        let context = CandidateContext {
                            install_dir: Some(&directory),
                            known_executables: &known,
                            baseline: &previous_pids,
                            launch_started_unix: None,
                            related_roots: &roots,
                            app_id: installation.external_id.as_deref(),
                            require_new_process: true,
                        };

                        for process in &new_processes {
                            let candidate = crate::process_monitor::ProcessCandidateScorer::score(
                                &current, process, &context,
                            );
                            if candidate.role != ProcessRole::Game
                                || candidate.score < EXTERNAL_SCORE_THRESHOLD
                            {
                                continue;
                            }
                            let metadata = format!(
                                "external score={}; reasons={}",
                                candidate.score,
                                candidate.reasons.join(" | ")
                            );
                            debug!(event="candidate_found", installation_id=%installation.id, pid=process.pid, score=candidate.score, "Candidato externo encontrado; aguardando persistência");
                            spawn_external_confirmation(
                                db_path.clone(),
                                installation.clone(),
                                process.clone(),
                                metadata,
                            );
                            break;
                        }
                    }
                }
            }
            previous = current;
        }
    });
}

fn close_incomplete_safely(
    db_path: &Path,
    id: &str,
    started_at: &str,
    last_seen_at: Option<&str>,
) -> Result<(), String> {
    let connection = open_db(db_path)?;
    let duration = safe_recovery_duration(started_at, last_seen_at);
    let ended_at = last_seen_at.unwrap_or(started_at);
    connection.execute(
        "UPDATE play_sessions SET ended_at=?1, duration_seconds=?2, recovered=1 WHERE id=?3 AND ended_at IS NULL",
        params![ended_at, duration, id],
    ).map_err(|e| e.to_string())?;
    info!(event="session_finished", session_id=%id, duration_seconds=duration, recovered=true, "Sessão incompleta encerrada com segurança");
    Ok(())
}

pub fn recover_incomplete_sessions(db_path: PathBuf) -> Result<(), String> {
    let connection = open_db(&db_path)?;
    let mut statement = connection.prepare(
        "SELECT id, installation_id, started_at, last_seen_at, process_id, process_path FROM play_sessions WHERE ended_at IS NULL"
    ).map_err(|e| e.to_string())?;
    let rows = statement
        .query_map([], |row| {
            Ok((
                row.get::<_, String>(0)?,
                row.get::<_, Option<String>>(1)?,
                row.get::<_, String>(2)?,
                row.get::<_, Option<String>>(3)?,
                row.get::<_, Option<i64>>(4)?,
                row.get::<_, Option<String>>(5)?,
            ))
        })
        .map_err(|e| e.to_string())?;
    let sessions = rows
        .collect::<Result<Vec<_>, _>>()
        .map_err(|e| e.to_string())?;
    drop(statement);
    drop(connection);

    for (id, installation_id, started_at, last_seen_at, old_pid, old_path) in sessions {
        let connection = open_db(&db_path)?;
        let runtime = connection
            .query_row(
                "SELECT root_pid, process_start_time, process_path FROM session_process_state WHERE session_id=?1",
                [&id],
                |row| {
                    Ok((
                        row.get::<_, Option<i64>>(0)?,
                        row.get::<_, Option<i64>>(1)?,
                        row.get::<_, Option<String>>(2)?,
                    ))
                },
            )
            .optional()
            .map_err(|e| e.to_string())?;

        let installation = if let Some(installation_id) = installation_id.as_deref() {
            connection
                .query_row(
                    "SELECT id, game_id, provider, external_id, executable, install_dir, working_dir, launch_args, installed FROM installations WHERE id=?1",
                    [installation_id],
                    |row| {
                        Ok(Installation {
                            id: row.get(0)?, game_id: row.get(1)?, provider: row.get(2)?, external_id: row.get(3)?, executable: row.get(4)?, install_dir: row.get(5)?, working_dir: row.get(6)?, launch_args: row.get(7)?, installed: row.get::<_, i64>(8)? != 0,
                        })
                    },
                )
                .optional()
                .map_err(|e| e.to_string())?
        } else {
            None
        };
        drop(connection);

        let identity = runtime
            .clone()
            .and_then(|(pid, start, path)| Some((pid? as u32, start? as u64, path?)));

        if let (Some(installation), Some((pid, process_start, path))) =
            (installation.clone(), identity)
        {
            let snapshot = ProcessMonitor::snapshot();
            if let Some(current) = snapshot.get(pid).cloned() {
                if current.identity_matches(pid, &path, Some(process_start))
                    && classify_process(&current) == ProcessRole::Game
                {
                    let connection = open_db(&db_path)?;
                    connection.execute(
                        "UPDATE session_process_state SET source='recovered', updated_at=CURRENT_TIMESTAMP WHERE session_id=?1",
                        [&id],
                    ).map_err(|e| e.to_string())?;
                    info!(event="session_recovered", session_id=%id, pid, "Sessão recuperada com PID+caminho+start time válidos");
                    thread::spawn({
                        let db_path = db_path.clone();
                        let id = id.clone();
                        move || track_session(db_path, installation, id, current, process_start)
                    });
                    continue;
                }
            }
        }

        // Compatibilidade com sessões antigas: sem start time persistido, só retomamos se o
        // heartbeat é muito recente e PID+caminho ainda batem. Caso contrário, fechamos.
        if runtime.is_none() && heartbeat_is_recent(last_seen_at.as_deref()) {
            if let (Some(pid), Some(path), Some(installation)) = (
                old_pid.map(|value| value as u32),
                old_path.as_deref(),
                installation,
            ) {
                let snapshot = ProcessMonitor::snapshot();
                if let Some(current) = snapshot.get(pid).cloned() {
                    if current.identity_matches(pid, path, None)
                        && classify_process(&current) == ProcessRole::Game
                    {
                        let connection = open_db(&db_path)?;
                        connection.execute(
                            "INSERT OR REPLACE INTO session_process_state(session_id, source, root_pid, process_start_time, process_path, discovery_metadata, updated_at)
                             VALUES (?1, 'recovered', ?2, ?3, ?4, 'legacy recovery with fresh heartbeat', CURRENT_TIMESTAMP)",
                            params![id, pid as i64, current.start_time as i64, current.executable],
                        ).map_err(|e| e.to_string())?;
                        info!(event="session_recovered", session_id=%id, pid, legacy=true, "Sessão antiga recuperada conservadoramente");
                        thread::spawn({
                            let db_path = db_path.clone();
                            let id = id.clone();
                            let start = current.start_time;
                            move || track_session(db_path, installation, id, current, start)
                        });
                        continue;
                    }
                }
            }
        }

        close_incomplete_safely(&db_path, &id, &started_at, last_seen_at.as_deref())?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::{
        create_session, duration_between, has_legitimate_game_process, safe_recovery_duration,
    };
    use crate::{db, process_monitor::ProcessInfo};
    use std::fs;
    use uuid::Uuid;

    fn process(name: &str, pid: u32) -> ProcessInfo {
        ProcessInfo {
            pid,
            ppid: None,
            name: name.into(),
            executable: Some(format!("D:/Game/{name}")),
            command_line: String::new(),
            start_time: 100,
            memory_bytes: 100,
        }
    }

    #[test]
    fn calculates_session_duration() {
        assert_eq!(
            duration_between("2026-08-28T10:00:00+00:00", "2026-08-28T11:30:05+00:00"),
            5405
        );
    }

    #[test]
    fn rejects_negative_duration() {
        assert_eq!(
            duration_between("2026-08-28T12:00:00+00:00", "2026-08-28T11:00:00+00:00"),
            0
        );
    }

    #[test]
    fn incomplete_session_uses_last_heartbeat_and_caps_corruption() {
        assert_eq!(
            safe_recovery_duration(
                "2026-08-01T00:00:00+00:00",
                Some("2026-08-28T00:00:00+00:00")
            ),
            18 * 60 * 60
        );
        assert_eq!(safe_recovery_duration("2026-08-28T10:00:00+00:00", None), 0);
    }

    #[test]
    fn launcher_closing_does_not_end_session_when_game_remains() {
        assert!(has_legitimate_game_process(&[process("game.exe", 2)]));
    }

    #[test]
    fn persistent_launcher_does_not_keep_session_alive() {
        assert!(!has_legitimate_game_process(&[process(
            "ThirdPartyLauncher.exe",
            2
        )]));
    }

    #[test]
    fn anticheat_alone_does_not_start_or_keep_session() {
        assert!(!has_legitimate_game_process(&[process(
            "EasyAntiCheat.exe",
            3
        )]));
    }

    #[test]
    fn duplicate_active_session_for_installation_is_rejected() {
        let path = std::env::temp_dir().join(format!("ludex-session-test-{}.db", Uuid::new_v4()));
        let connection = db::open(&path).unwrap();
        db::add_manual_game(
            &connection,
            "session-test-game",
            "Session Test Game",
            "PC",
            Some("D:\\Game\\game.exe"),
            Some("D:\\Game"),
            None,
        )
        .unwrap();
        let installation = db::installation_for_launch(&connection, "session-test-game")
            .unwrap()
            .unwrap();
        drop(connection);

        let candidate = process("game.exe", 42);
        let first = create_session(
            &path,
            &installation,
            &candidate,
            "external_detection",
            "2026-08-28T12:00:00+00:00",
            "test candidate",
        )
        .unwrap();
        let second = create_session(
            &path,
            &installation,
            &candidate,
            "ludex_launch",
            "2026-08-28T12:00:01+00:00",
            "duplicate candidate",
        )
        .unwrap();

        assert!(first.is_some());
        assert!(second.is_none());
        let connection = db::open(&path).unwrap();
        let count: i64 = connection
            .query_row(
                "SELECT COUNT(*) FROM play_sessions WHERE installation_id=?1 AND ended_at IS NULL",
                [&installation.id],
                |row| row.get(0),
            )
            .unwrap();
        assert_eq!(count, 1);
        drop(connection);
        let _ = fs::remove_file(path);
    }
}
