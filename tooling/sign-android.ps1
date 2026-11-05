[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$UnsignedApk,
    [Parameter(Mandatory)][string]$OutputApk,
    [Parameter(Mandatory)][string]$KeyStore,
    [string]$Alias = 'lanvault',
    [string]$Lineage = ''
)
$ErrorActionPreference = 'Stop'
if (!$env:LANVAULT_KEYSTORE_PASSWORD) { throw 'Set LANVAULT_KEYSTORE_PASSWORD in the process environment.' }
if (!$env:LANVAULT_KEY_PASSWORD) { $env:LANVAULT_KEY_PASSWORD = $env:LANVAULT_KEYSTORE_PASSWORD }
if (Test-Path -LiteralPath $OutputApk) { throw 'Output already exists; use a fresh candidate path.' }
$lanvaultSigner = Join-Path $env:ANDROID_HOME 'build-tools/36.0.0/apksigner.bat'
if (!(Test-Path -LiteralPath $lanvaultSigner)) { $lanvaultSigner = Join-Path $env:ANDROID_HOME 'build-tools/36.0.0/apksigner' }
$lanvaultSignArgs = @('sign', '--ks', (Resolve-Path -LiteralPath $KeyStore).Path, '--ks-key-alias', $Alias,
    '--ks-pass', 'env:LANVAULT_KEYSTORE_PASSWORD', '--key-pass', 'env:LANVAULT_KEY_PASSWORD',
    '--debuggable-apk-permitted', 'false', '--v1-signing-enabled', 'false', '--v3-signing-enabled', 'true',
    '--v4-signing-enabled', 'false', '--min-sdk-version', '29')
if ($Lineage) {
    # All supported devices are Android 10+. v3 carries the new signer and proof of rotation.
    # Avoid an old-key v2 block, which would require distributing the legacy private key to CI.
    $lanvaultSignArgs += @('--v2-signing-enabled', 'false', '--rotation-min-sdk-version', '28', '--lineage', (Resolve-Path -LiteralPath $Lineage).Path)
} else { $lanvaultSignArgs += @('--v2-signing-enabled', 'true') }
$lanvaultSignArgs += @('--out', [IO.Path]::GetFullPath($OutputApk), (Resolve-Path -LiteralPath $UnsignedApk).Path)
& $lanvaultSigner @lanvaultSignArgs
if ($LASTEXITCODE -ne 0) { throw 'APK signing failed.' }
& $lanvaultSigner verify --verbose --print-certs --min-sdk-version 29 $OutputApk
if ($LASTEXITCODE -ne 0) { throw 'Signed APK verification failed.' }
