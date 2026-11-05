use std::{io, path::Path, ptr, slice};
use windows_sys::Win32::{
    Foundation::{CloseHandle, LocalFree},
    Security::{Authorization::*, Cryptography::*, *},
    System::Threading::*,
};

pub fn protect(bytes: &[u8]) -> io::Result<Vec<u8>> {
    dpapi(bytes, true)
}
pub fn unprotect(bytes: &[u8]) -> io::Result<Vec<u8>> {
    dpapi(bytes, false)
}

fn dpapi(bytes: &[u8], encrypt: bool) -> io::Result<Vec<u8>> {
    let input = CRYPT_INTEGER_BLOB {
        cbData: u32::try_from(bytes.len()).map_err(io::Error::other)?,
        pbData: bytes.as_ptr().cast_mut(),
    };
    let mut output = CRYPT_INTEGER_BLOB::default();
    // SAFETY: DPAPI receives valid buffers; the returned LocalAlloc memory is freed below.
    let success = unsafe {
        if encrypt {
            CryptProtectData(
                &input,
                ptr::null(),
                ptr::null(),
                ptr::null(),
                ptr::null(),
                CRYPTPROTECT_UI_FORBIDDEN,
                &mut output,
            )
        } else {
            CryptUnprotectData(
                &input,
                ptr::null_mut(),
                ptr::null(),
                ptr::null(),
                ptr::null(),
                CRYPTPROTECT_UI_FORBIDDEN,
                &mut output,
            )
        }
    };
    if success == 0 {
        return Err(io::Error::last_os_error());
    }
    let result = if output.cbData == 0 {
        Vec::new()
    } else {
        unsafe { slice::from_raw_parts(output.pbData, output.cbData as usize).to_vec() }
    };
    unsafe {
        LocalFree(output.pbData.cast());
    }
    Ok(result)
}

/// This is called only on the application's dedicated state directory, never on the archive.
pub fn restrict_state_directory(path: &Path) -> io::Result<()> {
    let mut token = ptr::null_mut();
    // SAFETY: output pointer and pseudo process handle are valid.
    if unsafe { OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &mut token) } == 0 {
        return Err(io::Error::last_os_error());
    }
    let result = (|| {
        let mut needed = 0;
        unsafe {
            GetTokenInformation(token, TokenUser, ptr::null_mut(), 0, &mut needed);
        }
        let mut storage = vec![0_u64; (needed as usize).div_ceil(8)];
        if unsafe {
            GetTokenInformation(
                token,
                TokenUser,
                storage.as_mut_ptr().cast(),
                needed,
                &mut needed,
            )
        } == 0
        {
            return Err(io::Error::last_os_error());
        }
        let user = unsafe { &*storage.as_ptr().cast::<TOKEN_USER>() };
        let mut sid_text = ptr::null_mut();
        if unsafe { ConvertSidToStringSidW(user.User.Sid, &mut sid_text) } == 0 {
            return Err(io::Error::last_os_error());
        }
        let mut len = 0;
        unsafe {
            while *sid_text.add(len) != 0 {
                len += 1;
            }
        }
        let sid = unsafe { String::from_utf16_lossy(slice::from_raw_parts(sid_text, len)) };
        unsafe {
            LocalFree(sid_text.cast());
        }
        let sddl: Vec<u16> = format!("D:P(A;OICI;FA;;;SY)(A;OICI;FA;;;{sid})\0")
            .encode_utf16()
            .collect();
        let mut descriptor = ptr::null_mut();
        if unsafe {
            ConvertStringSecurityDescriptorToSecurityDescriptorW(
                sddl.as_ptr(),
                SDDL_REVISION_1,
                &mut descriptor,
                ptr::null_mut(),
            )
        } == 0
        {
            return Err(io::Error::last_os_error());
        }
        let path = crate::wide(path)?;
        let success = unsafe {
            SetFileSecurityW(
                path.as_ptr(),
                DACL_SECURITY_INFORMATION | PROTECTED_DACL_SECURITY_INFORMATION,
                descriptor,
            )
        };
        unsafe {
            LocalFree(descriptor);
        }
        if success == 0 {
            return Err(io::Error::last_os_error());
        }
        Ok(())
    })();
    unsafe {
        CloseHandle(token);
    }
    result
}
