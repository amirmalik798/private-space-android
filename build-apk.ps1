$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
if (-not $env:ANDROID_HOME -and (Test-Path "$env:LOCALAPPDATA\Android\Sdk")) { $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk" }
if (-not $env:ANDROID_HOME) { throw "Android SDK not found. Install Android Studio and SDK Platform 36." }
if (-not (Test-Path (Join-Path $env:ANDROID_HOME "platforms\android-36\android.jar"))) { throw "Android SDK Platform 36 is missing." }
if (-not (Get-Command gradle -ErrorAction SilentlyContinue)) { throw "Gradle 8.13 was not found on PATH." }
gradle assembleDebug
if ($LASTEXITCODE -ne 0) { throw "APK build failed." }
$apk = Join-Path $PSScriptRoot "app\build\outputs\apk\debug\app-debug.apk"
if (Test-Path $apk) { Write-Host "APK created: $apk" } else { throw "Expected APK was not found." }
