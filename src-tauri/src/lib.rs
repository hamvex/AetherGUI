mod process;
mod settings;

use process::{emit_status, ProcessManager};
use settings::Settings;
use std::sync::Arc;
use tauri::{
    menu::{Menu, MenuItem},
    tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent},
    AppHandle, Emitter, Manager, WindowEvent,
};

struct AppState {
    process: Arc<ProcessManager>,
}

#[tauri::command]
async fn connect(
    app: AppHandle,
    state: tauri::State<'_, AppState>,
    settings: Settings,
) -> Result<(), String> {
    state.process.start(app, settings).await
}
#[tauri::command]
async fn disconnect(app: AppHandle, state: tauri::State<'_, AppState>) -> Result<(), String> {
    state.process.stop().await?;
    emit_status(&app, "disconnected", None, None);
    Ok(())
}
#[tauri::command]
async fn elapsed(state: tauri::State<'_, AppState>) -> Result<u64, String> {
    Ok(state.process.elapsed_secs().await)
}
#[tauri::command]
async fn load_settings(app: AppHandle) -> Result<Settings, String> {
    load_settings_value(&app)
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
#[tauri::command]
async fn set_language(app: AppHandle, language: String) -> Result<(), String> {
    if !matches!(language.as_str(), "en" | "fa") {
        return Err("Unsupported language".into());
    }
    let tray = app
        .tray_by_id("main")
        .ok_or_else(|| "Tray icon is not available".to_string())?;
    tray.set_menu(Some(tray_menu(&app, &language).map_err(display_err)?))
        .map_err(display_err)?;
    tray.set_tooltip(Some(tray_tooltip(&language)))
        .map_err(display_err)
}
fn tray_menu(app: &AppHandle, language: &str) -> tauri::Result<Menu<tauri::Wry>> {
    let (show_text, connect_text, disconnect_text, quit_text) = if language == "fa" {
        ("نمایش Firstham AetherGui", "اتصال", "قطع اتصال", "خروج")
    } else {
        ("Show Firstham AetherGui", "Connect", "Disconnect", "Exit")
    };
    let show = MenuItem::with_id(app, "show", show_text, true, None::<&str>)?;
    let connect = MenuItem::with_id(app, "connect", connect_text, true, None::<&str>)?;
    let disconnect = MenuItem::with_id(app, "disconnect", disconnect_text, true, None::<&str>)?;
    let quit = MenuItem::with_id(app, "quit", quit_text, true, None::<&str>)?;
    Menu::with_items(app, &[&show, &connect, &disconnect, &quit])
}
fn tray_tooltip(language: &str) -> &'static str {
    if language == "fa" {
        "Firstham AetherGui — قطع"
    } else {
        "Firstham AetherGui — Disconnected"
    }
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
async fn stop_and_exit(app: AppHandle, process: Arc<ProcessManager>) {
    let _ = process.stop().await;
    app.exit(0);
}
fn display_err(e: impl std::fmt::Display) -> String {
    e.to_string()
}

pub fn run() {
    let process = Arc::new(ProcessManager::default());
    let setup_process = process.clone();
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_single_instance::init(|app, _, _| {
            show_window(app)
        }))
        .manage(AppState {
            process: process.clone(),
        })
        .invoke_handler(tauri::generate_handler![
            connect,
            disconnect,
            elapsed,
            load_settings,
            save_settings,
            connection_test,
            set_language
        ])
        .setup(move |app| {
            let language = load_settings_value(app.handle())
                .unwrap_or_default()
                .language;
            let menu = tray_menu(app.handle(), &language)?;
            let tray_process = setup_process.clone();
            TrayIconBuilder::with_id("main")
                .icon(
                    app.default_window_icon()
                        .cloned()
                        .expect("application icon"),
                )
                .tooltip(tray_tooltip(&language))
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
                        tauri::async_runtime::spawn(async move {
                            let _ = p.stop().await;
                            emit_status(&app, "disconnected", None, None);
                        });
                    }
                    "quit" => {
                        let app = app.clone();
                        let p = tray_process.clone();
                        tauri::async_runtime::spawn(stop_and_exit(app, p));
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
        .expect("error while running Firstham AetherGui");
}
