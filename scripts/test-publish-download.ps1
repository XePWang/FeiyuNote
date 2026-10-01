# Checks publish-download.ps1 against a temporary local destination:
# a normal publish writes a valid index, and a failure before the switch keeps the old index.
param([string]$Apk = (Join-Path (Split-Path -Parent $PSScriptRoot) 'app\build\outputs\apk\debug\app-debug.apk'))
$ErrorActionPreference = 'Stop'
$publish = Join-Path $PSScriptRoot 'publish-download.ps1'
$dest = Join-Path ([System.IO.Path]::GetTempPath()) ("feiyu-publish-test-" + [guid]::NewGuid())
try {
    & $publish -Apk $Apk -NotesZh '说明 <b>&' -NotesEn 'Notes' -Destination $dest
    $index = Get-Content -Raw -Encoding utf8 (Join-Path $dest 'updates\android.json') | ConvertFrom-Json
    if ($index.schemaVersion -ne 1 -or $index.versionCode -le 0 -or $index.notes.zh -ne '说明 <b>&') { throw 'bad index' }
    $page = Get-Content -Raw -Encoding utf8 (Join-Path $dest 'feiyu\index.html')
    if ($page -notmatch [regex]::Escape('说明 &lt;b&gt;&amp;') -or $page -match '\{\{') { throw 'bad page' }
    if (-not (Test-Path (Join-Path $dest "feiyu\feiyu-notes-$($index.versionName).apk"))) { throw 'apk missing' }

    # Break the page directory so the upload fails before the index switch.
    $old = '{"schemaVersion":1,"versionCode":1}'
    Set-Content -NoNewline -Encoding utf8 (Join-Path $dest 'updates\android.json') $old
    Remove-Item -Recurse -Force (Join-Path $dest 'feiyu')
    Set-Content (Join-Path $dest 'feiyu') 'not a directory'
    $failed = $false
    try { & $publish -Apk $Apk -NotesZh 'x' -NotesEn 'x' -Destination $dest } catch { $failed = $true }
    if (-not $failed) { throw 'publish should have failed' }
    if ((Get-Content -Raw -Encoding utf8 (Join-Path $dest 'updates\android.json')) -ne $old) { throw 'index changed after a failed publish' }
    Write-Host 'publish-download checks passed'
} finally {
    Remove-Item -Recurse -Force -LiteralPath $dest -ErrorAction SilentlyContinue
}
