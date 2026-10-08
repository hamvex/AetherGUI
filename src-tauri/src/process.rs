use crate::settings::Settings;
use crate::{active_attempt, elapsed_since_ms, emit_timeline, endpoint_cache};
use serde::Serialize;
use sha2::{Digest, Sha256};
use std::{
    collections::VecDeque,
    ffi::{OsStr, OsString},
    fs,
    io::Write,
    process::Stdio,
    sync::Arc,
    time::Instant,
};
use tauri::{AppHandle, Emitter, Manager};
use tokio::{
    io::{AsyncBufReadExt, BufReader},
    process::{Child, Command},
    sync::Mutex,
    time::{sleep, Duration},
};

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct StatusEvent {
    pub state: &'static str,
    pub endpoint: Option<String>,
    pub message: Option<String>,
}

pub const AETHER_VERSION: &str = "2.3.0";
const AETHER_SHA256: &str = "4834bec4fa3b108275cac1b765d83aadf6b2fcae76ea74b00bfa3935f238e0c1";

/// Cumulative byte counters parsed from the core's `[=] up X down Y uptime ...` statistics
/// lines (v2.1.0, `AETHER_STATS=1`). Authoritative user-facing traffic totals: they count
/// real relayed client bytes on the core's listeners in BOTH connection modes and are
/// immune to TUN counter-direction ambiguity.
#[derive(Debug, Clone, Copy, Default, PartialEq)]
pub struct CoreStats {
    pub uploaded: u64,
    pub downloaded: u64,
}

/// Parse one core stats log line into byte counts. The v2.1.0 format is
/// `[=] up 291.1 KiB down 482.6 KiB uptime 00:01:30` — the size and its unit are separate
/// tokens. Units are binary (B, KiB, MiB, GiB, TiB); a plain number is bytes.
pub fn parse_stats_line(line: &str) -> Option<CoreStats> {
    if !line.contains("[=]") {
        return None;
    }
    let tokens: Vec<&str> = line.split_whitespace().collect();
    let mut uploaded = None;
    let mut downloaded = None;
    for (index, token) in tokens.iter().enumerate() {
        let field = match *token {
            "up" => "up",
            "down" => "down",
            _ => continue,
        };
        // Combine the value token with the unit token that follows it.
        let mut size_token = tokens.get(index + 1).copied().unwrap_or("").to_string();
        if let Some(unit) = tokens.get(index + 2).copied() {
            if is_size_unit(unit) {
                size_token = format!("{} {}", tokens.get(index + 1).copied().unwrap_or(""), unit);
            }
        }
        let value = parse_size_token(&size_token)?;
        match field {
            "up" => uploaded = Some(value),
            "down" => downloaded = Some(value),
            _ => {}
        }
    }
    Some(CoreStats {
        uploaded: uploaded?,
        downloaded: downloaded?,
    })
}

fn is_size_unit(token: &str) -> bool {
    matches!(token, "B" | "KiB" | "MiB" | "GiB" | "TiB")
}

fn parse_size_token(token: &str) -> Option<u64> {
    let token = token.trim();
    let (number, unit) = token.split_at(
        token
            .find(|c: char| !c.is_ascii_digit() && c != '.')
            .unwrap_or(token.len()),
    );
    let value: f64 = number.parse().ok()?;
    let multiplier = match unit.trim() {
        "" | "B" => 1.0,
        "KiB" => 1024.0,
        "MiB" => 1024.0 * 1024.0,
        "GiB" => 1024.0 * 1024.0 * 1024.0,
        "TiB" => 1024.0 * 1024.0 * 1024.0 * 1024.0,
        _ => return None,
    };
    Some((value * multiplier) as u64)
}

#[derive(Default)]
pub struct ProcessManager {
    child: Mutex<Option<Child>>,
    job: Mutex<Option<isize>>,
    generation: Mutex<u64>,
    started: Mutex<Option<Instant>>,
    connected: Mutex<bool>,
    recovering: Mutex<bool>,
    runtime_protocol: Mutex<Option<String>>,
    recent_lines: Mutex<VecDeque<String>>,
    core_stats: Mutex<CoreStats>,
    /// The warp-in-warp hops the running core reported choosing, if it has.
    /// Held in memory for the lifetime of the attempt and only written to the
    /// on-disk cache once that attempt reaches `Connected`.
    observed_endpoints: Mutex<Option<endpoint_cache::Endpoints>>,
    /// Whether the running core was started with a cached pin. Drives both the
    /// shortened readiness deadline and the decision to invalidate on failure.
    started_from_cache: Mutex<bool>,
    /// The network this attempt dialled out on, resolved before the core was
    /// spawned - that is, while the default route still belonged to the physical
    /// link rather than to the tunnel this attempt is about to create. A pin is
    /// filed and looked up under this value, so it has to be read once, here,
    /// and reused; see `endpoint_cache::network_fingerprint`.
    network_at_start: Mutex<Option<String>>,
}

/// Hand the previous connect's warp-in-warp hops back to the core, when there
/// is a pin that is still eligible and nothing more authoritative to respect.
///
/// Returns whether a pin was applied. The four refusals are deliberate:
/// a protocol other than gool has the core's own `lastconn` cache and does not
/// need this; `quick_reconnect` off is a user asking for a fresh scan; an
/// explicitly configured peer is a user's own choice, which a cache must never
/// silently override - v2.0.0 honours `AETHER_WG_PEER` for gool, where v1.8.0
/// ignored it, so overriding here would now actually change what they get; and
/// a network that could not be identified gets a full scan, because a pin from
/// one network is worthless on another and guessing is the failure mode.
fn apply_cached_endpoints(
    app: &AppHandle,
    settings: &Settings,
    data_dir: &std::path::Path,
    network: Option<&str>,
    env: &mut std::collections::HashMap<String, String>,
) -> bool {
    if crate::protocol::base_protocol(&settings.protocol) != "gool"
        || !settings.quick_reconnect
        || !settings.peer.trim().is_empty()
    {
        return false;
    }
    let Some(network) = network else {
        return false;
    };
    let Some(endpoints) =
        endpoint_cache::load(data_dir, network, &settings.scan_mode, AETHER_VERSION)
    else {
        return false;
    };
    env.insert("AETHER_WIW_PEERS".into(), endpoints.as_wiw_peers());
    let _ = app.emit(
        "aether-log",
        "[cache] reusing the warp-in-warp endpoints from the previous connect",
    );
    true
}

fn is_aether_environment_key(key: &OsStr) -> bool {
    key.to_string_lossy()
        .to_ascii_uppercase()
        .starts_with("AETHER_")
}

fn inherited_environment_without_aether<I>(
    variables: I,
) -> impl Iterator<Item = (OsString, OsString)>
where
    I: IntoIterator<Item = (OsString, OsString)>,
{
    variables
        .into_iter()
        .filter(|(key, _)| !is_aether_environment_key(key))
}

fn emit_process_stage(app: &AppHandle, settings: &Settings, stage: &str) {
    let attempt = active_attempt()
        .read()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .clone();
    if attempt.attempt_id.is_empty() {
        return;
    }
    emit_timeline(
        app,
        &attempt.attempt_id,
        &settings.protocol,
        &settings.connection_mode,
        stage,
        elapsed_since_ms(0),
    );
}

impl ProcessManager {
    pub async fn start(
        self: &Arc<Self>,
        app: AppHandle,
        settings: Settings,
    ) -> Result<u64, String> {
        self.start_with_options(app, settings, true, None).await
    }

    /// Start the core with a privacy-chain runtime layered onto its environment (ports,
    /// underlay remap, helper state dirs). `None` runs a plain protocol.
    pub async fn start_with_chain(
        self: &Arc<Self>,
        app: AppHandle,
        settings: Settings,
        chain: Option<&crate::chain::ChainRuntime>,
    ) -> Result<u64, String> {
        let allow_cache = crate::protocol::privacy_chain(&settings.protocol)
            == crate::protocol::PrivacyChain::None;
        self.start_with_options(app, settings, allow_cache, chain)
            .await
    }

    /// v2.1.0 chain mode runs everything through the underlay, so endpoint pinning (a
    /// v2.0.0 gool optimization) applies only to plain protocols.

    /// `allow_cached_endpoints` is the difference between the first attempt of a
    /// connect and a retry after one failed: a retry must scan, because the
    /// reason it is retrying is that the pin did not work.
    pub async fn start_with_options(
        self: &Arc<Self>,
        app: AppHandle,
        settings: Settings,
        allow_cached_endpoints: bool,
        chain: Option<&crate::chain::ChainRuntime>,
    ) -> Result<u64, String> {
        emit_process_stage(&app, &settings, "aether_configuration_validation_started");
        settings.validate()?;
        emit_process_stage(&app, &settings, "aether_configuration_validation_finished");
        let runtime_protocol = settings.protocol.clone();
        let mut child_guard = self.child.lock().await;
        if let Some(child) = child_guard.as_mut() {
            if child.try_wait().map_err(display_err)?.is_none() {
                return Err("Aether is already running".into());
            }
        }
        emit_process_stage(&app, &settings, "aether_runtime_directory_prepare_started");
        let data_dir = app.path().app_local_data_dir().map_err(display_err)?;
        std::fs::create_dir_all(&data_dir).map_err(display_err)?;
        emit_process_stage(&app, &settings, "aether_runtime_directory_prepare_finished");
        emit_process_stage(&app, &settings, "aether_log_initialization_started");
        let core_log = open_rotating_core_log(&data_dir)?;
        emit_process_stage(&app, &settings, "aether_log_initialization_finished");
        emit_process_stage(&app, &settings, "aether_environment_prepare_started");
        let mut env = settings.environment_with_chain(&data_dir.join("aether.toml"), chain)?;
        // Privacy helpers keep their state beside the core's working directory: the psiphon
        // data dir and tor consensus cache live under the app data dir, never a temp path.
        if let Some(runtime) = chain {
            if let Some(dir) = runtime.tor_dir_if_needed() {
                let _ = std::fs::create_dir_all(&dir);
            }
        }
        // Read while the tunnel is still down: at this point the default route
        // is the link the core is about to dial out on. After the helper runs it
        // would be our own TUN adapter instead.
        let network = endpoint_cache::network_fingerprint();
        let pinned = allow_cached_endpoints
            && apply_cached_endpoints(&app, &settings, &data_dir, network.as_deref(), &mut env);
        // v2.1.0 statistics: cumulative up/down byte counters on the core's listeners,
        // emitted every 5s. The authoritative traffic source (works in proxy mode too).
        env.insert("AETHER_STATS".into(), "1".into());
        env.insert("AETHER_STATS_SECS".into(), "5".into());
        // v2.1.0 privacy helpers: make sure the core finds its bundled helper binaries.
        // The installer places pt\psiphon-tunnel-core.exe + pt\lyrebird.exe beside
        // aether.exe (verified layout of the official Windows zip); AETHER_PSIPHON_BIN
        // pins the exact file so PATH search can never substitute a different one.
        if chain.is_some() {
            let pt_dir = core_pt_dir(&app);
            let psiphon_bin = pt_dir.join("psiphon-tunnel-core.exe");
            if psiphon_bin.is_file() {
                env.insert(
                    "AETHER_PSIPHON_BIN".into(),
                    psiphon_bin.to_string_lossy().into_owned(),
                );
            }
        }
        emit_process_stage(
            &app,
            &settings,
            if pinned {
                "aether_quick_reconnect_hit"
            } else {
                "aether_quick_reconnect_miss"
            },
        );
        emit_process_stage(&app, &settings, "aether_environment_prepare_finished");
        emit_process_stage(&app, &settings, "aether_binary_resolution_started");
        let core = resolve_core_binary(&app)?;
        emit_process_stage(&app, &settings, "aether_binary_resolution_finished");
        emit_process_stage(&app, &settings, "aether_hash_validation_started");
        // Reuses the verdict `prewarm_integrity_checks` recorded at startup when this
        // is the same file it hashed; re-hashes here otherwise. Either way the core is
        // not spawned below until its pinned digest has matched.
        let version = validate_core_binary_cached(&core)?;
        emit_process_stage(&app, &settings, "aether_hash_validation_finished");
        let _ = app.emit("aether-log", format!("[integrity] {version}"));
        let mut command = Command::new(&core);
        command
            // Start from the current process environment so PATH, SystemRoot, proxy
            // libraries, and other unrelated runtime settings remain available, but
            // never inherit stale AETHER_* feature flags from the launching shell.
            .env_clear()
            .envs(inherited_environment_without_aether(std::env::vars_os()))
            .envs(env)
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .kill_on_drop(true);
        emit_process_stage(&app, &settings, "aether_process_arguments_prepared");
        #[cfg(windows)]
        command.creation_flags(windows_sys::Win32::System::Threading::CREATE_NO_WINDOW);
        emit_status(&app, "scanning", None, Some("Starting Aether".into()));
        emit_process_stage(&app, &settings, "aether_process_spawn_requested");
        emit_process_stage(&app, &settings, "Aether_spawn_requested");
        let mut child = command
            .spawn()
            .map_err(|e| format!("Could not start {}: {e}", core.display()))?;
        emit_process_stage(&app, &settings, "aether_process_pid_created");
        emit_process_stage(&app, &settings, "Aether_pid_created");
        let stdout = child
            .stdout
            .take()
            .ok_or("Could not capture Aether output")?;
        let stderr = child
            .stderr
            .take()
            .ok_or("Could not capture Aether errors")?;
        emit_process_stage(&app, &settings, "aether_stdio_available");
        #[cfg(windows)]
        {
            let pid = child.id().ok_or("Could not identify the Aether process")?;
            match create_kill_on_close_job(pid) {
                Ok(job) => *self.job.lock().await = Some(job),
                Err(error) => {
                    let _ = child.kill().await;
                    let _ = child.wait().await;
                    return Err(error);
                }
            }
        }
        let mut generation = self.generation.lock().await;
        *generation += 1;
        let this_generation = *generation;
        *self.started.lock().await = Some(Instant::now());
        *self.connected.lock().await = false;
        *self.recovering.lock().await = false;
        *self.runtime_protocol.lock().await = Some(runtime_protocol);
        *self.observed_endpoints.lock().await = None;
        *self.started_from_cache.lock().await = pinned;
        *self.network_at_start.lock().await = network;
        self.recent_lines.lock().await.clear();
        *self.core_stats.lock().await = CoreStats::default();
        *child_guard = Some(child);
        drop(child_guard);
        drop(generation);
        spawn_reader(app.clone(), self.clone(), stdout, core_log.clone());
        spawn_reader(app.clone(), self.clone(), stderr, core_log);

        let monitor = self.clone();
        let monitor_app = app.clone();
        tauri::async_runtime::spawn(async move {
            loop {
                sleep(Duration::from_secs(1)).await;
                if *monitor.generation.lock().await != this_generation {
                    break;
                }
                let exited = {
                    let mut guard = monitor.child.lock().await;
                    let status = match guard.as_mut() {
                        Some(child) => child.try_wait().ok().flatten(),
                        None => None,
                    };
                    if status.is_some() {
                        *guard = None;
                    }
                    status
                };
                if let Some(status) = exited {
                    *monitor.started.lock().await = None;
                    *monitor.connected.lock().await = false;
                    *monitor.recovering.lock().await = false;
                    *monitor.runtime_protocol.lock().await = None;
                    monitor.close_job().await;
                    emit_status(
                        &monitor_app,
                        "error",
                        None,
                        Some(format!("Aether exited unexpectedly ({status})")),
                    );
                    break;
                }
            }
        });

        let manager = self.clone();
        // The stall watchdog budget must match the budget the connect flow itself uses
        // for this attempt, or the watchdog kills healthy work in progress. dev.031
        // defect (root cause of "Connection attempt was cancelled" on slow networks): a
        // chain-mode connect reaches its SOCKS listener at ~20s and then spends 60-90s
        // bringing up the system VPN layer (xray, TUN, DNS, routes) while
        // `connection_state()` is still "connecting" — the raw `stall_timeout` (90s)
        // from SPAWN fired mid-routing and stopped the core, which the generation guard
        // then reported as a cancellation. The chain startup allowance (psiphon 120s /
        // tor 300s) is exactly the extra budget `wait_for_core_socks` and the
        // announcement gate already use for the same chain-mode bootstrap; the watchdog
        // now uses the same total. (Computed before the spawn: `chain` is a borrow.)
        //
        // dev.033 Phase 3.3 ordering fix: `wait_for_privacy_announcement` runs AFTER the
        // SOCKS listener is up and waits the FULL `allowance + 15` seconds measured from
        // that moment — so the gate's deadline is (time-to-SOCKS) + allowance + 15 from
        // spawn. On a degraded underlay (measured: wg+tor reached SOCKS only after ~75s
        // of endpoint flap-retries) the old watchdog budget (stall + allowance = 390s)
        // fired while the gate was still legitimately inside its own window and the
        // generation guard turned the kill into the vague "Connection attempt was
        // cancelled" — the honest "The privacy chain did not become ready within N
        // seconds" error never surfaced (physically reproduced in the dev.032 wg+tor
        // failure: 54 min of cycling ending in the cancelled error). The watchdog now
        // adds the gate's exact +15s slack, bounded and chain-aware; nothing else grows.
        let watchdog_budget = match chain {
            Some(runtime) => match runtime.chain {
                crate::protocol::PrivacyChain::Psiphon => {
                    settings.stall_timeout + crate::protocol::PSIPHON_STARTUP_ALLOWANCE_SECS + 15
                }
                crate::protocol::PrivacyChain::Tor => {
                    settings.stall_timeout + crate::protocol::TOR_STARTUP_ALLOWANCE_SECS + 15
                }
                crate::protocol::PrivacyChain::None => settings.stall_timeout,
            },
            None => settings.stall_timeout,
        };
        tauri::async_runtime::spawn(async move {
            sleep(Duration::from_secs(watchdog_budget)).await;
            if *manager.generation.lock().await == this_generation
                && manager.connection_state().await == "connecting"
            {
                if settings.watchdog {
                    let _ = manager.stop().await;
                    emit_status(&app, "error", None, Some(format!("Aether did not open the SOCKS5 listener within {} seconds and was stopped", watchdog_budget)));
                } else {
                    emit_status(
                        &app,
                        "connecting",
                        None,
                        Some(format!(
                            "Aether is still working after {} seconds; watchdog is disabled",
                            watchdog_budget
                        )),
                    );
                }
            }
        });
        Ok(this_generation)
    }

    pub async fn stop(&self) -> Result<(), String> {
        *self.generation.lock().await += 1;
        if let Some(mut child) = self.child.lock().await.take() {
            match child.try_wait() {
                Ok(Some(_)) => {}
                Ok(None) => {
                    if let Err(error) = child.kill().await {
                        if child.try_wait().ok().flatten().is_none() {
                            return Err(display_err(error));
                        }
                    }
                    let _ = child.wait().await;
                }
                Err(error) => {
                    if child.kill().await.is_err() && child.try_wait().ok().flatten().is_none() {
                        return Err(display_err(error));
                    }
                    let _ = child.wait().await;
                }
            }
        }
        *self.started.lock().await = None;
        *self.connected.lock().await = false;
        *self.recovering.lock().await = false;
        *self.runtime_protocol.lock().await = None;
        self.close_job().await;
        Ok(())
    }

    /// Invalidate probe/watchdog tasks before routing teardown begins.
    pub async fn cancel_background_work(&self) {
        *self.generation.lock().await += 1;
    }
    pub async fn mark_connected(&self) {
        *self.connected.lock().await = true;
        *self.recovering.lock().await = false;
    }

    pub async fn mark_unhealthy(&self) {
        *self.connected.lock().await = false;
        *self.recovering.lock().await = false;
    }

    pub async fn mark_recovering(&self) {
        *self.connected.lock().await = false;
        *self.recovering.lock().await = true;
    }

    pub async fn runtime_protocol(&self) -> Option<String> {
        self.runtime_protocol.lock().await.clone()
    }

    /// Whether the running core was started from a cached pin rather than a scan.
    pub async fn started_from_cache(&self) -> bool {
        *self.started_from_cache.lock().await
    }

    /// The hops the running core reported choosing, once it has reported them.
    pub async fn observed_endpoints(&self) -> Option<endpoint_cache::Endpoints> {
        *self.observed_endpoints.lock().await
    }

    /// The network the running attempt dialled out on, as resolved before the
    /// core was spawned. `None` when it could not be identified, in which case
    /// nothing is remembered for it.
    pub async fn network_at_start(&self) -> Option<String> {
        self.network_at_start.lock().await.clone()
    }

    async fn close_job(&self) {
        if let Some(job) = self.job.lock().await.take() {
            close_job_handle(job);
        }
    }
    pub async fn elapsed_secs(&self) -> u64 {
        self.started
            .lock()
            .await
            .as_ref()
            .map(|i| i.elapsed().as_secs())
            .unwrap_or(0)
    }

    pub async fn elapsed_ms(&self) -> u64 {
        self.started
            .lock()
            .await
            .as_ref()
            .map(|started| started.elapsed().as_millis().min(u64::MAX as u128) as u64)
            .unwrap_or(0)
    }

    pub async fn connection_state(&self) -> &'static str {
        let running = match self.child.lock().await.as_mut() {
            Some(child) => child.try_wait().ok().flatten().is_none(),
            None => false,
        };
        if !running {
            "disconnected"
        } else if *self.connected.lock().await {
            "connected"
        } else if *self.recovering.lock().await {
            "reconnecting"
        } else {
            "connecting"
        }
    }

    pub async fn generation(&self) -> u64 {
        *self.generation.lock().await
    }

    pub async fn pid(&self) -> Option<u32> {
        self.child.lock().await.as_ref().and_then(Child::id)
    }

    pub async fn wait_for_generation_change(&self, expected: u64) {
        loop {
            if *self.generation.lock().await != expected {
                return;
            }
            sleep(Duration::from_millis(50)).await;
        }
    }

    pub async fn is_running(&self) -> bool {
        self.child
            .lock()
            .await
            .as_mut()
            .is_some_and(|child| child.try_wait().ok().flatten().is_none())
    }

    pub async fn diagnostic_tail(&self) -> String {
        self.recent_lines
            .lock()
            .await
            .iter()
            .rev()
            .take(12)
            .cloned()
            .collect::<Vec<_>>()
            .into_iter()
            .rev()
            .collect::<Vec<_>>()
            .join(" | ")
    }

    /// Full recent-log ring (privacy-chain announcement parsing walks this; ring holds ~80
    /// lines which comfortably covers a chain bootstrap).
    pub async fn recent_core_lines(&self) -> Vec<String> {
        self.recent_lines.lock().await.iter().cloned().collect()
    }

    /// The gool topology the RUNNING core actually established, derived from the core's
    /// own log proof lines (dev.033 Phase 2.2 topology truthfulness):
    ///   classic:      "[+] outer device=… | inner device=…" (plus `warp-in-warp exit:`)
    ///   masque gool:  "[+] gool: masque device=… carries the wireguard identity"
    /// `None` for non-gool protocols or before either line has appeared. This is the
    /// ground truth for the connected status message — never the stored setting, which
    /// can differ when topology memory or the bounded fallback applied.
    pub async fn runtime_gool_topology(&self) -> Option<&'static str> {
        let lines = self.recent_lines.lock().await;
        let mut masque_carried = false;
        let mut classic = false;
        for line in lines.iter() {
            if line.contains("gool: masque device") {
                masque_carried = true;
            }
            if line.contains("| inner device=") || line.contains("warp-in-warp exit:") {
                classic = true;
            }
        }
        // Whichever proof is newest wins: the ring is chronological, so the LAST seen
        // marker is the topology of the running attempt (a fallback restart re-proves).
        if masque_carried && !classic {
            Some("masque")
        } else if classic && !masque_carried {
            Some("classic")
        } else if masque_carried && classic {
            // Both markers present: prefer the most recent one in the ring.
            let last_masque = lines
                .iter()
                .rposition(|line| line.contains("gool: masque device"));
            let last_classic = lines
                .iter()
                .rposition(|line| line.contains("| inner device="));
            match (last_masque, last_classic) {
                (Some(m), Some(c)) => Some(if m > c { "masque" } else { "classic" }),
                (Some(_), None) => Some("masque"),
                (None, Some(_)) => Some("classic"),
                (None, None) => None,
            }
        } else {
            None
        }
    }

    /// Latest cumulative up/down counters parsed from the core's stats lines.
    pub async fn core_stats(&self) -> CoreStats {
        *self.core_stats.lock().await
    }
}

#[cfg(windows)]
fn create_kill_on_close_job(pid: u32) -> Result<isize, String> {
    use windows_sys::Win32::{
        Foundation::{CloseHandle, HANDLE},
        System::{
            JobObjects::{
                AssignProcessToJobObject, CreateJobObjectW, JobObjectExtendedLimitInformation,
                SetInformationJobObject, JOBOBJECT_EXTENDED_LIMIT_INFORMATION,
                JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE,
            },
            Threading::{OpenProcess, PROCESS_SET_QUOTA, PROCESS_TERMINATE},
        },
    };

    unsafe {
        let job = CreateJobObjectW(std::ptr::null(), std::ptr::null());
        if job.is_null() {
            return Err(format!(
                "Could not create the Aether lifecycle job: {}",
                std::io::Error::last_os_error()
            ));
        }
        let mut limits: JOBOBJECT_EXTENDED_LIMIT_INFORMATION = std::mem::zeroed();
        limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
        if SetInformationJobObject(
            job,
            JobObjectExtendedLimitInformation,
            &limits as *const _ as *const core::ffi::c_void,
            std::mem::size_of_val(&limits) as u32,
        ) == 0
        {
            let error = std::io::Error::last_os_error();
            CloseHandle(job);
            return Err(format!(
                "Could not configure the Aether lifecycle job: {error}"
            ));
        }
        let process: HANDLE = OpenProcess(PROCESS_SET_QUOTA | PROCESS_TERMINATE, 0, pid);
        if process.is_null() {
            let error = std::io::Error::last_os_error();
            CloseHandle(job);
            return Err(format!(
                "Could not open the Aether process for lifecycle management: {error}"
            ));
        }
        let assigned = AssignProcessToJobObject(job, process);
        CloseHandle(process);
        if assigned == 0 {
            let error = std::io::Error::last_os_error();
            CloseHandle(job);
            return Err(format!(
                "Could not assign Aether to its lifecycle job: {error}"
            ));
        }
        Ok(job as isize)
    }
}

#[cfg(not(windows))]
fn close_job_handle(_job: isize) {}

#[cfg(windows)]
fn close_job_handle(job: isize) {
    unsafe {
        windows_sys::Win32::Foundation::CloseHandle(job as _);
    }
}

fn spawn_reader<R>(
    app: AppHandle,
    manager: Arc<ProcessManager>,
    stream: R,
    log: Arc<Mutex<fs::File>>,
) where
    R: tokio::io::AsyncRead + Unpin + Send + 'static,
{
    tauri::async_runtime::spawn(async move {
        let mut lines = BufReader::new(stream).lines();
        while let Ok(Some(line)) = lines.next_line().await {
            let line = sanitize_log_line(&line);
            {
                let mut recent = manager.recent_lines.lock().await;
                if recent.len() == 80 {
                    recent.pop_front();
                }
                recent.push_back(line.clone());
            }
            let _ = app.emit("aether-log", &line);
            {
                let mut file = log.lock().await;
                let _ = file.write_all(line.as_bytes());
                let _ = file.write_all(b"\n");
                let _ = file.flush();
            }
            parse_status(&app, &line);
            // Core v2.1.0 statistics line: "[=] up 291.1 KiB down 482.6 KiB uptime 00:01:30".
            // Counted on the core's own listeners (incl. the 1819 underlay in chain mode),
            // available in BOTH connection modes — the authoritative user-facing counters.
            if let Some(stats) = parse_stats_line(&line) {
                *manager.core_stats.lock().await = stats;
            }
            // Recorded, not yet persisted: the pair is only worth remembering
            // if this attempt goes on to pass data-plane validation.
            if let Some(endpoints) = endpoint_cache::capture_from_log(&line) {
                *manager.observed_endpoints.lock().await = Some(endpoints);
            }
        }
    });
}

fn open_rotating_core_log(data_dir: &std::path::Path) -> Result<Arc<Mutex<fs::File>>, String> {
    let log_dir = data_dir.join("logs");
    fs::create_dir_all(&log_dir).map_err(display_err)?;
    let path = log_dir.join("aether-core.log");
    const MAX_BYTES: u64 = 2 * 1024 * 1024;
    if fs::metadata(&path)
        .map(|m| m.len() > MAX_BYTES)
        .unwrap_or(false)
    {
        let rotated = log_dir.join("aether-core.log.1");
        let _ = fs::remove_file(&rotated);
        fs::rename(&path, rotated).map_err(display_err)?;
    }
    let file = fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(path)
        .map_err(display_err)?;
    Ok(Arc::new(Mutex::new(file)))
}

fn sanitize_log_line(value: &str) -> String {
    let mut result = value.to_string();
    for key in [
        "password",
        "passwd",
        "token",
        "uuid",
        "private_key",
        "private-key",
        "secret",
    ] {
        loop {
            let lower = result.to_ascii_lowercase();
            let Some(start) = lower.find(key) else {
                break;
            };
            let value_start = result[start..]
                .find(['=', ':'])
                .map(|offset| start + offset + 1)
                .unwrap_or(start + key.len());
            let end = result[value_start..]
                .find([',', ' ', '\n', '}', ']'])
                .map(|offset| value_start + offset)
                .unwrap_or(result.len());
            result.replace_range(start..end, "[redacted]");
        }
    }
    result.chars().take(2000).collect()
}

fn parse_status(app: &AppHandle, line: &str) -> bool {
    let lower = line.to_ascii_lowercase();
    if lower.contains("socks5 server listening") || lower.contains("socks5 listening") {
        emit_status(
            app,
            "connecting",
            None,
            Some("SOCKS5 listener is ready; verifying the handshake".into()),
        );
        true
    } else {
        if lower.contains("selected ") && (lower.contains("gateway") || lower.contains("endpoint"))
        {
            emit_status(app, "connecting", extract_socket(line), None);
        } else if lower.contains("reconnecting") || lower.contains("rescanning") {
            emit_status(app, "reconnecting", None, None);
        } else if lower.contains("hunting for") || lower.contains("verifying cached") {
            emit_status(app, "scanning", None, None);
        }
        false
    }
}

fn extract_socket(line: &str) -> Option<String> {
    line.split_whitespace().find_map(|token| {
        let clean = token.trim_matches(|c: char| {
            !c.is_ascii_hexdigit() && c != '.' && c != ':' && c != '[' && c != ']'
        });
        clean
            .parse::<std::net::SocketAddr>()
            .ok()
            .map(|_| clean.to_string())
    })
}
pub fn emit_status(
    app: &AppHandle,
    state: &'static str,
    endpoint: Option<String>,
    message: Option<String>,
) {
    let _ = app.emit(
        "aether-status",
        StatusEvent {
            state,
            endpoint,
            message,
        },
    );
    if let Some(tray) = app.tray_by_id("main") {
        let mut chars = state.chars();
        let label = chars
            .next()
            .map(|c| c.to_uppercase().collect::<String>() + chars.as_str())
            .unwrap_or_default();
        let _ = tray.set_tooltip(Some(format!("Aether - {label}")));
    }
}
/// Where the Aether core may be read from, in priority order.
///
/// The dev path is debug-gated: `CARGO_MANIFEST_DIR` is an absolute path from whatever machine
/// ran the build, so baking it into a shipped image both discloses the build path and invites a
/// release binary to resolve the core out of a source tree that is not there. Extracted so the
/// release build's search path is assertable, as in `routing::xray_candidates`.
fn core_candidates(resource_dir: &std::path::Path, name: &str) -> Vec<std::path::PathBuf> {
    let mut candidates = vec![
        resource_dir.join("binaries").join(name),
        resource_dir.join(name),
    ];
    if cfg!(debug_assertions) {
        candidates.push(
            std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR"))
                .join("../../aether/target/release")
                .join(name),
        );
    }
    candidates
}

fn resolve_core_binary(app: &AppHandle) -> Result<std::path::PathBuf, String> {
    if let Ok(path) = std::env::var("AETHER_GUI_CORE_PATH") {
        return Ok(path.into());
    }
    let name = if cfg!(windows) {
        "aether.exe"
    } else {
        "aether"
    };
    let resource_dir = app.path().resource_dir().map_err(display_err)?;
    core_candidates(&resource_dir, name)
        .into_iter()
        .find(|path| path.is_file())
        .ok_or_else(|| {
            format!(
                "Bundled Aether core was not found at {}. Build it first or set AETHER_GUI_CORE_PATH.",
                resource_dir.join("binaries").join(name).display()
            )
        })
}

/// Directory holding the core's bundled pluggable-transport helpers (pt\). The installer
/// layout places them under `<resource>/binaries/pt/` (Tauri resources), while a dev
/// override (AETHER_GUI_CORE_PATH) or an extracted zip places them beside the core in `pt/`.
/// Both layouts are probed.
fn core_pt_dir(app: &AppHandle) -> std::path::PathBuf {
    let mut candidates: Vec<std::path::PathBuf> = Vec::new();
    if let Ok(core_path) = std::env::var("AETHER_GUI_CORE_PATH") {
        if let Some(parent) = std::path::PathBuf::from(core_path).parent() {
            candidates.push(parent.join("pt"));
        }
    }
    let name = if cfg!(windows) { "aether.exe" } else { "aether" };
    let resource_dir = app.path().resource_dir().unwrap_or_default();
    // Resource layout: binaries/pt (matches tauri.conf resources entries).
    candidates.push(resource_dir.join("binaries").join("pt"));
    // ExternalBin layout: beside the core at the install root.
    for base in [resource_dir.clone(), resource_dir.join("binaries")] {
        for candidate in core_candidates(&base, name) {
            if candidate.is_file() {
                if let Some(parent) = candidate.parent() {
                    candidates.push(parent.join("pt"));
                }
            }
        }
    }
    candidates
        .into_iter()
        .find(|dir| dir.join("psiphon-tunnel-core.exe").is_file())
        .unwrap_or_else(|| resource_dir.join("binaries").join("pt"))
}

/// The read size used when hashing a bundled binary for its integrity check.
///
/// `std::io::copy` from a `File` feeds the hasher through an 8 KiB stack buffer,
/// which is ~4,400 kernel round trips for the 34 MiB routing engine. The digest
/// is identical either way; only the number of round trips changes.
const INTEGRITY_READ_BUFFER: usize = 1024 * 1024;

/// SHA-256 of the whole file, hex encoded.
pub(crate) fn hash_file(path: &std::path::Path) -> Result<String, String> {
    use std::io::Read;
    let mut file = fs::File::open(path).map_err(display_err)?;
    let mut hasher = Sha256::new();
    let mut buffer = vec![0u8; INTEGRITY_READ_BUFFER];
    loop {
        let read = file.read(&mut buffer).map_err(display_err)?;
        if read == 0 {
            break;
        }
        hasher.update(&buffer[..read]);
    }
    Ok(format!("{:x}", hasher.finalize()))
}

fn validate_core_binary(path: &std::path::Path) -> Result<String, String> {
    let actual = hash_file(path)?;
    if actual != AETHER_SHA256 {
        return Err(format!(
            "Bundled Aether integrity check failed: expected {AETHER_SHA256}, got {actual}"
        ));
    }
    Ok(format!("Aether v{AETHER_VERSION} (SHA-256 verified)"))
}

/// A completed integrity check, kept together with the identity of the file it
/// was computed over.
#[derive(Clone)]
struct IntegrityRecord {
    path: std::path::PathBuf,
    len: u64,
    modified: Option<std::time::SystemTime>,
    outcome: Result<String, String>,
}

fn core_integrity_record() -> &'static std::sync::Mutex<Option<IntegrityRecord>> {
    static RECORD: std::sync::OnceLock<std::sync::Mutex<Option<IntegrityRecord>>> =
        std::sync::OnceLock::new();
    RECORD.get_or_init(|| std::sync::Mutex::new(None))
}

fn file_identity(path: &std::path::Path) -> Result<(u64, Option<std::time::SystemTime>), String> {
    let metadata = fs::metadata(path).map_err(display_err)?;
    Ok((metadata.len(), metadata.modified().ok()))
}

/// Verify the bundled core against its pinned digest, reusing the verdict of an
/// earlier verification of the *same* file.
///
/// Reuse is keyed on the file's identity - path, length and modification time -
/// and re-hashes as soon as any of the three has moved, so a core that was
/// swapped after the last check is hashed again rather than inheriting the
/// previous verdict. Refusals are remembered as well as successes: a core that
/// failed its check keeps failing without being re-read on every attempt.
///
/// This is what moves the check off the connect path. `prewarm_integrity_checks`
/// runs it once at application startup, so by the time Connect is pressed the
/// answer is already on record; the check itself is unchanged and still has to
/// have passed before anything is executed.
pub(crate) fn validate_core_binary_cached(path: &std::path::Path) -> Result<String, String> {
    let (len, modified) = file_identity(path)?;
    if let Some(record) = core_integrity_record()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .as_ref()
    {
        if record.path == path && record.len == len && record.modified == modified {
            return record.outcome.clone();
        }
    }
    let outcome = validate_core_binary(path);
    *core_integrity_record()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner()) = Some(IntegrityRecord {
        path: path.to_path_buf(),
        len,
        modified,
        outcome: outcome.clone(),
    });
    outcome
}

/// Hash the shipped executables once, at application startup, on a thread of
/// their own.
///
/// The core's verdict is cached, so the connect path finds it already decided.
/// The routing engine is a different case: it is verified inside the elevated
/// helper, which must not take an unelevated process's word for it, so the
/// helper still hashes `xray.exe` itself on every connection. Reading it here
/// is therefore not a substitute for that check - it pulls the file into the
/// page cache so the helper's own read is served from memory, and it surfaces a
/// tampered engine at startup instead of at the first connection attempt.
pub fn prewarm_integrity_checks(app: &AppHandle) {
    let app = app.clone();
    std::thread::spawn(move || {
        let started = Instant::now();
        let message = match resolve_core_binary(&app).and_then(|core| validate_core_binary_cached(&core)) {
            Ok(version) => format!(
                "[integrity] {version} in {} ms (verified at startup; the connect path reuses this)",
                started.elapsed().as_millis()
            ),
            Err(error) => format!(
                "[integrity] the bundled Aether core failed its integrity check and will not be started: {error}"
            ),
        };
        let _ = app.emit("aether-log", message);
        if let Some(message) = crate::routing::prewarm_engine_integrity() {
            let _ = app.emit("aether-log", message);
        }
    });
}

pub fn verified_core_version(app: &AppHandle) -> Result<String, String> {
    validate_core_binary_cached(&resolve_core_binary(app)?)
}
fn display_err(e: impl std::fmt::Display) -> String {
    e.to_string()
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn finds_ipv4_socket() {
        assert_eq!(
            extract_socket("selected gateway 162.159.1.2:443 (rtt)"),
            Some("162.159.1.2:443".into())
        );
    }
    #[tokio::test]
    async fn stop_is_idempotent() {
        let manager = ProcessManager::default();
        manager.stop().await.unwrap();
        manager.stop().await.unwrap();
    }
    #[tokio::test]
    async fn generation_waiter_is_released_by_stop() {
        let manager = Arc::new(ProcessManager::default());
        let generation = manager.generation().await;
        let waiter = manager.clone();
        let task = tokio::spawn(async move { waiter.wait_for_generation_change(generation).await });
        manager.stop().await.unwrap();
        tokio::time::timeout(Duration::from_secs(1), task)
            .await
            .unwrap()
            .unwrap();
    }
    #[test]
    fn no_false_endpoint() {
        assert_eq!(extract_socket("scanning for a gateway"), None);
    }
    #[test]
    fn core_logs_redact_sensitive_values_before_storage_or_emission() {
        let line = sanitize_log_line("uuid=abc token:secret private_key=key material");
        assert!(!line.contains("abc"));
        assert!(!line.contains(":secret"));
        assert!(!line.contains("=key"));
        assert!(line.contains("[redacted]"));
    }

    #[test]
    fn core_log_rotation_limit_is_bounded() {
        assert_eq!(2 * 1024 * 1024, 2097152);
    }

    #[test]
    fn inherited_environment_filter_removes_aether_flags_only() {
        let variables = vec![
            (OsString::from("PATH"), OsString::from("path-value")),
            (OsString::from("SystemRoot"), OsString::from("windows")),
            (OsString::from("AETHER_GATEWAY"), OsString::from("1")),
            (OsString::from("aether_ech"), OsString::from("stale")),
        ];
        let filtered: Vec<_> = inherited_environment_without_aether(variables).collect();
        assert_eq!(
            filtered,
            vec![
                (OsString::from("PATH"), OsString::from("path-value")),
                (OsString::from("SystemRoot"), OsString::from("windows")),
            ]
        );
    }

    #[test]
    fn pinned_aether_binary_has_expected_hash() {
        let binary = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("binaries/aether-x86_64-pc-windows-msvc.exe");
        assert_eq!(
            validate_core_binary(&binary).unwrap(),
            "Aether v2.3.0 (SHA-256 verified)"
        );
    }
    /// Instrument, not an assertion: prints the wall-clock cost of the shipped
    /// integrity checks so the connect-path budget can be measured rather than
    /// guessed. `#[ignore]` because it is a measurement, and only meaningful in
    /// `--release` (a debug `sha2` is an order of magnitude slower than shipped).
    ///
    ///   cargo test --release --lib measure_integrity_check_cost -- --ignored --nocapture
    #[test]
    #[ignore]
    fn measure_integrity_check_cost() {
        let binaries = [
            ("aether.exe", "binaries/aether-x86_64-pc-windows-msvc.exe"),
            ("xray.exe", "binaries/xray-x86_64-pc-windows-msvc.exe"),
        ];
        /// The pre-change implementation, kept here only as the measurement's
        /// control arm: `std::io::copy` from a `File` uses an 8 KiB stack buffer.
        fn hash_file_via_io_copy(path: &std::path::Path) -> Result<String, String> {
            let mut file = fs::File::open(path).map_err(display_err)?;
            let mut hasher = Sha256::new();
            std::io::copy(&mut file, &mut hasher).map_err(display_err)?;
            Ok(format!("{:x}", hasher.finalize()))
        }

        fn timed(
            samples: usize,
            mut run: impl FnMut() -> Result<String, String>,
        ) -> (Vec<f64>, String) {
            let mut times = Vec::with_capacity(samples);
            let mut digest = String::new();
            for _ in 0..samples {
                let started = Instant::now();
                digest = run().unwrap();
                times.push(started.elapsed().as_secs_f64() * 1000.0);
            }
            times.sort_by(f64::total_cmp);
            (times, digest)
        }

        for (label, relative) in binaries {
            let path = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR")).join(relative);
            if !path.is_file() {
                eprintln!("{label}: not present, skipped");
                continue;
            }
            let mib = fs::metadata(&path).unwrap().len() as f64 / (1024.0 * 1024.0);
            let (before, before_digest) = timed(9, || hash_file_via_io_copy(&path));
            let (after, after_digest) = timed(9, || hash_file(&path));
            // The whole point of the change is that only the cost moves.
            assert_eq!(before_digest, after_digest, "{label} digest changed");
            let median = |s: &[f64]| s[s.len() / 2];
            eprintln!(
                "{label}: {mib:.1} MiB\n  \
                 before (8 KiB io::copy): median {:.1} ms  min {:.1} ms  max {:.1} ms  ({:.0} MiB/s)\n  \
                 after  (1 MiB read)    : median {:.1} ms  min {:.1} ms  max {:.1} ms  ({:.0} MiB/s)\n  \
                 delta at median        : {:+.1} ms  digest {}",
                median(&before),
                before[0],
                before[before.len() - 1],
                mib / (median(&before) / 1000.0),
                median(&after),
                after[0],
                after[after.len() - 1],
                mib / (median(&after) / 1000.0),
                median(&after) - median(&before),
                &after_digest[..16],
            );
        }
    }

    #[test]
    fn a_substituted_aether_core_is_refused_on_the_hash() {
        let planted = std::env::temp_dir().join(format!("aethon-core-{}.exe", std::process::id()));
        fs::copy(std::env::current_exe().unwrap(), &planted).unwrap();
        let error = validate_core_binary(&planted).expect_err("a substituted core must be refused");
        let _ = fs::remove_file(&planted);
        assert!(
            error.contains("integrity check failed"),
            "expected a hash refusal, got: {error}"
        );
    }
    /// The caching wrapper is what the connect path calls, so the refusal has to
    /// survive it - both the first time and on every later attempt, which is the
    /// case a remembered verdict could plausibly get wrong.
    #[test]
    fn a_substituted_aether_core_is_refused_through_the_cached_path() {
        let planted = std::env::temp_dir().join(format!(
            "aethon-cached-refusal-{}-{:?}.exe",
            std::process::id(),
            std::thread::current().id()
        ));
        fs::copy(std::env::current_exe().unwrap(), &planted).unwrap();
        let first = validate_core_binary_cached(&planted);
        let second = validate_core_binary_cached(&planted);
        let _ = fs::remove_file(&planted);
        for outcome in [first, second] {
            let error = outcome.expect_err("a substituted core must be refused");
            assert!(
                error.contains("integrity check failed"),
                "expected a hash refusal, got: {error}"
            );
        }
    }
    /// A remembered verdict must belong to the file that is about to be executed.
    /// Replacing the file behind the same path has to produce a fresh answer, or
    /// the cache would be a way to launder a refused binary into an accepted one.
    #[test]
    fn a_replaced_file_does_not_inherit_the_cached_verdict() {
        let genuine = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("binaries/aether-x86_64-pc-windows-msvc.exe");
        let planted = std::env::temp_dir().join(format!(
            "aethon-cached-identity-{}-{:?}.exe",
            std::process::id(),
            std::thread::current().id()
        ));
        fs::write(&planted, b"not the core").unwrap();
        let refused = validate_core_binary_cached(&planted);
        fs::copy(&genuine, &planted).unwrap();
        let accepted = validate_core_binary_cached(&planted);
        let _ = fs::remove_file(&planted);
        assert!(
            refused.is_err(),
            "the planted file should have been refused first"
        );
        assert_eq!(
            accepted.expect("the genuine core at the same path must be re-hashed and accepted"),
            format!("Aether v{AETHER_VERSION} (SHA-256 verified)")
        );
    }
    #[test]
    fn the_release_build_does_not_search_the_build_tree_for_the_core() {
        let resources = std::path::Path::new(r"C:\Users\someone\AppData\Local\Aethon");
        let candidates = core_candidates(resources, "aether.exe");
        let manifest = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR"));
        let leaks: Vec<_> = candidates
            .iter()
            .filter(|path| path.starts_with(&manifest))
            .collect();
        if cfg!(debug_assertions) {
            assert_eq!(
                leaks.len(),
                1,
                "the dev tree should still be searched in debug"
            );
        } else {
            assert!(
                leaks.is_empty(),
                "a release build must not search the build machine's source tree: {leaks:?}"
            );
            assert_eq!(
                candidates,
                vec![
                    resources.join("binaries").join("aether.exe"),
                    resources.join("aether.exe")
                ],
                "a release build must look in the resource directory and nowhere else"
            );
        }
    }
}
