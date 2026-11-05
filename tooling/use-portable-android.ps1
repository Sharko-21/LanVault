# Dot-source to use project-local Android tools; changes affect this process only.
$lanvaultAndroidRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$lanvaultJdkDirectory = Get-ChildItem -LiteralPath (Join-Path $lanvaultAndroidRoot '.tools\android-jdk') -Directory | Select-Object -First 1
if (!$lanvaultJdkDirectory) { throw 'Local JDK 17 is not installed.' }
$env:JAVA_HOME = $lanvaultJdkDirectory.FullName
$env:ANDROID_HOME = Join-Path $lanvaultAndroidRoot '.tools\android-sdk'
$env:ANDROID_USER_HOME = Join-Path $lanvaultAndroidRoot '.cache\android'
$env:GRADLE_USER_HOME = Join-Path $lanvaultAndroidRoot '.cache\gradle'
$env:PATH = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:PATH"
