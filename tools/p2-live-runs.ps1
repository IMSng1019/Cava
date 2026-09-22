<#
Cava P2 live 接管轮：6 次真实服务端运行（3 次性能 + 3 次行为对拍）。
每次：reset 世界 -> start（指定模式）-> scenario -> 抓计数 -> stop。
结果落在 testbed/p2-live/out/<tag>.{log,counters.txt,dump.txt}。
#>
$ErrorActionPreference = 'Stop'
$root = 'J:\mc\Cava'
$script = Join-Path $root 'tools\p2-live.ps1'
$outDir = Join-Path $root 'testbed\p2-live\out'

$runs = @(
  @{ tag = 'perf-off';    mode = 'off';    kind = 'item';  batches = 60; per = 10; bsteps = 20; steps = 4000; dump = $false },
  @{ tag = 'perf-shadow'; mode = 'shadow'; kind = 'item';  batches = 60; per = 10; bsteps = 20; steps = 4000; dump = $false },
  @{ tag = 'perf-live';   mode = 'live';   kind = 'item';  batches = 60; per = 10; bsteps = 20; steps = 4000; dump = $false },
  @{ tag = 'beh-off';     mode = 'off';    kind = 'stand'; batches = 12; per = 10; bsteps = 25; steps = 400;  dump = $true },
  @{ tag = 'beh-shadow';  mode = 'shadow'; kind = 'stand'; batches = 12; per = 10; bsteps = 25; steps = 400;  dump = $true },
  @{ tag = 'beh-live';    mode = 'live';   kind = 'stand'; batches = 12; per = 10; bsteps = 25; steps = 400;  dump = $true }
)

foreach ($r in $runs) {
  Write-Host ('==================== ' + $r.tag + ' (mode=' + $r.mode + ' kind=' + $r.kind + ') ====================')
  & pwsh -NoProfile -File $script -Action reset | Out-Null
  & pwsh -NoProfile -File $script -Action start -Mode $r.mode -Tag $r.tag | Out-Null
  if ($r.dump) {
    & pwsh -NoProfile -File $script -Action scenario -Kind $r.kind -Batches $r.batches -PerBatch $r.per -BatchSteps $r.bsteps -Steps $r.steps -OutFile (Join-Path $outDir ($r.tag + '.dump.txt'))
  } else {
    & pwsh -NoProfile -File $script -Action scenario -Kind $r.kind -Batches $r.batches -PerBatch $r.per -BatchSteps $r.bsteps -Steps $r.steps -NoDump
  }
  & pwsh -NoProfile -File $script -Action stop | Out-Null
  $log = Join-Path $outDir ($r.tag + '.log')
  $final = Select-String -Path $log -Pattern '终局计数' | Select-Object -Last 1
  if (-not $final) { $final = Select-String -Path $log -Pattern 'cava/entity\] mode=' | Select-Object -Last 1 }
  $buckets = Select-String -Path $log -Pattern '计时分桶' | ForEach-Object { $_.Line }
  $text = New-Object System.Collections.Generic.List[string]
  $text.Add('### ' + $r.tag + ' mode=' + $r.mode + ' kind=' + $r.kind + ' batches=' + $r.batches + ' per=' + $r.per + ' bsteps=' + $r.bsteps + ' steps=' + $r.steps)
  $text.Add($final.Line)
  foreach ($b in $buckets) { $text.Add($b) }
  Set-Content -LiteralPath (Join-Path $outDir ($r.tag + '.counters.txt')) -Value ([string]::Join([char]10, $text)) -Encoding UTF8
  Write-Host $final.Line
  foreach ($b in $buckets) { Write-Host $b }
}
Write-Host 'ALL RUNS DONE'
