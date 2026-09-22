<#
Cava 差分测试：**脚本驱动的寻路差分**（用 P1 已有的 /cava pathfind bench）。

为什么需要它：实体层用"僵尸追村民"这种 AI 驱动时，寻路**何时发生**取决于 mob 的随机数
（本流实测：同一配置跑两次，p 在 515/600 个 tick 上不同，而 w 完全一致）。
/cava pathfind bench N 用的是**固定场景**（PathfindScenario：固定起点/终点/range/followRange/生物），
由 RCON 脚本驱动 N 次真实的 findPathToAny，所以它是"脚本化合成场景"的正解。

它同时是 **ServerCore 跳过** 那一问的实测面：native on 时我们 HEAD 取消，ServerCore 的
PathFinderMixin 体内 4x@Redirect + 2x@ModifyVariable 不执行；native off 时它们照常执行。
avgNodes 若一致，说明"只换容器实现"的补丁被跳过不改变可观测结果（单目标下）。

用法：
  pwsh -File tools/parity-bench.ps1 -Compare                # 跑两条腿并对比
  pwsh -File tools/parity-bench.ps1 -Native on -Bench 2000
#>
[CmdletBinding()]
param(
  [ValidateSet('on', 'off')][string]$Native = 'off',
  [switch]$Compare,
  [string]$Leg = '',
  [int]$Bench = 2000,
  [string]$Root = '',
  [int]$ServerPort = 25591,
  [int]$RconPort = 25592,
  [switch]$SkipRuns
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
. (Join-Path $PSScriptRoot 'lib-server.ps1')
$repo = Split-Path $PSScriptRoot -Parent
if (-not $Root) { $Root = Join-Path $repo 'testbed\parity' }
function Rcon([string]$cmd) {
  try { return (& (Join-Path $PSScriptRoot 'rcon.ps1') -Command $cmd -Port $RconPort -NoLog) }
  catch { Write-Host "[bench] rcon failed($cmd): $_"; return $null }
}

function Invoke-BenchLeg([string]$native) {
  $leg = "bench-$native"
  $serverDir = Join-Path $Root 'server'
  $worldDir = Join-Path $serverDir 'world'
  $snapPath = Join-Path $Root 'snapshots\parity-base'
  if (Test-Path $worldDir) { Remove-Item -LiteralPath $worldDir -Recurse -Force }
  New-Item -ItemType Directory -Force -Path $worldDir | Out-Null
  Copy-Item -Path (Join-Path $snapPath '*') -Destination $worldDir -Recurse -Force
  & (Join-Path $PSScriptRoot 'make-parity-datapack.ps1') -WorldDir $worldDir -PlatformX 72 -PlatformZ 0 -GroundY 70 | Out-Null
  $javaArgs = @('-Xms2G', '-Xmx4G', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
    '--enable-preview', '--enable-native-access=ALL-UNNAMED',
    "-Dcava.native.enabled=$(if ($native -eq 'on') { 'true' } else { 'false' })")
  if ($native -eq 'on') { $javaArgs += '-Dcava.pathfind.native=true' }
  $srv = Start-CavaServer -Root $Root -Name $leg -JavaArgs $javaArgs
  $r = Wait-CavaServerReady -Server $srv -TimeoutSec 300
  if (-not $r.Ready) { throw "服务端没起来：$($r.Reason)" }
  Start-Sleep -Seconds 3
  foreach ($n in @($Bench, $Bench, $Bench)) {
    $before = (Select-String -LiteralPath $srv.Log -Pattern 'BENCH id=' -ErrorAction SilentlyContinue | Measure-Object).Count
    Rcon "cava pathfind bench $n" | Out-Null
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    while ($sw.Elapsed.TotalSeconds -lt 180) {
      Start-Sleep -Seconds 2
      $now = (Select-String -LiteralPath $srv.Log -Pattern 'BENCH id=' -ErrorAction SilentlyContinue | Measure-Object).Count
      if ($now -gt $before) { break }
    }
    Write-Host ("[bench] {0} n={1} 回执={2}" -f $leg, $n, $(if ($now -gt $before) { 'OK' } else { 'MISSING（无效采样）' }))
  }
  Start-Sleep -Seconds 2
  $lines = Select-String -LiteralPath $srv.Log -Pattern 'BENCH id=|canary=.*takeovers=|金丝雀 PASS|接管条件' -ErrorAction SilentlyContinue |
    ForEach-Object { $_.Line.Trim() }
  Rcon 'stop' | Out-Null
  if (-not $srv.Process.WaitForExit(180000)) { $srv.Process.Kill() }
  return [pscustomobject]@{ leg = $leg; log = $srv.Log; lines = $lines }
}

if ($Compare) {
  $off = Invoke-BenchLeg 'off'
  $on = Invoke-BenchLeg 'on'
  Write-Host ''
  Write-Host '================ bench 两腿 ================'
  Write-Host '--- native off ---'; $off.lines | ForEach-Object { Write-Host "  $_" }
  Write-Host '--- native on ---'; $on.lines | ForEach-Object { Write-Host "  $_" }
  $avgOff = ($off.lines | Select-String -Pattern 'BENCH id=.*avgNodes=([\d.]+)' -AllMatches | ForEach-Object { $_.Matches[0].Groups[1].Value }) -join ','
  $avgOn = ($on.lines | Select-String -Pattern 'BENCH id=.*avgNodes=([\d.]+)' -AllMatches | ForEach-Object { $_.Matches[0].Groups[1].Value }) -join ','
  Write-Host ''
  Write-Host ("  决定性字段 avgNodes：off=[{0}]  on=[{1}]" -f $avgOff, $avgOn)
  Write-Host ("  判定：" + $(if ($avgOff -eq $avgOn -and $avgOff -ne '') { '两腿一致（单目标下跳过 ServerCore 体内补丁不可观测）' } else { '不一致或没采到 ⇒ 见上面的原文' }))
  exit 0
}

if ($SkipRuns) { exit 0 }
if (-not $Leg) { $Leg = "bench-$Native" }
$res = Invoke-BenchLeg $Native
Write-Host '--- 日志原文 ---'
$res.lines | ForEach-Object { Write-Host "  $_" }
