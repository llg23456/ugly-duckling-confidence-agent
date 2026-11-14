param([string]$SdkPath = 'D:\Android\Sdk')
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$previousAndroidHome = $env:ANDROID_HOME
Push-Location $projectRoot
try {
    if (!(Test-Path -LiteralPath $SdkPath)) { throw "Android SDK not found: $SdkPath" }
    $env:ANDROID_HOME = $SdkPath
    & .\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Cloud-test APK build failed: $LASTEXITCODE" }
    Write-Host 'APK: app\build\outputs\apk\debug\app-debug.apk (.cloudtest package, HTTPS invitation required)'
} finally {
    $env:ANDROID_HOME = $previousAndroidHome
    Pop-Location
}
