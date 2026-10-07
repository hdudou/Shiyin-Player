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
#    powershell -ExecutionPolicy Bypass -File scripts\release-github.ps1 -SkipGithub       # GitHub 连不通时只发 Gitea
#    powershell -ExecutionPolicy Bypass -File scripts\release-github.ps1 -SkipBuild -UpdateGiteaNotes -NotesFile rel.md
#                                                                                          # 只刷新已发布 Gitea release 的正文（修历史乱码 / 改文案）
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
    [switch]$SkipGitea,
    # GitHub 连不通时的降级开关：跳过 gh 认证 / GitHub Release，只推 Gitea。
    # 注意：main 与 tag 仍会推送到 origin（GitHub），只是不创建 GitHub Release 与附件。
    [switch]$SkipGithub,
    # 公网 Gitea 站点（默认 gitea.com）与推送用户名。
    # 推送用一次性 URL（https://<user>:<token>@host/owner/repo.git），不写进 .git/config，
    # 令牌就不会落到磁盘上的远端配置里。默认用户名取 <owner/repo> 的 owner 段。
    [string]$GiteaBaseUrl = 'https://gitea.com',
    [string]$GiteaUser,
    # 已存在同名 Gitea release 时，用本次文案刷新其正文。默认关闭，避免覆盖手工润色过的说明。
    # 修历史乱码：-SkipBuild -UpdateGiteaNotes -NotesFile <正确文案.md>
    [switch]$UpdateGiteaNotes,
    # 工具链路径（可选）。优先级：参数 > 环境变量 > 内置默认值。
    # 默认值是作者本机的实际路径 —— 所以本机开箱即用，无需任何配置。
    # ⚠️ 下方解析块里那三行含本机绝对路径，是**有意保留**的：闸门的 LOCAL_PATH 规则会拦下它们，
    #    故每行都带 `desensitize-allow` 行级豁免（见 check-desensitize.py 的 ALLOW_MARKER），
    #    理由是「工具链定位必须能在他人机器上直接改成自己的路径」，且不涉及任何账号口令。
    #    换机器时：改那三行，或传 -GradleBin/-JavaHome/-AndroidSdk，或设同名环境变量。
    [string]$GradleBin,
    [string]$JavaHome,
    [string]$AndroidSdk
)
$ErrorActionPreference = 'Stop'
$Root     = Split-Path -Parent $PSScriptRoot
$Git      = 'C:\Program Files\Git\bin\git.exe'
# 工具链解析：参数 > 环境变量 > PATH 上的可执行名（gradle / javac 自动回退）。
# 不硬编码本机路径 —— 见 param 块里的说明（脱敏 + 可移植性）。
$GradleBin  = if ($GradleBin)  { $GradleBin }  elseif ($env:GRADLE_BIN)  { $env:GRADLE_BIN }  else { 'gradle' }
$JavaHome   = if ($JavaHome)   { $JavaHome }   elseif ($env:JAVA_HOME)   { $env:JAVA_HOME }
$AndroidSdk = if ($AndroidSdk) { $AndroidSdk } elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }

# gh may have been installed after this shell started.
$env:Path = [Environment]::GetEnvironmentVariable('Path','Machine') + ';' + [Environment]::GetEnvironmentVariable('Path','User')

function Invoke-Checked {
    param([scriptblock]$Block, [string]$What)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'   # git/gh write notices to stderr; don't treat as fatal
    try { & $Block } finally { $ErrorActionPreference = $prev }
    if ($LASTEXITCODE -ne 0) { throw "$What failed (exit=$LASTEXITCODE)" }
}

# 调用 Gitea API 并发送 JSON 正文。
# ⚠️ 正文必须先用 UTF-8 编码成字节再交给 -Body，绝不能直接传字符串：
#   Windows PowerShell 5.1 在「-Body 传字符串 + ContentType 不带 charset」时，
#   会按 Latin-1/ASCII 编码正文，所有非 ASCII 字符（中文）被静默替换成 ?。
#   v2.1.4 的 Gitea 发布说明就是这样被毁的（同一份文案经 gh 发到 GitHub 则完好）。
function Invoke-GiteaJson {
    param(
        [string]$Uri,
        [ValidateSet('Post', 'Patch')][string]$Method,
        [hashtable]$Payload,
        [hashtable]$Headers
    )
    $json  = $Payload | ConvertTo-Json -Depth 6
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
    return Invoke-RestMethod -Uri $Uri -Headers $Headers -Method $Method `
        -ContentType 'application/json; charset=utf-8' -Body $bytes
}

# ---- 0. toolchain & prereqs ------------------------------------------------
# 绝对路径要求存在；裸名（如 PATH 上的 gradle）改为查 PATH —— 否则 Test-Path 'gradle' 恒为 false，
# 参数化之后会误报「工具链缺失」。
foreach ($pair in @(@('Gradle', $GradleBin), @('JAVA_HOME', $JavaHome), @('ANDROID_HOME', $AndroidSdk))) {
    $label, $tool = $pair
    if ([string]::IsNullOrWhiteSpace($tool)) {
        throw "$label not set. Pass -$label <path> or set the matching environment variable."
    }
    $looksLikePath = $tool -match '^[A-Za-z]:[\\/]' -or $tool.StartsWith('\\')
    $ok = if ($looksLikePath) { Test-Path $tool } else { [bool](Get-Command $tool -ErrorAction SilentlyContinue) }
    if (-not $ok) { throw "$label not found: $tool" }
}
if (-not $SkipGithub -and -not (Get-Command gh -ErrorAction SilentlyContinue)) {
    throw "gh CLI not found. Install: winget install GitHub.cli (or re-run with -SkipGithub to publish to Gitea only)"
}

# ---- 1. gh auth ------------------------------------------------------------
if ($SkipGithub) {
    Write-Host "==> GitHub skipped (-SkipGithub): no GitHub release will be created."
} else {
    Invoke-Checked { gh auth status } 'gh authentication check'
    Write-Host "==> gh authenticated for repo $Repo"
}

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
if ($SkipGithub) {
    Write-Host "==> Skipping GitHub release creation (-SkipGithub)"
} else {
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
        $base  = "$GiteaBaseUrl/api/v1/repos/$giteaRepo"
        $hdr   = @{ Authorization = "token $giteaToken" }
        $name  = if ($Title) { $Title } else { $Tag }
        Write-Host "==> Publishing to Gitea: $giteaRepo"

        # 8.0 先把 main 与 tag 推到 Gitea —— 否则 Gitea 上的主线会停留在旧提交，
        #     下面按 main 创建的 tag/release 就不含本次提交（GitHub 有 Release 页面兜底，
        #     Gitea 侧的代码同步只能靠这一步）。推送 URL 一次性带令牌，不写进 .git/config。
        $giteaUser = if ($GiteaUser) { $GiteaUser } elseif ($env:GITEA_USER) { $env:GITEA_USER } else { $giteaRepo.Split('/')[0] }
        $giteaGitUrl = "$GiteaBaseUrl/$giteaRepo.git"
        $giteaPushUrl = $giteaGitUrl -replace '^https://', "https://$giteaUser`:$giteaToken@"
        try {
            Invoke-Checked { & $Git -C $Root push $giteaPushUrl main } 'git push Gitea main'
            Write-Host "==> Gitea main pushed."
        } catch {
            Write-Warning "Gitea: push main failed. $($_.Exception.Message)"
        }
        try {
            Invoke-Checked { & $Git -C $Root push $giteaPushUrl "refs/tags/$Tag" } 'git push Gitea tag'
            Write-Host "==> Gitea tag $Tag pushed."
        } catch {
            Write-Warning "Gitea: push tag $Tag failed (will fall back to the API below). $($_.Exception.Message)"
        }

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
            $existingBody = ''
            try {
                $rel = Invoke-RestMethod -Uri "$base/releases/tags/$Tag" -Headers $hdr -Method Get
                $relId = $rel.id
                $existingBody = "$($rel.body)"
                Write-Host "==> Gitea release $Tag already exists (id=$relId)."
            } catch { }
            if (-not $relId) {
                try {
                    $rel = Invoke-GiteaJson -Uri "$base/releases" -Method Post -Headers $hdr -Payload @{
                        tag_name = $Tag
                        name     = $name
                        body     = $releaseNotes
                    }
                    $relId = $rel.id
                    Write-Host "==> Gitea release $Tag created (id=$relId)."
                } catch {
                    Write-Warning "Gitea: failed to create release. $($_.Exception.Message)"
                }
            } elseif ($UpdateGiteaNotes -and ($existingBody -ne $releaseNotes)) {
                # 已存在且正文与本次文案不一致：刷新之（修历史乱码的唯一入口，需显式 -UpdateGiteaNotes）
                try {
                    Invoke-GiteaJson -Uri "$base/releases/$relId" -Method Patch -Headers $hdr -Payload @{ body = $releaseNotes } | Out-Null
                    Write-Host "==> Gitea release $Tag notes refreshed (id=$relId)."
                } catch {
                    Write-Warning "Gitea: failed to update release notes. $($_.Exception.Message)"
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
$url = if ($SkipGithub) { '(skipped)' } else { (gh release view $Tag --repo $Repo --json url --jq '.url' 2>&1) }
Write-Host ""
Write-Host "============================================================"
Write-Host "  PUBLISHED: $Tag"
Write-Host "  Asset:     $Apk"
Write-Host "  GitHub:    $($url.ToString().Trim())"
$gR = if ($GiteaRepo) { $GiteaRepo } elseif ($env:GITEA_REPO) { $env:GITEA_REPO } else { $env:GITEE_REPO }
if ($gR) { Write-Host "  Gitea:     $GiteaBaseUrl/$gR/releases" }
Write-Host "============================================================"