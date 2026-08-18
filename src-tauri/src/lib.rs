mod process;
pub mod routing;
mod settings;
mod update;

use process::{emit_status, ProcessManager};
use routing::{RoutingManager, TrafficTotals};
use settings::Settings;
use std::sync::Arc;
use std::time::Instant;
use tauri::{
    menu::{Menu, MenuItem},
    tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent},
    AppHandle, Emitter, Manager, WindowEvent,
};
use tauri_plugin_dialog::DialogExt;

struct AppState {
    process: Arc<ProcessManager>,
    routing: Arc<RoutingManager>,
}

#[tauri::command]
async fn connect(
    app: AppHandle,
    state: tauri::State<'_, AppState>,
    settings: Settings,
) -> Result<(), String> {
    let mut settings = settings;
    settings.normalize_protocol_options();
    if settings.connection_mode == "smart" {
        settings.connection_mode = "vpn".into();
        let mut selected = None;
        let mut failures = Vec::new();
        for protocol in ["masque", "wg", "gool"] {
            let mut trial = settings.clone();
            trial.protocol = protocol.into();
            let started = Instant::now();
            match start_core_with_fallback(&app, &state.process, &trial).await {
                Ok(_) => {
                    let elapsed = started.elapsed();
                    if selected
                        .as_ref()
                        .is_none_or(|(_, best): &(String, std::time::Duration)| elapsed < *best)
                    {
                        selected = Some((protocol.to_string(), elapsed));
                    }
                }
                Err(error) => failures.push(format!("{protocol}: {error}")),
            }
            let _ = state.process.stop().await;
        }
        settings.protocol = selected.map(|value| value.0).ok_or_else(|| {
            format!(
                "Smart Connect could not establish a protocol: {}",
                failures.join("; ")
            )
        })?;
    }
    let generation = start_core_with_fallback(&app, &state.process, &settings).await?;
    if state.process.generation().await != generation {
        let _ = state.routing.stop(&app).await;
        return Err("Connection attempt was cancelled".into());
    }
    if settings.connection_mode == "vpn" {
        if let Err(error) = state
            .routing
            .start(app.clone(), &settings, state.process.clone())
            .await
        {
            let _ = state.routing.stop(&app).await;
            let _ = state.process.stop().await;
            emit_status(&app, "disconnected", None, None);
            return Err(format!("VPN Mode could not start: {error}"));
        }
        if state.process.generation().await != generation {
            let _ = state.routing.stop(&app).await;
            return Err("Connection attempt was cancelled".into());
        }
    }
    state.process.mark_connected().await;
    let message = if settings.connection_mode == "vpn" {
        "Aether and System-wide VPN Mode are ready"
    } else {
        "Aether SOCKS5 proxy is ready"
    };
    emit_status(&app, "connected", None, Some(message.into()));
    Ok(())
}

async fn start_core_with_fallback(
    app: &AppHandle,
    process: &Arc<ProcessManager>,
    settings: &Settings,
) -> Result<u64, String> {
    let generation = process.start(app.clone(), settings.clone()).await?;
    let primary_result = wait_for_core_socks(process, settings, generation).await;
    if primary_result.is_ok() {
        return Ok(generation);
    }
    if process.generation().await != generation {
        return Err("Connection attempt was cancelled".into());
    }
    let primary_error = primary_result.unwrap_err();
    if settings.protocol == "masque" && settings.masque_transport == "h3" {
        let _ = process.stop().await;
        let mut fallback = settings.clone();
        fallback.masque_transport = "h2".into();
        let _ = app.emit(
            "aether-log",
            "MASQUE HTTP/3 failed; retrying with HTTP/2 transport",
        );
        let fallback_generation = process.start(app.clone(), fallback.clone()).await?;
        match wait_for_core_socks(process, &fallback, fallback_generation).await {
            Ok(()) => Ok(fallback_generation),
            Err(error) => {
                let _ = process.stop().await;
                Err(format!(
                    "MASQUE HTTP/3 failed ({primary_error}); HTTP/2 fallback also failed ({error})"
                ))
            }
        }
    } else {
        let _ = process.stop().await;
        Err(primary_error)
    }
}

async fn wait_for_core_socks(
    process: &Arc<ProcessManager>,
    settings: &Settings,
    generation: u64,
) -> Result<(), String> {
    let deadline = Instant::now() + std::time::Duration::from_secs(settings.stall_timeout);
    loop {
        if process.generation().await != generation {
            return Err("connection attempt cancelled".into());
        }
        if probe_socks(&settings.socks_address).await {
            return Ok(());
        }
        let detail = process.diagnostic_tail().await;
        let lower = detail.to_ascii_lowercase();
        if settings.protocol == "masque"
            && (lower.contains("no usable masque gateway found")
                || lower.contains("prober: no clean endpoint found"))
        {
            return Err(format!("gateway scan failed: {detail}"));
        }
        if !process.is_running().await {
            return Err(if detail.is_empty() {
                "Aether exited before opening its SOCKS5 listener".into()
            } else {
                format!("Aether exited before opening its SOCKS5 listener: {detail}")
            });
        }
        if Instant::now() >= deadline {
            return Err(if detail.is_empty() {
                format!(
                    "Aether did not open its SOCKS5 listener within {} seconds",
                    settings.stall_timeout
                )
            } else {
                format!(
                    "Aether did not open its SOCKS5 listener within {} seconds: {detail}",
                    settings.stall_timeout
                )
            });
        }
        tokio::time::sleep(std::time::Duration::from_millis(150)).await;
    }
}

async fn probe_socks(address: &str) -> bool {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    let attempt = async {
        let mut stream = tokio::net::TcpStream::connect(address).await?;
        stream.write_all(&[5, 1, 0]).await?;
        let mut response = [0u8; 2];
        stream.read_exact(&mut response).await?;
        Ok::<bool, std::io::Error>(response == [5, 0])
    };
    tokio::time::timeout(std::time::Duration::from_millis(300), attempt)
        .await
        .ok()
        .and_then(Result::ok)
        .unwrap_or(false)
}
#[tauri::command]
async fn disconnect(app: AppHandle, state: tauri::State<'_, AppState>) -> Result<(), String> {
    let process_result = state.process.stop().await;
    let routing_result = state.routing.stop(&app).await;
    emit_status(&app, "disconnected", None, None);
    match (routing_result, process_result) {
        (Ok(()), Ok(())) => Ok(()),
        (Err(routing), Ok(())) => Err(format!(
            "Aether stopped, but Windows network cleanup needs attention: {routing}"
        )),
        (Ok(()), Err(process)) => Err(format!("Could not stop the Aether core: {process}")),
        (Err(routing), Err(process)) => Err(format!(
            "Aether and Windows network cleanup both reported errors: {process}; {routing}"
        )),
    }
}
#[tauri::command]
async fn elapsed(state: tauri::State<'_, AppState>) -> Result<u64, String> {
    Ok(state.process.elapsed_secs().await)
}

#[derive(serde::Serialize)]
#[serde(rename_all = "camelCase")]
struct ConnectionSnapshot {
    state: &'static str,
    elapsed: u64,
}

#[tauri::command]
async fn connection_state(state: tauri::State<'_, AppState>) -> Result<ConnectionSnapshot, String> {
    Ok(ConnectionSnapshot {
        state: state.process.connection_state().await,
        elapsed: state.process.elapsed_secs().await,
    })
}
#[tauri::command]
async fn traffic_totals(state: tauri::State<'_, AppState>) -> Result<TrafficTotals, String> {
    state.routing.traffic_totals().await
}

#[derive(serde::Serialize)]
#[serde(rename_all = "camelCase")]
struct VpnProbe {
    ping: Option<u64>,
    location: String,
}

#[tauri::command]
async fn vpn_probe(settings: Settings) -> Result<VpnProbe, String> {
    settings.validate()?;
    let proxy = reqwest::Proxy::all(format!("socks5h://{}", settings.socks_address))
        .map_err(display_err)?;
    let client = reqwest::Client::builder()
        .proxy(proxy)
        .timeout(std::time::Duration::from_secs(12))
        .build()
        .map_err(display_err)?;
    let started = Instant::now();
    let trace = client
        .get("https://www.cloudflare.com/cdn-cgi/trace")
        .send()
        .await
        .map_err(display_err)?
        .error_for_status()
        .map_err(display_err)?
        .text()
        .await
        .map_err(display_err)?;
    let ping = Some(started.elapsed().as_millis().min(u64::MAX as u128) as u64);
    let ip = trace
        .lines()
        .find_map(|line| line.strip_prefix("ip="))
        .unwrap_or("")
        .trim();
    let mut location = String::new();
    if !ip.is_empty() {
        if let Ok(response) = client.get(format!("https://ipwho.is/{ip}")).send().await {
            if let Ok(value) = response.json::<serde_json::Value>().await {
                if value
                    .get("success")
                    .and_then(|v| v.as_bool())
                    .unwrap_or(true)
                {
                    let city = value.get("city").and_then(|v| v.as_str()).unwrap_or("");
                    let country = value
                        .get("country_code")
                        .and_then(|v| v.as_str())
                        .unwrap_or("")
                        .to_uppercase();
                    if country.len() == 2 {
                        let flag: String = country
                            .chars()
                            .map(|c| char::from_u32(0x1f1e6 + c as u32 - 'A' as u32).unwrap_or('?'))
                            .collect();
                        let city_name = if city.is_empty() {
                            country.as_str()
                        } else {
                            city
                        };
                        location = format!("{flag} {city_name}");
                    }
                }
            }
        }
    }
    Ok(VpnProbe { ping, location })
}

#[derive(serde::Serialize)]
struct InstalledApplication {
    name: String,
    path: String,
    icon: String,
}

#[tauri::command]
async fn installed_applications() -> Result<Vec<InstalledApplication>, String> {
    #[cfg(windows)]
    {
        let script = "Add-Type -AssemblyName System.Drawing;$roots=@($env:ProgramData+'\\Microsoft\\Windows\\Start Menu\\Programs',$env:APPDATA+'\\Microsoft\\Windows\\Start Menu\\Programs');$w=New-Object -ComObject WScript.Shell;Get-ChildItem $roots -Filter *.lnk -Recurse -ErrorAction SilentlyContinue|%{$s=$w.CreateShortcut($_.FullName);if($s.TargetPath -match '\\.exe$'){$b='';try{$i=[Drawing.Icon]::ExtractAssociatedIcon($s.TargetPath);if($i){$m=New-Object IO.MemoryStream;$i.ToBitmap().Save($m,[Drawing.Imaging.ImageFormat]::Png);$b=[Convert]::ToBase64String($m.ToArray());$m.Dispose();$i.Dispose()}}catch{};('{0}`t{1}`t{2}' -f $_.BaseName,$s.TargetPath,$b)}}";
        let output = std::process::Command::new("powershell.exe")
            .args(["-NoProfile", "-NonInteractive", "-Command", script])
            .output()
            .map_err(display_err)?;
        let mut apps = Vec::new();
        for line in String::from_utf8_lossy(&output.stdout).lines() {
            let mut parts = line.splitn(3, '\t');
            if let (Some(name), Some(path), Some(icon)) = (parts.next(), parts.next(), parts.next())
            {
                apps.push(InstalledApplication {
                    name: name.into(),
                    path: path.into(),
                    icon: icon.into(),
                });
            }
        }
        apps.sort_by_key(|a| a.name.to_lowercase());
        apps.dedup_by(|a, b| a.path.eq_ignore_ascii_case(&b.path));
        return Ok(apps);
    }
    #[cfg(not(windows))]
    Ok(Vec::new())
}
#[tauri::command]
async fn load_settings(app: AppHandle) -> Result<Settings, String> {
    let mut settings = load_settings_value(&app)?;
    settings.normalize_protocol_options();
    Ok(settings)
}
fn load_settings_value(app: &AppHandle) -> Result<Settings, String> {
    let path = settings_path(app)?;
    if !path.exists() {
        return Ok(Settings::default());
    }
    serde_json::from_str(&std::fs::read_to_string(path).map_err(display_err)?)
        .map_err(|e| format!("Saved settings are invalid: {e}"))
}
#[tauri::command]
async fn save_settings(app: AppHandle, settings: Settings) -> Result<(), String> {
    let mut settings = settings;
    settings.normalize_protocol_options();
    settings.validate()?;
    let path = settings_path(&app)?;
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent).map_err(display_err)?;
    }
    std::fs::write(
        path,
        serde_json::to_vec_pretty(&settings).map_err(display_err)?,
    )
    .map_err(display_err)
}
#[tauri::command]
async fn connection_test(settings: Settings) -> Result<String, String> {
    settings.validate()?;
    let proxy = reqwest::Proxy::all(format!("socks5h://{}", settings.socks_address))
        .map_err(display_err)?;
    let client = reqwest::Client::builder()
        .proxy(proxy)
        .timeout(std::time::Duration::from_secs(20))
        .build()
        .map_err(display_err)?;
    client
        .get("https://www.cloudflare.com/cdn-cgi/trace")
        .send()
        .await
        .map_err(|e| format!("Connection test failed: {e}"))?
        .error_for_status()
        .map_err(display_err)?
        .text()
        .await
        .map_err(display_err)
}
fn tray_menu(app: &AppHandle) -> tauri::Result<Menu<tauri::Wry>> {
    let show = MenuItem::with_id(app, "show", "Show Aethon", true, None::<&str>)?;
    let connect = MenuItem::with_id(app, "connect", "Connect", true, None::<&str>)?;
    let disconnect = MenuItem::with_id(app, "disconnect", "Disconnect", true, None::<&str>)?;
    let quit = MenuItem::with_id(app, "quit", "Exit", true, None::<&str>)?;
    Menu::with_items(app, &[&show, &connect, &disconnect, &quit])
}
fn settings_path(app: &AppHandle) -> Result<std::path::PathBuf, String> {
    Ok(app
        .path()
        .app_config_dir()
        .map_err(display_err)?
        .join("settings.json"))
}
fn show_window(app: &AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.unminimize();
        let _ = window.show();
        let _ = window.set_focus();
    }
}
async fn stop_and_exit(app: AppHandle, process: Arc<ProcessManager>, routing: Arc<RoutingManager>) {
    let _ = routing.stop(&app).await;
    let _ = process.stop().await;
    app.exit(0);
}
fn display_err(e: impl std::fmt::Display) -> String {
    e.to_string()
}

#[tauri::command]
async fn repair_network(app: AppHandle) -> Result<(), String> {
    let base = app
        .path()
        .app_local_data_dir()
        .map_err(display_err)?
        .join("routing");
    let recovery = base.join("recovery.json");
    if !recovery.exists() {
        return Ok(());
    }
    let value: serde_json::Value =
        serde_json::from_slice(&std::fs::read(&recovery).map_err(display_err)?)
            .map_err(display_err)?;
    let session = value
        .get("sessionDir")
        .and_then(|v| v.as_str())
        .ok_or("Recovery snapshot is invalid")?;
    let _ = session;
    routing::repair_cli()
}
#[tauri::command]
async fn recovery_status(app: AppHandle) -> Result<bool, String> {
    Ok(app
        .path()
        .app_local_data_dir()
        .map_err(display_err)?
        .join("routing")
        .join("recovery.json")
        .exists())
}

#[tauri::command]
async fn pick_applications(app: AppHandle) -> Result<Vec<String>, String> {
    let files = app
        .dialog()
        .file()
        .add_filter("Windows applications", &["exe"])
        .blocking_pick_files()
        .unwrap_or_default();
    Ok(files
        .into_iter()
        .filter_map(|file| file.into_path().ok())
        .filter(|path| {
            path.is_absolute()
                && path
                    .extension()
                    .is_some_and(|ext| ext.eq_ignore_ascii_case("exe"))
        })
        .map(|path| path.to_string_lossy().into_owned())
        .collect())
}

pub fn run() {
    let process = Arc::new(ProcessManager::default());
    let routing = Arc::new(RoutingManager::default());
    let setup_process = process.clone();
    let setup_routing = routing.clone();
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_single_instance::init(|app, _, _| {
            show_window(app)
        }))
        .manage(AppState {
            process: process.clone(),
            routing: routing.clone(),
        })
        .manage(update::UpdateState::default())
        .invoke_handler(tauri::generate_handler![
            connect,
            disconnect,
            elapsed,
            connection_state,
            load_settings,
            save_settings,
            connection_test,
            traffic_totals,
            vpn_probe,
            installed_applications,
            repair_network,
            recovery_status,
            pick_applications,
            update::check_for_update,
            update::download_update,
            update::install_update
        ])
        .setup(move |app| {
            let menu = tray_menu(app.handle())?;
            let tray_process = setup_process.clone();
            let tray_routing = setup_routing.clone();
            TrayIconBuilder::with_id("main")
                .icon(
                    app.default_window_icon()
                        .cloned()
                        .expect("application icon"),
                )
                .tooltip("Aethon - Disconnected")
                .menu(&menu)
                .show_menu_on_left_click(false)
                .on_menu_event(move |app, event| match event.id.as_ref() {
                    "show" => show_window(app),
                    "connect" => {
                        show_window(app);
                        let _ = app.emit("tray-connect", ());
                    }
                    "disconnect" => {
                        let app = app.clone();
                        let p = tray_process.clone();
                        let r = tray_routing.clone();
                        tauri::async_runtime::spawn(async move {
                            let _ = r.stop(&app).await;
                            let _ = p.stop().await;
                            emit_status(&app, "disconnected", None, None);
                        });
                    }
                    "quit" => {
                        let app = app.clone();
                        let p = tray_process.clone();
                        let r = tray_routing.clone();
                        tauri::async_runtime::spawn(stop_and_exit(app, p, r));
                    }
                    _ => {}
                })
                .on_tray_icon_event(|tray, event| {
                    if matches!(
                        event,
                        TrayIconEvent::Click {
                            button: MouseButton::Left,
                            button_state: MouseButtonState::Up,
                            ..
                        }
                    ) {
                        show_window(tray.app_handle());
                    }
                })
                .build(app)?;
            Ok(())
        })
        .on_window_event(move |window, event| {
            if let WindowEvent::CloseRequested { api, .. } = event {
                api.prevent_close();
                let _ = window.hide();
            }
        })
        .run(tauri::generate_context!())
        .expect("error while running Aethon");
}
