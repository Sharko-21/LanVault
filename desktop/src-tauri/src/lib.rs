use lanvault_server::Engine;
use serde_json::Value;
use std::sync::{
    atomic::{AtomicBool, Ordering},
    Arc, Mutex,
};
use tauri::{
    menu::{Menu, MenuItem},
    tray::TrayIconBuilder,
    Manager, State,
};
use tauri_plugin_dialog::DialogExt;

fn state_directory_argument(
    args: impl Iterator<Item = std::ffi::OsString>,
) -> std::io::Result<Option<std::path::PathBuf>> {
    let mut args = args;
    let mut selected = None;
    while let Some(argument) = args.next() {
        if argument == "--state-dir" {
            let path = args
                .next()
                .map(std::path::PathBuf::from)
                .filter(|p| p.is_absolute())
                .ok_or_else(|| {
                    std::io::Error::new(
                        std::io::ErrorKind::InvalidInput,
                        "STATE_DIRECTORY_MUST_BE_ABSOLUTE",
                    )
                })?;
            if selected.replace(path).is_some() {
                return Err(std::io::Error::new(
                    std::io::ErrorKind::InvalidInput,
                    "DUPLICATE_STATE_DIRECTORY",
                ));
            }
        }
    }
    Ok(selected)
}

#[derive(Default)]
struct DesktopState {
    engine: Mutex<Option<Result<Arc<Engine>, String>>>,
    quitting: AtomicBool,
}
impl DesktopState {
    fn engine(&self) -> Result<Arc<Engine>, String> {
        self.engine
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .clone()
            .unwrap_or_else(|| Err("INITIALIZING".into()))
    }
}
fn public_error(error: lanvault_server::Error) -> String {
    error.to_string()
}

#[tauri::command]
async fn runtime_info(
    app: tauri::AppHandle,
    state: State<'_, DesktopState>,
) -> Result<Value, String> {
    let engine = state.engine()?;
    let snapshot = engine.snapshot().await.map_err(public_error)?;
    if let Some(tray) = app.tray_by_id("main") {
        let status = if engine.paused() {
            "пауза"
        } else if snapshot["serverListening"] == true {
            "приём включён"
        } else {
            "приём остановлен"
        };
        let _ = tray.set_tooltip(Some(format!("LanVault — {status}")));
    }
    Ok(snapshot)
}
#[tauri::command]
async fn pick_backup_folder(
    app: tauri::AppHandle,
    state: State<'_, DesktopState>,
) -> Result<Option<Value>, String> {
    let engine = state.engine()?;
    let selection =
        tauri::async_runtime::spawn_blocking(move || app.dialog().file().blocking_pick_folder())
            .await
            .map_err(|_| "FOLDER_DIALOG_FAILED".to_string())?;
    let Some(selection) = selection else {
        return Ok(None);
    };
    let path = selection
        .into_path()
        .map_err(|_| "LOCAL_FOLDER_REQUIRED".to_string())?;
    engine
        .set_folder(path)
        .await
        .map(Some)
        .map_err(public_error)
}
#[tauri::command]
async fn open_backup_folder(state: State<'_, DesktopState>) -> Result<(), String> {
    let snapshot = state.engine()?.snapshot().await.map_err(public_error)?;
    let path = snapshot["folder"]["path"]
        .as_str()
        .ok_or("ROOT_NOT_CONFIGURED")?;
    // Only the validated archive root can be opened. No frontend path or shell parsing.
    std::process::Command::new("explorer.exe")
        .arg(path.strip_prefix(r"\\?\").unwrap_or(path))
        .spawn()
        .map_err(|_| "FOLDER_OPEN_FAILED".to_string())?;
    Ok(())
}
#[tauri::command]
async fn network_interfaces(state: State<'_, DesktopState>) -> Result<Value, String> {
    let interfaces = state.engine()?.interfaces().await.map_err(public_error)?;
    serde_json::to_value(
        interfaces
            .into_iter()
            .filter(|n| n.allowed())
            .collect::<Vec<_>>(),
    )
    .map_err(|_| "INTERNAL".into())
}
#[tauri::command]
async fn start_server(
    state: State<'_, DesktopState>,
    interface_id: String,
) -> Result<Value, String> {
    let engine = state.engine()?;
    let result = engine
        .start(interface_id.clone(), 43827)
        .await
        .map_err(public_error)?;
    engine
        .remember_receiver(&interface_id)
        .await
        .map_err(public_error)?;
    Ok(result)
}
#[tauri::command]
async fn stop_server(state: State<'_, DesktopState>) -> Result<(), String> {
    state
        .engine()?
        .disable_auto_receiver()
        .await
        .map_err(public_error)
}
#[tauri::command]
fn set_paused(state: State<'_, DesktopState>, paused: bool) -> Result<(), String> {
    state.engine()?.set_paused(paused);
    Ok(())
}
#[tauri::command]
async fn create_pairing(state: State<'_, DesktopState>) -> Result<Value, String> {
    state.engine()?.create_pairing().await.map_err(public_error)
}
#[tauri::command]
async fn cancel_pairing(state: State<'_, DesktopState>) -> Result<(), String> {
    state.engine()?.cancel_pairing().await.map_err(public_error)
}
#[tauri::command]
async fn probe_discovery(state: State<'_, DesktopState>) -> Result<Value, String> {
    state
        .engine()?
        .probe_discovery()
        .await
        .map_err(public_error)
}
#[tauri::command]
async fn approve_pairing(
    state: State<'_, DesktopState>,
    request_id: String,
    approve: bool,
) -> Result<(), String> {
    state
        .engine()?
        .approve_pairing(request_id, approve)
        .await
        .map_err(public_error)
}
#[tauri::command]
async fn revoke_device(state: State<'_, DesktopState>, device_id: String) -> Result<(), String> {
    state
        .engine()?
        .revoke(device_id)
        .await
        .map_err(public_error)
}

fn quit(app: &tauri::AppHandle) {
    let state = app.state::<DesktopState>();
    if state.quitting.swap(true, Ordering::AcqRel) {
        return;
    }
    let engine = state.engine().ok();
    let handle = app.clone();
    tauri::async_runtime::spawn(async move {
        if let Some(engine) = engine {
            engine.suspend_auto_receiver();
            let _ = engine.stop().await;
        }
        handle.exit(0);
    });
}
fn show(app: &tauri::AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.show();
        let _ = window.unminimize();
        let _ = window.set_focus();
    }
}

pub fn run() {
    let application = tauri::Builder::default()
        .manage(DesktopState::default())
        .plugin(tauri_plugin_dialog::init())
        .invoke_handler(tauri::generate_handler![
            runtime_info,
            pick_backup_folder,
            open_backup_folder,
            network_interfaces,
            start_server,
            stop_server,
            set_paused,
            create_pairing,
            cancel_pairing,
            probe_discovery,
            approve_pairing,
            revoke_device
        ])
        .setup(|app| {
            let show_item = MenuItem::with_id(app, "show", "Открыть LanVault", true, None::<&str>)?;
            let pause = MenuItem::with_id(app, "pause", "Пауза / продолжить", true, None::<&str>)?;
            let stop = MenuItem::with_id(app, "stop", "Остановить приём", true, None::<&str>)?;
            let quit_item = MenuItem::with_id(app, "quit", "Выйти", true, None::<&str>)?;
            let menu = Menu::with_items(app, &[&show_item, &pause, &stop, &quit_item])?;
            let mut tray = TrayIconBuilder::with_id("main")
                .tooltip("LanVault — подготовка архива")
                .menu(&menu)
                .on_menu_event(|app, event| match event.id.as_ref() {
                    "show" => show(app),
                    "quit" => quit(app),
                    "pause" => {
                        if let Ok(engine) = app.state::<DesktopState>().engine() {
                            engine.set_paused(!engine.paused());
                        }
                    }
                    "stop" => {
                        if let Ok(engine) = app.state::<DesktopState>().engine() {
                            tauri::async_runtime::spawn(async move {
                                let _ = engine.disable_auto_receiver().await;
                            });
                        }
                    }
                    _ => {}
                });
            if let Some(icon) = app.default_window_icon() {
                tray = tray.icon(icon.clone());
            }
            tray.build(app)?;
            let default_path = app.path().app_local_data_dir()?.join("server");
            // Managed development hosts may redirect AppData via reparse points.
            // A deliberate debug-only override allows testing without relaxing no-follow I/O.
            #[cfg(debug_assertions)]
            let path = std::env::var_os("LANVAULT_DEV_STATE_DIR")
                .map(std::path::PathBuf::from)
                .unwrap_or(default_path);
            #[cfg(not(debug_assertions))]
            let path = default_path;
            // Explicit advanced/portable configuration; the same no-follow checks and ACL apply.
            let path = std::env::var_os("LANVAULT_STATE_DIR")
                .map(std::path::PathBuf::from)
                .unwrap_or(path);
            let path = state_directory_argument(std::env::args_os().skip(1))?.unwrap_or(path);
            let handle = app.handle().clone();
            tauri::async_runtime::spawn(async move {
                let engine = Engine::open(path, 512 * 1024 * 1024)
                    .await
                    .map_err(|error| format!("STATE_INIT:{}", public_error(error)));
                if let Err(error) = &engine {
                    eprintln!("{error}");
                }
                if let Ok(ready) = &engine {
                    let ready = ready.clone();
                    tauri::async_runtime::spawn(async move {
                        loop {
                            if let Err(error) = ready.restore_receiver().await {
                                eprintln!("RECEIVER_RESTORE:{}", public_error(error));
                            }
                            if let Err(error) = ready.sweep_uploads().await {
                                if !error.to_string().contains("ROOT_NOT_CONFIGURED") {
                                    eprintln!("UPLOAD_MAINTENANCE:{}", public_error(error));
                                }
                            }
                            tokio::time::sleep(std::time::Duration::from_secs(15)).await;
                        }
                    });
                }
                *handle
                    .state::<DesktopState>()
                    .engine
                    .lock()
                    .unwrap_or_else(|e| e.into_inner()) = Some(engine);
            });
            Ok(())
        })
        .on_window_event(|window, event| {
            if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                if window.hide().is_ok() {
                    api.prevent_close();
                }
            }
        })
        .build(tauri::generate_context!())
        .unwrap_or_else(|error| panic!("LanVault failed to start: {error}"));
    application.run(|app, event| {
        if let tauri::RunEvent::ExitRequested { api, .. } = event {
            if !app.state::<DesktopState>().quitting.load(Ordering::Acquire) {
                api.prevent_exit();
                quit(app);
            }
        }
    });
}
