use crate::settings::Settings;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
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

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct RoutingRequest {
    session_id: String,
    gui_pid: u32,
    socks_address: String,
    routing_mode: String,
    dns_leak_protection: bool,
    ipv6_behavior: String,
    kill_switch: bool,
    tun_mtu: u16,
    split_applications: Vec<String>,
    route_exclusions: Vec<String>,
    session_dir: PathBuf,
}

struct Session {
    dir: PathBuf,
}
#[derive(Default)]
pub struct RoutingManager {
    session: Mutex<Option<Session>>,
}

impl RoutingManager {
    pub async fn start(
        self: &Arc<Self>,
        app: AppHandle,
        settings: &Settings,
    ) -> Result<(), String> {
        if settings.connection_mode != "vpn" {
            return Ok(());
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
        let request = RoutingRequest {
            session_id,
            gui_pid: std::process::id(),
            socks_address: settings.socks_address.clone(),
            routing_mode: settings.routing_mode.clone(),
            dns_leak_protection: settings.dns_leak_protection,
            ipv6_behavior: settings.ipv6_behavior.clone(),
            kill_switch: settings.kill_switch,
            tun_mtu: settings.tun_mtu,
            split_applications: settings.split_applications.clone(),
            route_exclusions: settings.route_exclusions.clone(),
            session_dir: dir.clone(),
        };
        request.validate()?;
        let request_path = dir.join("request.json");
        atomic_json(&request_path, &request)?;
        let recovery = json!({"active":true,"sessionDir":dir,"createdAt":request.session_id});
        atomic_json(&base.join("recovery.json"), &recovery)?;
        if let Some(path) = cli_recovery_path() {
            atomic_json(&path, &recovery)?;
        }
        emit(
            &app,
            "requesting-admin",
            "Administrator permission is required to start the virtual adapter",
        );
        launch_elevated("--routing-helper", &request_path)?;
        *self.session.lock().await = Some(Session {
            dir: request.session_dir.clone(),
        });
        for _ in 0..90 {
            sleep(Duration::from_millis(500)).await;
            if let Ok(status) = read_status(&request.session_dir) {
                let _ = app.emit("routing-status", &status);
                if status.state == "connected" {
                    let monitor_dir = request.session_dir.clone();
                    let monitor_app = app.clone();
                    tauri::async_runtime::spawn(async move {
                        let mut last = String::new();
                        loop {
                            sleep(Duration::from_secs(1)).await;
                            if let Ok(value) = read_status(&monitor_dir) {
                                if value.state != last {
                                    last = value.state.clone();
                                    let _ = monitor_app.emit("routing-status", &value);
                                }
                                if matches!(value.state.as_str(), "disabled" | "error") {
                                    break;
                                }
                            }
                        }
                    });
                    return Ok(());
                }
                if status.state == "error" {
                    return Err(status.message);
                }
            }
        }
        Err("The routing helper did not become ready".into())
    }

    pub async fn stop(&self, app: &AppHandle) -> Result<(), String> {
        let session = self.session.lock().await.take();
        let Some(session) = session else {
            return Ok(());
        };
        emit(
            app,
            "restoring",
            "Restoring routes, DNS, and the virtual adapter",
        );
        atomic_json(&session.dir.join("control.json"), &json!({"action":"stop"}))?;
        for _ in 0..40 {
            sleep(Duration::from_millis(250)).await;
            if read_status(&session.dir)
                .map(|s| s.state == "disabled")
                .unwrap_or(false)
            {
                return Ok(());
            }
        }
        launch_elevated("--repair-network", &session.dir)?;
        Ok(())
    }
}

impl RoutingRequest {
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

fn sing_box_config(request: &RoutingRequest) -> Value {
    let socket: SocketAddr = request.socks_address.parse().unwrap();
    let mut rules = vec![
        json!({"action":"sniff"}),
        json!({"protocol":"dns","action":"hijack-dns"}),
    ];
    if request.ipv6_behavior == "block" {
        rules.push(json!({"ip_version":6,"action":"reject"}));
    }
    let selected: Vec<String> = request
        .split_applications
        .iter()
        .map(|p| p.replace('\\', "/"))
        .collect();
    if request.routing_mode == "bypass-local" {
        rules.push(json!({"ip_is_private":true,"action":"route","outbound":"direct"}));
    }
    for cidr in &request.route_exclusions {
        rules.push(json!({"ip_cidr":[cidr],"action":"route","outbound":"direct"}));
    }
    let final_outbound = if request.routing_mode == "split-include" {
        "direct"
    } else {
        "aether"
    };
    if request.routing_mode == "split-include" && !selected.is_empty() {
        rules.push(json!({"process_path":selected,"action":"route","outbound":"aether"}));
    }
    if request.routing_mode == "split-exclude" && !selected.is_empty() {
        rules.push(json!({"process_path":selected,"action":"route","outbound":"direct"}));
    }
    json!({
      "log":{"level":"info","timestamp":true},
      "dns":{"servers":[{"type":"https","tag":"secure-dns","server":"1.1.1.1","server_port":443,"path":"/dns-query","tls":{"enabled":true,"server_name":"cloudflare-dns.com"},"detour":"aether"}],"final":"secure-dns","strategy":"prefer_ipv4"},
      "inbounds":[{"type":"tun","tag":"tun-in","interface_name":"FirsthamAether","address":["172.19.0.1/30","fdfe:dcba:9876::1/126"],"mtu":request.tun_mtu,"auto_route":true,"strict_route":request.dns_leak_protection,"stack":"mixed"}],
      "outbounds":[{"type":"socks","tag":"aether","server":socket.ip().to_string(),"server_port":socket.port(),"version":"5"},{"type":"direct","tag":"direct"}],
      "route":{"auto_detect_interface":true,"find_process":request.routing_mode.starts_with("split-"),"rules":rules,"final":final_outbound}
    })
}

pub fn helper_main(request_path: &Path) -> Result<(), String> {
    let request: RoutingRequest =
        serde_json::from_slice(&fs::read(request_path).map_err(display_err)?)
            .map_err(display_err)?;
    request.validate()?;
    if request_path.file_name().and_then(|value| value.to_str()) != Some("request.json")
        || request_path.parent() != Some(request.session_dir.as_path())
    {
        return Err("Request path is outside its session directory".into());
    }
    write_status(
        &request.session_dir,
        "preparing",
        "Validating the embedded routing engine",
        0,
    )?;
    let engine = resolve_beside_current("sing-box.exe")?;
    let config_path = request.session_dir.join("sing-box.json");
    atomic_json(&config_path, &sing_box_config(&request))?;
    write_status(
        &request.session_dir,
        "starting-adapter",
        "Starting the FirsthamAether virtual adapter",
        0,
    )?;
    let log = fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(request.session_dir.join("routing.log"))
        .map_err(display_err)?;
    let err = log.try_clone().map_err(display_err)?;
    let mut child = Command::new(engine)
        .args(["run", "-c"])
        .arg(&config_path)
        .stdin(Stdio::null())
        .stdout(Stdio::from(log))
        .stderr(Stdio::from(err))
        .spawn()
        .map_err(display_err)?;
    write_status(
        &request.session_dir,
        "configuring-routes",
        "Configuring routes and protected DNS",
        child.id(),
    )?;
    std::thread::sleep(Duration::from_secs(2));
    if let Some(exit) = child.try_wait().map_err(display_err)? {
        return Err(format!("Routing engine exited during startup: {exit}"));
    }
    write_status(
        &request.session_dir,
        "connected",
        "System-wide routing is active",
        child.id(),
    )?;
    let mut health_tick = 0u8;
    let mut socks_down = false;
    loop {
        if request.session_dir.join("control.json").exists() || !process_alive(request.gui_pid) {
            break;
        }
        if let Some(exit) = child.try_wait().map_err(display_err)? {
            write_status(
                &request.session_dir,
                "error",
                &format!("Routing engine exited unexpectedly: {exit}"),
                0,
            )?;
            return Err("Routing engine stopped".into());
        }
        health_tick = health_tick.wrapping_add(1);
        if health_tick % 5 == 0 {
            if !sync_socks_ready(&request.socks_address) {
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
            } else if socks_down {
                socks_down = false;
                write_status(
                    &request.session_dir,
                    "connected",
                    "Aether recovered and system-wide routing resumed",
                    child.id(),
                )?;
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
    let _ = child.kill();
    let _ = child.wait();
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
}

pub fn repair_main(session_dir: &Path) -> Result<(), String> {
    if let Ok(status) = read_status(session_dir) {
        if status.engine_pid > 0 {
            terminate_pid(status.engine_pid);
        }
    }
    write_status(
        session_dir,
        "disabled",
        "Recovery completed; dynamic TUN routes and DNS filters were released",
        0,
    )?;
    if let Some(base) = session_dir.parent() {
        let _ = fs::remove_file(base.join("recovery.json"));
    }
    if let Some(path) = cli_recovery_path() {
        let _ = fs::remove_file(path);
    }
    Ok(())
}

pub fn repair_cli() -> Result<(), String> {
    let recovery = cli_recovery_path().ok_or("LOCALAPPDATA is unavailable")?;
    if !recovery.exists() {
        return Ok(());
    }
    let value: Value =
        serde_json::from_slice(&fs::read(recovery).map_err(display_err)?).map_err(display_err)?;
    let session = value
        .get("sessionDir")
        .and_then(|v| v.as_str())
        .ok_or("Recovery snapshot is invalid")?;
    launch_elevated("--repair-network", Path::new(session))
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
fn terminate_pid(pid: u32) {
    #[cfg(windows)]
    unsafe {
        use windows_sys::Win32::{
            Foundation::CloseHandle,
            System::Threading::{OpenProcess, TerminateProcess, PROCESS_TERMINATE},
        };
        let h = OpenProcess(PROCESS_TERMINATE, 0, pid);
        if !h.is_null() {
            TerminateProcess(h, 1);
            CloseHandle(h);
        }
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
fn resolve_beside_current(name: &str) -> Result<PathBuf, String> {
    let exe = std::env::current_exe().map_err(display_err)?;
    let path = exe.parent().ok_or("Invalid application path")?.join(name);
    if path.is_file() {
        return Ok(path);
    }
    let dev = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("binaries/sing-box-x86_64-pc-windows-msvc.exe");
    if dev.is_file() {
        Ok(dev)
    } else {
        Err(format!(
            "Bundled routing engine was not found at {}",
            path.display()
        ))
    }
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
fn atomic_json(path: &Path, value: &impl Serialize) -> Result<(), String> {
    let tmp = path.with_extension("tmp");
    let mut file = fs::File::create(&tmp).map_err(display_err)?;
    file.write_all(&serde_json::to_vec_pretty(value).map_err(display_err)?)
        .map_err(display_err)?;
    file.sync_all().map_err(display_err)?;
    fs::rename(tmp, path).map_err(display_err)
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
            gui_pid: 1,
            socks_address: "127.0.0.1:1819".into(),
            routing_mode: "full".into(),
            dns_leak_protection: true,
            ipv6_behavior: "tunnel".into(),
            kill_switch: false,
            tun_mtu: 1500,
            split_applications: vec![],
            route_exclusions: vec![],
            session_dir: "C:/x".into(),
        }
    }
    #[test]
    fn full_config_uses_socks_and_strict_routes() {
        let c = sing_box_config(&request()).to_string();
        assert!(c.contains("strict_route"));
        assert!(c.contains("aether"));
        assert!(c.contains("hijack-dns"));
    }
    #[test]
    fn pinned_engine_accepts_generated_configuration() {
        let engine = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("binaries/sing-box-x86_64-pc-windows-msvc.exe");
        if !engine.exists() {
            return;
        }
        let path = std::env::temp_dir().join(format!(
            "firstham-singbox-check-{}.json",
            std::process::id()
        ));
        fs::write(
            &path,
            serde_json::to_vec(&sing_box_config(&request())).unwrap(),
        )
        .unwrap();
        let output = Command::new(engine)
            .args(["check", "-c"])
            .arg(&path)
            .output()
            .unwrap();
        let _ = fs::remove_file(path);
        assert!(
            output.status.success(),
            "{}",
            String::from_utf8_lossy(&output.stderr)
        );
    }
}
