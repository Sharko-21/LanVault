//! Managed archive storage. Publication is a no-replace rename of a verified file handle.

#[cfg(windows)]
mod archive;
#[cfg(windows)]
pub use archive::*;

use serde::Serialize;
use std::{fs, io, path::Path};

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FolderInfo {
    pub path: String,
    pub available_bytes: String,
    pub total_bytes: String,
}

pub fn inspect_folder(path: &Path) -> io::Result<FolderInfo> {
    if !path.is_absolute() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "an absolute folder is required",
        ));
    }
    #[cfg(windows)]
    {
        use std::path::{Component, Prefix};
        if matches!(path.components().next(), Some(Component::Prefix(prefix))
            if matches!(prefix.kind(), Prefix::UNC(..) | Prefix::VerbatimUNC(..) | Prefix::DeviceNS(..)))
        {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "network and device paths are unsupported",
            ));
        }
    }
    for ancestor in path.ancestors() {
        let metadata = fs::symlink_metadata(ancestor)?;
        if metadata.file_type().is_symlink() || is_reparse_point(&metadata) {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "linked folders are unsupported",
            ));
        }
    }
    if !fs::metadata(path)?.is_dir() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "not a directory",
        ));
    }
    let canonical = fs::canonicalize(path)?;
    let space = lanvault_platform_windows::disk_space(&canonical)?;
    Ok(FolderInfo {
        path: canonical.to_string_lossy().into_owned(),
        available_bytes: space.available_bytes.to_string(),
        total_bytes: space.total_bytes.to_string(),
    })
}

#[cfg(windows)]
fn is_reparse_point(metadata: &fs::Metadata) -> bool {
    use std::os::windows::fs::MetadataExt;
    metadata.file_attributes() & 0x400 != 0
}

#[cfg(not(windows))]
fn is_reparse_point(_metadata: &fs::Metadata) -> bool {
    false
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_relative_paths() {
        assert!(inspect_folder(Path::new("relative/archive")).is_err());
    }

    #[cfg(windows)]
    #[test]
    fn inspects_without_creating_files() -> io::Result<()> {
        let root = std::env::temp_dir().join(format!("lanvault-stage2-{}", std::process::id()));
        fs::create_dir(&root)?;
        let inspected = inspect_folder(&root);
        let entries = fs::read_dir(&root)?.count();
        fs::remove_dir(&root)?;
        let info = inspected?;
        assert_eq!(entries, 0);
        assert!(info.total_bytes.parse::<u64>().map_err(io::Error::other)? > 0);
        Ok(())
    }
}
