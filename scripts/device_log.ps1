<#
    device_log.ps1 —— 真机回归日志捕获
    用法:
      .\device_log.ps1 [start|stop] [-Device <serial>] [<logcat过滤正则>]
    示例:
      .\device_log.ps1 start                      # 清屏并后台抓全部日志 → scripts/logs/cat_<ts>.log
      .\device_log.ps1 stop                       # 结束本次后台抓取
      .\device_log.ps1 start "Ffmpeg|ffmpeg|l|Xmp|Decoder|Renderer|Scan"   # 只抓渲染/软解/扫描相关
#>
[CmdletBinding()]
param(
    [ValidateSet("start","stop")][string]$Action = "start",
    [string]$Device = "",
    [string]$Pattern = ""
)
# adb：优先用 ANDROID_HOME，否则用系统 PATH 中的 adb
$adb = if ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME "platform-tools\adb.exe" } else { "adb" }
$pkg = "com.musicplayer.debug"
$logDir = Join-Path $PSScriptRoot "logs"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$pidFile = Join-Path $logDir "logcat.pid"
$logFile = Join-Path $logDir ("cat_" + (Get-Date -Format "yyyyMMdd_HHmmss") + ".log")

switch ($Action) {
  "start" {
    if (Test-Path $pidFile) {
      $old = Get-Content $pidFile
      Write-Host "已有抓取在跑 (PID $old)；先停止旧进程。"
      Stop-Process -Id $old -Force -ErrorAction SilentlyContinue
    }
    & $adb -s $Device logcat -c
    $argList = @("-s", $Device, "logcat", "-v", "time")
    if ($Pattern) { $argList += @("-e", $Pattern) }
    $p = Start-Process -FilePath $adb -ArgumentList $argList -RedirectStandardOutput $logFile -NoNewWindow -PassThru
    Set-Content -Path $pidFile -Value $p.Id
    Write-Host "已开始抓日志 -> $logFile  (PID $($p.Id))  停止: device_log stop"
  }
  "stop" {
    if (Test-Path $pidFile) {
      $old = Get-Content $pidFile
      Stop-Process -Id $old -Force -ErrorAction SilentlyContinue
      Remove-Item $pidFile -ErrorAction SilentlyContinue
      Write-Host "已停止抓取 (PID $old)。日志在 $logDir"
    } else {
      Write-Host "没有在跑的抓取。"
    }
  }
}