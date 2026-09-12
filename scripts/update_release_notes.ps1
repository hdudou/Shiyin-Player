$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$doc = Join-Path $ProjectRoot "docs\RELEASE_NOTES.md"
$chl = Join-Path $ProjectRoot "app\src\main\assets\changelog.json"

$lines = Get-Content $doc -Encoding UTF8

# 分两部分：head 保留 开头到 `## 下一版` 标题行（含）；其余全部是 tail（1.0.87 及更早的历史版本）
$head = New-Object System.Collections.Generic.List[string]
$tail = New-Object System.Collections.Generic.List[string]
$pastNext = $false
foreach ($ln in $lines) {
    if (-not $pastNext) {
        $head.Add($ln)
        if ($ln -match '^## 下一版') { $pastNext = $true }
    } else {
        $tail.Add($ln)
    }
}

# changelog 数据源：取已发布版本（1.0.88 起），按新版优先排序
$j = (Get-Content $chl -Raw -Encoding UTF8) | ConvertFrom-Json
$raw = @($j.versions | Where-Object { $_.version -ge '1.0.88' })
# 去重：按 version 分组保留最后一条
$final = [ordered]@{}
foreach ($v in $raw) { $final[$v.version] = $v }
$seq = @($final.Values)
$seq = @($seq | Sort-Object -Property @{
    Expression = { [int]($_.version -split '\.')[0] }
}, @{
    Expression = { [int]($_.version -split '\.')[1] }
}, @{
    Expression = { [int]($_.version -split '\.')[2] }
} -Descending)

$sb = New-Object System.Text.StringBuilder
foreach ($v in $seq) {
    [void]$sb.AppendLine("## $($v.version) — $($v.date)（$($v.channel)）")
    foreach ($n in $v.notes) { [void]$sb.AppendLine("- $n") }
    [void]$sb.AppendLine("")
}
$block = $sb.ToString()

# 组合
$result = ($head -join "`n").TrimEnd("`r","`n") + "`n`n" + $block.TrimEnd("`r","`n") + "`n`n" + ($tail -join "`n").TrimStart("`r","`n")
$result = $result -replace '(\r?\n){3,}', "`n`n"
[System.IO.File]::WriteAllText($doc, $result, (New-Object System.Text.UTF8Encoding($false)))
Write-Output "已更新 RELEASE_NOTES.md；本次插入版本: $(@($seq | Select-Object -First 8 | ForEach-Object { $_.version }) -join ', ')"