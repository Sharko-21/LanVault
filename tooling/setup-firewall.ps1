[CmdletBinding()]
param([string]$Executable = (Join-Path $PSScriptRoot 'lanvault-desktop.exe'))
$ErrorActionPreference = 'Stop'
$lanvaultExe = (Resolve-Path -LiteralPath $Executable).Path
if ([IO.Path]::GetFileName($lanvaultExe) -ne 'lanvault-desktop.exe') { throw 'Select the LanVault receiver EXE.' }
if (!(Get-Command New-NetFirewallRule -ErrorAction SilentlyContinue)) { throw 'Windows NetSecurity module is required.' }
$lanvaultName = 'LanVault-LAN-' + [BitConverter]::ToString([Security.Cryptography.SHA256]::Create().ComputeHash([Text.Encoding]::UTF8.GetBytes($lanvaultExe.ToLowerInvariant()))).Replace('-','').Substring(0,12)
if (Get-NetFirewallRule -Name $lanvaultName -ErrorAction SilentlyContinue) {
    Write-Output 'The scoped LanVault rule already exists.'
} else {
    New-NetFirewallRule -Name $lanvaultName -DisplayName 'LanVault — private LAN receiver' -Direction Inbound -Action Allow -Program $lanvaultExe -Protocol TCP -LocalPort 43827 -Profile Private -RemoteAddress LocalSubnet | Out-Null
    Write-Output 'Created a rule for this EXE, TCP 43827, Private profile and LocalSubnet.'
}
