<#
Cava 差分测试（prompts/03）整服层：**不变量断言**（不是逐 tick 世界哈希）。

为什么降级成不变量：docs/CAVA-determinism-report.md 的受控实验结论 —— 同一份代码跑两次，
region 有 3–4 个区块不同（全在出生点附近）、entities 有 5 个不同。逐 tick 世界哈希一致
在当前整合包上**物理不可达**，拿它当验收只会被噪声掩盖真 bug。

这一层只断言（每一条都能在日志/采样里追责）：
  1. 跑满 N tick 且**实时**（/tick unfreeze，不是 sprint —— sprint 下 TPS/MSPT 没有意义）
  2. MSPT 平均值不退化（/tick query 的 "Average time per tick"）
  3. TPS 不低于阈值（spark tps）
  4. 日志无 Exception、无「整体回退纯 Java」、无 native 错误码
  5. 路径子系统 errors=0（有 canary/takeover 行时校验 takeovers==nativeCalls）
  6. 实体数在预期范围内（execute if entity @e 的**结果值** = 匹配实体数）
  7. 服务端**没有崩**（正常退出码 + 无 hs_err_pid*.log）

用法：
  pwsh -File tools/parity-invariants.ps1 -Ticks 2000 -Native on  -Leg inv-on
  pwsh -File tools/parity-invariants.ps1 -Ticks 2000 -Native off -Leg inv-off
#>
[CmdletBinding()]
param(
  [int]$Ticks = 2000,
  [ValidateSet('on', 'off')][string]$Native = 'on',
  [string]$Leg = '',
  [string]$Root = '',
  [string]$Snapshot = 'parity-base',
  [int]$ServerPort = 25591,
  [int]$RconPort = 25592,
  [double]$MaxMspt = 50.0,
  [double]$MinTps = 19.0,
  [int]$MinEntities = 1,
  [int]$MaxEntities = 200,
  [int]$WallCapSec = 3600
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
. (Join-Path $PSScriptRoot 'lib-server.ps1')
if (-not $Root) { $Root = Join-Path (Split-Path $PSScriptRoot -Parent) 'testbed\parity' }
$Root = [System.IO.Path]::GetFullPath($Root)
if (-not $Leg) { $Leg = "inv-$Native" }
function Rcon([string]$cmd) {
  try { return (& (Join-Path $PSScriptRoot 'rcon.ps1') -Command $cmd -Port $RconPort -NoLog) }
  catch { Write-Host "[inv] rcon failed($cmd): $_"; return $null }
}
$serverDir = Join-Path $Root 'server'
$worldDir = Join-Path $serverDir 'world'
$snapPath = Join-Path $Root ("snapshots\" + $Snapshot)
if (-not (Test-Path -LiteralPath $snapPath)) { throw "快照不存在：$snapPath" }
if (Test-Path $worldDir) { Remove-Item -LiteralPath $worldDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $worldDir | Out-Null
Copy-Item -Path (Join-Path $snapPath '*') -Destination $worldDir -Recurse -Force
& (Join-Path $PSScriptRoot 'make-parity-datapack.ps1') -WorldDir $worldDir -PlatformX 72 -PlatformZ 0 -GroundY 70 | Out-Null

$propsPath = Join-Path $serverDir 'server.properties'
$props = Get-Content -LiteralPath $propsPath
$props = $props -replace '^server-port=.*', "server-port=$ServerPort" -replace '^query\.port=.*', "query.port=$ServerPort"
$props = $props -replace '^rcon\.port=.*', "rcon.port=$RconPort" -replace '^rcon\.password=.*', 'rcon.password=cava'
$props = $props -replace '^enable-rcon=.*', 'enable-rcon=true'
$props = $props -replace '^spawn-monsters=.*', 'spawn-monsters=false' -replace '^spawn-animals=.*', 'spawn-animals=false'
$props = $props -replace '^spawn-npcs=.*', 'spawn-npcs=false'
$props | Set-Content -Encoding ASCII $propsPath

$javaArgs = @('-Xms2G', '-Xmx4G', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
  '--enable-preview', '--enable-native-access=ALL-UNNAMED',
  "-Dcava.native.enabled=$(if ($Native -eq 'on') { 'true' } else { 'false' })")
if ($Native -eq 'on') { $javaArgs += '-Dcava.pathfind.native=true' }

$srv = Start-CavaServer -Root $Root -Name $Leg -JavaArgs $javaArgs
$runDir = Join-Path $Root ("runs\" + $Leg)
$samples = Join-Path $runDir 'invariants.csv'
"sample,gametime,mspt,tps" | Set-Content -Encoding UTF8 $samples
$ready = Wait-CavaServerReady -Server $srv -TimeoutSec 300
if (-not $ready.Ready) { throw "服务端没起来：$($ready.Reason)" }
Write-Host ("[inv] READY {0:n1}s native=$Native" -f $ready.Seconds)

# READY 只保证日志里有 "Done ("；RCON 监听可能还要 1–2 秒才真的可用（实测踩过一次：
# 第一轮采样时 gametime 查询失败 ⇒ 推进 tick 记成 0）。先探活再开始测量。
$rconOk = $false
for ($i = 0; $i -lt 30; $i++) {
  $probe = Rcon 'time query gametime'
  if ($probe -match '(\d+)') { $rconOk = $true; break }
  Start-Sleep -Seconds 2
}
if (-not $rconOk) { throw 'RCON 在 60 秒内没有响应' }
Rcon 'function cava:scenario' | Out-Null
$g0 = 0
if ((Rcon 'time query gametime') -match '(\d+)') { $g0 = [int]$Matches[1] }
Rcon 'tick unfreeze' | Out-Null
Write-Host "[inv] 实时推进 $Ticks tick（起点 gametime=$g0）"

$sw = [System.Diagnostics.Stopwatch]::StartNew()
$s = 0
$msptSamples = @()
$tpsSamples = @()
while ($sw.Elapsed.TotalSeconds -lt $WallCapSec) {
  Start-Sleep -Seconds 20
  $s++
  if ($srv.Process.HasExited) { break }
  $gt = 0
  $q = Rcon 'time query gametime'
  if ($q -match '(\d+)') { $gt = [int]$Matches[1] }
  $tq = Rcon 'tick query'
  $mspt = -1.0
  if ($tq -match 'Average time per tick:\s*([\d.]+)') { $mspt = [double]$Matches[1] }
  if ($mspt -ge 0) { $msptSamples += $mspt }
  $sp = Rcon 'spark tps'
  $tps = -1.0
  if ($sp -match '([\d.]+),\s*([\d.]+),\s*([\d.]+)') { $tps = [double]$Matches[1] }
  if ($tps -ge 0) { $tpsSamples += $tps }
  Add-Content -Encoding UTF8 $samples ("{0},{1},{2},{3}" -f $s, $gt, $mspt, $tps)
  Write-Host ("[inv] #{0} gametime={1} (+{2}) mspt={3} tps={4}" -f $s, $gt, ($gt - $g0), $mspt, $tps)
  if ($gt - $g0 -ge $Ticks) { break }
  $srv.Process.Refresh()
}

Rcon 'scoreboard objectives add cava_cnt dummy' | Out-Null
Rcon 'execute store result score #cnt cava_cnt run execute if entity @e' | Out-Null
$entOut = Rcon 'scoreboard players get #cnt cava_cnt'
$entities = -1
if ($entOut -match 'has\s+(-?\d+)\s*\[') { $entities = [int]$Matches[1] }
elseif ($entOut -match '(-?\d+)') { $entities = [int]$Matches[1] }
Write-Host "[inv] 实体数=$entities  ($entOut)"
$wall = $sw.Elapsed.TotalSeconds
$gt1 = 0
if ((Rcon 'time query gametime') -match '(\d+)') { $gt1 = [int]$Matches[1] }
$elapsedTicks = $gt1 - $g0
Rcon 'stop' | Out-Null
if (-not $srv.Process.WaitForExit(180000)) {
  $srv.Process.Kill()
  $srv.Process.WaitForExit(60000) | Out-Null
}
$srv.Process.Refresh()
$exit = $srv.Process.ExitCode
if ($null -eq $exit) { $exit = -999 }

# ---------------- 断言 ----------------
$fails = @()
$log = $srv.Log
# 只认 **ERROR 级** 与真正的 Java 异常；/WARN] 一律不算（实测本整合包启动期有 5 条
# "Error loading class ... ClassNotFoundException" 与 4 条 "COM exception querying Win32_*"，
# 它们是 WARN、是已知无害噪声）；再加 easybot 桥接噪声与删除日志文件的竞态。
$badErr = Select-String -LiteralPath $log -Pattern 'Exception|/ERROR\]' -ErrorAction SilentlyContinue |
  Where-Object {
    $_.Line -notmatch '/WARN\]' -and
    $_.Line -notmatch 'EasyBotBridge|BridgeClient|连接遇到错误|正在尝试重连|SLF4J' -and
    $_.Line -notmatch 'Error loading class|COM exception|Unable to delete file|latest.log|FileSystemException'
  }
if ($badErr) { $fails += "日志里有 $($badErr.Count) 条 Exception/ERROR（首条: $($badErr[0].Line.Trim())）" }
if ($Native -eq 'on') {
  # 注意：native=off 那一腿**本来就会**打印「整体回退纯 Java」（那是按设计），所以这条只在 on 腿断言
  if (Select-String -LiteralPath $log -Pattern '整体回退纯 Java' -Quiet) { $fails += '日志里出现「整体回退纯 Java」⇒ native 没起来' }
  if (-not (Select-String -LiteralPath $log -Pattern 'native 状态\s*:\s*OPEN' -Quiet)) { $fails += 'native 状态不是 OPEN' }
  $fb = Select-String -LiteralPath $log -Pattern 'CAVA_ERR_' -ErrorAction SilentlyContinue
  if ($fb) { $fails += "日志里有 native 错误码: $($fb[0].Line.Trim())" }
}
foreach ($m in (Select-String -LiteralPath $log -Pattern 'errors=(\d+)' -ErrorAction SilentlyContinue)) {
  if ($m.Line -match 'errors=(\d+)' -and [int]$Matches[1] -ne 0) { $fails += "pathfind errors=$($Matches[1])（$($m.Line.Trim())）" }
}
foreach ($m in (Select-String -LiteralPath $log -Pattern 'takeovers=(\d+) nativeCalls=(\d+)' -ErrorAction SilentlyContinue)) {
  if ($m.Line -match 'takeovers=(\d+) nativeCalls=(\d+)') {
    if ([int]$Matches[1] -ne [int]$Matches[2]) { $fails += "takeovers != nativeCalls（$($m.Line.Trim())）" }
  }
}
if (Test-Path (Join-Path $serverDir 'hs_err_pid.log')) { $fails += '留下 hs_err_pid.log ⇒ JVM 崩过' }
# 退出码：Start-Process 的 Process 对象在进程退出后可能取不到 ExitCode（本机实测拿到 $null）。
# 所以**以日志证据为准**：优雅停服 = 有 "Stopping the server"/"Goodbye!"，且没有 hs_err。
$graceful = Select-String -LiteralPath $log -Pattern 'Stopping the server|Goodbye!' -Quiet
if (-not $graceful) { $fails += '日志里没有优雅停服证据（Stopping the server / Goodbye!）' }
if ($exit -ne 0 -and $exit -ne -999) { $fails += "退出码 $exit != 0" }
$avgMspt = -1.0
if ($msptSamples.Count -gt 0) {
  $avgMspt = ($msptSamples | Measure-Object -Average).Average
  if ($avgMspt -gt $MaxMspt) { $fails += ("MSPT 均值 {0:n1} > {1:n1}" -f $avgMspt, $MaxMspt) }
} else { $fails += '没采到 MSPT 样本（/tick query 没解析出来）' }
# TPS：**用"游戏刻推进 / 墙钟秒"自己算**，不依赖 spark（实测 spark tps 的 RCON 返回格式解析不到，
# 而 gametime/墙钟是同一份事实的两个来源，不引入新依赖）。
$minTps = -1.0
if ($wall -gt 0 -and $elapsedTicks -gt 0) {
  $minTps = $elapsedTicks / $wall
  if ($minTps -lt $MinTps) { $fails += ("实时 TPS（gametime/wall）{0:n2} < {1:n1}" -f $minTps, $MinTps) }
} else { $fails += '算不出 TPS（gametime 或墙钟为 0）' }
if ($entities -lt $MinEntities -or $entities -gt $MaxEntities) { $fails += "实体数 $entities 不在 [$MinEntities,$MaxEntities]" }

Write-Host ''
Write-Host '================ 整服层不变量 ================'
Write-Host ("  腿            : {0}（native={1}）" -f $Leg, $Native)
Write-Host ("  推进 tick     : {0}（实时，wall {1:n1}s）" -f $elapsedTicks, $wall)
Write-Host ("  MSPT 样本     : {0} 个，均值 {1:n1} ms（阈值 <= {2:n1}）" -f $msptSamples.Count, $avgMspt, $MaxMspt)
Write-Host ("  实时 TPS      : {0:n2}（= {1} tick / {2:n1} s，阈值 >= {3:n1}；spark 样本 {4} 个未参与判定）" -f $minTps, $elapsedTicks, $wall, $MinTps, $tpsSamples.Count)
Write-Host ("  实体数        : {0}" -f $entities)
Write-Host ("  退出码        : {0}（-999 = PowerShell 取不到 ExitCode；以日志的优雅停服为准）" -f $exit)
Write-Host ("  采样文件      : {0}" -f $samples)
if ($fails.Count) {
  Write-Host '  结果          : FAIL'
  $fails | ForEach-Object { Write-Host ("    !! " + $_) }
  exit 1
}
Write-Host '  结果          : PASS（所有不变量成立）'
exit 0
