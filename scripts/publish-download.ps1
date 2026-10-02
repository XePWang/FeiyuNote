<#
.SYNOPSIS
  Publishes a signed APK to the independent download site (spec §9 U02, plan P3).

.DESCRIPTION
  Version, versionCode and minSdk are read from the APK itself. Upload order is APK -> licence ->
  download page -> update index; the index is switched last by an atomic rename, so any earlier
  failure leaves the previous index (and the build it points to) in place.

.EXAMPLE
  pwsh scripts/publish-download.ps1 -Apk app-release.apk -NotesZh "修复…" -NotesEn "Fixes…" -Destination yz_vps:/srv/feiyunote
  pwsh scripts/publish-download.ps1 -Apk app-debug.apk -NotesZh 测试 -NotesEn test -Destination build\site-test
#>
param(
    [Parameter(Mandatory)] [string]$Apk,
    [Parameter(Mandatory)] [string]$NotesZh,
    [Parameter(Mandatory)] [string]$NotesEn,
    # A local directory, or ssh-host:/absolute/remote/dir
    [Parameter(Mandatory)] [string]$Destination,
    # Validated: a stray unquoted argument (e.g. curly quotes in the notes) must not land here.
    [ValidatePattern('^https://[A-Za-z0-9.-]+$')]
    [string]$BaseUrl = 'https://feiyunote.cangming.fyi'
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$root = Split-Path -Parent $PSScriptRoot
$Apk = (Resolve-Path -LiteralPath $Apk).Path

if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk" }
$buildTools = Get-ChildItem (Join-Path $env:ANDROID_HOME 'build-tools') -Directory | Sort-Object { [version]($_.Name -replace '-.*$', '') } | Select-Object -Last 1
if (-not $buildTools) { throw 'Android build-tools not found; set ANDROID_HOME.' }

# Signature first: never publish an unsigned or tampered file.
& (Join-Path $buildTools.FullName 'apksigner.bat') verify $Apk
if ($LASTEXITCODE -ne 0) { throw 'apksigner verify failed' }

$badging = & (Join-Path $buildTools.FullName 'aapt2.exe') dump badging $Apk
if ($LASTEXITCODE -ne 0) { throw 'aapt2 could not read the APK' }
$package = [regex]::Match(($badging -join "`n"), "package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'")
$minSdk = [regex]::Match(($badging -join "`n"), "(?m)^(?:min)?[sS]dkVersion:'(\d+)'")
if (-not $package.Success -or -not $minSdk.Success) { throw 'APK metadata not found' }
if ($package.Groups[1].Value -ne 'com.feiyu.notes') { throw "Unexpected package $($package.Groups[1].Value)" }
$versionCode = [long]$package.Groups[2].Value
$versionName = $package.Groups[3].Value
$minSdkValue = [int]$minSdk.Groups[1].Value
if ($versionName -notmatch '^[0-9A-Za-z.\-]+$') { throw "Unsafe versionName $versionName" }

$androidRelease = @{ 26 = '8.0'; 27 = '8.1'; 28 = '9'; 29 = '10'; 30 = '11'; 31 = '12'; 32 = '12L'; 33 = '13'; 34 = '14'; 35 = '15'; 36 = '16' }[$minSdkValue]
if (-not $androidRelease) { $androidRelease = "API $minSdkValue" }
$apkName = "feiyu-notes-$versionName.apk"
$apkInfo = Get-Item -LiteralPath $Apk
$enc = { param($s) [System.Net.WebUtility]::HtmlEncode($s) }

$stage = Join-Path ([System.IO.Path]::GetTempPath()) ("feiyu-publish-" + [guid]::NewGuid())
New-Item -ItemType Directory -Force (Join-Path $stage 'feiyu'), (Join-Path $stage 'updates') | Out-Null
try {
    Copy-Item -LiteralPath $Apk (Join-Path $stage "feiyu\$apkName")
    Copy-Item -LiteralPath (Join-Path $root 'LICENSE') (Join-Path $stage 'feiyu\LICENSE.txt')
    $page = (Get-Content -Raw -Encoding utf8 (Join-Path $root 'site\feiyu\index.html'))
    $values = @{
        VERSION_NAME = $versionName
        MIN_ANDROID  = $androidRelease
        APK_FILE     = $apkName
        APK_URL      = "$BaseUrl/feiyu/$apkName"
        APK_SIZE     = '{0:N1} MB' -f ($apkInfo.Length / 1MB)
        APK_SHA256   = (Get-FileHash -Algorithm SHA256 -LiteralPath $Apk).Hash.ToLowerInvariant()
        NOTES_ZH     = $NotesZh
        NOTES_EN     = $NotesEn
    }
    foreach ($key in $values.Keys) { $page = $page.Replace("{{$key}}", (& $enc $values[$key])) }
    if ($page -match '\{\{[A-Z_]+\}\}') { throw "Unfilled placeholder $($Matches[0])" }
    [System.IO.File]::WriteAllText((Join-Path $stage 'feiyu\index.html'), $page, [System.Text.UTF8Encoding]::new($false))

    $manifest = [ordered]@{
        schemaVersion   = 1
        versionCode     = $versionCode
        versionName     = $versionName
        minSdk          = $minSdkValue
        notes           = [ordered]@{ zh = $NotesZh; en = $NotesEn }
        downloadPageUrl = "$BaseUrl/feiyu/"
    } | ConvertTo-Json -Compress
    if ([System.Text.Encoding]::UTF8.GetByteCount($manifest) -gt 32KB) { throw 'Update index exceeds 32 KiB' }
    [System.IO.File]::WriteAllText((Join-Path $stage 'updates\android.json'), $manifest, [System.Text.UTF8Encoding]::new($false))

    if ($Destination -match '^(?<host>[A-Za-z0-9_.@-]+):(?<dir>/[A-Za-z0-9_./-]+)$') {
        $sshHost = $Matches.host; $dir = $Matches.dir.TrimEnd('/')
        $remoteStage = "$dir/.publish-$versionName"
        & ssh $sshHost "rm -rf '$remoteStage' && mkdir -p '$remoteStage'"
        if ($LASTEXITCODE -ne 0) { throw 'ssh staging failed' }
        & scp -r (Join-Path $stage 'feiyu') (Join-Path $stage 'updates') "${sshHost}:$remoteStage/"
        if ($LASTEXITCODE -ne 0) { throw 'upload failed; the live index was not changed' }
        $switch = @(
            'set -e'
            "mkdir -p '$dir/feiyu' '$dir/updates'"
            "cp '$remoteStage/feiyu/$apkName' '$dir/feiyu/.$apkName.tmp' && mv -f '$dir/feiyu/.$apkName.tmp' '$dir/feiyu/$apkName'"
            "cp '$remoteStage/feiyu/LICENSE.txt' '$dir/feiyu/.LICENSE.txt.tmp' && mv -f '$dir/feiyu/.LICENSE.txt.tmp' '$dir/feiyu/LICENSE.txt'"
            "cp '$remoteStage/feiyu/index.html' '$dir/feiyu/.index.html.tmp' && mv -f '$dir/feiyu/.index.html.tmp' '$dir/feiyu/index.html'"
            "cp '$remoteStage/updates/android.json' '$dir/updates/.android.json.tmp' && mv -f '$dir/updates/.android.json.tmp' '$dir/updates/android.json'"
            "rm -rf '$remoteStage'"
        ) -join "`n"
        # Passed as the remote command, not via stdin: piping from PowerShell appends CR to the last line.
        & ssh $sshHost $switch
        if ($LASTEXITCODE -ne 0) { throw 'remote switch failed; check which files were replaced before retrying' }
    } else {
        $feiyu = Join-Path $Destination 'feiyu'
        $updates = Join-Path $Destination 'updates'
        New-Item -ItemType Directory -Force $feiyu, $updates | Out-Null
        foreach ($pair in @(
            @("feiyu\$apkName", $feiyu, $apkName),
            @('feiyu\LICENSE.txt', $feiyu, 'LICENSE.txt'),
            @('feiyu\index.html', $feiyu, 'index.html'),
            @('updates\android.json', $updates, 'android.json')
        )) {
            $temp = Join-Path $pair[1] ".$($pair[2]).tmp"
            Copy-Item -LiteralPath (Join-Path $stage $pair[0]) $temp -Force
            Move-Item -LiteralPath $temp (Join-Path $pair[1] $pair[2]) -Force
        }
    }
    Write-Host "Published $versionName (versionCode $versionCode, minSdk $minSdkValue) to $Destination"
} finally {
    Remove-Item -Recurse -Force -LiteralPath $stage -ErrorAction SilentlyContinue
}
