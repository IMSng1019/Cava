<#
Cava P2 live 接管轮：跑一次（reset -> start -> scenario -> 抓计数 -> stop）。
用法：pwsh -File tools/p2-live-one.ps1 -Tag perf-off -Mode off -Kind item -Batches 60 -PerBatch 10 -BatchSteps 20 -Steps 4000
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][string]$Tag,
  [Parameter(Mandatory = $true)][ValidateSet('off','shadow','live')][string]$Mode,
  [ValidateSet('stand','item')][string]$Kind = 'stand',
  [int]$Batches = 12,
  [int]$PerBatch = 10,
  [int]$BatchSteps = 25,
  [int]$Steps = 400,
  [switch]$Dump,
  [string]$Canary = 'off'
)
$ErrorActionPreference = 'Stop'
$root = 'J:\mc\Cava'
$script = Join-Path $root 'tools\p2-live.ps1'
$outDir = Join-Path $root 'testbed\p2-live\out'
Write-Host ('==== ' + $Tag + ' mode=' + $Mode + ' kind=' + $Kind + ' batches=' + $Batches + ' per=' + $PerBatch + ' bsteps=' + $BatchSteps + ' steps=' + $Steps + ' canary=' + $Canary)
& pwsh -NoProfile -File $script -Action reset | Out-Null
$startOut = & pwsh -NoProfile -File $script -Action start -Mode $Mode -Tag $Tag -Canary $Canary
if (-not (($startOut | Out-String) -match 'ready-confirmed')) {
  throw ('服务端没有就绪，本次不跑场景：' + ($startOut | Out-String))
}
$sw = [System.Diagnostics.Stopwatch]::StartNew()
if ($Dump) {
  & pwsh -NoProfile -File $script -Action scenario -Kind $Kind -Batches $Batches -PerBatch $PerBatch -BatchSteps $BatchSteps -Steps $Steps -OutFile (Join-Path $outDir ($Tag + '.dump.txt'))
} else {
  & pwsh -NoProfile -File $script -Action scenario -Kind $Kind -Batches $Batches -PerBatch $PerBatch -BatchSteps $BatchSteps -Steps $Steps -NoDump
}
Write-Host ('scenario 用时 ' + [Math]::Round($sw.Elapsed.TotalSeconds, 1) + 's')
& pwsh -NoProfile -File $script -Action stop | Out-Null
$log = Join-Path $outDir ($Tag + '.log')
$final = Select-String -Path $log -Pattern '终局计数' | Select-Object -Last 1
if (-not $final) { $final = Select-String -Path $log -Pattern 'cava/entity\] mode=' | Select-Object -Last 1 }
$buckets = Select-String -Path $log -Pattern '计时分桶' | ForEach-Object { $_.Line }
$text = New-Object System.Collections.Generic.List[string]
$text.Add('### ' + $Tag + ' mode=' + $Mode + ' kind=' + $Kind + ' batches=' + $Batches + ' per=' + $PerBatch + ' bsteps=' + $BatchSteps + ' steps=' + $Steps + ' canary=' + $Canary)
$text.Add($final.Line)
foreach ($b in $buckets) { $text.Add($b) }
Set-Content -LiteralPath (Join-Path $outDir ($Tag + '.counters.txt')) -Value ([string]::Join([char]10, $text)) -Encoding UTF8
Write-Host '---- 终局计数 ----'
Write-Host $final.Line
Write-Host '---- 分桶趋势 ----'
foreach ($b in $buckets) { Write-Host $b }
