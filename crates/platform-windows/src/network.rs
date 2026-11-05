use serde::{Deserialize, Serialize};
use std::{io, net::Ipv4Addr};

#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct NetworkInterface {
    pub id: String,
    pub name: String,
    pub address: Ipv4Addr,
    pub prefix: u8,
    #[serde(default)]
    pub index: u32,
    pub physical: bool,
    pub category: String,
}

impl NetworkInterface {
    pub fn allowed(&self) -> bool {
        self.physical
            && self.index > 0
            && self.category == "Private"
            && self.address.is_private()
            && (8..=30).contains(&self.prefix)
    }
    pub fn allows_peer(&self, peer: Ipv4Addr) -> bool {
        if !self.allowed() || !peer.is_private() {
            return false;
        }
        let mask = u32::MAX << (32 - self.prefix);
        let network = u32::from(self.address) & mask;
        let value = u32::from(peer);
        value & mask == network && value != network && value != network | !mask
    }
}

#[cfg(windows)]
pub fn network_interfaces() -> io::Result<Vec<NetworkInterface>> {
    use std::{
        io::Read,
        os::windows::process::CommandExt,
        process::{Command, Stdio},
        time::{Duration, Instant},
    };
    // Fixed read-only code, no user values are interpolated. Fail closed on policy/permission errors.
    const QUERY: &str = r#"$ErrorActionPreference='Stop'; [Console]::OutputEncoding=[Text.UTF8Encoding]::new($false); $adapters=@(Get-NetAdapter); $profiles=@(Get-NetConnectionProfile); $r=@(Get-NetIPAddress -AddressFamily IPv4 | ForEach-Object { $index=$_.InterfaceIndex; $a=$adapters | Where-Object { $_.ifIndex -eq $index } | Select-Object -First 1; $p=$profiles | Where-Object { $_.InterfaceIndex -eq $index } | Select-Object -First 1; if($a -and $p) { [pscustomobject]@{id=([string]$a.InterfaceGuid+'@'+[string]$_.IPAddress);name=[string]$a.Name;address=[string]$_.IPAddress;prefix=[int]$_.PrefixLength;index=[int]$_.InterfaceIndex;physical=[bool]$a.HardwareInterface;category=[string]$p.NetworkCategory} } }); ConvertTo-Json -InputObject $r -Compress"#;
    let mut child = Command::new("powershell.exe")
        .args([
            "-NoLogo",
            "-NoProfile",
            "-NonInteractive",
            "-Command",
            QUERY,
        ])
        .creation_flags(0x08000000)
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()?;
    let deadline = Instant::now() + Duration::from_secs(5);
    let status = loop {
        if let Some(status) = child.try_wait()? {
            break status;
        }
        if Instant::now() >= deadline {
            let _ = child.kill();
            let _ = child.wait();
            return Err(io::Error::new(
                io::ErrorKind::TimedOut,
                "network query timed out",
            ));
        }
        std::thread::sleep(Duration::from_millis(25));
    };
    let mut output = Vec::new();
    child
        .stdout
        .take()
        .ok_or_else(|| io::Error::other("network query output missing"))?
        .take(1024 * 1024)
        .read_to_end(&mut output)?;
    if !status.success() {
        return Err(io::Error::other("Windows network policy query failed"));
    }
    serde_json::from_slice(&output).map_err(io::Error::other)
}

#[cfg(not(windows))]
pub fn network_interfaces() -> io::Result<Vec<NetworkInterface>> {
    Err(io::Error::new(
        io::ErrorKind::Unsupported,
        "Windows network policy required",
    ))
}

/// Pin return traffic to the selected physical interface, independently of default VPN routes.
#[cfg(windows)]
pub fn pin_tcp_interface<T: std::os::windows::io::AsRawSocket>(
    socket: &T,
    index: u32,
) -> io::Result<()> {
    use windows_sys::Win32::Networking::WinSock::{
        setsockopt, WSAGetLastError, IPPROTO_IP, IP_UNICAST_IF, SOCKET_ERROR,
    };
    if index == 0 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "missing interface index",
        ));
    }
    let value = index.to_be();
    // IP_UNICAST_IF expects a DWORD in network byte order, even on little-endian Windows.
    let result = unsafe {
        setsockopt(
            socket.as_raw_socket() as _,
            IPPROTO_IP,
            IP_UNICAST_IF,
            (&value as *const u32).cast(),
            4,
        )
    };
    if result == SOCKET_ERROR {
        return Err(io::Error::from_raw_os_error(unsafe { WSAGetLastError() }));
    }
    Ok(())
}

#[cfg(not(windows))]
pub fn pin_tcp_interface<T>(_socket: &T, _index: u32) -> io::Result<()> {
    Err(io::Error::new(
        io::ErrorKind::Unsupported,
        "Windows network binding required",
    ))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn only_private_physical_same_subnet() {
        let mut n = NetworkInterface {
            id: "adapter".into(),
            name: "Ethernet".into(),
            address: Ipv4Addr::new(192, 168, 10, 2),
            prefix: 24,
            index: 1,
            physical: true,
            category: "Private".into(),
        };
        assert!(n.allows_peer(Ipv4Addr::new(192, 168, 10, 5)));
        for p in [
            Ipv4Addr::new(192, 168, 11, 5),
            Ipv4Addr::new(8, 8, 8, 8),
            Ipv4Addr::new(192, 168, 10, 0),
            Ipv4Addr::new(192, 168, 10, 255),
        ] {
            assert!(!n.allows_peer(p));
        }
        n.physical = false;
        assert!(!n.allowed());
        n.physical = true;
        n.category = "Public".into();
        assert!(!n.allowed());
    }
}
