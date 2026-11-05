use std::{
    fs::{File, OpenOptions},
    io,
    os::windows::{
        ffi::OsStrExt,
        fs::{MetadataExt, OpenOptionsExt},
        io::{AsRawHandle, FromRawHandle},
    },
    path::{Component, Path, PathBuf},
};
use windows_sys::Win32::Storage::FileSystem::*;
use windows_sys::{
    Wdk::{Foundation::OBJECT_ATTRIBUTES, Storage::FileSystem as nt},
    Win32::{
        Foundation::{
            RtlNtStatusToDosError, OBJ_CASE_INSENSITIVE, OBJ_DONT_REPARSE, UNICODE_STRING,
        },
        System::IO::IO_STATUS_BLOCK,
    },
};

fn invalid(message: &str) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidInput, message)
}

pub fn wide(path: &Path) -> io::Result<Vec<u16>> {
    let mut value: Vec<u16> = path.as_os_str().encode_wide().collect();
    if value.contains(&0) {
        return Err(invalid("NUL in path"));
    }
    value.push(0);
    Ok(value)
}

pub struct DirectoryGuard {
    path: PathBuf,
    // No FILE_SHARE_DELETE: all ancestors stay locked against renaming/replacement.
    handles: Vec<File>,
}

impl DirectoryGuard {
    pub fn create(path: &Path) -> io::Result<Self> {
        if path.exists() {
            return Self::open(path);
        }
        let parent = path.parent().ok_or_else(|| invalid("missing parent"))?;
        let name = path
            .file_name()
            .and_then(|v| v.to_str())
            .ok_or_else(|| invalid("invalid directory name"))?;
        Self::create(parent)?.child(&[name])
    }
    pub fn open(path: &Path) -> io::Result<Self> {
        use std::path::Prefix;
        if !path.is_absolute()
            || !matches!(path.components().next(),Some(Component::Prefix(p)) if matches!(p.kind(),Prefix::Disk(_) | Prefix::VerbatimDisk(_)))
        {
            return Err(invalid("only absolute local disk paths are supported"));
        }
        let mut handles = Vec::new();
        let mut current = PathBuf::new();
        for part in path.components() {
            match part {
                Component::ParentDir | Component::CurDir => {
                    return Err(invalid("noncanonical path"))
                }
                Component::Prefix(_) => {
                    current.push(part.as_os_str());
                    continue;
                }
                _ => current.push(part.as_os_str()),
            }
            if !current.has_root() {
                continue;
            }
            let handle = if let Some(parent) = handles.last() {
                open_relative(
                    parent,
                    part.as_os_str()
                        .to_str()
                        .ok_or_else(|| invalid("invalid directory name"))?,
                    true,
                    false,
                    false,
                )
                .inspect_err(|e| {
                    eprintln!(
                        "directory_open depth={} win32={:?}",
                        handles.len(),
                        e.raw_os_error()
                    )
                })?
            } else {
                open_directory(&current)?
            };
            handles.push(handle);
        }
        let path = final_path(handles.last().ok_or_else(|| invalid("missing directory"))?)?;
        Ok(Self { path, handles })
    }
    pub fn path(&self) -> &Path {
        &self.path
    }
    pub fn identity(&self) -> io::Result<String> {
        let mut info = BY_HANDLE_FILE_INFORMATION::default();
        // SAFETY: handle is live and output has the correct layout.
        if unsafe { GetFileInformationByHandle(self.handle().as_raw_handle(), &mut info) } == 0 {
            return Err(io::Error::last_os_error());
        }
        Ok(format!(
            "{}:{}:{}",
            info.dwVolumeSerialNumber, info.nFileIndexHigh, info.nFileIndexLow
        ))
    }
    fn handle(&self) -> &File {
        &self.handles[self.handles.len() - 1]
    }
    pub fn child(&self, components: &[&str]) -> io::Result<Self> {
        let mut current = self.path.clone();
        let mut handles = self
            .handles
            .iter()
            .map(File::try_clone)
            .collect::<io::Result<Vec<_>>>()?;
        for part in components {
            validate_component(part)?;
            current.push(part);
            let parent = handles.last().ok_or_else(|| invalid("missing parent"))?;
            handles.push(open_relative(parent, part, true, true, false)?);
        }
        Ok(Self {
            path: current,
            handles,
        })
    }
    pub fn open_existing_child(&self, components: &[&str]) -> io::Result<Self> {
        let mut path = self.path.clone();
        let mut handles = self
            .handles
            .iter()
            .map(File::try_clone)
            .collect::<io::Result<Vec<_>>>()?;
        for component in components {
            validate_component(component)?;
            path.push(component);
            let parent = handles.last().ok_or_else(|| invalid("missing parent"))?;
            handles.push(open_relative(parent, component, true, false, false)?);
        }
        Ok(Self { path, handles })
    }
    pub fn create_file(&self, name: &str) -> io::Result<File> {
        validate_component(name)?;
        open_relative(self.handle(), name, false, true, true)
    }
    pub fn open_file(&self, name: &str, writable: bool) -> io::Result<File> {
        validate_component(name)?;
        open_relative(self.handle(), name, false, false, writable)
    }
    pub fn remove_file(&self, name: &str) -> io::Result<()> {
        // Open relative to a pinned directory; reject junctions, links and ADS first.
        let file = match self.open_file(name, true) {
            Ok(file) => file,
            Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(()),
            Err(error) => return Err(error),
        };
        let disposition = FILE_DISPOSITION_INFO { DeleteFile: true };
        // SAFETY: the checked regular-file handle has DELETE access; the structure is live.
        if unsafe {
            SetFileInformationByHandle(
                file.as_raw_handle(),
                FileDispositionInfo,
                (&disposition as *const FILE_DISPOSITION_INFO).cast(),
                std::mem::size_of::<FILE_DISPOSITION_INFO>() as u32,
            )
        } == 0
        {
            return Err(io::Error::last_os_error());
        }
        Ok(())
    }
    pub fn publish(&self, file: &File, name: &str) -> io::Result<()> {
        validate_component(name)?;
        // NT handle-relative rename never resolves an ancestor through its path.
        let name_utf16: Vec<u16> = name.encode_utf16().collect();
        let byte_count = std::mem::offset_of!(nt::FILE_RENAME_INFORMATION, FileName)
            + (name_utf16.len() + 1) * 2;
        let mut buffer = vec![0_u64; byte_count.div_ceil(8)];
        // SAFETY: aligned storage is large enough for the header and UTF-16 name.
        unsafe {
            let header = buffer.as_mut_ptr().cast::<nt::FILE_RENAME_INFORMATION>();
            (*header).Anonymous.ReplaceIfExists = false;
            (*header).RootDirectory = self.handle().as_raw_handle();
            (*header).FileNameLength = (name_utf16.len() * 2) as u32;
            std::ptr::copy_nonoverlapping(
                name_utf16.as_ptr(),
                std::ptr::addr_of_mut!((*header).FileName).cast::<u16>(),
                name_utf16.len(),
            );
            let mut status = IO_STATUS_BLOCK::default();
            let result = nt::NtSetInformationFile(
                file.as_raw_handle(),
                &mut status,
                header.cast(),
                byte_count as u32,
                nt::FileRenameInformation,
            );
            if result < 0 {
                return Err(io::Error::from_raw_os_error(
                    RtlNtStatusToDosError(result) as i32
                ));
            }
        }
        file.sync_all()
    }
}

fn final_path(file: &File) -> io::Result<PathBuf> {
    let mut buffer = vec![0_u16; 32768];
    // SAFETY: live handle and writable UTF-16 output; zero flags request a normalized DOS path.
    let length = unsafe {
        GetFinalPathNameByHandleW(
            file.as_raw_handle(),
            buffer.as_mut_ptr(),
            buffer.len() as u32,
            0,
        )
    };
    if length == 0 || length as usize >= buffer.len() {
        return Err(io::Error::last_os_error());
    }
    Ok(PathBuf::from(
        String::from_utf16(&buffer[..length as usize]).map_err(io::Error::other)?,
    ))
}

fn open_directory(path: &Path) -> io::Result<File> {
    // Attribute-only handles do not participate in Windows sharing checks.
    // FILE_LIST_DIRECTORY makes deny-delete effective for every ancestor.
    let handle = OpenOptions::new()
        .access_mode(FILE_READ_ATTRIBUTES | FILE_LIST_DIRECTORY)
        .share_mode(FILE_SHARE_READ | FILE_SHARE_WRITE)
        .custom_flags(FILE_FLAG_OPEN_REPARSE_POINT | FILE_FLAG_BACKUP_SEMANTICS)
        .open(path)?;
    let metadata = handle.metadata()?;
    if !metadata.is_dir() || metadata.file_attributes() & FILE_ATTRIBUTE_REPARSE_POINT != 0 {
        return Err(invalid("reparse points are forbidden"));
    }
    Ok(handle)
}

fn open_relative(
    parent: &File,
    name: &str,
    directory: bool,
    create: bool,
    writable: bool,
) -> io::Result<File> {
    validate_component(name)?;
    if parent.metadata()?.file_attributes() & FILE_ATTRIBUTE_REPARSE_POINT != 0 {
        return Err(invalid("reparse parent"));
    }
    let mut wide: Vec<u16> = name.encode_utf16().collect();
    let length = u16::try_from(wide.len() * 2).map_err(io::Error::other)?;
    let object_name = UNICODE_STRING {
        Length: length,
        MaximumLength: length,
        Buffer: wide.as_mut_ptr(),
    };
    let attributes = OBJECT_ATTRIBUTES {
        Length: std::mem::size_of::<OBJECT_ATTRIBUTES>() as u32,
        RootDirectory: parent.as_raw_handle(),
        ObjectName: &object_name,
        Attributes: OBJ_CASE_INSENSITIVE | OBJ_DONT_REPARSE,
        ..Default::default()
    };
    let access = if directory {
        FILE_LIST_DIRECTORY | FILE_TRAVERSE | FILE_READ_ATTRIBUTES | SYNCHRONIZE
    } else if writable {
        FILE_GENERIC_READ | FILE_GENERIC_WRITE | DELETE
    } else {
        FILE_GENERIC_READ
    };
    let disposition = if create {
        if directory {
            nt::FILE_OPEN_IF
        } else {
            nt::FILE_CREATE
        }
    } else {
        nt::FILE_OPEN
    };
    let options = nt::FILE_OPEN_REPARSE_POINT
        | nt::FILE_SYNCHRONOUS_IO_NONALERT
        | if directory {
            nt::FILE_DIRECTORY_FILE
        } else {
            nt::FILE_NON_DIRECTORY_FILE
        };
    let share = if directory {
        FILE_SHARE_READ | FILE_SHARE_WRITE
    } else {
        FILE_SHARE_READ
    };
    let mut handle = std::ptr::null_mut();
    let mut status = IO_STATUS_BLOCK::default();
    // SAFETY: all inputs live through this synchronous call, and returned ownership is transferred exactly once.
    let result = unsafe {
        nt::NtCreateFile(
            &mut handle,
            access,
            &attributes,
            &mut status,
            std::ptr::null(),
            FILE_ATTRIBUTE_NORMAL,
            share,
            disposition,
            options,
            std::ptr::null(),
            0,
        )
    };
    if result < 0 {
        return Err(io::Error::from_raw_os_error(
            unsafe { RtlNtStatusToDosError(result) } as i32,
        ));
    }
    let file = unsafe { File::from_raw_handle(handle) };
    if directory {
        let metadata = file.metadata()?;
        if !metadata.is_dir() || metadata.file_attributes() & FILE_ATTRIBUTE_REPARSE_POINT != 0 {
            return Err(invalid("reparse directory"));
        }
    } else {
        check_regular_file(&file)?;
    }
    Ok(file)
}

fn check_regular_file(file: &File) -> io::Result<()> {
    let metadata = file.metadata()?;
    let mut info = BY_HANDLE_FILE_INFORMATION::default();
    if unsafe { GetFileInformationByHandle(file.as_raw_handle(), &mut info) } == 0 {
        return Err(io::Error::last_os_error());
    }
    if !metadata.is_file()
        || metadata.file_attributes() & FILE_ATTRIBUTE_REPARSE_POINT != 0
        || info.nNumberOfLinks != 1
    {
        return Err(invalid("not a regular single-link file"));
    }
    Ok(())
}

pub fn validate_component(value: &str) -> io::Result<()> {
    if value.is_empty()
        || value.len() > 220
        || value == "."
        || value == ".."
        || value.ends_with(['.', ' '])
        || value
            .chars()
            .any(|c| c.is_control() || "<>:\"/\\|?*".contains(c))
    {
        return Err(invalid("unsafe path component"));
    }
    let base = value.split('.').next().unwrap_or("").to_ascii_uppercase();
    if matches!(base.as_str(), "CON" | "PRN" | "AUX" | "NUL")
        || (base.len() == 4
            && (base.starts_with("COM") || base.starts_with("LPT"))
            && base.as_bytes()[3].is_ascii_digit())
    {
        return Err(invalid("reserved path component"));
    }
    Ok(())
}

pub fn require_local_ntfs(path: &Path) -> io::Result<()> {
    let path = wide(path)?;
    let mut root = vec![0_u16; 32768];
    let mut fs_name = [0_u16; 64];
    // SAFETY: all buffers are live, correctly sized and terminated.
    unsafe {
        if GetVolumePathNameW(path.as_ptr(), root.as_mut_ptr(), root.len() as u32) == 0 {
            return Err(io::Error::last_os_error());
        }
        // DRIVE_REMOVABLE=2, DRIVE_FIXED=3 (GetDriveTypeW).
        if !matches!(GetDriveTypeW(root.as_ptr()), 2 | 3) {
            return Err(invalid("a local disk is required"));
        }
        if GetVolumeInformationW(
            root.as_ptr(),
            std::ptr::null_mut(),
            0,
            std::ptr::null_mut(),
            std::ptr::null_mut(),
            std::ptr::null_mut(),
            fs_name.as_mut_ptr(),
            64,
        ) == 0
        {
            return Err(io::Error::last_os_error());
        }
    }
    let len = fs_name
        .iter()
        .position(|c| *c == 0)
        .unwrap_or(fs_name.len());
    if String::from_utf16_lossy(&fs_name[..len]) != "NTFS" {
        return Err(invalid("NTFS is required"));
    }
    Ok(())
}
