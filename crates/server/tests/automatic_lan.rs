#![cfg(windows)]
use lanvault_server::Engine;
use mdns_sd::{IfKind, ServiceDaemon, ServiceEvent};
use std::{
    net::{IpAddr, TcpListener},
    time::{Duration, Instant},
};

#[tokio::test]
#[ignore = "requires an approved physical Private LAN; run explicitly"]
async fn receiver_restores_same_adapter_and_advertises_only_its_lan_address(
) -> Result<(), Box<dyn std::error::Error>> {
    exercise_receiver(true).await
}

#[tokio::test]
#[ignore = "requires an approved physical Private LAN; run explicitly"]
async fn receiver_restarts_without_multicast_and_honors_explicit_stop(
) -> Result<(), Box<dyn std::error::Error>> {
    exercise_receiver(false).await
}

async fn exercise_receiver(require_mdns: bool) -> Result<(), Box<dyn std::error::Error>> {
    let temporary = tempfile::tempdir()?;
    let archive = temporary.path().join("archive");
    std::fs::create_dir(&archive)?;
    let state = temporary.path().join("state");
    let engine = Engine::open(state.clone(), 0).await?;
    engine.set_folder(archive).await?;
    let interface = engine
        .interfaces()
        .await?
        .into_iter()
        .find(|n| n.allowed())
        .ok_or("No approved Private physical LAN")?;
    let available = TcpListener::bind((interface.address, 0))?;
    lanvault_platform_windows::pin_tcp_interface(&available, interface.index)?;
    assert!(lanvault_platform_windows::pin_tcp_interface(&available, 0).is_err());
    let port = available.local_addr()?.port();
    drop(available);
    engine.start(interface.id.clone(), port).await?;
    engine.remember_receiver(&interface.id).await?;
    let id = engine.snapshot().await?["serverId"]
        .as_str()
        .ok_or("missing ID")?
        .to_owned();
    engine.stop().await?;
    drop(engine);
    let restored = Engine::open(state.clone(), 0).await?;
    restored.restore_receiver().await?;
    assert!(restored.is_running().await);
    assert_eq!(
        restored.snapshot().await?["bindAddress"],
        format!("{}:{port}", interface.address)
    );
    if !require_mdns {
        restored.disable_auto_receiver().await?;
        drop(restored);
        let stopped = Engine::open(state, 0).await?;
        stopped.restore_receiver().await?;
        assert!(!stopped.is_running().await);
        return Ok(());
    }
    let browser = ServiceDaemon::new()?;
    let monitor = browser.monitor()?;
    browser.disable_interface(IfKind::All)?;
    browser.enable_interface(IfKind::Addr(IpAddr::V4(interface.address)))?;
    let events = browser.browse("_lanvault._tcp.local.")?;
    let deadline = Instant::now() + Duration::from_secs(10);
    let mut resolved = false;
    while Instant::now() < deadline {
        for event in monitor.try_iter().take(10) {
            eprintln!("mDNS test: {event:?}");
        }
        if let Ok(ServiceEvent::ServiceResolved(info)) =
            events.recv_timeout(Duration::from_millis(500))
        {
            if info.get_property_val_str("serverId") == Some(id.as_str()) {
                assert_eq!(info.get_property_val_str("protocolVersion"), Some("1"));
                assert_eq!(info.port, port);
                assert_eq!(
                    info.get_addresses_v4(),
                    [interface.address].into_iter().collect()
                );
                resolved = true;
                break;
            }
        }
    }
    let _ = browser.shutdown();
    restored.disable_auto_receiver().await?;
    drop(restored);
    let stopped = Engine::open(state, 0).await?;
    stopped.restore_receiver().await?;
    assert!(!stopped.is_running().await);
    assert!(
        resolved,
        "mDNS did not resolve the pinned physical LAN advertisement"
    );
    Ok(())
}
