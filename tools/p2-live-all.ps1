<#
Cava P2 live 接管轮：全部 7 次真实服务端运行（显式顺序，不捕获子进程 stdout —— 踩过管道阻塞）。
每次：stop -> reset -> start -> scenario -> stop -> 抽计数。
#>
$ErrorActionPreference = 'Continue'
$root = 'J:\mc\Cava'
$script = Join-Path $root 'tools\p2-live.ps1'
$outDir = Join-Path $root 'testbed\p2-live\out'

function Log([string]$m) { Write-Host ((Get-Date).ToString('HH:mm:ss') + ' ' + $m) }

function RunOne($tag, $mode, $kind, $batches, $per, $bsteps, $steps, $dump, $canary) {
  Log ('==== ' + $tag + ' mode=' + $mode + ' kind=' + $kind + ' canary=' + $canary)
  & pwsh -NoProfile -File $script -Action stop *> $null
  & pwsh -NoProfile -File $script -Action reset *> $null
  & pwsh -NoProfile -File $script -Action start -Mode $mode -Tag $tag -Canary $canary *> $null
  $log = Join-Path $outDir ($tag + '.log')
  $ok = $false
  for ($i = 0; $i -lt 60; $i++) {
    if ((Test-Path -LiteralPath $log) -and ((Get-Content -LiteralPath $log -Tail 200 -ErrorAction SilentlyContinue) -match 'Done \(')) { $ok = $true; break }
    Start-Sleep -Milliseconds 1000
  }
  if (-not $ok) { Log ('!! ' + $tag + ' 服务端没就绪，跳过'); return }
  $sw = [System.Diagnostics.Stopwatch]::StartNew()
  if ($dump) {
    & pwsh -NoProfile -File $script -Action scenario -Kind $kind -Batches $batches -PerBatch $per -BatchSteps $bsteps -Steps $steps -OutFile (Join-Path $outDir ($tag + '.dump.txt')) *> $null
  } else {
    & pwsh -NoProfile -File $script -Action scenario -Kind $kind -Batches $batches -PerBatch $per -BatchSteps $bsteps -Steps $steps -NoDump *> $null
  }
  Log ('   scenario ' + [Math]::Round($sw.Elapsed.TotalSeconds,1) + 's')
  & pwsh -NoProfile -File $script -Action stop *> $null
  $final = Select-String -Path $log -Pattern '终局计数' | Select-Object -Last 1
  if (-not $final) { $final = Select-String -Path $log -Pattern 'cava/entity\] mode=' | Select-Object -Last 1 }
  $buckets = Select-String -Path $log -Pattern '计时分桶' | ForEach-Object { $_.Line }
  $lines = New-Object System.Collections.Generic.List[string]
  $lines.Add('### ' + $tag + ' mode=' + $mode + ' kind=' + $kind + ' batches=' + $batches + ' per=' + $per + ' bsteps=' + $bsteps + ' steps=' + $steps + ' canary=' + $canary)
  if ($final) { $lines.Add($final.Line) }
  foreach ($b in $buckets) { $lines.Add($b) }
  Set-Content -LiteralPath (Join-Path $outDir ($tag + '.counters.txt')) -Value ([string]::Join([char]10, $lines)) -Encoding UTF8
  if ($final) { Log ('   ' + $final.Line) }
  foreach ($b in $buckets) { Log ('   ' + $b) }
}

RunOne 'perf-off'    'off'    'item'  60 10 20 4000 $false 'off'
RunOne 'perf-shadow' 'shadow' 'item'  60 10 20 4000 $false 'off'
RunOne 'perf-live'   'live'   'item'  60 10 20 4000 $false 'off'
RunOne 'beh-off'     'off'    'stand' 12 10 25 400  $true  'off'
RunOne 'beh-shadow'  'shadow' 'stand' 12 10 25 400  $true  'off'
RunOne 'beh-live'    'live'   'stand' 12 10 25 400  $true  'off'
RunOne 'canary-live' 'live'   'stand' 12 10 25 400  $true  'skip-setposition'
Log 'ALL RUNS DONE'
