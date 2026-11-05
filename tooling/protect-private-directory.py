"""Restrict an explicitly selected private directory to its Windows user and SYSTEM."""
import argparse
import ctypes
from ctypes import wintypes
from pathlib import Path
import re
import stat

parser = argparse.ArgumentParser()
parser.add_argument('directory', type=Path)
parser.add_argument('--owner-sid', required=True)
args = parser.parse_args()
if not re.fullmatch(r'S-1-[0-9-]+', args.owner_sid):
    raise SystemExit('Invalid SID')
path = args.directory.absolute()
for parent in [path, *path.parents]:
    if parent.exists() and parent.stat(follow_symlinks=False).st_file_attributes & stat.FILE_ATTRIBUTE_REPARSE_POINT:
        raise SystemExit('Private directory must not traverse reparse points')
path.mkdir(parents=True, exist_ok=True)
advapi = ctypes.WinDLL('advapi32', use_last_error=True)
convert = advapi.ConvertStringSecurityDescriptorToSecurityDescriptorW
convert.argtypes = [wintypes.LPCWSTR, wintypes.DWORD, ctypes.POINTER(ctypes.c_void_p), ctypes.c_void_p]
convert.restype = wintypes.BOOL
set_security = advapi.SetFileSecurityW
set_security.argtypes = [wintypes.LPCWSTR, wintypes.DWORD, ctypes.c_void_p]
set_security.restype = wintypes.BOOL
free = ctypes.WinDLL('kernel32').LocalFree
free.argtypes = [ctypes.c_void_p]
free.restype = ctypes.c_void_p
descriptor = ctypes.c_void_p()
if not convert(f'D:P(A;OICI;FA;;;SY)(A;OICI;FA;;;{args.owner_sid})', 1, ctypes.byref(descriptor), None):
    raise ctypes.WinError(ctypes.get_last_error())
try:
    # Change DACL only. No SACL/owner operation or SeSecurityPrivilege is needed.
    if not set_security(str(path), 0x00000004 | 0x80000000, descriptor):
        raise ctypes.WinError(ctypes.get_last_error())
finally:
    free(descriptor)
print('Private directory ACL applied (current user and SYSTEM).')
