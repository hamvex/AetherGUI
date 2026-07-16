use crate::settings::Settings;
use serde::Serialize;
use std::{process::Stdio, sync::Arc, time::Instant};
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

#[derive(Default)]
pub struct ProcessManager {
    child: Mutex<Option<Child>>,
    generation: Mutex<u64>,
    started: Mutex<Option<Instant>>,
    connected: Mutex<bool>,
}

impl ProcessManager {
    pub async fn start(self: &Arc<Self>, app: AppHandle, settings: Settings) -> Result<(), String> {
        settings.validate()?;
        let mut child_guard = self.child.lock().await;
        if let Some(child) = child_guard.as_mut() {
            if child.try_wait().map_err(display_err)?.is_none() {
                return Err("Aether is already running".into());
            }
        }
        let data_dir = app.path().app_local_data_dir().map_err(display_err)?;
        std::fs::create_dir_all(&data_dir).map_err(display_err)?;
        let env = settings.environment(&data_dir.join("aether.toml"))?;
        let core = resolve_core_binary(&app)?;
        let mut command = Command::new(&core);
        command
            .envs(env)
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .kill_on_drop(true);
        #[cfg(windows)]
        command.creation_flags(windows_sys::Win32::System::Threading::CREATE_NO_WINDOW);
        emit_status(&app, "scanning", None, Some("Starting Aether".into()));
        let mut child = command
            .spawn()
            .map_err(|e| format!("Could not start {}: {e}", core.display()))?;
        let stdout = child
            .stdout
            .take()
            .ok_or("Could not capture Aether output")?;
        let stderr = child
            .stderr
            .take()
            .ok_or("Could not capture Aether errors")?;
        let mut generation = self.generation.lock().await;
        *generation += 1;
        let this_generation = *generation;
        *self.started.lock().await = Some(Instant::now());
        *self.connected.lock().await = false;
        *child_guard = Some(child);
        drop(child_guard);
        drop(generation);
        spawn_reader(app.clone(), self.clone(), stdout);
        spawn_reader(app.clone(), self.clone(), stderr);

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
        tauri::async_runtime::spawn(async move {
            sleep(Duration::from_secs(settings.stall_timeout)).await;
            if *manager.generation.lock().await == this_generation
                && !*manager.connected.lock().await
            {
                if settings.watchdog {
                    let _ = manager.stop().await;
                    emit_status(&app, "error", None, Some(format!("Aether did not open the SOCKS5 listener within {} seconds and was stopped", settings.stall_timeout)));
                } else {
                    emit_status(
                        &app,
                        "connecting",
                        None,
                        Some(format!(
                            "Aether is still working after {} seconds; watchdog is disabled",
                            settings.stall_timeout
                        )),
                    );
                }
            }
        });
        Ok(())
    }

    pub async fn stop(&self) -> Result<(), String> {
        *self.generation.lock().await += 1;
        if let Some(mut child) = self.child.lock().await.take() {
            if child.try_wait().map_err(display_err)?.is_none() {
                child.kill().await.map_err(display_err)?;
                let _ = child.wait().await;
            }
        }
        *self.started.lock().await = None;
        *self.connected.lock().await = false;
        Ok(())
    }
    pub async fn mark_connected(&self) {
        *self.connected.lock().await = true;
    }
    pub async fn elapsed_secs(&self) -> u64 {
        self.started
            .lock()
            .await
            .as_ref()
            .map(|i| i.elapsed().as_secs())
            .unwrap_or(0)
    }
}

fn spawn_reader<R>(app: AppHandle, manager: Arc<ProcessManager>, stream: R)
where
    R: tokio::io::AsyncRead + Unpin + Send + 'static,
{
    tauri::async_runtime::spawn(async move {
        let mut lines = BufReader::new(stream).lines();
        while let Ok(Some(line)) = lines.next_line().await {
            let _ = app.emit("aether-log", &line);
            if parse_status(&app, &line) {
                manager.mark_connected().await;
            }
        }
    });
}

fn parse_status(app: &AppHandle, line: &str) -> bool {
    let lower = line.to_ascii_lowercase();
    if lower.contains("socks5 server listening") || lower.contains("socks5 listening") {
        emit_status(app, "connected", None, None);
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
    let resource = resource_dir.join("binaries").join(name);
    if resource.is_file() {
        return Ok(resource);
    }
    let beside_app = resource_dir.join(name);
    if beside_app.is_file() {
        return Ok(beside_app);
    }
    let dev = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../../aether/target/release")
        .join(name);
    if dev.is_file() {
        return Ok(dev);
    }
    Err(format!(
        "Bundled Aether core was not found at {}. Build it first or set AETHER_GUI_CORE_PATH.",
        resource.display()
    ))
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
    #[test]
    fn no_false_endpoint() {
        assert_eq!(extract_socket("scanning for a gateway"), None);
    }
}
