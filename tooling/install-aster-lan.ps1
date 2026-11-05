[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
$lanvaultIdentity=[Security.Principal.WindowsIdentity]::GetCurrent()
$lanvaultPrincipal=New-Object Security.Principal.WindowsPrincipal($lanvaultIdentity)
if (!$lanvaultPrincipal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Open PowerShell as Administrator, then run this script. The installed ASTER and WFP rules require Windows administrator rights.'
}
$lanvaultProject=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$lanvaultArtifacts=Join-Path $lanvaultProject 'artifacts\aster-lan'
$lanvaultPackage=Join-Path $lanvaultArtifacts 'package'
$lanvaultManifest=Get-Content -LiteralPath (Join-Path $lanvaultArtifacts 'candidate-hashes.json') -Raw -Encoding UTF8 | ConvertFrom-Json
foreach ($lanvaultName in @('aster-client.exe','wintun.dll')) {
    $lanvaultFile=Join-Path $lanvaultPackage $lanvaultName
    if ((Get-Item -LiteralPath $lanvaultFile).Attributes -band [IO.FileAttributes]::ReparsePoint) {throw 'Refusing reparse point'}
    if ((Get-FileHash -LiteralPath $lanvaultFile -Algorithm SHA256).Hash -ne $lanvaultManifest.$lanvaultName) {throw "Candidate hash changed: $lanvaultName"}
}
$lanvaultRegistration=Join-Path $lanvaultArtifacts 'local-services.json'
if ((Get-FileHash -LiteralPath $lanvaultRegistration -Algorithm SHA256).Hash -ne $lanvaultManifest.'local-services.json') {throw 'Registration hash changed'}
$lanvaultNewConfig=Get-Content -LiteralPath $lanvaultRegistration -Raw -Encoding UTF8 | ConvertFrom-Json
foreach ($lanvaultService in $lanvaultNewConfig.services) {
    if (!(Test-Path -LiteralPath $lanvaultService.executable -PathType Leaf)) {throw 'Registered service executable does not exist; rebuild LanVault or update the registration first'}
}
$lanvaultAddress=$lanvaultNewConfig.services[0].local_ip
$lanvaultIP=Get-NetIPAddress -AddressFamily IPv4 -IPAddress $lanvaultAddress
$lanvaultAdapter=Get-NetAdapter -InterfaceIndex $lanvaultIP.InterfaceIndex
if (!$lanvaultAdapter.HardwareInterface -or $lanvaultAdapter.Status -ne 'Up') {throw 'Configured physical LAN adapter is unavailable'}
$lanvaultProfile=Get-NetConnectionProfile -InterfaceIndex $lanvaultIP.InterfaceIndex
if ($lanvaultProfile.NetworkCategory -ne 'Private') {throw 'Configured LAN must have a Private Windows network profile'}
$lanvaultInstalled=Join-Path $env:ProgramFiles 'ASTER\aster-client.exe'
$lanvaultData=Join-Path $env:ProgramData 'ASTER'
if ((Get-Item -LiteralPath $lanvaultData).Attributes -band [IO.FileAttributes]::ReparsePoint) {throw 'Refusing reparse point'}
$lanvaultRelease=Get-Content -LiteralPath (Join-Path $lanvaultData 'release-state.json') -Raw -Encoding UTF8 | ConvertFrom-Json
if ($null -ne $lanvaultRelease.pending) {throw 'Finish the existing ASTER update first'}
$lanvaultSequence=[uint64]$lanvaultRelease.highest + [uint64]1
# The existing trusted client uses the protected DPAPI key; no key is exported.
& $lanvaultInstalled release-sign --package $lanvaultPackage --version $lanvaultManifest.version --sequence $lanvaultSequence
if ($LASTEXITCODE -ne 0) {throw 'Signing failed; installed ASTER was not changed'}
$lanvaultConfig=Join-Path $lanvaultData 'local-services.json'
$lanvaultOldBytes=$null
if (Test-Path -LiteralPath $lanvaultConfig) {
    if ((Get-Item -LiteralPath $lanvaultConfig).Attributes -band [IO.FileAttributes]::ReparsePoint) {throw 'Refusing reparse point'}
    $lanvaultOldBytes=[IO.File]::ReadAllBytes($lanvaultConfig)
    $lanvaultExisting=[Text.Encoding]::UTF8.GetString($lanvaultOldBytes) | ConvertFrom-Json
    if ($lanvaultExisting.version -ne 1) {throw 'Existing local services have an unsupported version'}
    $lanvaultExe=$lanvaultNewConfig.services[0].executable
    $lanvaultOthers=@($lanvaultExisting.services | Where-Object {$_.executable -ne $lanvaultExe})
    $lanvaultNewConfig.services=@($lanvaultOthers)+@($lanvaultNewConfig.services)
}
if (@($lanvaultNewConfig.services).Count -gt 8) {throw 'Too many registered local services'}
$lanvaultTemporary=Join-Path $lanvaultData ('local-services-'+[guid]::NewGuid().ToString()+'.tmp')
$lanvaultCompleted=$false
try {
    [IO.File]::WriteAllText($lanvaultTemporary, ($lanvaultNewConfig | ConvertTo-Json -Depth 8), (New-Object Text.UTF8Encoding($false)))
    Move-Item -LiteralPath $lanvaultTemporary -Destination $lanvaultConfig -Force
    & $lanvaultInstalled update --package $lanvaultPackage
    if ($LASTEXITCODE -ne 0) {throw 'Signed update failed; ASTER handles rollback and retains its fail-closed protection'}
    $lanvaultCompleted=$true
    Write-Host 'ASTER update activated. Retry LanVault while leaving both VPNs enabled.'
} finally {
    if (!$lanvaultCompleted) {
        if ($null -ne $lanvaultOldBytes) {[IO.File]::WriteAllBytes($lanvaultConfig,$lanvaultOldBytes)}
        elseif (Test-Path -LiteralPath $lanvaultConfig) {Remove-Item -LiteralPath $lanvaultConfig}
    }
    if (Test-Path -LiteralPath $lanvaultTemporary) {Remove-Item -LiteralPath $lanvaultTemporary}
}
