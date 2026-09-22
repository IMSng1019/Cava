<#
Cava 差分测试（prompts/03）场景层：**跑一条腿**（native on 或 off），落一份黄金轨迹。

私有测试服：testbed/parity（**绝不与别人共用目录/端口**，P1 的教训见 docs/CAVA-p1-inject-notes.md §2.8）。

一条腿做四件事：
  1. 还原世界快照（保证两条腿**从同一个世界状态出发**——不然比的不是 native on/off）
  2. 装脚本化合成场景数据包（tools/make-parity-datapack.ps1）
  3. 起服务端，系统属性带 -Dcava.parity.trace / -Dcava.parity.ticks / -Dcava.native.enabled / -Dcava.pathfind.native
  4. RCON 布场 + /tick sprint N；采样侧采满 N tick 自动停服

用法：
  pwsh -File tools/parity-scenario.ps1 -Prepare -Ticks 40                 # 建 base 快照（含区块预热）
  pwsh -File tools/parity-scenario.ps1 -Leg off-a -Ticks 600 -Native off
  pwsh -File tools/parity-scenario.ps1 -Leg on-a  -Ticks 600 -Native on -PathfindNative on
#>
[CmdletBinding()]
param(
  [string]$Leg = 'leg',
  [int]$Ticks = 600,
  [ValidateSet('on', 'off')][string]$Native = 'off',
  [ValidateSet('on', 'off', 'default')][string]$PathfindNative = 'default',
  [string]$Root = '',
  [string]$Snapshot = 'parity-base',
  [int]$ServerPort = 25591,
  [int]$RconPort = 25592,
  [int]$SetupTimeoutSec = 300,
  [int]$ExitTimeoutSec = 600,
  [switch]$Prepare,
  [switch]$NoRestore,
  [switch]$Detail = $true,
  [switch]$KeepWorld,
  [switch]$PrepareWorld,
  [switch]$SnapshotOnly,
  [int]$ChunkyRadius = 170
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
. (Join-Path $PSScriptRoot 'lib-server.ps1')
$Root = Get-CavaRoot -Root $Root
if (-not (Test-Path -LiteralPath (Join-Path $Root 'server\server.jar'))) {
  throw "私有测试服不存在：$Root（先复制一份 testbed/server，见 docs/CAVA-parity-notes.md）"
}
# rcon.ps1 的默认端口是共享 testbed 的 25576；私有测试服必须**显式传端口**，否则会打到别人的服务器上
function Rcon([string]$cmd) {
  try { return (& (Join-Path $PSScriptRoot 'rcon.ps1') -Command $cmd -Port $RconPort -NoLog) }
  catch { Write-Host "[parity] rcon 失败($cmd): $_"; return $null }
}
$serverDir = Join-Path $Root 'server'
$worldDir = Join-Path $serverDir 'world'
$snapDir = Join-Path $Root 'snapshots'
$snapPath = Join-Path $snapDir $Snapshot
$tracesDir = Join-Path $Root 'traces'
New-Item -ItemType Directory -Force -Path $snapDir, $tracesDir | Out-Null

# ---- 端口与确定性 server.properties（每次跑都重写，保证不会被人改坏）----
$propsPath = Join-Path $serverDir 'server.properties'
$props = Get-Content -LiteralPath $propsPath
$props = $props -replace '^server-port=.*', "server-port=$ServerPort"
$props = $props -replace '^query\.port=.*', "query.port=$ServerPort"
$props = $props -replace '^rcon\.port=.*', "rcon.port=$RconPort"
$props = $props -replace '^rcon\.password=.*', 'rcon.password=cava'
$props = $props -replace '^enable-rcon=.*', 'enable-rcon=true'
$props = $props -replace '^spawn-monsters=.*', 'spawn-monsters=false'
$props = $props -replace '^spawn-animals=.*', 'spawn-animals=false'
$props = $props -replace '^spawn-npcs=.*', 'spawn-npcs=false'
$props = $props -replace '^sync-chunk-writes=.*', 'sync-chunk-writes=false'
$props = $props -replace '^level-seed=.*', 'level-seed=20260922'
$props | Set-Content -Encoding ASCII $propsPath
Write-Host "[parity] root=$Root ports=$ServerPort/$RconPort native=$Native pathfind=$PathfindNative ticks=$Ticks"

# ---- 1. 还原世界 ----
if ($Prepare) {
  Write-Host "[parity] PREPARE：用当前世界做 base 快照（要求它已经是"干净 + 已生成扫描盒内区块"的状态）"
  if (Test-Path $snapPath) { Remove-Item -LiteralPath $snapPath -Recurse -Force }
  New-Item -ItemType Directory -Force -Path $snapPath | Out-Null
  Copy-Item -Path (Join-Path $worldDir '*') -Destination $snapPath -Recurse -Force
  Write-Host "[parity] snapshot -> $snapPath"
  if ($SnapshotOnly) {
    Write-Host "[parity] -SnapshotOnly：快照完成即退出（不装数据包、不起服务端）"
    exit 0
  }
}
if (-not $NoRestore -and -not $PrepareWorld) {
  if (-not (Test-Path -LiteralPath $snapPath)) { throw "快照不存在：$snapPath（先跑一次 -Prepare）" }
  if (Test-Path $worldDir) { Remove-Item -LiteralPath $worldDir -Recurse -Force }
  New-Item -ItemType Directory -Force -Path $worldDir | Out-Null
  Copy-Item -Path (Join-Path $snapPath '*') -Destination $worldDir -Recurse -Force
  Write-Host "[parity] world restored from $snapPath"
}

# ---- 1b. PREPARE-WORLD：用 Chunky 预生成扫描盒（**不装冻结数据包**，否则 save-off 会丢掉生成结果）----
if ($PrepareWorld) {
  Write-Host "[parity] PREPARE-WORLD：先用 Chunky 预生成 ±$ChunkyRadius 方块，再 save-all flush 并停服"
  $warmArgs = @('-Xms2G', '-Xmx4G', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
    '--enable-preview', '--enable-native-access=ALL-UNNAMED', '-Dcava.native.enabled=false')
  $warm = Start-CavaServer -Root $Root -Name 'warmup' -JavaArgs $warmArgs
  $rd = Wait-CavaServerReady -Server $warm -TimeoutSec $SetupTimeoutSec
  if (-not $rd.Ready) { throw "预热服务端没起来：$($rd.Reason)" }
  Rcon 'gamerule doMobSpawning false' | Out-Null
  Write-Host "[parity] chunky shape: $(Rcon 'chunky shape square')"
  Write-Host "[parity] chunky center: $(Rcon 'chunky center 0 0')"
  Write-Host "[parity] chunky radius: $(Rcon "chunky radius $ChunkyRadius")"
  Write-Host "[parity] chunky start: $(Rcon 'chunky start')"
  $sw = [System.Diagnostics.Stopwatch]::StartNew()
  while ($sw.Elapsed.TotalSeconds -lt 3000) {
    Start-Sleep -Seconds 10
    $p = Rcon 'chunky progress'
    if ($p -match 'Task finished|Complete|No tasks running|100(.0+)?%') { Write-Host "[parity] chunky: $p"; break }
    Write-Host ("[parity] chunky {0:n0}s: {1}" -f $sw.Elapsed.TotalSeconds, ($p -join ' '))
  }
  Write-Host "[parity] save-all: $(Rcon 'save-all flush')"
  Start-Sleep -Seconds 3
  Rcon 'stop' | Out-Null
  $warm.Process.WaitForExit(120000) | Out-Null
  Write-Host "[parity] 预热完成（world 已保存），exit=$($warm.Process.ExitCode)"
}

# ---- 2. 场景数据包（幂等）----
if (-not $PrepareWorld) {
  & (Join-Path $PSScriptRoot 'make-parity-datapack.ps1') -WorldDir $worldDir -PlatformX 72 -PlatformZ 0 -GroundY 70 | Out-Null
}

# ---- 3. 起服务端 ----
$traceDir = Join-Path $tracesDir $Leg
if (Test-Path $traceDir) { Remove-Item -LiteralPath $traceDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $traceDir | Out-Null

$javaArgs = @('-Xms2G', '-Xmx4G', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
  '--enable-preview', '--enable-native-access=ALL-UNNAMED',
  "-Dcava.native.enabled=$(if ($Native -eq 'on') { 'true' } else { 'false' })",
  '-Dcava.parity.dims=overworld',
  "-Dcava.parity.trace=$traceDir",
  "-Dcava.parity.label=$Leg",
  "-Dcava.parity.ticks=$Ticks",
  # 半径 4（±64 方块）而不是契约定例的 8：世界哈希是 O(盒内方块数)/tick，
  # 半径 8 在 3 维覆盖下约 9.8M 方块/tick（实测 ~200ms/tick），6000 tick 要 20 分钟。
  # 半径 4 + 排除出生点 3 之后是 32 个区块（约 1.3M 方块/tick），场景平台在 chunk(4,-1)..(5,0) 内。
  # 这个取值写进 trace 头（radius 字段），可比性不受影响。
  '-Dcava.parity.world.radius=4',
  '-Dcava.parity.exclude.spawn.radius=3',
  '-Dcava.parity.entities=off')
if ($PathfindNative -ne 'default') {
  $javaArgs += "-Dcava.pathfind.native=$(if ($PathfindNative -eq 'on') { 'true' } else { 'false' })"
}
if ($Detail) { $javaArgs += '-Dcava.parity.detail=true' }

if ($PrepareWorld) {
  Write-Host "[parity] PREPARE-WORLD 完成，跳过采样（接下来用 -Prepare 建快照）"
  exit 0
}

$srv = Start-CavaServer -Root $Root -Name $Leg -JavaArgs $javaArgs
Write-Host "[parity] pid=$($srv.Process.Id) log=$($srv.Log)"
$ready = Wait-CavaServerReady -Server $srv -TimeoutSec $SetupTimeoutSec
if (-not $ready.Ready) { throw "服务端没起来：$($ready.Reason)" }
Write-Host ("[parity] READY after {0:n1}s" -f $ready.Seconds)

# ---- 4. 布场 + 精确推进 ----
$setup = Rcon 'function cava:scenario'
Write-Host "[parity] > function cava:scenario -> $setup"
$sp = Rcon "tick sprint $Ticks"
Write-Host "[parity] > tick sprint $Ticks"
Write-Host "[parity] 等采样侧采满 $Ticks tick 并停服…"
if (-not $srv.Process.WaitForExit($ExitTimeoutSec * 1000)) {
  Write-Host '[parity] !! 超时未退出，强制杀'
  $srv.Process.Kill(); $srv.Process.WaitForExit(30000)
}
$srv.Process.Refresh()
Write-Host "[parity] exit=$($srv.Process.ExitCode)"

# ---- 5. 回执校验（不做回执校验的采样就是无效采样：P1 的教训）----
$trace = Join-Path $traceDir "trace-$Leg.ndjson"
if (-not (Test-Path -LiteralPath $trace)) { throw "没有轨迹文件：$trace" }
$lines = (Get-Content -LiteralPath $trace | Measure-Object -Line).Lines
$tickLines = (Select-String -LiteralPath $trace -Pattern '"t":"k"' | Measure-Object).Count
$hdr = Get-Content -LiteralPath $trace -TotalCount 1
Write-Host "[parity] trace=$trace  行=$lines  tick行=$tickLines"
Write-Host "[parity] 头行: $hdr"
$logOk = (Select-String -LiteralPath $srv.Log -Pattern '轨迹落盘' | Measure-Object).Count
$bad = @()
if ($tickLines -lt $Ticks) { $bad += "采到的 tick 行 $tickLines < 请求的 $Ticks（采样被截断）" }
if ($logOk -lt 1) { $bad += '日志里没有「轨迹落盘」⇒ 采集侧没有正常收尾' }
# 已知无害噪声（实测）：easybot-fabric 的 Bridge 客户端在无外部桥时每 5 秒打一条
# "连接遇到错误: Connection refused"（Jetty/BridgeClient 两个 logger）；它与被测路径无关，
# 但会被"无 ERROR"断言误伤 —— 必须显式排除，并在报告里写明排除了什么。
$badErr = Select-String -LiteralPath $srv.Log -Pattern 'Exception|/ERROR\]' -ErrorAction SilentlyContinue |
  Where-Object {
    $_.Line -notmatch '/WARN\]' -and
    $_.Line -notmatch 'EasyBotBridge|BridgeClient|连接遇到错误|正在尝试重连|SLF4J' -and
    $_.Line -notmatch 'Error loading class|COM exception' -and
    $_.Line -notmatch 'Unable to delete file|latest.log|FileSystemException'
  }
if ($badErr) {
  $bad += ("日志里有 {0} 条 Exception/ERROR（首条: {1}）" -f $badErr.Count, $badErr[0].Line.Trim())
}
if ($bad.Count) {
  $bad | ForEach-Object { Write-Host "[parity] !! $_" }
  throw "[parity] LEG $Leg INVALID"
}
Write-Host "[parity] LEG $Leg OK（$tickLines tick）"
