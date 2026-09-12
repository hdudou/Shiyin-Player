# =============================================================================
#  Publish a GitHub release: build (R8) + commit + tag + gh release + upload APK.
#  Single entry point for "user asks assistant to ship a release".
#
#  Usage (from repo root, or anywhere):
#    powershell -ExecutionPolicy Bypass -File scripts\release-github.ps1
#    powershell -ExecutionPolicy Bypass -File scripts\release-github.ps1 -Version 2.1.4 -NotesFile rel.md
#    powershell -ExecutionPolicy Bypass -File scripts\release-github.ps1 -SkipBuild        # reuse existing APK in dist/
#    powershell -ExecutionPolicy Bypass -File scripts\release-github.ps1 -Offline          # build from local cache
#    powershell -ExecutionPolicy Bypass -File scripts\release-github.ps1 -AutoCommit       # auto commit pending changes
#
#  Requirements: gh CLI installed & logged in (gh auth status), git creds via gh.
#  Version defaults to app/version.properties -> versionName.
# =============================================================================
param(
    [string]$Version,
    [switch]$SkipBuild,
    [switch]$Offline,
    [switch]$AutoCommit,
    [string]$NotesFile,
    [string]$Title,
    [string]$Repo = 'hdudou/Shiyin-Player'
)
$ErrorActionPreference = 'Stop'
$Root     = Split-Path -Parent $PSScriptRoot
$Git      = 'C:\Program Files\Git\bin\git.exe'
$GradleBin = 'E:\androidplayer-t\.build_env\gradle-8.9\bin\gradle.bat'
$JavaHome  = 'E:\androidplayer-t\.build_env\jdk17'
$AndroidSdk = 'E:\androidplayer-t\.build_env\sdk'

# gh may have been installed after this shell started.
$env:Path = [Environment]::GetEnvironmentVariable('Path','Machine') + ';' + [Environment]::GetEnvironmentVariable('Path','User')

function Invoke-Checked {
    param([scriptblock]$Block, [string]$What)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'   # git/gh write notices to stderr; don't treat as fatal
    try { & $Block } finally { $ErrorActionPreference = $prev }
    if ($LASTEXITCODE -ne 0) { throw "$What failed (exit=$LASTEXITCODE)" }
}

# ---- 0. toolchain & prereqs ------------------------------------------------
foreach ($tool in @($GradleBin, $JavaHome, $AndroidSdk)) {
    if (-not (Test-Path $tool)) { throw "Toolchain not found: $tool" }
}
if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { throw "gh CLI not found. Install: winget install GitHub.cli" }

# ---- 1. gh auth ------------------------------------------------------------
Invoke-Checked { gh auth status } 'gh authentication check'
Write-Host "==> gh authenticated for repo $Repo"

# ---- 2. resolve version ----------------------------------------------------
if (-not $Version) {
    $vpFile = Join-Path $Root 'app\version.properties'
    $vp = @{}
    if (Test-Path $vpFile) {
        foreach ($line in [System.IO.File]::ReadAllLines($vpFile)) {
            $m = [regex]::Match($line, '^[ \t]*(\w+)[ \t]*=[ \t]*(.+?)[ \t]*$')
            if ($m.Success) { $vp[$m.Groups[1].Value] = $m.Groups[2].Value }
        }
    }
    $Version = if ($vp['versionName']) { $vp['versionName'] } elseif ($vp['buildCode']) { '1.0.' + $vp['buildCode'] } else { throw 'Cannot resolve version; pass -Version' }
}
$Tag = 'v' + $Version
$Apk = Join-Path $Root "dist\app-release-$Version.apk"
Write-Host "==> Target: $Tag ($Repo)"

# ---- 3. working tree must be clean (or auto-commit) -------------------------
$dirty = (& $Git -C $Root status --porcelain 2>&1) | Where-Object { $_ }
if ($dirty) {
    if ($AutoCommit) {
        Invoke-Checked { & $Git -C $Root add -A } 'git add'
        Invoke-Checked { & $Git -C $Root commit -m "release: $Tag" } 'git commit'
        Write-Host "==> Auto-committed working tree for $Tag"
    } else {
        throw "Working tree is dirty. Commit first or re-run with -AutoCommit."
    }
}

# ---- 4. build release (unless skipped) -------------------------------------
if ($SkipBuild) {
    if (-not (Test-Path $Apk)) { throw "No prebuilt APK found: $Apk (build it or drop -SkipBuild)" }
    Write-Host "==> Reusing existing APK: $Apk"
} else {
    $buildParams = @()
    if ($Offline) { $buildParams += '-Offline' }
    Write-Host "==> Building release..."
    & (Join-Path $PSScriptRoot 'build-release.ps1') @buildParams
    if ($LASTEXITCODE -ne 0) { throw "build-release.ps1 failed (exit=$LASTEXITCODE)" }
}
if (-not (Test-Path $Apk)) { throw "Expected APK missing: $Apk" }

# ---- 5. push main -----------------------------------------------------------
Write-Host "==> Pushing main..."
Invoke-Checked { & $Git -C $Root push origin main } 'git push main'

# ---- 6. tag (create+push only if not already on remote) ----------------------
$remoteTag = (& $Git ls-remote origin "refs/tags/$Tag" 2>&1) | Where-Object { $_ }
if ($remoteTag) {
    Write-Host "==> Tag $Tag already exists on remote, skipping tag push."
} else {
    Write-Host "==> Tagging $Tag..."
    Invoke-Checked { & $Git -C $Root tag $Tag } 'git tag'
    Invoke-Checked { & $Git -C $Root push origin $Tag } 'git tag push'
}

# ---- 7. create release or add asset -----------------------------------------
$existing = gh release view $Tag --repo $Repo --json tagName --jq '.tagName' 2>&1
$create = $true
if ($existing -and $existing.Trim() -eq $Tag) { $create = $false }

if ($create) {
    Write-Host "==> Creating GitHub release and uploading asset..."
    $args = @('release','create',$Tag,'--repo',$Repo)
    if ($Title) { $args += @('--title',$Title) }
    if ($NotesFile -and (Test-Path $NotesFile)) { $args += @('--notes-file',$NotesFile) }
    else { $args += @('--notes',"Release $Tag of Shiyin Player (GPL-3.0).") }
    $args += $Apk
    Invoke-Checked { & gh @args } 'gh release create'
} else {
    Write-Host "==> Release $Tag exists; uploading/updating asset..."
    Invoke-Checked { & gh release upload $Tag --repo $Repo --clobber $Apk } 'gh release upload'
}

# ---- 8. report -----------------------------------------------------------------
$url = gh release view $Tag --repo $Repo --json url --jq '.url' 2>&1
Write-Host ""
Write-Host "============================================================"
Write-Host "  PUBLISHED: $Tag"
Write-Host "  Asset:     $Apk"
Write-Host "  URL:       $($url.Trim())"
Write-Host "============================================================"