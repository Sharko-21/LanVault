# Dot-source only. Changes are limited to the current process environment.
$lanvaultRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$lanvaultCompiler = Join-Path $lanvaultRoot '.tools\rust-gnu\bin'
$lanvaultMingw = Join-Path $lanvaultRoot '.tools\llvm-mingw\llvm-mingw-20261020-msvcrt-x86_64\bin'
if (!(Test-Path -LiteralPath (Join-Path $lanvaultCompiler 'rustc.exe')) -or !(Test-Path -LiteralPath (Join-Path $lanvaultMingw 'x86_64-w64-mingw32-clang.exe'))) {
    throw 'Portable GNU toolchain is not installed. Use the regular MSVC toolchain instead.'
}
$env:RUSTC = Join-Path $lanvaultCompiler 'rustc.exe'
$env:RUSTDOC = Join-Path $lanvaultCompiler 'rustdoc.exe'
$env:CARGO_HOME = Join-Path $lanvaultRoot '.cache\cargo'
$env:CARGO_TARGET_DIR = Join-Path $lanvaultRoot 'target\gnu'
$env:CARGO_TARGET_X86_64_PC_WINDOWS_GNU_LINKER = Join-Path $lanvaultMingw 'x86_64-w64-mingw32-clang.exe'
$env:CC_x86_64_pc_windows_gnu = Join-Path $lanvaultMingw 'x86_64-w64-mingw32-clang.exe'
$env:AR_x86_64_pc_windows_gnu = Join-Path $lanvaultMingw 'llvm-ar.exe'
$env:CARGO_ENCODED_RUSTFLAGS = '-L' + [char]31 + ('native=' + (Join-Path $lanvaultRoot '.tools\gcc-runtime'))
$env:PATH = "$lanvaultCompiler;$(Join-Path $lanvaultRoot '.tools\rust-gnu\lib');$lanvaultMingw;$(Join-Path $lanvaultRoot '.tools\rust\lib');$(Join-Path $lanvaultRoot '.tools\rustfmt');$(Join-Path $lanvaultRoot '.tools\cargo');$env:PATH"
