use mdns_sd::{DaemonEvent, IfKind, ServiceDaemon, ServiceInfo};
use std::net::{IpAddr, Ipv4Addr};
use uuid::Uuid;

pub(crate) fn probe(id: Uuid, address: Ipv4Addr) -> Result<bool, mdns_sd::Error> {
    let daemon = ServiceDaemon::new()?;
    daemon.disable_interface(IfKind::All)?;
    daemon.enable_interface(IfKind::Addr(IpAddr::V4(address)))?;
    let events = daemon.browse("_lanvault._tcp.local.")?;
    let expected = id.to_string();
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(10);
    let mut found = false;
    while std::time::Instant::now() < deadline {
        if let Ok(mdns_sd::ServiceEvent::ServiceResolved(info)) =
            events.recv_timeout(std::time::Duration::from_millis(250))
        {
            if info.get_property_val_str("serverId") == Some(expected.as_str())
                && info.get_addresses_v4().contains(&address)
            {
                found = true;
                break;
            }
        }
    }
    let _ = daemon.shutdown();
    Ok(found)
}

pub(crate) struct Advertisement(ServiceDaemon, String);

impl Advertisement {
    pub fn start(id: Uuid, address: Ipv4Addr, port: u16) -> Result<Self, mdns_sd::Error> {
        let daemon = ServiceDaemon::new()?;
        let events = daemon.monitor()?;
        std::thread::spawn(move || {
            while let Ok(event) = events.recv() {
                match event {
                    DaemonEvent::Error(error) => eprintln!("discovery_error={error}"),
                    DaemonEvent::Announce(_, interfaces) => {
                        eprintln!("discovery_announced={interfaces:?}")
                    }
                    _ => {}
                }
            }
        });
        daemon.disable_interface(IfKind::All)?;
        daemon.enable_interface(IfKind::Addr(IpAddr::V4(address)))?;
        let properties = [
            ("serverId", id.to_string()),
            ("protocolVersion", "1".into()),
        ];
        let info = ServiceInfo::new(
            "_lanvault._tcp.local.",
            &format!("LanVault-{id}"),
            &format!("lanvault-{id}.local."),
            IpAddr::V4(address),
            port,
            &properties[..],
        )?;
        let fullname = info.get_fullname().to_owned();
        if let Err(error) = daemon.register(info) {
            let _ = daemon.shutdown();
            return Err(error);
        }
        Ok(Self(daemon, fullname))
    }
}

impl Drop for Advertisement {
    fn drop(&mut self) {
        let _ = self.0.unregister(&self.1);
        let _ = self.0.shutdown();
    }
}
