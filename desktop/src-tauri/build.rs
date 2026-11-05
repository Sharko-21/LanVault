fn main() {
    tauri_build::try_build(tauri_build::Attributes::new().app_manifest(
        tauri_build::AppManifest::new().commands(&[
            "runtime_info",
            "pick_backup_folder",
            "open_backup_folder",
            "network_interfaces",
            "start_server",
            "stop_server",
            "set_paused",
            "create_pairing",
            "probe_discovery",
            "cancel_pairing",
            "approve_pairing",
            "revoke_device",
        ]),
    ))
    .unwrap_or_else(|error| panic!("Tauri build configuration failed: {error}"));
}
