# =============================================================================
#  Publish a release to GitHub AND Gitea: build (R8) + commit + tag +
#  gh + Gitea API release + upload APK.
#  Single entry point for "user asks assistant to ship a release".
#
#  Usage (from repo root, or anywhere):
#    powershell -ExecutionPolicy Bypass -File scripts\release-github.ps1
#    powershell -ExecutionPolicy Bypass -File scripts\release-github.ps1 -Version 2.1.4 -NotesFile rel.md
#    powershell -ExecutionPolicy Bypass -File scripts\release-github.ps1 -SkipBuild        # reuse existing APK in dist/
#    powershell -ExecutionPolicy Bypass -File scripts\release-github.ps1 -Offline          # build from local cache
#    powershell -ExecutionPolicy Bypass -File scripts\release-github.ps1 -AutoCommit       # auto commit pending changes
#    powershell -ExecutionPolicy Bypass -File scripts\release-github.ps1 -GiteaRepo user/repo   # Gitea target
#    powershell -ExecutionPolicy Bypass -File scripts\release-github.ps1 -SkipGitea        # skip Gitea release
#
#  Requirements:
#    - GitHub: gh CLI installed & logged in (gh auth status), git creds via gh.
#    - Gitea (e.g. gitea.com): optional auto-publish via Gitea API v1 using
#      $env:GITEA_REPO (owner/repo) and $env:GITEA_TOKEN (personal access token).
#      Env vars GITEE_REPO/GITEE_TOKEN are also honored as aliases.
#  Version defaults to app/version.properties -> versionName.
# =============================================================================
param(
    [string]$Version,
    [switch]$SkipBuild,
    [switch]$Offline,
    [switch]$AutoCommit,
    [string]$NotesFile,
    [string]$Title,
    [string]$Repo = 'hdudou/Shiyin-Player',
    [string]$GiteaRepo,
    [switch]$SkipGitea
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
$releaseNotes = if ($NotesFile -and (Test-Path $NotesFile)) {
    [System.IO.File]::ReadAllText($NotesFile)
} else {
    "Release $Tag of Shiyin Player (GPL-3.0).`r`n`r`nDownload and install the APK (Android 10+).`r`n`r`nNote: This build is un-minified (R8 obfuscation disabled) for stability.`r`n`r`n功能概览：多源曲库（本机文件夹 / SMB / WebDAV）、全格式解码（内置 FFmpeg）、自动/手动元数据刮削、内置全球电台、无缝播放 / 音量归一化 / 均衡器、播放器与收音机双模式、车载蓝牙歌词、睡眠定时等。"
}
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
$remoteTag = (& $Git -C $Root ls-remote origin "refs/tags/$Tag" 2>&1) | Where-Object { $_ }
if ($remoteTag) {
    Write-Host "==> Tag $Tag already exists on remote, skipping tag push."
} else {
    Write-Host "==> Tagging $Tag..."
    Invoke-Checked { & $Git -C $Root tag $Tag } 'git tag'
    Invoke-Checked { & $Git -C $Root push origin $Tag } 'git tag push'
}

# ---- 7. create release or add asset (GitHub) --------------------------------
$existing = gh release view $Tag --repo $Repo --json tagName --jq '.tagName' 2>&1
$create = $true
if ($existing -and $existing.Trim() -eq $Tag) { $create = $false }

if ($create) {
    Write-Host "==> Creating GitHub release and uploading asset..."
    $args = @('release','create',$Tag,'--repo',$Repo,'--notes',$releaseNotes)
    if ($Title) { $args += @('--title',$Title) }
    $args += $Apk
    Invoke-Checked { & gh @args } 'gh release create'
} else {
    Write-Host "==> Release $Tag exists; uploading/updating asset..."
    Invoke-Checked { & gh release upload $Tag --repo $Repo --clobber $Apk } 'gh release upload'
}

# ---- 8. Gitea publish (optional, Gitea API v1) ------------------------------
if ($SkipGitea) {
    Write-Host "==> Gitea skipped (-SkipGitea)"
} else {
    $giteaRepo  = if ($GiteaRepo) { $GiteaRepo } elseif ($env:GITEA_REPO) { $env:GITEA_REPO } else { $env:GITEE_REPO }
    $giteaToken = if ($env:GITEA_TOKEN) { $env:GITEA_TOKEN } else { $env:GITEE_TOKEN }
    if (-not $giteaRepo -or -not $giteaToken) {
        Write-Host "==> Gitea SKIPPED: set env GITEA_REPO + GITEA_TOKEN (or -GiteaRepo) to publish there."
    } else {
        $base  = "https://gitea.com/api/v1/repos/$giteaRepo"
        $hdr   = @{ Authorization = "token $giteaToken" }
        $name  = if ($Title) { $Title } else { $Tag }
        Write-Host "==> Publishing to Gitea: $giteaRepo"

        # 8.1 main branch commit sha (needed to create the tag ref)
        $sha = $null
        try {
            $branch = Invoke-RestMethod -Uri "$base/branches/main" -Headers $hdr -Method Get
            $sha = $branch.commit.id
        } catch {
            Write-Warning "Gitea: cannot read main branch (is the repo pushed?). Details: $($_.Exception.Message)"
        }
        if ($sha) {
            # 8.2 ensure tag ref exists (create if missing)
            $tagExists = $false
            try {
                Invoke-RestMethod -Uri "$base/git/refs/tags/$Tag" -Headers $hdr -Method Get | Out-Null
                $tagExists = $true
            } catch { }
            if (-not $tagExists) {
                try {
                    Invoke-RestMethod -Uri "$base/git/refs" -Headers $hdr -Method Post -ContentType 'application/json' `
                        -Body (@{ ref = "refs/tags/$Tag"; sha = $sha } | ConvertTo-Json) | Out-Null
                    Write-Host "==> Gitea tag $Tag created."
                } catch {
                    Write-Warning "Gitea: failed to create tag $Tag. $($_.Exception.Message)"
                }
            }
            # 8.3 ensure release exists (create if missing)
            $relId = $null
            try {
                $rel = Invoke-RestMethod -Uri "$base/releases/tags/$Tag" -Headers $hdr -Method Get
                $relId = $rel.id
                Write-Host "==> Gitea release $Tag already exists (id=$relId)."
            } catch { }
            if (-not $relId) {
                try {
                    $body = @{
                        tag_name = $Tag
                        name     = $name
                        body     = $releaseNotes
                    } | ConvertTo-Json
                    $rel = Invoke-RestMethod -Uri "$base/releases" -Headers $hdr -Method Post -ContentType 'application/json' -Body $body
                    $relId = $rel.id
                    Write-Host "==> Gitea release $Tag created (id=$relId)."
                } catch {
                    Write-Warning "Gitea: failed to create release. $($_.Exception.Message)"
                }
            }
            # 8.4 upload APK asset (curl multipart; PowerShell5 lacks Invoke-RestMethod -Form)
            if ($relId) {
                Write-Host "==> Uploading APK to Gitea release $relId..."
                & curl.exe -s -H "Authorization: token $giteaToken" -F "attachment=@$Apk" "$base/releases/$relId/assets" 2>&1 | Out-Null
                if ($LASTEXITCODE -ne 0) {
                    Write-Warning "Gitea asset upload failed (exit=$LASTEXITCODE). If the asset already exists, replace it in the Gitea web UI."
                } else {
                    Write-Host "==> Gitea asset uploaded."
                }
            }
        } else {
            Write-Warning "Gitea publish aborted (no main branch). Push code to Gitea repo first, then re-run."
        }
    }
}

# ---- 9. report -----------------------------------------------------------------
$url = gh release view $Tag --repo $Repo --json url --jq '.url' 2>&1
Write-Host ""
Write-Host "============================================================"
Write-Host "  PUBLISHED: $Tag"
Write-Host "  Asset:     $Apk"
Write-Host "  GitHub:    $($url.Trim())"
$gR = if ($GiteaRepo) { $GiteaRepo } elseif ($env:GITEA_REPO) { $env:GITEA_REPO } else { $env:GITEE_REPO }
if ($gR) { Write-Host "  Gitea:     https://gitea.com/$gR/releases" }
Write-Host "============================================================"