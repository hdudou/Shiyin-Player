# Open-source Release build script (un-minified: R8 obfuscation disabled)
# Usage: powershell -ExecutionPolicy Bypass -File scripts\build-release.ps1 [-Offline]
#   -Offline   build from local dependency cache (recommended on this machine)
# Output is always copied to <open-source root>/dist/ named with the version.
param(
    [switch]$Offline
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot

# ---- Fixed toolchain (not relying on system PATH) ----
$GradleBin  = 'E:\androidplayer-t\.build_env\gradle-8.9\bin\gradle.bat'
$JavaHome   = 'E:\androidplayer-t\.build_env\jdk17'
$AndroidSdk = 'E:\androidplayer-t\.build_env\sdk'

foreach ($tool in @($GradleBin, $JavaHome, $AndroidSdk)) {
    if (-not (Test-Path $tool)) { throw "Toolchain not found: $tool" }
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