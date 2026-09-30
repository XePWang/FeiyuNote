<#
.SYNOPSIS
  Local CI/CD for 肥鱼笔记: build, unit tests, emulator instrumented + UI tests, APK artifact.

.EXAMPLE
  pwsh scripts/ci.ps1          # fast: unit tests + debug/test APK builds + artifact
  pwsh scripts/ci.ps1 -Full    # fast + all instrumented/UI tests on the emulator
#>
param(
    [switch]$Full,
    [string]$Serial = 'emulator-5554',
    [string]$Avd = 'Feiyu_Fold_API36'
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [Console]::OutputEncoding
if ($Serial -ne 'emulator-5554') { throw 'This pipeline only targets emulator-5554.' }
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

# Reuse the machine's toolchain; fall back to the standard install locations.
if (-not $env:JAVA_HOME) {
    $env:JAVA_HOME = (Get-ChildItem "$env:USERPROFILE\.jdks" -Directory -Filter 'jdk-21*' | Select-Object -First 1).FullName
}
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk" }
$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
if (-not (Test-Path -LiteralPath $adb)) { $adb = (Get-Command adb -ErrorAction Stop).Source }
$emulator = Join-Path $env:ANDROID_HOME 'emulator\emulator.exe'
$logDir = Join-Path $root 'build\ci'
New-Item -ItemType Directory -Force $logDir | Out-Null

function Invoke-Step([string]$Name, [scriptblock]$Body) {
    Write-Host "==> $Name" -ForegroundColor Cyan
    $started = Get-Date
    & $Body
    if ($LASTEXITCODE -ne 0) { throw "$Name failed (exit $LASTEXITCODE)" }
    Write-Host ("    ok in {0:N0}s" -f ((Get-Date) - $started).TotalSeconds) -ForegroundColor Green
}

Invoke-Step 'Gradle: unit tests, app and test APKs' {
    & .\gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest --console=plain *> (Join-Path $logDir 'gradle.log')
    if ($LASTEXITCODE -ne 0) { Get-Content (Join-Path $logDir 'gradle.log') -Tail 40 }
}

if ($Full) {
    Invoke-Step "Emulator $Serial" {
        $online = (& $adb devices) -match "^$Serial\s+device"
        if (-not $online) {
            Write-Host "    starting $Avd headless"
            Start-Process -FilePath $emulator -ArgumentList '-avd', $Avd, '-no-window', '-no-audio', '-no-boot-anim', '-no-snapshot-save' -WindowStyle Hidden
            & $adb -s $Serial wait-for-device
        }
        $deadline = (Get-Date).AddMinutes(3)
        while ((& $adb -s $Serial shell getprop sys.boot_completed 2>$null) -ne '1') {
            if ((Get-Date) -gt $deadline) { throw "emulator $Serial did not finish booting" }
            Start-Sleep -Seconds 2
        }
        & $adb -s $Serial shell input keyevent KEYCODE_WAKEUP | Out-Null
        & $adb -s $Serial shell svc power stayon true | Out-Null
        & $adb -s $Serial shell wm dismiss-keyguard | Out-Null
        $global:LASTEXITCODE = 0
    }

    Invoke-Step 'Install app and test APKs (emulator only)' {
        & $adb -s $Serial install -r -t app\build\outputs\apk\debug\app-debug.apk | Out-Null
        if ($LASTEXITCODE -eq 0) { & $adb -s $Serial install -r -t app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk | Out-Null }
    }

    Invoke-Step 'Instrumented + UI tests' {
        $log = Join-Path $logDir 'instrumented.log'
        & $adb -s $Serial shell am instrument -w com.feiyu.notes.test/androidx.test.runner.AndroidJUnitRunner *> $log
        $out = Get-Content $log -Raw
        # am instrument exits 0 even on failures; the runner's summary line is the verdict.
        if ($out -match '(?m)^OK \((\d+) tests?\)') {
            Write-Host "    $($Matches[0])"
            $global:LASTEXITCODE = 0
        } else {
            Get-Content $log | Where-Object { $_ -notmatch '^\s+at ' } | Select-Object -Last 40
            $global:LASTEXITCODE = 1
        }
    }
    Invoke-Step 'UI screenshots' {
        $screenshots = Join-Path $logDir 'screenshots'
        New-Item -ItemType Directory -Force $screenshots | Out-Null
        foreach ($name in @('chat-en-phone', 'chat-zh-phone')) {
            & $adb -s $Serial pull "/sdcard/Android/data/com.feiyu.notes/files/ui-evidence/$name.png" (Join-Path $screenshots "$name.png")
            if ($LASTEXITCODE -ne 0) { throw "Missing UI screenshot: $name" }
        }
    }
}

Invoke-Step 'Artifact' {
    $version = (Select-String -Path app\build.gradle.kts -Pattern 'versionName = "([^"]+)"').Matches[0].Groups[1].Value
    $sha = (git rev-parse --short HEAD 2>$null)
    if (-not $sha) { $sha = 'nogit' }
    $dirty = if (git status --porcelain 2>$null) { '-dirty' } else { '' }
    New-Item -ItemType Directory -Force dist | Out-Null
    $target = "dist\feiyu-notes-$version-$sha$dirty-debug.apk"
    Copy-Item app\build\outputs\apk\debug\app-debug.apk $target -Force
    Write-Host "    $target"
    $global:LASTEXITCODE = 0
}

Write-Host ("CI passed ({0})" -f $(if ($Full) { 'full' } else { 'fast' })) -ForegroundColor Green
