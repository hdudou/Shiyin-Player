#Requires -Version 5.1
<#
  SHIYINPLAYER 一键发版脚本（v2：通道可选 + 严格校验）
  作用：按 -Channel 构建所选通道(debug/release/both) → 重命名安装包 → 计算 size/MD5 →
        生成 latest.json（未选通道保留服务器现值，绝不覆盖）→ 上传 → 三关严格校验。

  用法（在本项目任意位置以 PowerShell 运行）：
    .\scripts\publish.ps1 -Channel both -ReleaseNote "更新说明"   # debug+release 齐发（默认）
    .\scripts\publish.ps1 -Channel debug                          # 仅发 debug（等价旧 -DebugOnly）
    .\scripts\publish.ps1 -Channel release                        # 仅发 release
    .\scripts\publish.ps1 -Channel debug -SkipBuild               # 不构建、仅上传本地既有产物
    .\scripts\publish.ps1 -Channel both  -SkipUpload              # 只构建并生成发布产物，不上传

  版本机制：每次发版（非 -SkipBuild）version.properties 的 buildCode 自增 1，
    versionName = 1.0.<buildCode>，debug 与 release 共用同一版本号（包名不同，无冲突）。

  【严格校验（防陈旧包冒名 / 防覆盖未选通道 / 防上传损坏）】
    1° 构建产物内嵌版本核对：用 aapt dump badging 断言每个待发 APK 的 versionName/versionCode
       与本次目标完全一致。若不一致直接中止——尤其是 -SkipBuild 复用旧包时会立即暴露，
       彻底杜绝「旧 release 包被重命名成新版本号上传」这类历史事故。
    2° latest.json 通道保留：只写入本次选中的通道节，其余通道（及其它顶层字段）从服务器
       当前 latest.json 原样保留；debug-only 绝不触碰服务器 release 节，反之亦然。
    3° 上传后回读校验：断言服务器 latest.json 含本次选中通道的 versionCode，并对每个已发 APK
       下载回读比对 MD5、核对 HTTP 200，杜绝「标称版本与服务器文件不一致」。

# 开源版：更新仓库地址与凭据由 -WebDAVUrl/-WebDAVUser/-WebDAVPass 显式传入（不再内嵌任何默认值）。
#>
param(
  # 可选通道：debug / release / both（或 all 同义）。
  [ValidateSet("debug", "release", "both", "all")]
  [string]$Channel = "both",
  # 兼容旧用法：等效 -Channel debug。
  [switch]$DebugOnly,
  [switch]$SkipBuild,
  [switch]$SkipUpload,
  [string]$ReleaseNote = "",
  # 可选：自定义 versionName（默认 1.0.<buildCode>）。重大版本如 "2.0.0"。
  [string]$VersionName = "",
  # 开源版：不内嵌默认更新服务器地址/凭据，发布前用 -WebDAVUrl/-WebDAVUser/-WebDAVPass 显式传入。
  [string]$WebDAVUrl  = "",
  [string]$WebDAVUser = "",
  [string]$WebDAVPass = ""
)
$ErrorActionPreference = "Stop"

# ---------- 构建环境配置 (开源版，可根据本地构建环境修改) ----------
$ProjectRoot = Split-Path -Parent $PSScriptRoot          # scripts/ 上一级 = 项目根
$AppDir      = Join-Path $ProjectRoot "app"

# 开源版建议通过环境变量指定，或取消注释修改为你本地绝对路径
# 1. 本地 Gradle 8.9 安装位置
# $GradleBin   = "C:\gradle-8.9\bin\gradle.bat"
$GradleBin = if ($env:GRADLE_BIN) { $env:GRADLE_BIN } else { "C:\gradle-8.9\bin\gradle.bat" }

# 2. 本地 JDK 17 (必须含 jlink 工具)
# $JavaHome    = "C:\Program Files\Amazon Corretto\jdk17.0.11_9"
$JavaHome = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { "<your-local-jdk17>" }

# 3. Android SDK 根目录
# $SdkDir      = "C:\Users\YourName\AppData\Local\Android\Sdk"
$SdkDir = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { "<your-android-sdk>" }

$AaptBin     = Join-Path $SdkDir "build-tools\34.0.0\aapt.exe"
$VersionProp = Join-Path $AppDir "version.properties"
$DebugApk    = Join-Path $AppDir "build\outputs\apk\debug\app-debug.apk"
$ReleaseApk  = Join-Path $AppDir "build\outputs\apk\release\app-release.apk"

# 本地发布产物输出目录（开源版可保留项目同级或自定义）
$PubDir      = Join-Path (Split-Path -Parent $ProjectRoot) "update-stage"
$Changelog   = Join-Path $AppDir "src\main\assets\changelog.json"  # 应用内置版本更新数据源
$Latest      = Join-Path $PubDir "latest.json"

function Log  { param([string]$m) Write-Host "[发版] $m" -ForegroundColor Cyan }
function Ok   { param([string]$m) Write-Host "[OK]  $m" -ForegroundColor Green }
function Warn { param([string]$m) Write-Host "[警告] $m" -ForegroundColor Yellow }

# ---------- 0. 归一化通道选择 ----------
if ($DebugOnly) { $Channel = "debug" }
if ($Channel -eq "all") { $Channel = "both" }
$channels = @()
switch ($Channel) {
  "debug"   { $channels = @("debug");               $label = "仅 debug" }
  "release" { $channels = @("release");             $label = "仅 release" }
  default   { $channels = @("debug", "release");    $label = "debug + release" }
}
Log "发布通道：$label"

function Get-ChannelCfg {
  param([string]$ch)
  if ($ch -eq "debug") {
    return @{ Slot = "debug"; Task = ":app:assembleDebug"; Apk = $DebugApk }
  }
  return @{ Slot = "release"; Task = ":app:assembleRelease"; Apk = $ReleaseApk }
}

# ---------- 1. 读取 / 递增版本号 ----------
if (-not (Test-Path $VersionProp)) {
  throw "找不到 $VersionProp，请确认在项目根运行，或修正脚本内 ProjectRoot/AppDir。"
}
Log "项目根: $ProjectRoot"
$curCode = [int](((Get-Content $VersionProp | Select-String '^buildCode=').Line) -replace '.*buildCode=(\d+).*', '$1')
if ($SkipBuild) { $nextCode = $curCode } else { $nextCode = $curCode + 1 }
$nextVer = if ($VersionName.Trim()) { $VersionName.Trim() } else { "1.0.$nextCode" }
Log "版本: $nextVer (versionCode=$nextCode)"

# ---------- 2. 更新说明拆分 + 追加内置 changelog ----------
function Split-Notes {
  param([string]$note)
  if ([string]::IsNullOrWhiteSpace($note)) { return @() }
  return ($note -split "[\r\n;；]+" | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne "" })
}
function Add-ChangelogEntry {
  param([string]$Ver, [int]$Code, [string]$Note, [string]$Chl, [string]$File)
  if (-not (Test-Path $File)) { $obj = @{ versions = @() } } else {
    $obj = (Get-Content $File -Raw -Encoding UTF8) | ConvertFrom-Json
  }
  $notes = Split-Notes $Note
  if ($notes.Count -eq 0) { $notes = @($Note) }
  $entry = [ordered]@{
    version = $Ver
    code    = $Code
    date    = (Get-Date -Format "yyyy-MM-dd")
    channel = $Chl
    notes   = @($notes)
  }
  $arr = @($obj.versions)
  $arr += [pscustomobject]$entry
  $obj.versions = $arr
  New-Item -ItemType Directory -Path (Split-Path $File) -Force | Out-Null
  [System.IO.File]::WriteAllText($File, ($obj | ConvertTo-Json -Depth 6),
    (New-Object System.Text.UTF8Encoding($false)))
  Ok "已追加版本更新记录 v$Ver 到内置 changelog.json"
}
# 未指定说明时的默认文案
$note = if ($ReleaseNote.Trim()) { $ReleaseNote.Trim() }
        else { "发版 v$nextVer（$label）；versionCode=$nextCode" }
$chlChannel = $channels -join "+"          # "debug" / "release" / "debug+release"

# ---------- 3. 构建 ----------
if (-not $SkipBuild) {
  if (-not (Test-Path $GradleBin)) { throw "找不到 Gradle: $GradleBin" }
  $verHeader = "# " + (Get-Date -Format 'ddd MMM dd HH:mm:ss zzz yyyy')
  Set-Content -Path $VersionProp -Value @($verHeader, "buildCode=$nextCode", "versionName=$nextVer") -Encoding ASCII
  Add-ChangelogEntry -Ver $nextVer -Code $nextCode -Note $note -Chl $chlChannel -File $Changelog
  $tasks = @($channels | ForEach-Object { (Get-ChannelCfg $_).Task })
  $env:JAVA_HOME = $JavaHome
  Log "开始构建 $label（buildCode=$nextCode）... task: $($tasks -join ' ')"
  # 数组展开为独立参数（gradle 8.9 不接受多 task 串成单个字符串）。偶发的任务解析失败自动重试一次。
  $attempt = 0
  do {
    $attempt++
    # 构建进程会向 stderr 写 Gradle warning，而 $ErrorActionPreference=Stop 会把
    # 本地错误记录直接抛为终止错误、误判构建失败。构建调用期间临时降为 Continue。
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
      $gradleOut = & $GradleBin @tasks --console=plain 2>&1 | Out-String
    } finally {
      $ErrorActionPreference = $prevEap
    }
    if ($LASTEXITCODE -eq 0) { break }
    if ($attempt -eq 1 -and $gradleOut -match "Cannot locate tasks") {
      Warn "首次 Gradle 任务解析失败（偶发），自动重试一次..."
    } else { break }
  } while ($true)
  if ($LASTEXITCODE -ne 0) {
    Write-Host ($gradleOut -split "\r?\n" | Select-Object -Last 30) -ForegroundColor Red
    throw "Gradle 构建失败 (exit $LASTEXITCODE)"
  }
  # 构建后可能被改回，重新读一次以保证与磁盘一致
  $line = (Get-Content $VersionProp | Select-String '^buildCode=').Line
  $nextCode = [int]($line -replace '.*buildCode=(\d+).*', '$1')
  $nextVer = if ($VersionName.Trim()) { $VersionName.Trim() } else { "1.0.$nextCode" }
  Ok "构建完成 ($label)"
} else {
  Log "跳过构建（-SkipBuild），不追加 changelog，使用本地既有产物"
}
$buildCode   = $nextCode
$ver         = $nextVer
$relNote     = if ($ReleaseNote.Trim()) { $ReleaseNote.Trim() } else { $note }

# ---------- 4. 严格校验①：产物内嵌版本必须与目标一致（防陈旧包冒名/复旧包） ----------
function Assert-ApkVersion {
  param([string]$Apk, [string]$ExpectVer, [int]$ExpectCode, [string]$Slot)
  if (-not (Test-Path $Apk)) { throw "缺少构建产物: $Apk（请先构建）。" }
  if (-not (Test-Path $AaptBin)) { throw "找不到 aapt: $AaptBin" }
  $raw = & $AaptBin dump badging $Apk 2>&1 | Out-String
  $code = if ($raw -match "versionCode='(\d+)'") { [int]$Matches[1] } else { -1 }
  $vname = if ($raw -match "versionName='([^']+)'") { $Matches[1] } else { "" }
  if ($code -ne $ExpectCode -or $vname -ne $ExpectVer) {
    throw ("[严格校验①失败][$Slot] APK 内嵌版本=$vname(code=$code) 与目标 $ExpectVer(code=$ExpectCode) 不一致。" +
           "疑似用了未随本次发版构建的陈旧产物（尤其 -SkipBuild 复用旧包）。请删除该产物后完整构建再发版。")
  }
  Ok "[严格校验①][$Slot] 产物内嵌版本 $vname (code $code) 与目标一致"
  return @{ versionCode = $code; versionName = $vname }
}

# ---------- 5. 重命名产物 + 计算 size/MD5 ----------
New-Item -ItemType Directory -Path $PubDir -Force | Out-Null
$sections = @{}
foreach ($ch in $channels) {
  $cfg = Get-ChannelCfg $ch
  Assert-ApkVersion -Apk $cfg.Apk -ExpectVer $ver -ExpectCode $buildCode -Slot $ch | Out-Null
  $name = "shiyinplayer-$ch-v$ver.apk"
  $dst  = Join-Path $PubDir $name
  Copy-Item $cfg.Apk $dst -Force
  $len = (Get-Item $dst).Length
  $md5 = (Get-FileHash -Algorithm MD5 $dst).Hash.ToLower()
  if ($len -lt (10MB)) { throw "[校验] $name 体积异常偏小($len)，疑似产物异常，中止发版。" }
  $sections[$ch] = @{
    versionCode = $buildCode
    versionName = $ver
    apk         = $name
    size        = $len
    md5         = $md5
    releaseNote = $relNote
  }
  Ok "[$ch] 产物: $name size=$len md5=$md5"
}

# ---------- 6. 生成 latest.json（严格保留未选通道） ----------
$cur = $null
if (-not $SkipUpload) {
  $curlProbe = (Get-Command curl.exe -ErrorAction SilentlyContinue).Source
  if ($curlProbe) {
    Log "拉取服务器当前 latest.json（用于保留未选通道）..."
    $curJson = & $curlProbe -s -u "${WebDAVUser}:${WebDAVPass}" --connect-timeout 8 "$WebDAVUrl/latest.json"
    if ($curJson) { try { $cur = $curJson | ConvertFrom-Json } catch { Warn "解析服务器 latest.json 失败：$_" } }
  }
}
if (-not $cur -and (Test-Path $Latest)) {
  # SkipUpload 或服务器不可达时，以本地 last 值作为保留来源
  try { $cur = (Get-Content $Latest -Raw -Encoding UTF8) | ConvertFrom-Json } catch { $cur = $null }
}
$latestObj = [ordered]@{}
foreach ($ch in $channels) { $latestObj[$ch] = $sections[$ch] }   # 选中通道：全新节
if ($cur) {
  foreach ($prop in $cur.PSObject.Properties) {                 # 未选通道/未知字段：原样保留
    if ($sections.ContainsKey($prop.Name)) { continue }
    $latestObj[$prop.Name] = $prop.Value
  }
}
$latestJson = $latestObj | ConvertTo-Json -Depth 6
[System.IO.File]::WriteAllText($Latest, $latestJson, (New-Object System.Text.UTF8Encoding($false)))
Ok "已生成 latest.json（发布目录: $PubDir）"

if (-not $SkipUpload) {
  # ---------- 7. 上传 ----------
  $curl = (Get-Command curl.exe -ErrorAction SilentlyContinue).Source
  if (-not $curl) { throw "找不到 curl.exe（Windows 10 自带），请安装或加入 PATH。" }
  Log "上传到 $WebDAVUrl ..."
  $releaseFiles = @($channels | ForEach-Object { $sections[$_].apk }) + @("latest.json")
  foreach ($full in $releaseFiles) {
    $src = Join-Path $PubDir $full
    $code = & $curl -s -u "${WebDAVUser}:${WebDAVPass}" -T $src -o NUL `
      -w "%{http_code}" --connect-timeout 10 "$WebDAVUrl/$full"
    if ($LASTEXITCODE -ne 0 -or "$code" -notmatch '^2\d\d$') {
      throw "上传失败: $full (HTTP $code)"
    }
    Ok "上传 ${full}: HTTP $code"
  }

  # ---------- 8. 严格校验②③：回读 latest.json + 下载比对 MD5/HTTP200 ----------
  Log "严格校验②：回读服务器 latest.json ..."
  $jsonOk = $false
  for ($try = 1; $try -le 3; $try++) {
    $resp = & $curl -s -u "${WebDAVUser}:${WebDAVPass}" --connect-timeout 8 "$WebDAVUrl/latest.json"
    $parsed = $null
    try { $parsed = $resp | ConvertFrom-Json } catch {}
    if ($parsed) {
      $allOk = $true
      foreach ($ch in $channels) {
        if ("$($parsed.$ch.versionCode)" -ne "$buildCode") { $allOk = $false; break }
      }
      if ($allOk) { $jsonOk = $true; break }
    }
    if ($try -lt 3) { Log "服务器 latest.json 尚缺验证($ver)，3s 后重试（第 $try 次）..." ; Start-Sleep -Seconds 3 }
  }
  if (-not $jsonOk) { throw "[严格校验②失败] 服务器 latest.json 未包含本次选中通道的 versionCode=$buildCode" }
  Ok "[严格校验②] 服务器 latest.json 已包含本次选中通道 versionCode=$buildCode"

  Log "严格校验③：下载回读比对 MD5（选中通道安装包）..."
  foreach ($ch in $channels) {
    $name  = $sections[$ch].apk
    $local = (Get-FileHash -Algorithm MD5 (Join-Path $PubDir $name)).Hash.ToLower()
    $dl    = Join-Path $env:TEMP ("shiyin_verify_{0}_v{1}.apk" -f $ch, $ver)
    & $curl -s -u "${WebDAVUser}:${WebDAVPass}" -o $dl "$WebDAVUrl/$name"
    if ($LASTEXITCODE -ne 0) { throw "下载回读失败: $name" }
    $serverMd5 = (Get-FileHash -Algorithm MD5 $dl).Hash.ToLower()
    Remove-Item $dl -ErrorAction SilentlyContinue
    if ($serverMd5 -ne $local) {
      throw "[严格校验③失败][$ch] 服务器文件 MD5($serverMd5) 与本地($local) 不一致，可能上传不完整，中止。"
    }
    Ok "[严格校验③][$ch] 服务器下载 $name MD5 一致"
  }
  Ok "服务器验证通过：latest.json 已指向 $ver，选中通道安装包可下载且 MD5 一致"
} else {
  Log "跳过上传与回读校验（-SkipUpload）"
}

# ---------- 9. 汇总 ----------
Write-Host ""
foreach ($ch in $channels) {
  $s = $sections[$ch]
  Write-Host ("  [{0}] {1}  v{2} (code {3})  {4}  md5 {5}" -f $ch, $s.apk, $ver, $s.versionCode, $s.size, $s.md5) -ForegroundColor Green
}
Ok "发版完成：'$label' → 1.0.$buildCode (versionCode=$buildCode)"
Log "本地发布产物目录: $PubDir"