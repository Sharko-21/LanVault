[CmdletBinding()]
param([switch]$Portable, [string]$DesktopServerExe = '')
$ErrorActionPreference='Stop'
$lanvaultProject=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
Push-Location -LiteralPath $lanvaultProject
try {
    if ($Portable) {
        python tooling/prepare-android-build.py
        if ($LASTEXITCODE -ne 0) {throw 'Preparing Android build snapshot failed'}
        $lanvaultBuildRoot=(Get-Content -LiteralPath artifacts/android-build-root.txt -Raw).Trim()
        $env:JAVA_HOME=Join-Path $lanvaultBuildRoot 'jdk'
        $env:ANDROID_HOME=Join-Path $lanvaultBuildRoot 'sdk'
        $env:ANDROID_USER_HOME=Join-Path $lanvaultBuildRoot 'android-user'
        $env:GRADLE_USER_HOME=Join-Path $lanvaultBuildRoot 'gradle'
    } else { $lanvaultBuildRoot=$lanvaultProject }
    $env:PATH="$env:JAVA_HOME\bin;$env:PATH"
    node tooling/doctor.mjs --target=android --strict
    if ($LASTEXITCODE -ne 0) {throw 'Android toolchain is incomplete'}
    $lanvaultGradleArgs=@('-p',(Join-Path $lanvaultBuildRoot 'android'),'--no-daemon',':app:assembleDebug',':app:assembleDebugAndroidTest',':app:lintDebug','testDebugUnitTest',':core:model:test',':core:transport:test')
    if ($DesktopServerExe) {
        $lanvaultResolvedServer=(Resolve-Path -LiteralPath $DesktopServerExe).Path
        $lanvaultGradleArgs+=('-PlanvaultServerExe='+$lanvaultResolvedServer)
    }
    & (Join-Path $lanvaultBuildRoot 'android/gradlew.bat') @lanvaultGradleArgs
    if ($LASTEXITCODE -ne 0) {throw 'Android checks failed'}
    New-Item -ItemType Directory -Path artifacts/android -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $lanvaultBuildRoot 'android/app/build/outputs/apk/debug/app-debug.apk') -Destination artifacts/android/app-debug.apk -Force
    Copy-Item -LiteralPath (Join-Path $lanvaultBuildRoot 'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk') -Destination artifacts/android/app-debug-androidTest.apk -Force
    Copy-Item -LiteralPath (Join-Path $lanvaultBuildRoot 'android/app/build/reports/lint-results-debug.xml') -Destination artifacts/android/lint-results-debug.xml -Force
    if ($Portable) {
        Copy-Item -LiteralPath (Join-Path $lanvaultBuildRoot 'android/core/database/schemas') -Destination android/core/database -Recurse -Force
        python tooling/prepare-android-build.py --verify
        if ($LASTEXITCODE -ne 0) {throw 'Build inputs changed during checks'}
    }
    Get-FileHash -LiteralPath artifacts/android/app-debug.apk,artifacts/android/app-debug-androidTest.apk -Algorithm SHA256
} finally {Pop-Location}
