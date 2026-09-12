$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$assets = Join-Path $ProjectRoot "app\src\main\assets\radio_stations.json"
$out    = Join-Path (Split-Path -Parent $ProjectRoot) "update-stage\radio_builtin.json"

$a = Get-Content $assets -Raw -Encoding UTF8 | ConvertFrom-Json
$as = if ($a -is [array]) { $a } else { $a.stations }

$stations = foreach ($s in $as) {
    [PSCustomObject]@{
        name    = $s.name
        url     = $s.url
        genre   = $s.genre
        country = $s.country
        logoUrl = $s.logoUrl
    }
}
$root = [PSCustomObject]@{ version = 2; stations = $stations }
$json = $root | ConvertTo-Json -Depth 6 -Compress
[System.IO.File]::WriteAllText($out, $json, (New-Object System.Text.UTF8Encoding($false)))
Write-Output "已生成 $out (version=2, 条数=$($as.Count))"
Write-Output "文件大小: $((Get-Item $out).Length) bytes"