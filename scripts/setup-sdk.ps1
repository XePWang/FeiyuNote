<# Reinstall missing SDK components in a persistent directory; never removes AVDs or user data. #>
param(
    [string]$SdkRoot = $(if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }),
    [switch]$WithEmulator
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [Console]::OutputEncoding
if (-not $env:JAVA_HOME) {
    $env:JAVA_HOME = (Get-ChildItem "$env:USERPROFILE\.jdks" -Directory -Filter 'jdk-21*' | Select-Object -First 1).FullName
}
if (-not (Test-Path "$env:JAVA_HOME\bin\java.exe")) { throw 'JDK 21 is required. Set JAVA_HOME first.' }
$SdkRoot = [IO.Path]::GetFullPath($SdkRoot)
$manager = Join-Path $SdkRoot 'cmdline-tools\19.0\bin\sdkmanager.bat'
if (-not (Test-Path $manager)) {
    # Use official repository metadata, including its archive checksum, to bootstrap tools.
    [xml]$metadata = (Invoke-WebRequest 'https://dl.google.com/android/repository/repository2-3.xml').Content
    # Pin the Java sdkmanager used by CI; newer wrappers delegate to a different CLI.
    $package = $metadata.SelectNodes('//*[local-name()="remotePackage"]') | Where-Object { $_.path -eq 'cmdline-tools;19.0' }
    $archive = $package.archives.archive | Where-Object { $_.'host-os' -eq 'windows' }
    $staging = Join-Path $PSScriptRoot '../build/sdk-bootstrap'
    New-Item -ItemType Directory -Force $staging | Out-Null
    $zip = Join-Path $staging 'tools.zip'
    Invoke-WebRequest ("https://dl.google.com/android/repository/" + $archive.complete.url) -OutFile $zip
    if ((Get-FileHash $zip -Algorithm SHA1).Hash -ne $archive.complete.checksum.InnerText) { throw 'SDK tools checksum mismatch.' }
    Expand-Archive $zip $staging -Force
    New-Item -ItemType Directory -Force "$SdkRoot\cmdline-tools\19.0" | Out-Null
    Copy-Item "$staging\cmdline-tools\*" "$SdkRoot\cmdline-tools\19.0" -Recurse -Force
}
$packages = @('platforms;android-37.0', 'build-tools;36.0.0', 'platform-tools')
if ($WithEmulator) { $packages += @('emulator', 'system-images;android-36;google_apis;x86_64') }
# sdkmanager asks for license acceptance when necessary; do not silently accept on other machines.
& $manager "--sdk_root=$SdkRoot" @packages
if ($LASTEXITCODE -ne 0) { throw "SDK installation failed (exit $LASTEXITCODE)." }
if (-not (Test-Path "$SdkRoot\platforms\android-37.0\android.jar")) { throw 'Android platform installation is incomplete.' }
if ($WithEmulator -and -not (Test-Path "$env:USERPROFILE\.android\avd\Feiyu_CI_API36.ini")) {
    'no' | & "$SdkRoot\cmdline-tools\19.0\bin\avdmanager.bat" create avd --name Feiyu_CI_API36 --package 'system-images;android-36;google_apis;x86_64' --device pixel_9_pro_fold
    if ($LASTEXITCODE -ne 0) { throw 'Could not create the isolated CI emulator.' }
}
$env:ANDROID_HOME = $SdkRoot
[Environment]::SetEnvironmentVariable('ANDROID_HOME', $SdkRoot, 'User')
$properties = Join-Path $PSScriptRoot '../local.properties'
$lines = if (Test-Path $properties) { @(Get-Content $properties -Encoding utf8 | Where-Object { $_ -notmatch '^sdk\.dir=' }) } else { @() }
$lines += 'sdk.dir=' + $SdkRoot.Replace('\', '/').Replace(':', '\:')
Set-Content $properties $lines -Encoding utf8
Write-Host "SDK ready: $SdkRoot. local.properties and user ANDROID_HOME now agree."
