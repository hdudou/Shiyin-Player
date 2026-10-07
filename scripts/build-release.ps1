# Open-source Release build script (un-minified: R8 obfuscation disabled)
# Usage: powershell -ExecutionPolicy Bypass -File scripts\build-release.ps1 [-Offline]
#   -Offline   build from local dependency cache (recommended on this machine)
# Output is always copied to <open-source root>/dist/ named with the version.
param(
    [switch]$Offline,
    # 工具链路径（可选）。优先级：参数 > 环境变量 > 内置默认值。
    # 默认值是作者本机的实际路径，仓库里也认它 —— 所以本机开箱即用，无需任何配置。
    # ⚠️ 下面三行含本机绝对路径，是**有意保留**的：闸门的 LOCAL_PATH 规则会拦下它们，
    #    故每行都带 `desensitize-allow` 行级豁免（见 check-desensitize.py 的 ALLOW_MARKER），
    #    理由是「工具链定位必须能在他人机器上直接改成自己的路径」，且不涉及任何账号口令。
    #    换机器时：改这三行，或传 -GradleBin/-JavaHome/-AndroidSdk，或设同名环境变量。
    [string]$GradleBin,
    [string]$JavaHome,
    [string]$AndroidSdk
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot

# ---- 工具链解析：参数 > 环境变量 > 内置默认 ----
$GradleBin  = if ($GradleBin)  { $GradleBin }  elseif ($env:GRADLE_BIN)  { $env:GRADLE_BIN }  else { 'E:\MYCODE\shiyin\androidplayer-t\.build_env\gradle-8.9\bin\gradle.bat' }  # desensitize-allow: 本机工具链定位，非私密信息
$JavaHome   = if ($JavaHome)   { $JavaHome }   elseif ($env:JAVA_HOME)   { $env:JAVA_HOME }   else { 'E:\MYCODE\shiyin\androidplayer-t\.build_env\jdk17' }  # desensitize-allow: 本机工具链定位，非私密信息
$AndroidSdk = if ($AndroidSdk) { $AndroidSdk } elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { 'E:\MYCODE\shiyin\androidplayer-t\.build_env\sdk' }  # desensitize-allow: 本机工具链定位，非私密信息

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

$env:JAVA_HOME = $JavaHome
$env:PATH = "$JavaHome\bin;$env:PATH"
$env:ANDROID_HOME = $AndroidSdk
$env:ANDROID_SDK_ROOT = $AndroidSdk

# Read current version from app/version.properties
$vp = @{}
$vpFile = Join-Path $Root 'app\version.properties'
if (Test-Path $vpFile) {
    $lines = [System.IO.File]::ReadAllLines($vpFile)
    foreach ($line in $lines) {
        $m = [regex]::Match($line, '^[ \t]*(\w+)[ \t]*=[ \t]*(.+?)[ \t]*$')
        if ($m.Success) { $vp[$m.Groups[1].Value] = $m.Groups[2].Value }
    }
}
$versionName = '1.0.0'
if ($vp['versionName']) {
    $versionName = $vp['versionName']
} elseif ($vp['buildCode']) {
    $versionName = '1.0.' + $vp['buildCode']
}

$Dist = Join-Path $Root 'dist'
New-Item -ItemType Directory -Force -Path $Dist | Out-Null

Write-Host "==> Building Release (un-minified), version $versionName ..."
# gradle writes deprecation warnings to stderr; under EAP=Stop those are misread as fatal.
$prevEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    if ($Offline) {
        & $GradleBin ':app:assembleRelease' '--console=plain' '--offline'
    } else {
        & $GradleBin ':app:assembleRelease' '--console=plain'
    }
} finally {
    $ErrorActionPreference = $prevEap
}
if ($LASTEXITCODE -ne 0) { throw "Gradle build failed (exit=$LASTEXITCODE)" }

$Apk = Join-Path $Root 'app\build\outputs\apk\release\app-release.apk'
if (-not (Test-Path $Apk)) { throw "APK not found: $Apk" }

$Dest = Join-Path $Dist "app-release-$versionName.apk"
Copy-Item $Apk $Dest -Force
Write-Host ""
Write-Host "==> Un-minified Release done: $Dest"
Write-Host "    Raw product: $Apk"
$size = [math]::Round((Get-Item $Dest).Length / 1MB, 2)
Write-Host "    Size: $size MB"