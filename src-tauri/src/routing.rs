use crate::{
    process::{emit_status, ProcessManager},
    settings::Settings,
};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{
    fs,
    io::Write,
    net::SocketAddr,
    path::{Path, PathBuf},
    process::{Command, Stdio},
    sync::Arc,
    time::{Duration, SystemTime, UNIX_EPOCH},
};
use tauri::{AppHandle, Emitter, Manager};
use tokio::{net::TcpStream, sync::Mutex, time::sleep};

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct RoutingStatus {
    pub state: String,
    pub message: String,
    pub public_ip: String,
    pub engine_pid: u32,
}

#[derive(Debug, Clone, Serialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct TrafficTotals {
    pub uploaded: u64,
    pub downloaded: u64,
    /// VPN-mode TUN interface counters (diagnostics cross-check; None outside a routing
    /// session or on error). The core's listener stats remain the displayed values.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tun_uploaded: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tun_downloaded: Option<u64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct RoutingRequest {
    session_id: String,
    connect_started_ms: u64,
    connect_started_monotonic_ms: u64,
    timeline_id: String,
    session_generation: u64,
    gui_pid: u32,
    socks_address: String,
    routing_mode: String,
    dns_leak_protection: bool,
    ipv6_behavior: String,
    kill_switch: bool,
    tun_mtu: u16,
    #[serde(default)]
    ipv6_upstream: bool,
    split_applications: Vec<String>,
    route_exclusions: Vec<String>,
    session_dir: PathBuf,
    tun_interface: String,
    #[serde(default = "default_tun_backend")]
    tun_backend: String,
    previous_session_dir: Option<PathBuf>,
}

fn default_tun_backend() -> String {
    "xray".into()
}

fn selected_tun_backend() -> String {
    match std::env::var("AETHON_TUN_BACKEND") {
        Ok(value) if value.eq_ignore_ascii_case("tun2proxy") => "tun2proxy".into(),
        _ => default_tun_backend(),
    }
}

struct Session {
    dir: PathBuf,
    tun_interface: String,
    traffic: Arc<Mutex<TrafficCounterState>>,
}

#[derive(Default)]
struct TrafficCounterState {
    baseline: Option<TrafficTotals>,
    interface_index: Option<u32>,
}
#[derive(Default)]
pub struct RoutingManager {
    session: Mutex<Option<Session>>,
    lifecycle: Mutex<()>,
}

impl RoutingManager {
    pub async fn engine_pid(&self) -> Option<u32> {
        let dir = self
            .session
            .lock()
            .await
            .as_ref()
            .map(|session| session.dir.clone())?;
        let pid = read_status(&dir).ok()?.engine_pid;
        (pid > 0 && process_alive(pid)).then_some(pid)
    }

    pub async fn runtime_tun_info(&self) -> (Option<String>, Option<u16>) {
        let session = self.session.lock().await;
        let Some(session) = session.as_ref() else {
            return (None, None);
        };
        let mtu = fs::read(session.dir.join(REQUEST_FILE_NAME))
            .ok()
            .and_then(|value| serde_json::from_slice::<RoutingRequest>(&value).ok())
            .map(|request| request.tun_mtu);
        (Some(session.tun_interface.clone()), mtu)
    }

    pub async fn traffic_totals(&self) -> Result<TrafficTotals, String> {
        let session = self
            .session
            .lock()
            .await
            .as_ref()
            .map(|session| (session.tun_interface.clone(), session.traffic.clone()));
        let Some((tun_interface, traffic)) = session else {
            return Ok(TrafficTotals::default());
        };
        #[cfg(windows)]
        {
            let mut state = traffic.lock().await;
            let (index, current) = match state.interface_index {
                Some(index) => match windows_traffic_totals(index) {
                    Ok(current) => (index, current),
                    Err(_) => resolve_windows_traffic_totals(&tun_interface)?,
                },
                None => resolve_windows_traffic_totals(&tun_interface)?,
            };
            state.interface_index = Some(index);
            let base = state
                .baseline
                .get_or_insert_with(|| current.clone())
                .clone();
            Ok(TrafficTotals {
                uploaded: current.uploaded.saturating_sub(base.uploaded),
                downloaded: current.downloaded.saturating_sub(base.downloaded),
                tun_uploaded: None,
                tun_downloaded: None,
            })
        }
        #[cfg(not(windows))]
        Ok(TrafficTotals::default())
    }

    #[allow(clippy::too_many_arguments)]
    pub async fn start(
        self: &Arc<Self>,
        app: AppHandle,
        settings: &Settings,
        process: Arc<ProcessManager>,
        connect_started_ms: u64,
        connect_started_monotonic_ms: u64,
        timeline_id: String,
        session_generation: u64,
    ) -> Result<(), String> {
        if settings.connection_mode != "vpn" {
            return Ok(());
        }
        let _operation = self.lifecycle.lock().await;
        let existing_dir = self
            .session
            .lock()
            .await
            .as_ref()
            .map(|session| session.dir.clone());
        if let Some(dir) = existing_dir {
            match read_status(&dir).map(|status| status.state) {
                Ok(state) if !matches!(state.as_str(), "error" | "disabled") => {
                    return Err("An Aethon routing session is already active".into());
                }
                _ => {
                    self.session.lock().await.take();
                    cleanup_owned_adapters(None);
                }
            }
        }
        wait_for_socks(
            &settings.socks_address,
            Duration::from_secs(settings.stall_timeout),
        )
        .await?;
        let base = app
            .path()
            .app_local_data_dir()
            .map_err(display_err)?
            .join("routing");
        fs::create_dir_all(&base).map_err(display_err)?;
        // Re-derive the base exactly the way every elevated entry point does, so the
        // GUI and the helper can never disagree about which tree is authoritative.
        // The two agree on Windows by construction; the fallback keeps other
        // platforms, where routing is unsupported anyway, behaving as before.
        let base = routing_base_dir().unwrap_or(base);
        let previous_session_dir = read_recovery_session(&base);
        let session_id = format!(
            "{}-{}",
            std::process::id(),
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap_or_default()
                .as_millis()
        );
        let dir = base.join(&session_id);
        fs::create_dir_all(&dir).map_err(display_err)?;
        let tun_interface = format!(
            "AethonTun-{}-{}",
            std::process::id() % 10000,
            session_id
                .rsplit('-')
                .next()
                .unwrap_or("0")
                .chars()
                .rev()
                .take(6)
                .collect::<String>()
                .chars()
                .rev()
                .collect::<String>()
        );
        let mut request = RoutingRequest {
            session_id,
            connect_started_ms,
            connect_started_monotonic_ms,
            timeline_id,
            session_generation,
            gui_pid: std::process::id(),
            socks_address: settings.socks_address.clone(),
            routing_mode: settings.routing_mode.clone(),
            dns_leak_protection: settings.dns_leak_protection,
            ipv6_behavior: settings.ipv6_behavior.clone(),
            kill_switch: settings.kill_switch,
            tun_mtu: settings.effective_tun_mtu(),
            ipv6_upstream: settings.ipv6_upstream(),
            split_applications: settings.split_applications.clone(),
            route_exclusions: settings.route_exclusions.clone(),
            session_dir: dir.clone(),
            tun_interface,
            tun_backend: selected_tun_backend(),
            previous_session_dir,
        };
        let request_path = dir.join(REQUEST_FILE_NAME);
        request.validate()?;
        // Validate before the privilege boundary as well as behind it: the helper
        // repeats this check, but nothing unsound should ever be written into a file
        // an elevated process is about to read.
        request.authorize_paths(&request_path)?;
        let request = request;
        atomic_json(&request_path, &request)?;
        let recovery =
            json!({"active":true,"sessionDir":request.session_dir,"createdAt":request.session_id});
        atomic_json(&base.join("recovery.json"), &recovery)?;
        if let Some(path) = cli_recovery_path() {
            atomic_json(&path, &recovery)?;
        }
        emit(
            &app,
            "requesting-admin",
            "Administrator permission is required to start the virtual adapter",
        );
        *self.session.lock().await = Some(Session {
            dir: request.session_dir.clone(),
            tun_interface: request.tun_interface.clone(),
            traffic: Arc::new(Mutex::new(TrafficCounterState::default())),
        });
        if let Err(error) = launch_elevated("--routing-helper", &request_path) {
            let _ = write_status(
                &request.session_dir,
                "error",
                &format!("Routing helper could not start: {error}"),
                0,
            );
            self.session.lock().await.take();
            return Err(error);
        }
        // Keep the original 45-second readiness budget while polling more frequently.
        for _ in 0..450 {
            sleep(Duration::from_millis(100)).await;
            if let Ok(status) = read_status(&request.session_dir) {
                let _ = app.emit("routing-status", &status);
                if status.state == "connected" {
                    if let Ok(timeline) =
                        fs::read_to_string(request.session_dir.join("timeline.log"))
                    {
                        for line in timeline.lines().filter(|line| !line.trim().is_empty()) {
                            let _ = app.emit("aether-log", format!("[timeline] {line}"));
                        }
                    }
                    let monitor_dir = request.session_dir.clone();
                    let monitor_app = app.clone();
                    let monitor_manager = self.clone();
                    tauri::async_runtime::spawn(async move {
                        let mut last = String::new();
                        loop {
                            sleep(Duration::from_secs(1)).await;
                            if let Ok(value) = read_status(&monitor_dir) {
                                if value.state != last {
                                    last = value.state.clone();
                                    let _ = monitor_app.emit("routing-status", &value);
                                }
                                if value.state == "error" {
                                    let _ = process.stop().await;
                                    let _ = monitor_manager.stop(&monitor_app).await;
                                    emit_status(
                                        &monitor_app,
                                        "disconnected",
                                        None,
                                        Some(value.message.clone()),
                                    );
                                    break;
                                }
                                if value.state == "disabled" {
                                    break;
                                }
                            }
                        }
                    });
                    return Ok(());
                }
                if status.state == "error" {
                    return Err(sanitize_diagnostic(&status.message));
                }
            }
        }
        Err("The routing helper did not become ready; check Diagnostics for routing.log".into())
    }

    pub async fn stop(&self, app: &AppHandle) -> Result<(), String> {
        let _operation = self.lifecycle.lock().await;
        let session = self.session.lock().await.take();
        let Some(session) = session else {
            return Ok(());
        };
        emit(
            app,
            "restoring",
            "Restoring routes, DNS, and the virtual adapter",
        );
        let control_result =
            atomic_json(&session.dir.join("control.json"), &json!({"action":"stop"}));
        // The helper owns the privileged teardown. Do not release the lifecycle lock or
        // report disconnect complete while it is still restoring routes and removing the TUN.
        // A repair pass is only a fallback, and is itself awaited before allowing reconnect.
        for _ in 0..80 {
            sleep(Duration::from_millis(250)).await;
            if read_status(&session.dir)
                .map(|s| s.state == "disabled")
                .unwrap_or(false)
            {
                return Ok(());
            }
        }
        launch_elevated("--repair-network", &session.dir).map_err(|repair_error| {
            if let Err(control_error) = control_result {
                format!(
                    "Could not signal the routing helper ({control_error}) or launch recovery ({repair_error})"
                )
            } else {
                format!("The routing helper did not stop and recovery failed: {repair_error}")
            }
        })?;
        for _ in 0..80 {
            sleep(Duration::from_millis(250)).await;
            if read_status(&session.dir)
                .map(|s| s.state == "disabled")
                .unwrap_or(false)
            {
                return Ok(());
            }
        }
        Err("The routing helper did not finish cleanup after recovery was requested".into())
    }
}

/// TUN interface octet mapping. From the interface's own perspective, `InOctets` counts
/// bytes the adapter received (for a TUN, the userspace consumer's responses toward the
/// OS = user download) and `OutOctets` counts bytes the adapter sent into the tunnel (OS
/// traffic = user upload). The displayed Home counters come from the core's listener
/// stats; these TUN values serve as the VPN-mode cross-check and are direction-verified
/// empirically during physical validation.
fn traffic_totals_from_octets(in_octets: u64, out_octets: u64) -> TrafficTotals {
    TrafficTotals {
        uploaded: out_octets,
        downloaded: in_octets,
        tun_uploaded: None,
        tun_downloaded: None,
    }
}

#[cfg(windows)]
fn windows_traffic_totals(interface_index: u32) -> Result<TrafficTotals, String> {
    use windows_sys::Win32::NetworkManagement::IpHelper::{GetIfEntry2, MIB_IF_ROW2};

    let mut row: MIB_IF_ROW2 = unsafe { std::mem::zeroed() };
    row.InterfaceIndex = interface_index;
    let result = unsafe { GetIfEntry2(&mut row) };
    if result != 0 {
        return Err(format!(
            "Could not read native counters for interface {interface_index}: Windows error {result}"
        ));
    }
    Ok(traffic_totals_from_octets(row.InOctets, row.OutOctets))
}

#[cfg(windows)]
fn resolve_windows_traffic_totals(interface_name: &str) -> Result<(u32, TrafficTotals), String> {
    use windows_sys::Win32::NetworkManagement::IpHelper::{
        FreeMibTable, GetIfTable2, MIB_IF_TABLE2,
    };

    struct MibTable(*mut MIB_IF_TABLE2);
    impl Drop for MibTable {
        fn drop(&mut self) {
            unsafe { FreeMibTable(self.0.cast()) };
        }
    }

    let mut table = std::ptr::null_mut();
    let result = unsafe { GetIfTable2(&mut table) };
    if result != 0 || table.is_null() {
        return Err(format!(
            "Could not enumerate native Windows network counters: Windows error {result}"
        ));
    }
    let table = MibTable(table);
    let rows = unsafe {
        std::slice::from_raw_parts((*table.0).Table.as_ptr(), (*table.0).NumEntries as usize)
    };
    let row = rows
        .iter()
        .find(|row| {
            row.Alias
                .iter()
                .copied()
                .take_while(|unit| *unit != 0)
                .eq(interface_name.encode_utf16())
        })
        .ok_or_else(|| format!("The Aethon TUN interface '{interface_name}' was not found"))?;
    Ok((
        row.InterfaceIndex,
        traffic_totals_from_octets(row.InOctets, row.OutOctets),
    ))
}

impl RoutingRequest {
    /// Shape validation only — no filesystem access, so it stays cheap and
    /// unit-testable. [`RoutingRequest::authorize_paths`] is the check that guards
    /// the privilege boundary.
    fn validate(&self) -> Result<(), String> {
        let socket: SocketAddr = self
            .socks_address
            .parse()
            .map_err(|_| "Invalid SOCKS5 address")?;
        if !socket.ip().is_loopback() {
            return Err("VPN Mode requires a loopback SOCKS5 listener".into());
        }
        if !matches!(
            self.routing_mode.as_str(),
            "full" | "bypass-local" | "split-include" | "split-exclude"
        ) {
            return Err("Invalid routing mode".into());
        }
        if !matches!(self.ipv6_behavior.as_str(), "tunnel" | "block")
            || !(1280..=9000).contains(&self.tun_mtu)
        {
            return Err("Invalid TUN configuration".into());
        }
        if self
            .session_id
            .contains(|c: char| !c.is_ascii_digit() && c != '-')
        {
            return Err("Invalid session identifier".into());
        }
        if self.connect_started_ms == 0
            || self.timeline_id.len() > 80
            || !self
                .timeline_id
                .chars()
                .all(|c| c.is_ascii_alphanumeric() || c == '-')
        {
            return Err("Invalid connection timeline metadata".into());
        }
        if self
            .session_dir
            .file_name()
            .and_then(|value| value.to_str())
            != Some(&self.session_id)
            || self
                .session_dir
                .parent()
                .and_then(Path::file_name)
                .and_then(|value| value.to_str())
                != Some("routing")
        {
            return Err("Invalid routing session path".into());
        }
        if self.tun_interface.len() > 32
            || !self
                .tun_interface
                .chars()
                .all(|c| c.is_ascii_alphanumeric() || c == '-')
            || !self.tun_interface.starts_with("AethonTun-")
        {
            return Err("Invalid Aethon TUN interface name".into());
        }
        if !matches!(self.tun_backend.as_str(), "xray" | "tun2proxy") {
            return Err("Invalid TUN backend".into());
        }
        if self.tun_backend == "tun2proxy"
            && matches!(
                self.routing_mode.as_str(),
                "split-include" | "split-exclude"
            )
        {
            return Err(
                "The experimental tun2proxy backend does not support per-process split tunnelling"
                    .into(),
            );
        }
        if let Some(previous) = &self.previous_session_dir {
            if previous.parent() != self.session_dir.parent() || previous.file_name().is_none() {
                return Err("Invalid previous routing session path".into());
            }
        }
        for path in &self.split_applications {
            let p = Path::new(path);
            if !p.is_absolute()
                || !p
                    .extension()
                    .and_then(|v| v.to_str())
                    .map(|v| v.eq_ignore_ascii_case("exe"))
                    .unwrap_or(false)
            {
                return Err("Invalid split-tunnel executable".into());
            }
        }
        Ok(())
    }

    /// The privilege-boundary check.
    ///
    /// Every path in this request can steer an elevated process towards a file, and
    /// the request itself arrives as JSON from a directory any unprivileged process
    /// can write. So each path is resolved against the routing base this process
    /// derives for itself, and the request is rewritten to hold the resolved form —
    /// downstream code then works with paths that are already free of `..`
    /// segments, symlinks and junctions rather than re-deriving trust from the
    /// original strings.
    fn authorize_paths(&mut self, request_path: &Path) -> Result<(), String> {
        self.session_dir = authorized_session_dir(&self.session_dir)?;
        if request_path.file_name().and_then(|value| value.to_str()) != Some(REQUEST_FILE_NAME)
            || !request_path
                .parent()
                .is_some_and(|parent| same_path(parent, &self.session_dir))
        {
            return Err("Request path is outside its session directory".into());
        }
        if let Some(previous) = self.previous_session_dir.take() {
            // A stale session that has already been cleaned up is nothing to
            // recover, not a reason to refuse the connection.
            let resolved = authorized_session_dir_if_present(&previous)?;
            if resolved
                .as_deref()
                .is_some_and(|resolved| same_path(resolved, &self.session_dir))
            {
                return Err("Previous routing session path duplicates the active session".into());
            }
            self.previous_session_dir = resolved;
        }
        Ok(())
    }
}

pub async fn wait_for_socks(address: &str, timeout: Duration) -> Result<(), String> {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    let deadline = tokio::time::Instant::now() + timeout;
    loop {
        if let Ok(mut stream) = TcpStream::connect(address).await {
            if stream.write_all(&[5, 1, 0]).await.is_ok() {
                let mut response = [0u8; 2];
                if stream.read_exact(&mut response).await.is_ok() && response == [5, 0] {
                    return Ok(());
                }
            }
        }
        if tokio::time::Instant::now() >= deadline {
            return Err(format!("SOCKS5 handshake timed out at {address}"));
        }
        sleep(Duration::from_millis(300)).await;
    }
}

fn xray_config(request: &RoutingRequest, physical_interface: &str) -> Value {
    let socket: SocketAddr = request.socks_address.parse().unwrap();
    let mut rules =
        vec![json!({"type":"field","process":["aether.exe","xray.exe"],"outboundTag":"direct"})];
    if request.dns_leak_protection {
        rules.push(json!({"type":"field","network":"tcp,udp","port":"53","outboundTag":"aether"}));
    }
    if !request.ipv6_upstream {
        rules.push(json!({"type":"field","network":"tcp,udp","ip":["::/0"],"outboundTag":"block"}));
    }
    if request.routing_mode == "bypass-local" {
        rules.push(json!({"type":"field","ip":["10.0.0.0/8","100.64.0.0/10","127.0.0.0/8","169.254.0.0/16","172.16.0.0/12","192.168.0.0/16","224.0.0.0/4","fc00::/7","fe80::/10","ff00::/8"],"outboundTag":"direct"}));
    }
    for cidr in &request.route_exclusions {
        rules.push(json!({"type":"field","ip":[cidr],"outboundTag":"direct"}));
    }
    let selected_processes: Vec<_> = request
        .split_applications
        .iter()
        .filter_map(|value| Path::new(value).file_name())
        .map(|value| value.to_string_lossy().into_owned())
        .collect();
    if request.routing_mode == "split-include" && !selected_processes.is_empty() {
        rules.push(json!({"type":"field","process":selected_processes,"outboundTag":"aether"}));
    } else if request.routing_mode == "split-exclude" && !selected_processes.is_empty() {
        rules.push(json!({"type":"field","process":selected_processes,"outboundTag":"direct"}));
    }
    let final_tag = if request.routing_mode == "split-include" {
        "direct"
    } else {
        "aether"
    };
    rules.push(json!({"type":"field","network":"tcp,udp","outboundTag":final_tag}));
    json!({
      "log":{"loglevel":"info"},
      "dns":{"servers":["1.1.1.1"],"queryStrategy":"UseIPv4"},
      "inbounds":[{"tag":"tun-in","protocol":"tun","settings":{"name":request.tun_interface,"MTU":request.tun_mtu,"userLevel":0},"sniffing":{"enabled":true,"destOverride":["http","tls","quic"]}}],
      "outbounds":[{"tag":"aether","protocol":"socks","settings":{"servers":[{"address":socket.ip().to_string(),"port":socket.port()}]}},{"tag":"direct","protocol":"freedom","settings":{"domainStrategy":"UseIPv4"},"streamSettings":{"sockopt":{"interface":physical_interface}}},{"tag":"block","protocol":"blackhole","settings":{}}],
      "routing":{"domainStrategy":"AsIs","rules":rules},
      "policy":{"levels":{"0":{"handshake":4,"connIdle":300}}}
    })
}

pub fn helper_main(request_path: &Path) -> Result<(), String> {
    let mut request: RoutingRequest =
        serde_json::from_slice(&fs::read(request_path).map_err(display_err)?)
            .map_err(display_err)?;
    request.validate()?;
    request.authorize_paths(request_path)?;
    let request = request;
    let result = (|| -> Result<(), String> {
        record_routing_stage(&request, "routing_helper_started", None)?;
        // Dispatched before any of the helper's own setup so the engine's SHA-256 is
        // computed alongside it rather than after it. Joined below, before the engine
        // is spawned: nothing is executed until the digest has matched.
        let engine_integrity =
            (request.tun_backend != "tun2proxy").then(dispatch_engine_integrity_check);
        record_routing_stage(&request, "xray_hash_dispatched", None)?;
        let recovered_previous = if let Some(previous) = &request.previous_session_dir {
            record_routing_stage(&request, "stale_session_cleanup_started", None)?;
            recover_owned_session(previous);
            record_routing_stage(&request, "stale_session_cleanup_finished", None)?;
            true
        } else {
            false
        };
        record_routing_stage(&request, "helper_lock_started", None)?;
        let _helper_lock = acquire_helper_lock()?;
        record_routing_stage(&request, "helper_lock_finished", None)?;
        write_status(
            &request.session_dir,
            "preparing",
            "Validating the embedded routing engine",
            0,
        )?;
        record_routing_stage(&request, "owned_adapter_cleanup_started", None)?;
        if recovered_previous || !owned_adapter_requires_recovery(None) {
            record_routing_stage(&request, "owned_adapter_cleanup_skipped", None)?;
        } else {
            cleanup_owned_adapters(None);
        }
        record_routing_stage(&request, "owned_adapter_cleanup_finished", None)?;
        let (engine, version, mut engine_command) = if request.tun_backend == "tun2proxy" {
            let engine = resolve_tun2proxy_beside_current()?;
            ensure_wintun_beside(&engine)?;
            let version = validate_tun2proxy_binary(&engine)?;
            let mut command = Command::new(&engine);
            command
                .arg("--proxy")
                .arg(format!("socks5://{}", request.socks_address))
                .args(["--tun", &request.tun_interface])
                .args(["--dns", "over-tcp", "--dns-addr", "1.1.1.1"])
                .args(["--mtu", &request.tun_mtu.to_string()])
                .args(["--tcp-mss", &request.tun_mtu.saturating_sub(40).to_string()])
                .args(["--verbosity", "info", "--exit-on-fatal-error"]);
            if request.ipv6_upstream {
                command.arg("--ipv6-enabled");
            }
            record_routing_stage(&request, "tun2proxy_config_ready", None)?;
            (engine, version, command)
        } else {
            record_routing_stage(&request, "physical_interface_discovery_started", None)?;
            let physical_interface = default_network_interface()?;
            record_routing_stage(&request, "physical_interface_discovery_finished", None)?;
            record_routing_stage(&request, "xray_path_resolution_started", None)?;
            let engine = resolve_xray_beside_current()?;
            record_routing_stage(&request, "xray_path_resolution_finished", None)?;
            record_routing_stage(&request, "wintun_prepare_started", None)?;
            ensure_wintun_beside(&engine)?;
            record_routing_stage(&request, "wintun_prepare_finished", None)?;
            let config_path = request.session_dir.join("xray.json");
            record_routing_stage(&request, "t5_xray_config_generation_started", None)?;
            let config = xray_config(&request, &physical_interface);
            record_routing_stage(&request, "xray_config_generation_finished", None)?;
            record_routing_stage(&request, "xray_config_write_started", None)?;
            atomic_json(&config_path, &config)?;
            record_routing_stage(&request, "t6_xray_config_ready", None)?;
            record_routing_stage(&request, "t7_xray_integrity_version_started", None)?;
            record_routing_stage(&request, "xray_hash_started", None)?;
            // Join the check dispatched at helper start. Whatever of it is still
            // outstanding is waited for here; the digest must match this exact
            // path before the engine below is executed.
            let version = match engine_integrity {
                Some(handle) => {
                    let (hashed, version) = handle
                        .join()
                        .map_err(|_| "The routing engine integrity check did not complete")??;
                    if hashed != engine {
                        return Err(format!(
                            "Integrity check verified {} but {} was about to be started",
                            hashed.display(),
                            engine.display()
                        ));
                    }
                    version
                }
                None => validate_xray_binary(&engine, None)?,
            };
            record_routing_stage(&request, "xray_hash_finished", None)?;
            record_routing_stage(&request, "xray_version_derived_from_pinned_hash", None)?;
            record_routing_stage(&request, "t8_xray_integrity_version_finished", None)?;
            let mut command = Command::new(&engine);
            command.args(["run", "-c"]).arg(&config_path);
            record_routing_stage(&request, "xray_config_ready", None)?;
            (engine, version, command)
        };
        record_routing_stage(&request, "tun_backend_config_ready", None)?;
        write_status(
            &request.session_dir,
            "starting-adapter",
            "Starting the Aethon virtual adapter",
            0,
        )?;
        let mut log = fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(request.session_dir.join("routing.log"))
            .map_err(display_err)?;
        writeln!(
            log,
            "[Aethon] verified {} backend at {}: {version}",
            request.tun_backend,
            engine.display()
        )
        .map_err(display_err)?;
        if request.tun_backend == "tun2proxy" {
            writeln!(log, "[Aethon] experiment note: packet-only tun2proxy cannot provide Xray's process-aware Aether bypass; loop prevention must be proven by live testing before promotion").map_err(display_err)?;
        }
        let err = log.try_clone().map_err(display_err)?;
        engine_command
            .stdin(Stdio::null())
            .stdout(Stdio::from(log))
            .stderr(Stdio::from(err));
        hide_command_window(&mut engine_command);
        if request.tun_backend == "xray" {
            record_routing_stage(
                &request,
                "xray_config_parse_boundary",
                Some(std::process::id()),
            )?;
            record_routing_stage(
                &request,
                "xray_create_process_started",
                Some(std::process::id()),
            )?;
            record_routing_stage(&request, "tun_creation_requested", Some(std::process::id()))?;
            record_routing_stage(&request, "TUN_started", Some(std::process::id()))?;
        }
        record_routing_stage(&request, "t9_xray_process_spawn_requested", None)?;
        record_routing_stage(&request, "Xray_spawn_requested", None)?;
        let child = match engine_command.spawn() {
            Ok(child) => child,
            Err(error) => {
                cleanup_owned_adapters(None);
                return Err(display_err(error));
            }
        };
        let mut child = SessionChild::new(child);
        record_routing_stage(&request, "t10_xray_process_created", Some(child.id()))?;
        if request.tun_backend == "xray" {
            record_routing_stage(&request, "xray_pid_created", Some(child.id()))?;
            record_routing_stage(&request, "Xray_pid_created", Some(child.id()))?;
            record_routing_stage(&request, "xray_stdio_available", Some(child.id()))?;
            // Xray has no structured readiness IPC. Process creation is the first observable
            // boundary after its validated config is handed to the engine; retain it explicitly
            // so the profile can separate config handoff from adapter readiness.
            record_routing_stage(&request, "xray_configuration_loaded", Some(child.id()))?;
        }
        record_routing_stage(&request, "tun_backend_process_started", Some(child.id()))?;
        record_routing_stage(&request, "tun_backend_process_alive", Some(child.id()))?;
        if request.tun_backend == "xray" {
            record_routing_stage(&request, "xray_process_started", Some(child.id()))?;
            record_routing_stage(&request, "xray_process_alive", Some(child.id()))?;
        } else {
            record_routing_stage(&request, "tun2proxy_process_started", Some(child.id()))?;
            record_routing_stage(&request, "tun2proxy_process_alive", Some(child.id()))?;
        }
        write_status(
            &request.session_dir,
            "configuring-routes",
            "Configuring routes and protected DNS",
            child.id(),
        )?;
        if request.tun_backend == "xray" {
            record_routing_stage(&request, "xray_readiness_check_started", Some(child.id()))?;
            record_routing_stage(&request, "tun_initialization_started", Some(child.id()))?;
        }
        wait_for_tun_ready(
            &mut child,
            &request.session_dir.join("routing.log"),
            &request.tun_interface,
        )?;
        if request.tun_backend == "xray" {
            record_routing_stage(&request, "xray_readiness_check_succeeded", Some(child.id()))?;
        }
        record_routing_stage(&request, "t11_wintun_adapter_appeared", Some(child.id()))?;
        record_routing_stage(&request, "t12_wintun_adapter_usable", Some(child.id()))?;
        if request.tun_backend == "xray" {
            record_routing_stage(&request, "xray_readiness_detected", Some(child.id()))?;
            record_routing_stage(&request, "Xray_ready", Some(child.id()))?;
        }
        record_routing_stage(&request, "wintun_tun_ready", Some(child.id()))?;
        record_routing_stage(&request, "TUN_ready", Some(child.id()))?;
        record_routing_stage(&request, "tun_backend_ready", Some(child.id()))?;
        record_routing_stage(&request, "t13_dns_programming_started", Some(child.id()))?;
        record_routing_stage(&request, "t15_route_programming_started", Some(child.id()))?;
        record_routing_stage(&request, "mtu_setup_started", Some(child.id()))?;
        record_routing_stage(&request, "dns_configuration_started", Some(child.id()))?;
        record_routing_stage(&request, "route_programming_started", Some(child.id()))?;
        configure_tun_and_routes(&request)?;
        record_routing_stage(&request, "mtu_setup_finished", Some(child.id()))?;
        record_routing_stage(&request, "dns_configuration_finished", Some(child.id()))?;
        record_routing_stage(&request, "route_programming_finished", Some(child.id()))?;
        record_routing_stage(&request, "dns_applied", Some(child.id()))?;
        record_routing_stage(&request, "t14_dns_programming_finished", Some(child.id()))?;
        record_routing_stage(&request, "dns_operation_complete", Some(child.id()))?;
        record_routing_stage(&request, "route_operation_complete", Some(child.id()))?;
        record_routing_stage(&request, "routes_installed", Some(child.id()))?;
        record_routing_stage(&request, "t16_route_programming_finished", Some(child.id()))?;
        wait_for_takeover_route_ready(
            &request.tun_interface,
            &mut child,
            &request.session_dir.join("routing.log"),
        )?;
        record_routing_stage(&request, "route_takeover_verified", Some(child.id()))?;
        write_status(
            &request.session_dir,
            "connected",
            "System-wide routing is active",
            child.id(),
        )?;
        record_routing_stage(&request, "routing_connected", Some(child.id()))?;
        record_routing_stage(&request, "t17_routing_fully_ready", Some(child.id()))?;
        record_routing_stage(&request, "routing_ready", Some(child.id()))?;
        let mut last_socks_check = std::time::Instant::now();
        let mut socks_failures = 0u8;
        let mut socks_down = false;
        loop {
            if request.session_dir.join("control.json").exists() || !process_alive(request.gui_pid)
            {
                break;
            }
            if let Some(exit) = child.try_wait().map_err(display_err)? {
                let reason = read_log_tail(&request.session_dir.join("routing.log"));
                return Err(format!(
                    "Routing engine exited unexpectedly. Exit code: {}.{}",
                    exit.code().unwrap_or(1),
                    reason
                ));
            }
            if last_socks_check.elapsed() >= Duration::from_secs(10) {
                last_socks_check = std::time::Instant::now();
                if !sync_socks_ready(&request.socks_address) {
                    socks_failures = socks_failures.saturating_add(1);
                    if socks_failures < 3 {
                        std::thread::sleep(Duration::from_millis(400));
                        continue;
                    }
                    socks_down = true;
                    if request.kill_switch {
                        write_status(
                            &request.session_dir,
                            "reconnecting",
                            "Kill Switch is holding system traffic while Aether recovers",
                            child.id(),
                        )?;
                    } else {
                        write_status(
                            &request.session_dir,
                            "restoring",
                            "Aether is unavailable; fail-open is restoring normal networking",
                            child.id(),
                        )?;
                        break;
                    }
                } else {
                    socks_failures = 0;
                    if socks_down {
                        socks_down = false;
                        write_status(
                            &request.session_dir,
                            "connected",
                            "Aether recovered and system-wide routing resumed",
                            child.id(),
                        )?;
                    }
                }
            }
            std::thread::sleep(Duration::from_millis(400));
        }
        write_status(
            &request.session_dir,
            "restoring",
            "Closing the adapter and restoring Windows routes",
            child.id(),
        )?;
        record_routing_stage(&request, "disconnect_cleanup_started", Some(child.id()))?;
        record_routing_stage(&request, "xray_stop_started", Some(child.id()))?;
        let _ = child.kill();
        let _ = child.wait();
        record_routing_stage(&request, "xray_stop_finished", Some(child.id()))?;
        record_routing_stage(&request, "Xray_stopped", Some(child.id()))?;
        child.disarm();
        record_routing_stage(&request, "adapter_removal_requested", None)?;
        cleanup_owned_adapters(None);
        record_routing_stage(&request, "adapter_removal_finished", None)?;
        record_routing_stage(&request, "routes_removed", None)?;
        record_routing_stage(&request, "DNS_restored", None)?;
        record_routing_stage(&request, "adapter_cleanup_finished", None)?;
        record_routing_stage(&request, "disconnect_cleanup_finished", None)?;
        write_status(
            &request.session_dir,
            "disabled",
            "Windows networking was restored",
            0,
        )?;
        if let Some(base) = request.session_dir.parent() {
            let _ = fs::remove_file(base.join("recovery.json"));
        }
        if let Some(path) = cli_recovery_path() {
            let _ = fs::remove_file(path);
        }
        Ok(())
    })();
    if let Err(error) = &result {
        let message = sanitize_diagnostic(error);
        let _ = write_status(&request.session_dir, "error", &message, 0);
        cleanup_owned_adapters(None);
    }
    result
}

fn wait_for_tun_ready(
    child: &mut std::process::Child,
    log_path: &Path,
    interface_name: &str,
) -> Result<(), String> {
    let deadline = std::time::Instant::now() + Duration::from_secs(20);
    loop {
        if let Some(exit) = child.try_wait().map_err(display_err)? {
            return Err(format!(
                "Routing engine exited unexpectedly. Exit code: {}.{}",
                exit.code().unwrap_or(1),
                read_log_tail(log_path)
            ));
        }
        if tun_adapter_ready(interface_name) {
            return Ok(());
        }
        if std::time::Instant::now() >= deadline {
            let _ = child.kill();
            let _ = child.wait();
            return Err(format!(
                "Routing engine did not create a ready Aethon TUN adapter.{}",
                read_log_tail(log_path)
            ));
        }
        std::thread::sleep(Duration::from_millis(50));
    }
}

fn default_network_interface() -> Result<String, String> {
    #[cfg(windows)]
    {
        if let Ok(interface) = native_default_network_interface() {
            return Ok(interface);
        }
        let script = "$r=Get-NetRoute -AddressFamily IPv4 -DestinationPrefix '0.0.0.0/0' -ErrorAction Stop | Where-Object { $_.NextHop -ne '0.0.0.0' } | Sort-Object RouteMetric,InterfaceMetric | Select-Object -First 1; if(-not $r){exit 2}; (Get-NetAdapter -InterfaceIndex $r.InterfaceIndex -ErrorAction Stop).Name";
        let mut command = Command::new("powershell.exe");
        command.args(["-NoProfile", "-NonInteractive", "-Command", script]);
        hide_command_window(&mut command);
        let output = command.output().map_err(display_err)?;
        let interface = String::from_utf8_lossy(&output.stdout).trim().to_string();
        if !output.status.success()
            || interface.is_empty()
            || interface.contains(['\r', '\n', '\0'])
        {
            return Err(
                "Could not identify the physical Internet interface for Xray loop prevention"
                    .into(),
            );
        }
        Ok(interface)
    }
    #[cfg(not(windows))]
    Err("Xray TUN is only supported on Windows".into())
}

#[cfg(windows)]
fn native_default_network_interface() -> Result<String, String> {
    use windows_sys::Win32::NetworkManagement::IpHelper::{
        GetBestInterface, GetIfEntry2, MIB_IF_ROW2,
    };
    let mut interface_index = 0u32;
    let result =
        unsafe { GetBestInterface(u32::from_ne_bytes([1, 1, 1, 1]), &mut interface_index) };
    if result != 0 || interface_index == 0 {
        return Err(format!(
            "Could not resolve the native default interface: Windows error {result}"
        ));
    }
    let mut row: MIB_IF_ROW2 = unsafe { std::mem::zeroed() };
    row.InterfaceIndex = interface_index;
    let result = unsafe { GetIfEntry2(&mut row) };
    if result != 0 {
        return Err(format!(
            "Could not resolve the native default interface name: Windows error {result}"
        ));
    }
    let alias = String::from_utf16_lossy(
        &row.Alias
            .iter()
            .copied()
            .take_while(|unit| *unit != 0)
            .collect::<Vec<_>>(),
    );
    if alias.is_empty() || alias.starts_with("AethonTun-") || alias == "FirsthamAether" {
        return Err("The native default route did not resolve to a physical interface".into());
    }
    Ok(alias)
}

/// Configure the tunnel and install takeover routes in one netsh invocation. Both operations
/// depend on the same newly-created adapter, so batching removes one shell startup from the
/// connection hot path while preserving their ordering inside netsh.
fn configure_tun_and_routes(request: &RoutingRequest) -> Result<(), String> {
    #[cfg(windows)]
    {
        let interface = &request.tun_interface;
        let mut commands = vec![
            format!("interface ipv4 set address name=\"{interface}\" source=static address=172.19.0.1 mask=255.255.255.252 gateway=none store=active"),
            format!("interface ipv6 add address interface=\"{interface}\" address=fdfe:dcba:9876::1/126 store=active"),
        ];
        if request.dns_leak_protection {
            commands.push(format!("interface ipv4 set dnsservers name=\"{interface}\" source=static address=1.1.1.1 validate=no"));
        }
        for family in ["ipv4", "ipv6"] {
            commands.push(format!(
                "interface {family} set subinterface \"{interface}\" mtu={} store=active",
                request.tun_mtu
            ));
        }
        for prefix in ["0.0.0.0/1", "128.0.0.0/1"] {
            commands.push(format!("interface ipv4 add route prefix={prefix} interface=\"{interface}\" nexthop=172.19.0.2 metric=0 store=active"));
        }
        if request.ipv6_upstream {
            for prefix in ["::/1", "8000::/1"] {
                commands.push(format!("interface ipv6 add route prefix={prefix} interface=\"{interface}\" nexthop=:: metric=0 store=active"));
            }
        }
        // The interface metric is lowered *after* the takeover routes are added.
        //
        // Reversing this was tried, on the theory that adding the /1 routes
        // while the adapter still carried its auto-assigned metric would insert
        // them at a rank they would not keep, so lowering the metric afterwards
        // would invalidate the destination cache and restart `GetBestInterface`
        // convergence at the end of the batch. Measured back to back on one
        // machine and one link, it made no difference: convergence p50 4,546 ms
        // over 50 sessions with the metric set first against p50 4,609 ms over
        // 16 with it set last - 63 ms apart, well inside the run-to-run spread.
        // The order is kept as it was rather than churned on a theory that did
        // not pay; see AETHON_WINDOWS_FINAL_PERFORMANCE_REPORT.md section 8.
        commands.push(format!(
            "interface ipv4 set interface name=\"{interface}\" metric=1"
        ));
        run_netsh_script(
            request.session_dir.as_path(),
            "interface-and-routes.netsh",
            &commands,
            "configure the Aethon TUN interface, protected DNS, and takeover routes",
        )
    }
    #[cfg(not(windows))]
    {
        let _ = request;
        Ok(())
    }
}

#[allow(dead_code)]
fn configure_tun_interface(request: &RoutingRequest) -> Result<(), String> {
    #[cfg(windows)]
    {
        let interface = &request.tun_interface;
        let mut commands = vec![
            format!("interface ipv4 set address name=\"{interface}\" source=static address=172.19.0.1 mask=255.255.255.252 gateway=none store=active"),
            format!("interface ipv6 add address interface=\"{interface}\" address=fdfe:dcba:9876::1/126 store=active"),
        ];
        if request.dns_leak_protection {
            commands.push(format!("interface ipv4 set dnsservers name=\"{interface}\" source=static address=1.1.1.1 validate=no"));
        }
        for family in ["ipv4", "ipv6"] {
            commands.push(format!(
                "interface {family} set subinterface \"{interface}\" mtu={} store=active",
                request.tun_mtu
            ));
        }
        run_netsh_script(
            &request.session_dir,
            "interface.netsh",
            &commands,
            "configure the Aethon TUN interface and protected DNS",
        )
    }
    #[cfg(not(windows))]
    {
        let _ = request;
        Ok(())
    }
}

#[cfg(windows)]
fn run_netsh_script(
    session_dir: &Path,
    file_name: &str,
    commands: &[String],
    action: &str,
) -> Result<(), String> {
    let script_path = session_dir.join(file_name);
    fs::write(&script_path, commands.join("\r\n") + "\r\n").map_err(display_err)?;
    let mut command = Command::new("netsh.exe");
    command.arg("-f").arg(&script_path);
    hide_command_window(&mut command);
    let output = command.output().map_err(display_err)?;
    if output.status.success() {
        Ok(())
    } else {
        let detail = String::from_utf8_lossy(if output.stderr.is_empty() {
            &output.stdout
        } else {
            &output.stderr
        });
        Err(format!("Could not {action}: {}", detail.trim()))
    }
}

/// A competing VPN can install two more-specific /1 routes, which would
/// bypass the Aethon /0 route even though the TUN adapter is ready. Install
/// active-session /1 routes on our adapter so system traffic follows Aethon.
#[allow(dead_code)]
fn install_takeover_routes(request: &RoutingRequest, include_ipv6: bool) -> Result<(), String> {
    #[cfg(windows)]
    {
        let interface_name = &request.tun_interface;
        let mut commands = Vec::new();
        for prefix in ["0.0.0.0/1", "128.0.0.0/1"] {
            commands.push(format!("interface ipv4 add route prefix={prefix} interface=\"{interface_name}\" nexthop=172.19.0.2 metric=0 store=active"));
        }
        if include_ipv6 {
            for prefix in ["::/1", "8000::/1"] {
                commands.push(format!("interface ipv6 add route prefix={prefix} interface=\"{interface_name}\" nexthop=:: metric=0 store=active"));
            }
        }
        commands.push(format!(
            "interface ipv4 set interface name=\"{interface_name}\" metric=1"
        ));
        run_netsh_script(
            &request.session_dir,
            "routes.netsh",
            &commands,
            "install and prioritize the Aethon takeover routes",
        )
    }
    #[cfg(not(windows))]
    {
        let _ = (request, include_ipv6);
        Ok(())
    }
}

struct SessionChild {
    child: std::process::Child,
    armed: bool,
}

impl SessionChild {
    fn new(child: std::process::Child) -> Self {
        Self { child, armed: true }
    }

    fn disarm(&mut self) {
        self.armed = false;
    }
}

impl std::ops::Deref for SessionChild {
    type Target = std::process::Child;

    fn deref(&self) -> &Self::Target {
        &self.child
    }
}

impl std::ops::DerefMut for SessionChild {
    fn deref_mut(&mut self) -> &mut Self::Target {
        &mut self.child
    }
}

impl Drop for SessionChild {
    fn drop(&mut self) {
        if self.armed {
            let _ = self.child.kill();
            let _ = self.child.wait();
        }
    }
}

fn tun_adapter_ready(interface_name: &str) -> bool {
    #[cfg(windows)]
    {
        use windows_sys::Win32::NetworkManagement::IpHelper::{
            FreeMibTable, GetIfTable2, MIB_IF_TABLE2,
        };
        let mut table: *mut MIB_IF_TABLE2 = std::ptr::null_mut();
        if unsafe { GetIfTable2(&mut table) } != 0 || table.is_null() {
            return false;
        }
        let rows = unsafe {
            std::slice::from_raw_parts((*table).Table.as_ptr(), (*table).NumEntries as usize)
        };
        let found = rows.iter().any(|row| {
            row.OperStatus == 1
                && row.Mtu > 0
                && row
                    .Alias
                    .iter()
                    .copied()
                    .take_while(|unit| *unit != 0)
                    .eq(interface_name.encode_utf16())
        });
        unsafe { FreeMibTable(table.cast()) };
        found
    }
    #[cfg(not(windows))]
    {
        let _ = interface_name;
        true
    }
}

/// How long Windows is given to make the Aethon adapter the best interface for
/// a public destination.
///
/// The measured convergence is p50 2,625 ms, p90 3,765 ms and max 6,063 ms
/// across 180 retained helper sessions - a max that sat *above* the 5 s this
/// used to allow, with 3 sessions over it, so the slowest converging machines
/// failed the connect on a tunnel that was about to come up. A later 50-cycle
/// run on a congested cellular link saw convergence reach 8,485 ms, with two
/// consecutive cycles at 7,469 and 7,297 ms that the old deadline would have
/// failed outright. The budget is a deadline, not a delay: the poll returns the
/// instant the route is live, so widening it costs nothing on a fast path and
/// only stops turning a slow one into a failure.
const TAKEOVER_ROUTE_BUDGET: Duration = Duration::from_secs(12);

fn wait_for_takeover_route_ready(
    interface_name: &str,
    child: &mut std::process::Child,
    log_path: &Path,
) -> Result<(), String> {
    let deadline = std::time::Instant::now() + TAKEOVER_ROUTE_BUDGET;
    loop {
        if let Some(exit) = child.try_wait().map_err(display_err)? {
            return Err(format!(
                "Routing engine exited while Windows routes were converging. Exit code: {}.{}",
                exit.code().unwrap_or(1),
                read_log_tail(log_path)
            ));
        }
        if takeover_route_ready(interface_name) {
            return Ok(());
        }
        if std::time::Instant::now() >= deadline {
            return Err(format!(
                "Windows did not activate the Aethon takeover route within {} seconds",
                TAKEOVER_ROUTE_BUDGET.as_secs()
            ));
        }
        std::thread::sleep(Duration::from_millis(50));
    }
}

fn takeover_route_ready(interface_name: &str) -> bool {
    #[cfg(windows)]
    {
        use windows_sys::Win32::NetworkManagement::IpHelper::{
            FreeMibTable, GetBestInterface, GetIfTable2, MIB_IF_TABLE2,
        };
        let mut table: *mut MIB_IF_TABLE2 = std::ptr::null_mut();
        if unsafe { GetIfTable2(&mut table) } != 0 || table.is_null() {
            return false;
        }
        let rows = unsafe {
            std::slice::from_raw_parts((*table).Table.as_ptr(), (*table).NumEntries as usize)
        };
        let expected_index = rows.iter().find_map(|row| {
            let matches = row
                .Alias
                .iter()
                .copied()
                .take_while(|unit| *unit != 0)
                .eq(interface_name.encode_utf16());
            matches.then_some(row.InterfaceIndex)
        });
        unsafe { FreeMibTable(table.cast()) };
        let Some(expected_index) = expected_index else {
            return false;
        };
        let mut actual_index = 0u32;
        let result =
            unsafe { GetBestInterface(u32::from_ne_bytes([1, 1, 1, 1]), &mut actual_index) };
        result == 0 && actual_index == expected_index
    }
    #[cfg(not(windows))]
    {
        let _ = interface_name;
        true
    }
}

fn hide_command_window(command: &mut Command) {
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        command.creation_flags(windows_sys::Win32::System::Threading::CREATE_NO_WINDOW);
    }
    #[cfg(not(windows))]
    {
        let _ = command;
    }
}

#[cfg(windows)]
struct HelperLock(windows_sys::Win32::Foundation::HANDLE);

#[cfg(windows)]
impl Drop for HelperLock {
    fn drop(&mut self) {
        unsafe { windows_sys::Win32::Foundation::CloseHandle(self.0) };
    }
}

#[cfg(not(windows))]
struct HelperLock;

fn acquire_helper_lock() -> Result<HelperLock, String> {
    #[cfg(windows)]
    {
        use windows_sys::Win32::{
            Foundation::{CloseHandle, GetLastError, ERROR_ALREADY_EXISTS},
            System::Threading::CreateMutexW,
        };
        let name = wide("Local\\AethonRoutingHelper");
        for _ in 0..20 {
            let handle = unsafe { CreateMutexW(std::ptr::null(), 0, name.as_ptr()) };
            if handle.is_null() {
                return Err("Could not create the Aethon routing-session lock".into());
            }
            if unsafe { GetLastError() } != ERROR_ALREADY_EXISTS {
                return Ok(HelperLock(handle));
            }
            unsafe { CloseHandle(handle) };
            std::thread::sleep(Duration::from_millis(250));
        }
        Err("Another Aethon routing helper is still active".into())
    }
    #[cfg(not(windows))]
    {
        Ok(HelperLock)
    }
}

fn read_log_tail(path: &Path) -> String {
    let Ok(value) = fs::read_to_string(path) else {
        return String::new();
    };
    let lines: Vec<_> = value.lines().rev().take(12).collect();
    if lines.is_empty() {
        return String::new();
    }
    let reason = lines.into_iter().rev().collect::<Vec<_>>().join(" ");
    let sanitized = sanitize_diagnostic(&reason);
    if sanitized.is_empty() {
        String::new()
    } else {
        format!(" Reason: {sanitized}")
    }
}

/// Tauri's application identifier, copied from `tauri.conf.json`.
///
/// `AppHandle::path().app_local_data_dir()` resolves to
/// `%LOCALAPPDATA%\{APP_IDENTIFIER}` on Windows. The elevated helper is started as
/// a bare CLI process and has no `AppHandle`, so it derives the same location from
/// its own environment rather than trusting the caller to name it.
const APP_IDENTIFIER: &str = "io.github.hamvex.aether-gui";
const REQUEST_FILE_NAME: &str = "request.json";

/// `fs::canonicalize` returns Windows verbatim paths (`\\?\C:\...`). Those are
/// fully resolved, which is what the anchoring checks need, but the routing engine
/// and the elevation command line expect ordinary absolute paths, so the prefix is
/// removed again afterwards.
fn strip_verbatim_prefix(path: &Path) -> PathBuf {
    let text = path.to_string_lossy();
    match text.strip_prefix(r"\\?\") {
        // `\\?\UNC\server\share` means something different without its prefix, so
        // that form is left exactly as it is.
        Some(rest) if !rest.starts_with("UNC\\") => PathBuf::from(rest),
        _ => path.to_path_buf(),
    }
}

/// Windows paths are case-insensitive, so a case difference is not a redirection.
fn same_path(left: &Path, right: &Path) -> bool {
    left.to_string_lossy()
        .eq_ignore_ascii_case(&right.to_string_lossy())
}

/// True when both paths name the same file, allowing for differing spellings of
/// the same location.
fn same_file_path(left: &Path, right: &Path) -> bool {
    same_path(left, right)
        || matches!(
            (fs::canonicalize(left), fs::canonicalize(right)),
            (Ok(left), Ok(right)) if same_path(&left, &right)
        )
}

/// The single directory tree any Aethon process — elevated or not — operates on
/// for routing sessions.
///
/// Derived only from the process environment; nothing a caller supplies can move
/// it. Canonicalising and then comparing against the unresolved form also rejects
/// a directory junction planted on `{APP_IDENTIFIER}` or `routing`, which would
/// otherwise redirect every elevated write out of the application's own tree.
/// Junctions need no privilege to create, so a textual check is not enough.
fn routing_base_dir() -> Result<PathBuf, String> {
    let local =
        std::env::var_os("LOCALAPPDATA").ok_or_else(|| "LOCALAPPDATA is not set".to_string())?;
    let local = strip_verbatim_prefix(&fs::canonicalize(local).map_err(|error| {
        format!("The local application data directory is unavailable: {error}")
    })?);
    let base = local.join(APP_IDENTIFIER).join("routing");
    let resolved = strip_verbatim_prefix(
        &fs::canonicalize(&base)
            .map_err(|error| format!("The Aethon routing directory is unavailable: {error}"))?,
    );
    if !same_path(&resolved, &base) {
        return Err("The Aethon routing directory is redirected and was refused".into());
    }
    Ok(resolved)
}

/// Resolve `candidate` and require it to be a direct child of the routing base.
///
/// This is the only barrier between a user-writable JSON file and an elevated file
/// handle, so it fails closed on every ambiguity. Resolution is what matters:
/// `..` segments, symlinks and directory junctions all survive a check that only
/// inspects path components as text.
fn authorized_session_dir(candidate: &Path) -> Result<PathBuf, String> {
    #[cfg(windows)]
    {
        if !candidate.is_absolute() {
            return Err("Routing session path must be absolute".into());
        }
        let base = routing_base_dir()?;
        let resolved = strip_verbatim_prefix(
            &fs::canonicalize(candidate)
                .map_err(|error| format!("Routing session path is unavailable: {error}"))?,
        );
        if !resolved.is_dir() {
            return Err("Routing session path is not a directory".into());
        }
        if resolved.file_name().is_none()
            || !resolved
                .parent()
                .is_some_and(|parent| same_path(parent, &base))
        {
            return Err("Routing session path is outside the Aethon routing directory".into());
        }
        Ok(resolved)
    }
    #[cfg(not(windows))]
    {
        Ok(candidate.to_path_buf())
    }
}

/// Same check as [`authorized_session_dir`], except that a session directory which
/// no longer exists is simply nothing to recover rather than an error — recovery
/// snapshots routinely outlive the session they describe.
fn authorized_session_dir_if_present(candidate: &Path) -> Result<Option<PathBuf>, String> {
    if !candidate.exists() {
        return Ok(None);
    }
    authorized_session_dir(candidate).map(Some)
}

fn read_recovery_session(base: &Path) -> Option<PathBuf> {
    let value: Value = serde_json::from_slice(&fs::read(base.join("recovery.json")).ok()?).ok()?;
    let session = PathBuf::from(value.get("sessionDir")?.as_str()?);
    // `recovery.json` is user-writable and this path is handed to the elevated
    // helper as `previousSessionDir`, so it is anchored like every other path that
    // crosses the privilege boundary.
    authorized_session_dir_if_present(&session).ok().flatten()
}

fn recover_owned_session(session_dir: &Path) {
    let _ = atomic_json(&session_dir.join("control.json"), &json!({"action":"stop"}));
    if let Ok(status) = read_status(session_dir) {
        if status.engine_pid > 0 {
            terminate_owned_engine(status.engine_pid);
        }
    }
    cleanup_owned_adapters(None);
    let _ = write_status(
        session_dir,
        "disabled",
        "Previous Aethon routing session was recovered",
        0,
    );
}

fn sanitize_diagnostic(value: &str) -> String {
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
        while let Some(start) = result.to_ascii_lowercase().find(key) {
            let end = result[start..]
                .find([',', ' ', '\n', '}'])
                .map(|v| start + v)
                .unwrap_or(result.len());
            result.replace_range(start..end, "[redacted]");
        }
    }
    result.chars().take(1200).collect()
}

pub fn repair_main(session_dir: &Path) -> Result<(), String> {
    // This runs elevated with a path taken straight from the command line, so the
    // path is re-derived and re-anchored here rather than trusted from the caller.
    let base = routing_base_dir()?;
    match authorized_session_dir_if_present(session_dir)? {
        Some(session_dir) => {
            if let Ok(status) = read_status(&session_dir) {
                if status.engine_pid > 0 {
                    terminate_owned_engine(status.engine_pid);
                }
            }
            cleanup_owned_adapters(None);
            write_status(
                &session_dir,
                "disabled",
                "Recovery completed; dynamic TUN routes and DNS filters were released",
                0,
            )?;
        }
        // The session directory is already gone. Releasing the adapter and clearing
        // the snapshots is still the entire point of a repair, so that part runs.
        None => {
            cleanup_owned_adapters(None);
        }
    }
    let _ = fs::remove_file(base.join("recovery.json"));
    if let Some(path) = cli_recovery_path() {
        let _ = fs::remove_file(path);
    }
    Ok(())
}

pub fn repair_cli() -> Result<(), String> {
    match cli_recovery_session() {
        Some(session) => repair_session(&session),
        // No snapshot at all. An unclean shutdown can still leave a TUN adapter behind with its
        // routes and DNS filters attached, and the uninstaller calls this already elevated, so the
        // adapter sweep runs instead of reporting success without touching anything.
        None => {
            if cleanup_owned_adapters(None) == 0 {
                Err("No Aethon network state was found to repair".into())
            } else {
                Ok(())
            }
        }
    }
}

/// Hand a known routing session to the elevated recovery pass.
pub fn repair_session(session_dir: &Path) -> Result<(), String> {
    // The elevated pass re-checks this, but a path that is already known to be
    // outside the routing tree should never reach a UAC prompt in the first place.
    // A session directory that is simply gone is allowed through: `repair_main`
    // recognises that and falls back to clearing adapter state.
    let session_dir = authorized_session_dir_if_present(session_dir)?
        .unwrap_or_else(|| session_dir.to_path_buf());
    launch_elevated("--repair-network", &session_dir)
}

/// True when the machine-wide recovery snapshot is present, so `recovery_status` can report on
/// every snapshot `repair_cli` is able to act on rather than just the app-local copy.
pub fn cli_recovery_exists() -> bool {
    cli_recovery_path().is_some_and(|path| path.exists())
}

fn cli_recovery_session() -> Option<PathBuf> {
    cli_recovery_session_at(&cli_recovery_path()?)
}

fn cli_recovery_session_at(recovery: &Path) -> Option<PathBuf> {
    let value: Value = serde_json::from_slice(&fs::read(recovery).ok()?).ok()?;
    let session = value.get("sessionDir")?.as_str()?;
    if session.is_empty() {
        return None;
    }
    // This file sits in `%LOCALAPPDATA%` and is writable without any privilege,
    // while the path it names is handed to an elevated process. It gets exactly the
    // same anchoring as the app-local snapshot.
    authorized_session_dir_if_present(Path::new(session))
        .ok()
        .flatten()
}
fn cli_recovery_path() -> Option<PathBuf> {
    std::env::var_os("LOCALAPPDATA")
        .map(|v| PathBuf::from(v).join("FirsthamAetherGui-routing-recovery.json"))
}

fn launch_elevated(mode: &str, path: &Path) -> Result<(), String> {
    #[cfg(windows)]
    unsafe {
        use windows_sys::Win32::UI::Shell::ShellExecuteW;
        use windows_sys::Win32::UI::WindowsAndMessaging::SW_HIDE;
        let exe = wide(
            &std::env::current_exe()
                .map_err(display_err)?
                .to_string_lossy(),
        );
        let verb = wide("runas");
        let params = wide(&format!("{mode} \"{}\"", path.display()));
        let result = ShellExecuteW(
            std::ptr::null_mut(),
            verb.as_ptr(),
            exe.as_ptr(),
            params.as_ptr(),
            std::ptr::null(),
            SW_HIDE,
        );
        if result as isize <= 32 {
            return Err("Administrator permission was denied or elevation failed".into());
        }
        Ok(())
    }
    #[cfg(not(windows))]
    {
        let _ = (mode, path);
        Err("System-wide VPN Mode is only supported on Windows".into())
    }
}

#[cfg(windows)]
fn wide(value: &str) -> Vec<u16> {
    use std::os::windows::ffi::OsStrExt;
    std::ffi::OsStr::new(value)
        .encode_wide()
        .chain(Some(0))
        .collect()
}
fn process_alive(pid: u32) -> bool {
    #[cfg(windows)]
    unsafe {
        use windows_sys::Win32::{
            Foundation::CloseHandle,
            System::Threading::{
                GetExitCodeProcess, OpenProcess, PROCESS_QUERY_LIMITED_INFORMATION,
            },
        };
        let h = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid);
        if h.is_null() {
            return false;
        }
        let mut code = 0;
        let ok = GetExitCodeProcess(h, &mut code) != 0 && code == 259;
        CloseHandle(h);
        ok
    }
    #[cfg(not(windows))]
    {
        let _ = pid;
        true
    }
}
/// The executables this installation is allowed to terminate: the two routing
/// engines it ships, resolved the same way the helper resolves them before
/// launching one.
fn owned_engine_paths() -> Vec<PathBuf> {
    [
        resolve_xray_beside_current(),
        resolve_tun2proxy_beside_current(),
    ]
    .into_iter()
    .flatten()
    .collect()
}

/// Terminate `pid`, but only when it is one of this installation's routing engines.
///
/// `engine_pid` is read back from `status.json`, which lives in a user-writable
/// directory, and every caller is elevated. Without an image check, an
/// unprivileged edit of that file would let a repair kill any process on the
/// machine — a security product included. The image is read through the same
/// handle that performs the termination, so the PID cannot be recycled between the
/// check and the kill.
fn terminate_owned_engine(pid: u32) -> bool {
    #[cfg(windows)]
    unsafe {
        use windows_sys::Win32::{
            Foundation::CloseHandle,
            System::Threading::{
                OpenProcess, QueryFullProcessImageNameW, TerminateProcess,
                PROCESS_QUERY_LIMITED_INFORMATION, PROCESS_TERMINATE,
            },
        };
        // 0 is the idle process and 4 is System; neither is ever ours.
        if pid <= 4 {
            return false;
        }
        let allowed = owned_engine_paths();
        if allowed.is_empty() {
            return false;
        }
        let handle = OpenProcess(
            PROCESS_TERMINATE | PROCESS_QUERY_LIMITED_INFORMATION,
            0,
            pid,
        );
        if handle.is_null() {
            return false;
        }
        let mut buffer = [0u16; 1024];
        let mut length = buffer.len() as u32;
        let owned = QueryFullProcessImageNameW(handle, 0, buffer.as_mut_ptr(), &mut length) != 0
            && {
                let image = PathBuf::from(String::from_utf16_lossy(
                    &buffer[..(length as usize).min(buffer.len())],
                ));
                allowed
                    .iter()
                    .any(|candidate| same_file_path(candidate, &image))
            };
        if owned {
            TerminateProcess(handle, 1);
        }
        CloseHandle(handle);
        owned
    }
    #[cfg(not(windows))]
    {
        let _ = pid;
        false
    }
}

/// Remove only adapters Aethon creates. The explicit legacy name is included
/// for upgrades from earlier releases; physical and third-party adapters are untouched.
fn cleanup_owned_adapters(keep: Option<&str>) -> usize {
    #[cfg(windows)]
    {
        let keep = keep.unwrap_or("").replace('\'', "''");
        let script = format!(
            "$keep='{keep}'; $ids=@(Get-NetAdapter -Name 'AethonTun-*','FirsthamAether' -ErrorAction SilentlyContinue | Where-Object Name -ne $keep | ForEach-Object PnPDeviceID); $ids += @(Get-PnpDevice -Class Net -ErrorAction SilentlyContinue | Where-Object {{ $_.FriendlyName -eq 'FirsthamAether' -or $_.FriendlyName -like 'AethonTun-*' }} | ForEach-Object InstanceId); $ids | Where-Object {{ $_ }} | Sort-Object -Unique | ForEach-Object {{ & pnputil.exe /remove-device $_ | Out-Null; $_ }}"
        );
        let mut command = Command::new("powershell.exe");
        command.args([
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-Command",
            &script,
        ]);
        hide_command_window(&mut command);
        command
            .stderr(Stdio::null())
            .output()
            .map(|output| String::from_utf8_lossy(&output.stdout).lines().count())
            .unwrap_or(0)
    }
    #[cfg(not(windows))]
    {
        let _ = keep;
        0
    }
}

fn owned_adapter_requires_recovery(keep: Option<&str>) -> bool {
    #[cfg(windows)]
    {
        use windows_sys::Win32::NetworkManagement::IpHelper::{
            FreeMibTable, GetIfTable2, MIB_IF_TABLE2,
        };
        let keep = keep.unwrap_or("");
        let mut table: *mut MIB_IF_TABLE2 = std::ptr::null_mut();
        if unsafe { GetIfTable2(&mut table) } != 0 || table.is_null() {
            return true;
        }
        let rows = unsafe {
            std::slice::from_raw_parts((*table).Table.as_ptr(), (*table).NumEntries as usize)
        };
        let found = rows.iter().any(|row| {
            let alias = String::from_utf16_lossy(
                &row.Alias
                    .iter()
                    .copied()
                    .take_while(|unit| *unit != 0)
                    .collect::<Vec<_>>(),
            );
            alias != keep
                && (alias.starts_with("AethonTun-") || alias == "FirsthamAether")
                && row.OperStatus == 1
        });
        unsafe { FreeMibTable(table.cast()) };
        found
    }
    #[cfg(not(windows))]
    {
        let _ = keep;
        false
    }
}
fn sync_socks_ready(address: &str) -> bool {
    use std::io::{Read, Write};
    let Ok(addr) = address.parse::<SocketAddr>() else {
        return false;
    };
    let Ok(mut stream) = std::net::TcpStream::connect_timeout(&addr, Duration::from_secs(1)) else {
        return false;
    };
    let _ = stream.set_read_timeout(Some(Duration::from_secs(1)));
    stream.write_all(&[5, 1, 0]).is_ok() && {
        let mut r = [0u8; 2];
        stream.read_exact(&mut r).is_ok() && r == [5, 0]
    }
}
/// Where the routing engine may be read from, in priority order.
///
/// Extracted and debug-gated for the same reason as `wintun_candidates` and
/// `tun2proxy_candidates`: `CARGO_MANIFEST_DIR` is an absolute path from whatever machine ran
/// the build, so baking it into a shipped image both discloses the build path and invites a
/// release binary to resolve an executable out of a source tree that is not there.
fn xray_candidates(current_dir: &Path) -> Vec<PathBuf> {
    let mut candidates = vec![current_dir.join("xray.exe")];
    if cfg!(debug_assertions) {
        candidates.push(
            PathBuf::from(env!("CARGO_MANIFEST_DIR"))
                .join("binaries")
                .join("xray-x86_64-pc-windows-msvc.exe"),
        );
    }
    candidates
}

fn resolve_xray_beside_current() -> Result<PathBuf, String> {
    let exe = std::env::current_exe().map_err(display_err)?;
    let current_dir = exe.parent().ok_or("Invalid application path")?;
    xray_candidates(current_dir)
        .into_iter()
        .find(|path| path.is_file())
        .ok_or_else(|| {
            format!(
                "Bundled routing engine was not found at {}",
                current_dir.join("xray.exe").display()
            )
        })
}

/// Where the experimental tun2proxy helper may be read from, in priority order.
///
/// Extracted so the release build's search path is assertable, for the same reason as
/// `wintun_candidates`: `CARGO_MANIFEST_DIR` is an absolute path from whatever machine ran
/// the build, so baking it into a shipped image both discloses the build path and invites a
/// release binary to resolve a helper out of a source tree that is not there.
fn tun2proxy_candidates(current_dir: &Path) -> Vec<PathBuf> {
    let mut candidates = vec![current_dir.join("tun2proxy.exe")];
    if cfg!(debug_assertions) {
        candidates.push(
            PathBuf::from(env!("CARGO_MANIFEST_DIR"))
                .join("binaries")
                .join("tun2proxy-x86_64-pc-windows-msvc.exe"),
        );
    }
    candidates
}

fn resolve_tun2proxy_beside_current() -> Result<PathBuf, String> {
    let exe = std::env::current_exe().map_err(display_err)?;
    let current_dir = exe.parent().ok_or("Invalid application path")?;
    tun2proxy_candidates(current_dir)
        .into_iter()
        .find(|path| path.is_file())
        .ok_or_else(|| "Experimental tun2proxy helper was not found".into())
}

const XRAY_VERSION: &str = "26.3.27";
const XRAY_SHA256: &str = "15c2d007954ac53ba69b80ec91242786b3c0b71d52649165b4ca1d5cc96ef8f1";
const TUN2PROXY_VERSION: &str = "0.8.3";
const TUN2PROXY_SHA256: &str = "fd45feecd8bfe224edb9c66de453e186669ef0d9ce0803acdd318e7f88ef30db";
const WINTUN_VERSION: &str = "0.14.1";
const WINTUN_SHA256: &str = "e5da8447dc2c320edc0fc52fa01885c103de8c118481f683643cacc3220dafce";
/// Wintun ships EV-signed by its author; that certificate has since expired, but the
/// RFC 3161 countersignature keeps the signature valid, so this is checkable indefinitely.
const WINTUN_PUBLISHER: &str = "WireGuard LLC";

fn validate_xray_binary(path: &Path, request: Option<&RoutingRequest>) -> Result<String, String> {
    if let Some(request) = request {
        record_routing_stage(request, "xray_hash_started", None)?;
    }
    let actual = crate::process::hash_file(path)?;
    if actual != XRAY_SHA256 {
        return Err(format!(
            "Bundled Xray integrity check failed: expected {XRAY_SHA256}, got {actual}"
        ));
    }
    if let Some(request) = request {
        record_routing_stage(request, "xray_hash_finished", None)?;
        record_routing_stage(request, "xray_version_derived_from_pinned_hash", None)?;
    }
    Ok(format!("Xray {XRAY_VERSION} (SHA-256 verified)"))
}

/// The routing engine's integrity check, started on its own thread.
///
/// The elevated helper cannot inherit a verdict from the unelevated GUI, so it
/// hashes `xray.exe` itself on every connection. What it can do is stop waiting
/// for that hash while it has other work to do: this is dispatched as the helper
/// starts and joined immediately before the engine is spawned, so the check
/// overlaps the helper lock, adapter cleanup, interface discovery, the Wintun
/// copy and configuration generation instead of following them.
type EngineIntegrityCheck = std::thread::JoinHandle<Result<(PathBuf, String), String>>;

fn dispatch_engine_integrity_check() -> EngineIntegrityCheck {
    std::thread::spawn(|| {
        let engine = resolve_xray_beside_current()?;
        let version = validate_xray_binary(&engine, None)?;
        Ok((engine, version))
    })
}

/// Read and verify the routing engine from the GUI at application startup.
///
/// This does **not** stand in for the helper's own check - the helper repeats it
/// behind the privilege boundary, because a medium-integrity process's word is
/// not evidence to an elevated one. It exists so that a tampered engine is
/// reported when the application opens rather than in the middle of the first
/// connection, and so the helper's mandatory read is served from the page cache.
pub(crate) fn prewarm_engine_integrity() -> Option<String> {
    let started = std::time::Instant::now();
    let engine = resolve_xray_beside_current().ok()?;
    Some(match validate_xray_binary(&engine, None) {
        Ok(version) => format!(
            "[integrity] {version} in {} ms (read at startup; the elevated helper still verifies it itself)",
            started.elapsed().as_millis()
        ),
        Err(error) => format!(
            "[integrity] the bundled routing engine failed its integrity check and will not be started: {error}"
        ),
    })
}

fn validate_tun2proxy_binary(path: &Path) -> Result<String, String> {
    let actual = crate::process::hash_file(path)?;
    if actual != TUN2PROXY_SHA256 {
        return Err(format!(
            "Experimental tun2proxy integrity check failed: expected {TUN2PROXY_SHA256}, got {actual}"
        ));
    }
    let mut command = Command::new(path);
    command.arg("--version");
    hide_command_window(&mut command);
    let output = command.output().map_err(display_err)?;
    let version = String::from_utf8_lossy(&output.stdout).trim().to_string();
    if !output.status.success() || !version.starts_with(&format!("tun2proxy {TUN2PROXY_VERSION} "))
    {
        return Err(format!(
            "Experimental tun2proxy version mismatch: expected {TUN2PROXY_VERSION}, got {version}"
        ));
    }
    Ok(version)
}

/// Where a bundled wintun.dll may be read from, in priority order.
///
/// Split out from the copy so the release build's search path is assertable: see
/// `the_release_build_does_not_search_the_build_machines_source_tree`.
fn wintun_candidates(current_dir: &Path) -> Vec<PathBuf> {
    let mut candidates = vec![
        current_dir.join("binaries/wintun.dll"),
        current_dir.join("resources/wintun.dll"),
        current_dir.join("resources/binaries/wintun.dll"),
    ];
    // The build tree is a candidate only in a debug build. In a release binary
    // CARGO_MANIFEST_DIR is an absolute path from whatever machine did the build, which is
    // both useless on a user's machine and a build-path disclosure in the shipped image.
    if cfg!(debug_assertions) {
        candidates.push(PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("binaries/wintun.dll"));
    }
    candidates
}

fn ensure_wintun_beside(engine: &Path) -> Result<(), String> {
    let engine_dir = engine.parent().ok_or("Invalid Xray application path")?;
    let destination = engine_dir.join("wintun.dll");
    // Verified even when it is already there. This directory sits under %LOCALAPPDATA% for
    // a currentUser install, so the unprivileged user - and anything running as them - can
    // write to it, and wintun.dll is loaded straight into the Xray process, which holds the
    // Wintun device mutex and therefore runs elevated. Returning early on `is_file()` alone
    // would mean a DLL nobody checked gets mapped into an elevated process.
    if destination.is_file() {
        return validate_wintun(&destination).map(|_| ());
    }
    let current_dir = std::env::current_exe()
        .map_err(display_err)?
        .parent()
        .ok_or("Invalid application path")?
        .to_path_buf();
    let candidates = wintun_candidates(&current_dir);
    let source = candidates
        .iter()
        .find(|path| path.is_file())
        .ok_or("Bundled wintun.dll was not found for Xray")?;
    validate_wintun(source)?;
    fs::copy(source, &destination)
        .map_err(|error| format!("Could not prepare wintun.dll beside Xray: {error}"))?;
    // Re-checked at the destination rather than trusting the copy, because the source and
    // the destination are two different files in a directory the user can write to.
    validate_wintun(&destination).map(|_| ())
}

/// Refuse any wintun.dll that is not the pinned WireGuard build.
///
/// Both checks are required and neither substitutes for the other: the hash says these are
/// the exact bytes this release was tested against, and the signature says WireGuard LLC
/// produced them, which is what still holds if the pin is ever updated.
fn validate_wintun(path: &Path) -> Result<String, String> {
    let mut file = fs::File::open(path).map_err(display_err)?;
    let mut hasher = Sha256::new();
    std::io::copy(&mut file, &mut hasher).map_err(display_err)?;
    let actual = format!("{:x}", hasher.finalize());
    if actual != WINTUN_SHA256 {
        return Err(format!(
            "Bundled wintun.dll integrity check failed: expected {WINTUN_SHA256}, got {actual}"
        ));
    }
    // Verified through the open handle, so the bytes checked are the bytes just hashed and
    // not whatever the path resolves to a moment later.
    let signer = crate::signature::verify(path, Some(&file))
        .map_err(|error| format!("Bundled wintun.dll was rejected: {error}"))?;
    if !signer.is(WINTUN_PUBLISHER) {
        return Err(format!(
            "wintun.dll is signed by \"{}\" but must be signed by \"{WINTUN_PUBLISHER}\"",
            signer.name
        ));
    }
    Ok(format!(
        "Wintun {WINTUN_VERSION} (SHA-256 and publisher verified)"
    ))
}
fn emit(app: &AppHandle, state: &str, message: &str) {
    let _ = app.emit(
        "routing-status",
        RoutingStatus {
            state: state.into(),
            message: message.into(),
            public_ip: String::new(),
            engine_pid: 0,
        },
    );
}
fn read_status(dir: &Path) -> Result<RoutingStatus, String> {
    serde_json::from_slice(&fs::read(dir.join("status.json")).map_err(display_err)?)
        .map_err(display_err)
}
fn write_status(dir: &Path, state: &str, message: &str, pid: u32) -> Result<(), String> {
    atomic_json(
        &dir.join("status.json"),
        &RoutingStatus {
            state: state.into(),
            message: message.into(),
            public_ip: String::new(),
            engine_pid: pid,
        },
    )
}
fn record_routing_stage(
    request: &RoutingRequest,
    stage: &str,
    process_id: Option<u32>,
) -> Result<(), String> {
    let timestamp_ms = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis()
        .min(u64::MAX as u128) as u64;
    let line = json!({
        "timelineId": request.timeline_id,
        "attemptId": request.timeline_id,
        "sessionGeneration": request.session_generation,
        "stage": stage,
        "timestampMs": timestamp_ms,
        "monotonicMs": monotonic_ms(),
        "elapsedMs": monotonic_ms().saturating_sub(request.connect_started_monotonic_ms),
        "processId": process_id.unwrap_or(std::process::id()),
        "result": "success",
        "errorCategory": ""
    });
    let mut file = fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(request.session_dir.join("timeline.log"))
        .map_err(display_err)?;
    writeln!(file, "{line}").map_err(display_err)
}
fn monotonic_ms() -> u64 {
    #[cfg(windows)]
    {
        unsafe { windows_sys::Win32::System::SystemInformation::GetTickCount64() }
    }
    #[cfg(not(windows))]
    {
        static START: std::sync::OnceLock<std::time::Instant> = std::sync::OnceLock::new();
        START
            .get_or_init(std::time::Instant::now)
            .elapsed()
            .as_millis() as u64
    }
}
fn atomic_json(path: &Path, value: &impl Serialize) -> Result<(), String> {
    let tmp = path.with_extension("tmp");
    let mut file = fs::File::create(&tmp).map_err(display_err)?;
    file.write_all(&serde_json::to_vec_pretty(value).map_err(display_err)?)
        .map_err(display_err)?;
    file.sync_all().map_err(display_err)?;
    match fs::rename(&tmp, path) {
        Ok(()) => Ok(()),
        Err(_) if path.exists() => {
            fs::remove_file(path).map_err(display_err)?;
            fs::rename(tmp, path).map_err(display_err)
        }
        Err(error) => Err(display_err(error)),
    }
}
fn display_err(e: impl std::fmt::Display) -> String {
    e.to_string()
}

#[cfg(test)]
mod tests {
    use super::*;
    fn request() -> RoutingRequest {
        RoutingRequest {
            session_id: "1-2".into(),
            connect_started_ms: 1,
            connect_started_monotonic_ms: 1,
            timeline_id: "1-1".into(),
            session_generation: 1,
            gui_pid: 1,
            socks_address: "127.0.0.1:1819".into(),
            routing_mode: "full".into(),
            dns_leak_protection: true,
            ipv6_behavior: "tunnel".into(),
            kill_switch: false,
            tun_mtu: 1500,
            ipv6_upstream: false,
            split_applications: vec![],
            route_exclusions: vec![],
            session_dir: "C:/x".into(),
            tun_interface: format!("AethonTun-{}", std::process::id()),
            tun_backend: "xray".into(),
            previous_session_dir: None,
        }
    }
    #[test]
    fn xray_config_uses_tun_socks_and_loop_prevention() {
        let mut r = request();
        r.routing_mode = "split-include".into();
        r.split_applications = vec!["C:/Program Files/Browser/browser.exe".into()];
        let c = xray_config(&r, "Ethernet").to_string();
        assert!(c.contains("\"protocol\":\"tun\""));
        assert!(c.contains("\"protocol\":\"socks\""));
        assert!(c.contains("127.0.0.1"));
        assert!(c.contains("\"interface\":\"Ethernet\""));
        assert!(c.contains("browser.exe"));
        assert!(c.contains("\"protocol\":\"blackhole\""));
    }

    #[test]
    fn ipv4_only_core_rejects_ipv6_instead_of_black_holing_it() {
        let mut r = request();
        r.ipv6_upstream = false;
        let blocked = xray_config(&r, "Ethernet").to_string();
        assert!(blocked.contains("\"ip\":[\"::/0\"]"));
        assert!(blocked.contains("\"outboundTag\":\"block\""));
        r.ipv6_upstream = true;
        let tunnelled = xray_config(&r, "Ethernet").to_string();
        assert!(!tunnelled.contains("\"ip\":[\"::/0\"]"));
    }

    #[test]
    fn routing_diagnostics_redact_credentials() {
        let message =
            sanitize_diagnostic("startup failed password=hunter2 token=abc123 invalid option");
        assert!(!message.contains("hunter2"));
        assert!(!message.contains("abc123"));
        assert!(message.contains("[redacted]"));
    }
    #[test]
    fn routing_diagnostics_redact_every_secret_occurrence() {
        let message = sanitize_diagnostic("token=one token=two password=three password=four");
        assert!(!message.contains("one"));
        assert!(!message.contains("two"));
        assert!(!message.contains("three"));
        assert!(!message.contains("four"));
    }
    #[test]
    fn native_rx_and_tx_counters_keep_download_and_upload_separate() {
        let totals = traffic_totals_from_octets(9_000, 2_000);
        assert_eq!(totals.downloaded, 9_000);
        assert_eq!(totals.uploaded, 2_000);
    }
    #[test]
    fn atomic_json_replaces_existing_status() {
        let dir = std::env::temp_dir().join(format!("aethon-routing-test-{}", std::process::id()));
        fs::create_dir_all(&dir).unwrap();
        let path = dir.join("status.json");
        atomic_json(&path, &json!({"state":"preparing"})).unwrap();
        atomic_json(&path, &json!({"state":"connected"})).unwrap();
        let value: Value = serde_json::from_slice(&fs::read(&path).unwrap()).unwrap();
        assert_eq!(value["state"], "connected");
        let _ = fs::remove_dir_all(dir);
    }
    #[test]
    fn pinned_engine_accepts_generated_configuration() {
        let engine = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("binaries/xray-x86_64-pc-windows-msvc.exe");
        if !engine.exists() {
            return;
        }
        let path =
            std::env::temp_dir().join(format!("aethon-xray-check-{}.json", std::process::id()));
        fs::write(
            &path,
            serde_json::to_vec(&xray_config(&request(), "Ethernet")).unwrap(),
        )
        .unwrap();
        let output = Command::new(engine)
            .args(["run", "-test", "-c"])
            .arg(&path)
            .output()
            .unwrap();
        let _ = fs::remove_file(path);
        let combined = format!(
            "{}{}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr)
        );
        // `-test` loads the configuration and then builds the server, and building
        // a TUN inbound needs the Wintun device mutex, which needs Administrator.
        // The two stages report differently, so an unelevated run still proves what
        // this test is for: a configuration fault says
        // "failed to load config files", while reaching "failed to create server"
        // means the generated configuration was accepted in full.
        let configuration_accepted = output.status.success()
            || (combined.contains("failed to create server")
                && combined.contains("Access is denied"));
        assert!(
            configuration_accepted,
            "generated configuration was rejected: {combined}"
        );
        assert!(
            !combined.contains("failed to load config files"),
            "generated configuration failed to load: {combined}"
        );
    }

    #[test]
    fn pinned_xray_binary_has_expected_version_and_hash() {
        let engine = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("binaries/xray-x86_64-pc-windows-msvc.exe");
        if engine.exists() {
            assert!(validate_xray_binary(&engine, None)
                .unwrap()
                .starts_with("Xray 26.3.27 "));
        }
    }

    #[test]
    fn experimental_tun2proxy_binary_has_expected_version_and_hash() {
        // Conditional because the helper is not shipped and, as of the PHASE 6 review, is not
        // present in this tree either: its provenance could not be established, so it was
        // moved to the evidence store rather than left in the bundler's input directory. See
        // security-evidence/logs/phase6-external-binaries.txt. The assertion still runs on any
        // machine that has deliberately placed a copy here.
        let engine = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("binaries/tun2proxy-x86_64-pc-windows-msvc.exe");
        if engine.exists() {
            assert!(validate_tun2proxy_binary(&engine)
                .unwrap()
                .starts_with("tun2proxy 0.8.3 "));
        }
    }

    #[test]
    fn a_substituted_tun2proxy_is_refused_on_the_hash() {
        // Unconditional, unlike the test above: this one plants its own file, so it holds the
        // hash gate in place whether or not a real helper is present.
        let planted =
            std::env::temp_dir().join(format!("aethon-tun2proxy-{}.exe", std::process::id()));
        fs::copy(std::env::current_exe().unwrap(), &planted).unwrap();
        let error =
            validate_tun2proxy_binary(&planted).expect_err("a substituted helper must be refused");
        let _ = fs::remove_file(&planted);
        assert!(
            error.contains("integrity check failed"),
            "expected a hash refusal, got: {error}"
        );
    }

    #[test]
    fn the_release_build_does_not_search_the_build_tree_for_tun2proxy() {
        let install = Path::new(r"C:\Users\someone\AppData\Local\Aethon");
        let candidates = tun2proxy_candidates(install);
        let manifest = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
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
                vec![install.join("tun2proxy.exe")],
                "a release build must look beside the executable and nowhere else"
            );
        }
    }

    #[test]
    fn the_release_build_does_not_search_the_build_tree_for_xray() {
        let install = Path::new(r"C:\Users\someone\AppData\Local\Aethon");
        let candidates = xray_candidates(install);
        let manifest = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
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
                vec![install.join("xray.exe")],
                "a release build must look beside the executable and nowhere else"
            );
        }
    }

    #[cfg(windows)]
    #[test]
    fn bundled_wintun_matches_its_pinned_hash_and_publisher() {
        let wintun = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("binaries/wintun.dll");
        if wintun.exists() {
            assert_eq!(
                validate_wintun(&wintun).unwrap(),
                "Wintun 0.14.1 (SHA-256 and publisher verified)"
            );
        }
    }

    #[cfg(windows)]
    #[test]
    fn a_substituted_wintun_is_refused_on_the_hash() {
        // The realistic attack is not a corrupt DLL but a valid, signed, *different* one
        // dropped into the engine directory. The hash is what distinguishes the build this
        // release was tested against from any other, so it has to be the first gate.
        let planted =
            std::env::temp_dir().join(format!("aethon-wintun-{}.dll", std::process::id()));
        fs::copy(std::env::current_exe().unwrap(), &planted).unwrap();
        let error = validate_wintun(&planted).expect_err("a substituted wintun must be refused");
        let _ = fs::remove_file(&planted);
        assert!(
            error.contains("integrity check failed"),
            "expected a hash refusal, got: {error}"
        );
    }

    #[cfg(windows)]
    #[test]
    fn a_missing_wintun_is_refused_rather_than_skipped() {
        let missing = std::env::temp_dir().join("aethon-wintun-does-not-exist-4b1e77.dll");
        assert!(validate_wintun(&missing).is_err());
    }

    #[test]
    fn the_release_build_does_not_search_the_build_machines_source_tree() {
        // CARGO_MANIFEST_DIR is an absolute path on whichever machine ran the build. It is
        // useful in a dev tree and is both dead weight and a path disclosure in a shipped
        // binary, so it must only ever be consulted in a debug build.
        let install = Path::new(r"C:\Users\someone\AppData\Local\Aethon");
        let candidates = wintun_candidates(install);
        let manifest = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
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
        }
        assert!(
            candidates
                .iter()
                .all(|path| path.file_name().unwrap() == "wintun.dll"),
            "every candidate must name wintun.dll: {candidates:?}"
        );
    }

    // ---------------------------------------------------------------------------
    // Privileged-path regression tests.
    //
    // Every path below reaches an elevated process, and every one of them is read
    // from a directory an unprivileged process can write. These tests exist because
    // the shape-only checks they replace accepted paths anywhere on the disk.
    // ---------------------------------------------------------------------------

    #[test]
    fn verbatim_prefix_is_removed_but_unc_paths_are_left_alone() {
        assert_eq!(
            strip_verbatim_prefix(Path::new(r"\\?\C:\Users\x\AppData\Local")),
            PathBuf::from(r"C:\Users\x\AppData\Local")
        );
        assert_eq!(
            strip_verbatim_prefix(Path::new(r"\\?\UNC\server\share")),
            PathBuf::from(r"\\?\UNC\server\share")
        );
        assert_eq!(
            strip_verbatim_prefix(Path::new(r"C:\already\plain")),
            PathBuf::from(r"C:\already\plain")
        );
    }

    #[test]
    fn paths_compare_case_insensitively_because_windows_does() {
        assert!(same_path(
            Path::new(r"C:\Users\X"),
            Path::new(r"c:\users\x")
        ));
        assert!(!same_path(
            Path::new(r"C:\Users\X"),
            Path::new(r"C:\Users\Y")
        ));
    }

    /// A real directory under the authoritative routing base, removed on drop, so
    /// the anchoring checks have something legitimate to accept.
    #[cfg(windows)]
    struct BaseChild(PathBuf);

    #[cfg(windows)]
    impl BaseChild {
        fn new(tag: &str) -> Self {
            let local = std::env::var_os("LOCALAPPDATA").expect("LOCALAPPDATA");
            let base = PathBuf::from(local).join(APP_IDENTIFIER).join("routing");
            fs::create_dir_all(&base).expect("create routing base");
            let dir = routing_base_dir()
                .expect("routing base")
                .join(format!("999{tag}-{}", std::process::id()));
            fs::create_dir_all(&dir).expect("create session dir");
            Self(dir)
        }

        fn path(&self) -> &Path {
            &self.0
        }

        fn name(&self) -> String {
            self.0.file_name().unwrap().to_string_lossy().into_owned()
        }
    }

    #[cfg(windows)]
    impl Drop for BaseChild {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }

    #[cfg(windows)]
    #[test]
    fn routing_base_is_derived_from_the_environment_not_from_input() {
        let session = BaseChild::new("1");
        let base = routing_base_dir().unwrap();
        assert!(base.ends_with(Path::new(APP_IDENTIFIER).join("routing")));
        assert!(same_path(base.parent().unwrap().parent().unwrap(), &{
            let local = std::env::var_os("LOCALAPPDATA").unwrap();
            strip_verbatim_prefix(&fs::canonicalize(local).unwrap())
        }));
        assert!(same_path(session.path().parent().unwrap(), &base));
    }

    #[cfg(windows)]
    #[test]
    fn authorized_session_dir_accepts_a_real_child_of_the_base() {
        let session = BaseChild::new("2");
        let resolved = authorized_session_dir(session.path()).unwrap();
        assert!(same_path(&resolved, session.path()));
        assert!(!resolved.to_string_lossy().starts_with(r"\\?\"));
    }

    #[cfg(windows)]
    #[test]
    fn authorized_session_dir_rejects_paths_outside_the_routing_base() {
        // The exact shape the previous check accepted: a directory whose last two
        // components spell `routing\<session id>` but which lives anywhere at all.
        let outside = std::env::temp_dir()
            .join(format!("aethon-anchor-{}", std::process::id()))
            .join("routing")
            .join("1-2");
        fs::create_dir_all(&outside).unwrap();
        let error = authorized_session_dir(&outside).unwrap_err();
        assert!(
            error.contains("outside the Aethon routing directory"),
            "unexpected error: {error}"
        );
        let _ = fs::remove_dir_all(outside.parent().unwrap().parent().unwrap());
    }

    #[cfg(windows)]
    #[test]
    fn authorized_session_dir_rejects_relative_and_missing_and_nested_paths() {
        assert!(authorized_session_dir(Path::new(r"routing\1-2"))
            .unwrap_err()
            .contains("must be absolute"));
        let base = routing_base_dir().unwrap();
        assert!(authorized_session_dir(&base.join("does-not-exist-1-2"))
            .unwrap_err()
            .contains("unavailable"));
        // A grandchild is not a child: only one level below the base is a session.
        let session = BaseChild::new("3");
        let nested = session.path().join("nested");
        fs::create_dir_all(&nested).unwrap();
        assert!(authorized_session_dir(&nested)
            .unwrap_err()
            .contains("outside the Aethon routing directory"));
    }

    #[cfg(windows)]
    #[test]
    fn traversal_out_of_the_base_is_resolved_and_then_refused() {
        let base = routing_base_dir().unwrap();
        let escape = base.join("..").join("..");
        let error = authorized_session_dir(&escape).unwrap_err();
        assert!(
            error.contains("outside the Aethon routing directory"),
            "unexpected error: {error}"
        );
    }

    #[cfg(windows)]
    #[test]
    fn a_session_directory_that_is_gone_is_nothing_to_recover_not_an_error() {
        let base = routing_base_dir().unwrap();
        assert_eq!(
            authorized_session_dir_if_present(&base.join("already-removed-1-2")).unwrap(),
            None
        );
        // Absent is tolerated; present-but-elsewhere still is not.
        let outside = std::env::temp_dir().join(format!("aethon-absent-{}", std::process::id()));
        fs::create_dir_all(&outside).unwrap();
        assert!(authorized_session_dir_if_present(&outside).is_err());
        let _ = fs::remove_dir_all(outside);
    }

    #[cfg(windows)]
    #[test]
    fn authorize_paths_refuses_a_session_directory_outside_the_base() {
        let outside = std::env::temp_dir()
            .join(format!("aethon-request-{}", std::process::id()))
            .join("routing")
            .join("1-2");
        fs::create_dir_all(&outside).unwrap();
        let mut r = request();
        r.session_dir = outside.clone();
        // Shape validation passes — that is exactly the gap this check closes.
        assert!(r.validate().is_ok(), "shape check should still accept it");
        let error = r
            .authorize_paths(&outside.join(REQUEST_FILE_NAME))
            .unwrap_err();
        assert!(
            error.contains("outside the Aethon routing directory"),
            "unexpected error: {error}"
        );
        let _ = fs::remove_dir_all(outside.parent().unwrap().parent().unwrap());
    }

    #[cfg(windows)]
    #[test]
    fn authorize_paths_accepts_a_genuine_session_and_resolves_it() {
        let session = BaseChild::new("4");
        let mut r = request();
        r.session_id = session.name();
        r.session_dir = session.path().to_path_buf();
        r.validate().unwrap();
        r.authorize_paths(&session.path().join(REQUEST_FILE_NAME))
            .unwrap();
        assert!(same_path(&r.session_dir, session.path()));
    }

    #[cfg(windows)]
    #[test]
    fn authorize_paths_requires_the_request_file_to_live_in_its_own_session() {
        let session = BaseChild::new("5");
        let other = BaseChild::new("6");
        let mut r = request();
        r.session_id = session.name();
        r.session_dir = session.path().to_path_buf();
        assert!(r
            .clone()
            .authorize_paths(&session.path().join("control.json"))
            .unwrap_err()
            .contains("outside its session directory"));
        assert!(r
            .authorize_paths(&other.path().join(REQUEST_FILE_NAME))
            .unwrap_err()
            .contains("outside its session directory"));
    }

    #[cfg(windows)]
    #[test]
    fn authorize_paths_drops_a_stale_previous_session_but_refuses_a_foreign_one() {
        let session = BaseChild::new("7");
        let mut r = request();
        r.session_id = session.name();
        r.session_dir = session.path().to_path_buf();
        let request_path = session.path().join(REQUEST_FILE_NAME);

        let mut stale = r.clone();
        stale.previous_session_dir = Some(routing_base_dir().unwrap().join("1-2"));
        stale.authorize_paths(&request_path).unwrap();
        assert_eq!(stale.previous_session_dir, None, "stale session dropped");

        let foreign = std::env::temp_dir().join(format!("aethon-previous-{}", std::process::id()));
        fs::create_dir_all(&foreign).unwrap();
        r.previous_session_dir = Some(foreign.clone());
        assert!(r
            .authorize_paths(&request_path)
            .unwrap_err()
            .contains("outside the Aethon routing directory"));
        let _ = fs::remove_dir_all(foreign);
    }

    #[cfg(windows)]
    #[test]
    fn the_machine_wide_recovery_snapshot_is_anchored_before_it_is_used() {
        let session = BaseChild::new("8");
        let snapshot =
            std::env::temp_dir().join(format!("aethon-cli-recovery-{}.json", std::process::id()));

        // The F-W8 case: an unprivileged write to this file used to steer the
        // elevated repair pass at any directory on the machine.
        let outside =
            std::env::temp_dir().join(format!("aethon-cli-target-{}", std::process::id()));
        fs::create_dir_all(&outside).unwrap();
        atomic_json(&snapshot, &json!({"active": true, "sessionDir": outside})).unwrap();
        assert_eq!(cli_recovery_session_at(&snapshot), None);

        atomic_json(
            &snapshot,
            &json!({"active": true, "sessionDir": session.path()}),
        )
        .unwrap();
        assert_eq!(
            cli_recovery_session_at(&snapshot).as_deref(),
            Some(session.path())
        );

        atomic_json(&snapshot, &json!({"active": true, "sessionDir": ""})).unwrap();
        assert_eq!(cli_recovery_session_at(&snapshot), None);

        let _ = fs::remove_file(snapshot);
        let _ = fs::remove_dir_all(outside);
    }

    #[cfg(windows)]
    #[test]
    fn the_app_local_recovery_snapshot_is_anchored_before_it_is_used() {
        let session = BaseChild::new("9");
        let base =
            std::env::temp_dir().join(format!("aethon-local-recovery-{}", std::process::id()));
        fs::create_dir_all(&base).unwrap();
        let outside = base.join("routing").join("1-2");
        fs::create_dir_all(&outside).unwrap();

        atomic_json(
            &base.join("recovery.json"),
            &json!({"active": true, "sessionDir": outside}),
        )
        .unwrap();
        assert_eq!(read_recovery_session(&base), None);

        atomic_json(
            &base.join("recovery.json"),
            &json!({"active": true, "sessionDir": session.path()}),
        )
        .unwrap();
        assert_eq!(
            read_recovery_session(&base).as_deref(),
            Some(session.path())
        );

        let _ = fs::remove_dir_all(base);
    }

    #[cfg(windows)]
    #[test]
    fn a_process_that_is_not_a_shipped_engine_is_never_terminated() {
        // `engine_pid` comes from a user-writable status file and the caller is
        // elevated, so anything other than one of this installation's own engines
        // must be refused outright.
        assert!(!terminate_owned_engine(0), "idle process");
        assert!(!terminate_owned_engine(4), "System process");

        let mut child = Command::new("cmd")
            .args(["/c", "pause"])
            .stdin(Stdio::piped())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
            .expect("spawn a non-engine process");
        let pid = child.id();
        assert!(
            !terminate_owned_engine(pid),
            "cmd.exe is not a shipped routing engine"
        );
        assert!(process_alive(pid), "the non-engine process must survive");
        let _ = child.kill();
        let _ = child.wait();
    }

    #[cfg(windows)]
    #[test]
    fn the_engine_allowlist_only_ever_names_the_two_shipped_engines() {
        for engine in owned_engine_paths() {
            let name = engine
                .file_name()
                .and_then(|value| value.to_str())
                .unwrap_or_default()
                .to_ascii_lowercase();
            assert!(
                name.starts_with("xray") || name.starts_with("tun2proxy"),
                "unexpected engine in the termination allowlist: {}",
                engine.display()
            );
        }
    }
}
