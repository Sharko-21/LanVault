use lanvault_platform_windows::{require_local_ntfs, validate_component, DirectoryGuard};
use sha2::{Digest, Sha256};
use std::{
    fs::File,
    io::{self, Read, Seek, SeekFrom, Write},
    path::Path,
};
use uuid::Uuid;

fn normalize_disk_path(path: &Path) -> String {
    let value = path.to_string_lossy().to_lowercase();
    value.strip_prefix(r"\\?\").unwrap_or(&value).to_owned()
}

pub struct ArchiveRoot {
    pub directory: DirectoryGuard,
    staging: DirectoryGuard,
    pub identity: String,
}

impl ArchiveRoot {
    pub fn open(path: &Path) -> io::Result<Self> {
        let directory = DirectoryGuard::open(path)?;
        require_local_ntfs(directory.path())?;
        let normalized = normalize_disk_path(directory.path());
        for variable in ["OneDrive", "OneDriveConsumer", "OneDriveCommercial"] {
            if let Some(base) = std::env::var_os(variable) {
                if let Ok(base) = std::fs::canonicalize(base) {
                    let base = normalize_disk_path(&base);
                    if normalized == base || normalized.starts_with(&(base + "\\")) {
                        return Err(io::Error::new(
                            io::ErrorKind::InvalidInput,
                            "cloud-synchronized roots are not supported",
                        ));
                    }
                }
            }
        }
        let identity = directory.identity()?;
        let staging = directory.child(&[".lanvault", "staging"])?;
        Ok(Self {
            directory,
            staging,
            identity,
        })
    }
    pub fn path(&self) -> &Path {
        self.directory.path()
    }
    pub fn new_upload(&self, id: Uuid) -> io::Result<File> {
        self.staging.create_file(&format!("{id}.part"))
    }
    pub fn reset_upload(&self, id: Uuid) -> io::Result<File> {
        match self.new_upload(id) {
            Ok(file) => Ok(file),
            Err(e) if e.kind() == io::ErrorKind::AlreadyExists => {
                let file = self.staging.open_file(&format!("{id}.part"), true)?;
                file.set_len(0)?;
                file.sync_all()?;
                Ok(file)
            }
            Err(e) => Err(e),
        }
    }
    pub fn staged(&self, id: Uuid) -> io::Result<File> {
        self.staging.open_file(&format!("{id}.part"), true)
    }
    pub fn discard_staged(&self, id: Uuid) -> io::Result<()> {
        self.staging.remove_file(&format!("{id}.part"))
    }
    pub fn saved(&self, relative: &str) -> io::Result<File> {
        let parts = relative_parts(relative)?;
        let directory = self
            .directory
            .open_existing_child(&parts[..parts.len() - 1])?;
        directory.open_file(parts[parts.len() - 1], false)
    }
    pub fn publish(&self, file: &File, relative: &str) -> io::Result<()> {
        let parts = relative_parts(relative)?;
        let parent = self.directory.child(&parts[..parts.len() - 1])?;
        parent.publish(file, parts[parts.len() - 1])
    }
    pub fn space(&self) -> io::Result<super::FolderInfo> {
        let space = lanvault_platform_windows::disk_space(self.path())?;
        Ok(super::FolderInfo {
            path: self.path().to_string_lossy().into_owned(),
            available_bytes: space.available_bytes.to_string(),
            total_bytes: space.total_bytes.to_string(),
        })
    }
    pub fn ensure_marker(&self, dataset_id: Uuid) -> io::Result<()> {
        let meta = self.directory.child(&[".lanvault"])?;
        match meta.create_file("dataset.id") {
            Ok(mut file) => {
                file.write_all(dataset_id.to_string().as_bytes())?;
                file.sync_all()
            }
            Err(e) if e.kind() == io::ErrorKind::AlreadyExists => {
                let file = meta.open_file("dataset.id", false)?;
                let mut value = String::new();
                file.take(128).read_to_string(&mut value)?;
                if value != dataset_id.to_string() {
                    return Err(io::Error::new(
                        io::ErrorKind::InvalidData,
                        "foreign or damaged archive marker",
                    ));
                }
                Ok(())
            }
            Err(e) => Err(e),
        }
    }
}

fn relative_parts(relative: &str) -> io::Result<Vec<&str>> {
    let parts: Vec<_> = relative.split('/').collect();
    if parts.len() != 5 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "invalid archive path",
        ));
    }
    for part in &parts {
        validate_component(part)?;
    }
    Uuid::parse_str(parts[0]).map_err(io::Error::other)?;
    if !matches!(parts[1], "Photos" | "Videos")
        || parts[2].len() != 4
        || parts[3].len() != 2
        || !parts[2].bytes().all(|b| b.is_ascii_digit())
        || !parts[3].bytes().all(|b| b.is_ascii_digit())
    {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "invalid archive hierarchy",
        ));
    }
    Ok(parts)
}

pub fn destination(
    device: Uuid,
    object: Uuid,
    photo: bool,
    date: chrono::DateTime<chrono::Utc>,
    original: &str,
) -> String {
    use chrono::Datelike;
    let mut name = String::new();
    for c in original.chars() {
        let c = if c.is_control() || "<>:\"/\\|?*".contains(c) {
            '_'
        } else {
            c
        };
        if name.len() + c.len_utf8() > 150 {
            break;
        }
        name.push(c);
    }
    let mut name = name.trim_matches(['.', ' ']).to_string();
    if name.is_empty() {
        name = "media.bin".into();
    }
    let extension = name.rsplit('.').next().unwrap_or("").to_ascii_lowercase();
    const EXECUTABLES: &[&str] = &[
        "exe",
        "com",
        "dll",
        "scr",
        "cpl",
        "msi",
        "msp",
        "bat",
        "cmd",
        "ps1",
        "vbs",
        "vbe",
        "js",
        "jse",
        "wsf",
        "wsh",
        "lnk",
        "url",
        "hta",
        "html",
        "htm",
        "svg",
        "reg",
        "scf",
        "pif",
        "appref-ms",
    ];
    if EXECUTABLES.contains(&extension.as_str()) {
        name.push_str(".bin");
    }
    format!(
        "{}/{}/{:04}/{:02}/{}__{}",
        device,
        if photo { "Photos" } else { "Videos" },
        date.year().clamp(1970, 9999),
        date.month(),
        object,
        name
    )
}

pub fn verify(file: &mut File, size: u64, expected: &str) -> io::Result<bool> {
    if file.metadata()?.len() != size {
        return Ok(false);
    }
    file.seek(SeekFrom::Start(0))?;
    let mut hasher = Sha256::new();
    let mut buffer = [0_u8; 256 * 1024];
    let mut count = 0_u64;
    loop {
        let read = file.read(&mut buffer)?;
        if read == 0 {
            break;
        }
        count = count
            .checked_add(read as u64)
            .ok_or_else(|| io::Error::other("length overflow"))?;
        if count > size {
            return Ok(false);
        }
        hasher.update(&buffer[..read]);
    }
    Ok(count == size && format!("{:x}", hasher.finalize()) == expected)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn traversal_is_only_metadata() {
        let path = destination(
            Uuid::nil(),
            Uuid::nil(),
            true,
            chrono::Utc::now(),
            "../../CON:stream.exe",
        );
        assert_eq!(path.split('/').count(), 5);
        assert!(path.ends_with(".exe.bin"));
        assert!(relative_parts(&path).is_ok());
        assert!(relative_parts("../Photos/2026/01/evil.jpg").is_err());
    }
}
