[CmdletBinding()]
param([Parameter(Mandatory)][string]$KeyStore, [Parameter(Mandatory)][string]$ProtectedPasswordFile, [Parameter(Mandatory)][string]$Destination)
$ErrorActionPreference='Stop'
if (Test-Path -LiteralPath $Destination) { throw 'Backup already exists; select a new file.' }
$lanvaultPhrase=Read-Host 'Choose a strong NEW passphrase for this offline signing-key backup' -AsSecureString
try {
    $env:LANVAULT_BACKUP_PASSWORD=[Net.NetworkCredential]::new('', $lanvaultPhrase).Password
    if ($env:LANVAULT_BACKUP_PASSWORD.Length -lt 16) { throw 'Use a passphrase of at least 16 characters.' }
    $env:LANVAULT_SOURCE_PASSWORD=[Net.NetworkCredential]::new('',((Get-Content -LiteralPath $ProtectedPasswordFile -Raw).Trim() | ConvertTo-SecureString)).Password
    & "$env:JAVA_HOME\bin\keytool.exe" -importkeystore -noprompt -srckeystore $KeyStore -srcalias lanvault -srcstorepass:env LANVAULT_SOURCE_PASSWORD -srckeypass:env LANVAULT_SOURCE_PASSWORD -destkeystore $Destination -destalias lanvault -deststoretype PKCS12 -deststorepass:env LANVAULT_BACKUP_PASSWORD -destkeypass:env LANVAULT_BACKUP_PASSWORD
    if ($LASTEXITCODE -ne 0) { throw 'Offline backup export failed.' }
    Write-Output 'Encrypted backup created. Store it offline and keep the passphrase separately.'
} finally {
    Remove-Item Env:\LANVAULT_BACKUP_PASSWORD,Env:\LANVAULT_SOURCE_PASSWORD -ErrorAction SilentlyContinue
}
