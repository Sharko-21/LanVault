//! Windows storage guards, DPAPI, and read-only network policy discovery.

mod network;
#[cfg(windows)]
mod secrets;
#[cfg(windows)]
mod secure_fs;
pub use network::*;
#[cfg(windows)]
pub use secrets::*;
#[cfg(windows)]
pub use secure_fs::*;

use std::{io, path::Path};

pub struct DiskSpace {
    pub available_bytes: u64,
    pub total_bytes: u64,
}

#[cfg(windows)]
pub fn disk_space(path: &Path) -> io::Result<DiskSpace> {
    use std::os::windows::ffi::OsStrExt;
    use windows_sys::Win32::Storage::FileSystem::GetDiskFreeSpaceExW;

    let mut wide: Vec<u16> = path.as_os_str().encode_wide().collect();
    if wide.contains(&0) {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "path contains NUL",
        ));
    }
    wide.push(0);
    let mut available = 0_u64;
    let mut total = 0_u64;
    // SAFETY: wide is NUL-terminated and both output pointers remain valid for the call.
    let succeeded = unsafe {
        GetDiskFreeSpaceExW(
            wide.as_ptr(),
            &mut available,
            &mut total,
            std::ptr::null_mut(),
        )
    };
    if succeeded == 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(DiskSpace {
        available_bytes: available,
        total_bytes: total,
    })
}

#[cfg(not(windows))]
pub fn disk_space(_path: &Path) -> io::Result<DiskSpace> {
    Err(io::Error::new(
        io::ErrorKind::Unsupported,
        "disk inspection is currently Windows-only",
    ))
}
