[CmdletBinding()]
param(
    [ValidateSet('Web', 'Android', 'Desktop', 'Server', 'All')]
    [string]$Target = 'Web',
    [switch]$PortableRust,
    [switch]$PortableAndroid,
    [string]$AndroidServerExe = ''
)

$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
Push-Location -LiteralPath $projectRoot
$failed = $false

function Invoke-Checked {
    param([string]$File, [string[]]$Arguments)
    & $File @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$File failed with exit code $LASTEXITCODE" }
}

try {
    if ($PortableRust) { . (Join-Path $PSScriptRoot 'use-portable-rust.ps1') }
    $doctorFlags = if ($PortableRust) { @('--portable') } else { @() }
    if ($Target -in @('Web', 'All')) {
        try {
            Invoke-Checked -File 'node' -Arguments @('tooling/doctor.mjs', '--target=web', '--strict')
            Invoke-Checked -File 'pnpm' -Arguments @('check')
        } catch { Write-Warning $_; $failed = $true }
    }
    if ($Target -in @('Desktop', 'All')) {
        try {
            Invoke-Checked -File 'node' -Arguments (@('tooling/doctor.mjs', '--target=desktop', '--strict') + $doctorFlags)
            Invoke-Checked -File 'cargo' -Arguments @('fmt', '--all', '--check')
            Invoke-Checked -File 'cargo' -Arguments @('test', '--locked', '-p', 'lanvault-domain', '-p', 'lanvault-protocol', '-p', 'lanvault-storage', '-p', 'lanvault-platform-windows', '-p', 'lanvault-database', '-p', 'lanvault-server')
            Invoke-Checked -File 'pnpm' -Arguments @('build:web')
            Invoke-Checked -File 'cargo' -Arguments @('clippy', '--locked', '--workspace', '--all-targets', '--features', 'lanvault-desktop/custom-protocol', '--', '-D', 'warnings')
            if ($PortableRust) { Invoke-Checked -File 'cargo' -Arguments @('build', '--locked', '-p', 'lanvault-desktop', '--features', 'custom-protocol') }
            else { Invoke-Checked -File 'pnpm' -Arguments @('build:desktop') }
        } catch { Write-Warning $_; $failed = $true }
    }
    if ($Target -in @('Server', 'Desktop', 'All')) {
        try {
            Invoke-Checked -File 'node' -Arguments (@('tooling/doctor.mjs', '--target=server', '--strict') + $doctorFlags)
            if ($Target -eq 'Server') {
                Invoke-Checked -File 'cargo' -Arguments @('fmt', '--all', '--check')
                Invoke-Checked -File 'cargo' -Arguments @('test', '--locked', '-p', 'lanvault-server')
            }
            Invoke-Checked -File 'cargo' -Arguments @('build', '--locked', '-p', 'lanvault-server')
            $nativeOutput = if ($env:CARGO_TARGET_DIR) { $env:CARGO_TARGET_DIR } else { Join-Path $projectRoot 'target' }
            Invoke-Checked -File 'python' -Arguments @('tooling/test-server.py', '--exe', (Join-Path $nativeOutput 'debug/lanvaultd.exe'))
        } catch { Write-Warning $_; $failed = $true }
    }
    if ($Target -in @('Android', 'All')) {
        try {
            & (Join-Path $PSScriptRoot 'check-android.ps1') -Portable:$PortableAndroid -DesktopServerExe $AndroidServerExe
            if ($LASTEXITCODE -ne 0) {throw 'Android checks failed'}
        } catch { Write-Warning $_; $failed = $true }
    }

} finally { Pop-Location }

if ($failed) { exit 1 }
