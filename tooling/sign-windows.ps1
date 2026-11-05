[CmdletBinding()]
param([Parameter(Mandatory)][string]$Executable, [Parameter(Mandatory)][string]$CertificateThumbprint, [Parameter(Mandatory)][string]$SignTool, [string]$TimestampUrl = 'https://timestamp.digicert.com')
$ErrorActionPreference='Stop'
if ($CertificateThumbprint -notmatch '^[A-Fa-f0-9]{40}$') { throw 'Expected a certificate thumbprint in CurrentUser/My.' }
$lanvaultExe=(Resolve-Path -LiteralPath $Executable).Path
& $SignTool sign /sha1 $CertificateThumbprint /fd SHA256 /tr $TimestampUrl /td SHA256 $lanvaultExe
if ($LASTEXITCODE -ne 0) { throw 'Authenticode signing failed.' }
& $SignTool verify /pa $lanvaultExe
if ($LASTEXITCODE -ne 0) { throw 'Authenticode verification failed.' }
