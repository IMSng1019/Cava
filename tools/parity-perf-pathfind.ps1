<#
Cava P1-PERF：**大搜索空间**下 native on / off 的寻路性能与一致性（prompts/04 被推迟的那一项）。

为什么需要它：
  现成的 /cava pathfind bench 用的是 PathfindScenario（起点终点隔 6 格、NAV_RANGE=8、followRange=16），
  实测只展开 5 个节点 —— 在那个尺度上量到的是跨界固定成本，没有代表性。
  本脚本驱动 /cava pathfind perf 的**大搜索空间预设**（长路径 / 绕行 / 41x41 / 63x63 迷宫），
  同时钉住产物哈希、把每次测量的回执原文落盘，并给出可判红的一致性比对。

私有资源（绝不与别的流共用）：
  目录 testbed\perf-pathfind    端口 25660（游戏）/ 25661（RCON）

用法：
  pwsh -File tools/parity-perf-pathfind.ps1 -Leg off -Native off
  pwsh -File tools/parity-perf-pathfind.ps1 -Leg on  -Native on
  pwsh -File tools/parity-perf-pathfind.ps1 -Compare -OffTag off -OnTag on
  pwsh -File tools/parity-perf-pathfind.ps1 -Leg on-ctl -Native on -TargetOffset 1 -Presets long128   # 证伪对照
  pwsh -File tools/parity-perf-pathfind.ps1 -Probe -Leg probe -Presets long128                        # 生物存活探测
#>
[CmdletBinding()]
param(
  [string]$Leg = '',
  [ValidateSet('on', 'off')][string]$Native = 'off',
  [int]$TargetOffset = 0,
  [string]$Presets = 'long128,detour128,slalom,maze41,maze63,long128hash',
  [ValidateSet('reuse', 'repush', 'both')][string]$Modes = 'both',
  [string]$Root = '',
  [int]$ServerPort = 25660,
  [int]$RconPort = 25661,
  [string]$Snapshot = 'parity-base',
  [string]$SnapshotRoot = '',
  [switch]$NoRestore,
  [switch]$Compare,
  [string]$OffTag = 'off',
  [string]$OnTag = 'on',
  [switch]$Probe,
  [int]$ProbeWaitSec = 35,
  [int]$ForceN = 0,
  [switch]$Diag,
  [switch]$AiLoad,
  [int]$AiZombies = 40,
  [int]$AiTicks = 1200,
  [int]$OldBench = 0,
  [switch]$SkipBuildCheck
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
. (Join-Path $PSScriptRoot 'lib-server.ps1')
$repo = Split-Path $PSScriptRoot -Parent
if (-not $Root) { $Root = Join-Path $repo 'testbed\perf-pathfind' }
if (-not $SnapshotRoot) { $SnapshotRoot = Join-Path $repo 'testbed\parity\snapshots' }
$serverDir = Join-Path $Root 'server'
$worldDir = Join-Path $serverDir 'world'
$resultsDir = Join-Path $Root 'results'
New-Item -ItemType Directory -Force -Path $resultsDir | Out-Null

# 每个预设有自己的迭代次数：小的跑多点（噪声小），迷宫跑少点（单次贵）
$PresetN = [ordered]@{ long128 = 20000; long128hash = 2000; detour128 = 8000; slalom = 8000; maze41 = 6000; maze63 = 3000 }
$RepushN = [ordered]@{ long128 = 8000; long128hash = 1000; detour128 = 4000; slalom = 4000; maze41 = 3000; maze63 = 1500 }

function Rcon([string]$cmd, [int]$TimeoutMs = 900000) {
  try {
    return (& (Join-Path $PSScriptRoot 'rcon.ps1') -Command $cmd -Port $RconPort -NoLog -TimeoutMs $TimeoutMs -IdleMs 1500)
  } catch { Write-Host "[perf] rcon failed($cmd): $_"; return $null }
}

function Get-Artifacts {
  $dll = Join-Path $repo 'natives\windows-x64\cava.dll'
  $jar = Join-Path $repo 'build\libs\cava-0.1.0.jar'
  $dep = Join-Path $serverDir 'mods\cava-0.1.0.jar'
  $o = [ordered]@{}
  foreach ($pair in @(@('dll', $dll), @('jar', $jar), @('deployed', $dep))) {
    $name = $pair[0]; $path = $pair[1]
    if (Test-Path -LiteralPath $path) {
      $f = Get-Item -LiteralPath $path
      $o[$name] = "$($f.Length) bytes sha256=$((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash)"
    } else { $o[$name] = '(缺失)' }
  }
  [pscustomobject]$o
}

function Assert-PortsFree {
  foreach ($p in @($ServerPort, $RconPort)) {
    $busy = Get-NetTCPConnection -State Listen -LocalPort $p -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($busy) { throw "端口 $p 已被 pid $($busy.OwningProcess) 占用 —— 先清掉再跑（绝不与别的流抢端口）" }
  }
}

function Stop-LegServer($srv) {
  try { Rcon 'stop' 60000 | Out-Null } catch { }
  if (-not $srv.Process.WaitForExit(180000)) {
    Write-Host "[perf] 优雅停服超时，强杀 pid=$($srv.Process.Id)"
    $srv.Process.Kill()
    $srv.Process.WaitForExit(30000)
  }
  # **杀 job 不会杀 java**：必须显式确认进程真的没了，并再确认端口已释放
  Start-Sleep -Seconds 3
  $alive = Get-Process -Id $srv.Process.Id -ErrorAction SilentlyContinue
  if ($alive) { Write-Host "[perf] pid $($srv.Process.Id) 仍在，强杀"; Stop-Process -Id $srv.Process.Id -Force; Start-Sleep -Seconds 3 }
  foreach ($p in @($ServerPort, $RconPort)) {
    $busy = Get-NetTCPConnection -State Listen -LocalPort $p -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($busy) { Write-Host "[perf] !! 端口 $p 仍被 pid $($busy.OwningProcess) 占用" } else { Write-Host "[perf] 端口 $p 已释放" }
  }
}

function Get-Recipient([string]$file) {
  $map = @{}
  foreach ($line in Get-Content -LiteralPath $file) {
    if ($line -match 'PERF id=(\d+) preset=(\S+) mode=(\S+)') {
      $map["$($Matches[2])/$($Matches[3])"] = [ordered]@{ perf = $line; detail = $null }
    } elseif ($line -match 'PERFDETAIL id=(\d+) preset=(\S+) mode=(\S+)') {
      $k = "$($Matches[2])/$($Matches[3])"
      if ($map.ContainsKey($k)) { $map[$k].detail = $line }
    }
  }
  $map
}

function Field([string]$line, [string]$key) {
  if (-not $line) { return '(缺)' }
  $m = [regex]::Match($line, "(?:^|\s)$([regex]::Escape($key))=(\S+)")
  if ($m.Success) { return $m.Groups[1].Value }
  return '(缺)'
}

if ($Compare) {
  $offFile = Join-Path $resultsDir "$OffTag.txt"
  $onFile = Join-Path $resultsDir "$OnTag.txt"
  foreach ($f in @($offFile, $onFile)) { if (-not (Test-Path -LiteralPath $f)) { Write-Host "[perf] 缺少结果文件 $f"; exit 3 } }
  $off = Get-Recipient $offFile
  $on = Get-Recipient $onFile
  Write-Host "================ P1-PERF 一致性比对 ================"
  Write-Host "  off = $offFile"
  Write-Host "  on  = $onFile"
  Write-Host ''
  Write-Host "--- 产物哈希 ---"
  foreach ($f in @($offFile, $onFile)) {
    $hdr = Select-String -LiteralPath $f -Pattern '^#(dll|jar|deployed|dllStable)=' | ForEach-Object { $_.Line }
    Write-Host ("  {0}: {1}" -f (Split-Path $f -Leaf), ($hdr -join ' | '))
  }
  $keys = @($off.Keys + $on.Keys | Sort-Object -Unique)
  $red = @()
  $rows = @()
  foreach ($k in $keys) {
    $o = $off[$k]; $n = $on[$k]
    if (-not $o -or -not $n) { $red += "$k 只在一侧"; continue }
    $perfFields = @('ok', 'n', 'nodes_avg', 'nodes_p50', 'nodes_min', 'nodes_max', 'nullPaths', 'endDistinct', 'end', 'manh', 'reachesTargetFlag')
    $detFields = @('sig_coords', 'sig_types', 'sigDistinct', 'analyzeLen', 'startNode', 'endNode', 'collisionNodes', 'firstBadNode')
    $diff = @()
    foreach ($f in $perfFields) {
      $a = Field $o.perf $f; $b = Field $n.perf $f
      if ($a -ne $b) { $diff += "$f(off=$a on=$b)" }
    }
    foreach ($f in $detFields) {
      $a = Field $o.detail $f; $b = Field $n.detail $f
      if ($a -ne $b) { $diff += "$f(off=$a on=$b)" }
    }
    $tk = Field $n.detail 'takeovers'; $exp = Field $n.detail 'expectDelta'
    if ($tk -ne '(缺)' -and $exp -ne '(缺)' -and $tk -ne $exp) { $diff += "on腿未全部接管(takeovers=$tk expect=$exp)" }
    $rows += [pscustomobject]@{
      key = $k; ns_off = (Field $o.perf 'ns_avg'); ns_on = (Field $n.perf 'ns_avg')
      nodes = (Field $o.perf 'nodes_avg'); diff = ($diff -join '; ')
    }
    if ($diff.Count) { $red += "$k : $($diff -join ' | ')" }
  }
  Write-Host ''
  Write-Host "--- 每次调用耗时（ns/次）与节点数 ---"
  Write-Host ("  {0,-20} {1,14} {2,14} {3,10} {4}" -f 'preset/mode', 'off ns', 'on ns', 'off nodes', 'off/on')
  foreach ($r in $rows) {
    $ratio = ''
    if ($r.ns_off -match '^[\d.]+$' -and $r.ns_on -match '^[\d.]+$' -and [double]$r.ns_on -gt 0) {
      $ratio = ('{0:n3}x' -f ([double]$r.ns_off / [double]$r.ns_on))
    }
    Write-Host ("  {0,-20} {1,14} {2,14} {3,10} {4}" -f $r.key, $r.ns_off, $r.ns_on, $r.nodes, $ratio)
  }
  Write-Host ''
  if ($red.Count) {
    Write-Host "判定：**DIVERGENT**（$($red.Count) 处）"
    $red | ForEach-Object { Write-Host "  !! $_" }
    exit 2
  }
  Write-Host "判定：**CONSISTENT**（全部 preset/mode 的 ok/nodes/终点/坐标哈希/type 哈希/穿墙计数 逐字段相同）"
  exit 0
}

if (-not $Leg) { throw '需要 -Leg <名字>（或用 -Compare）' }

$want = $Presets.Split(',') | ForEach-Object { $_.Trim() } | Where-Object { $_ }
foreach ($w in $want) { if (-not $PresetN.Contains($w)) { throw "未知 preset：$w（可选 $($PresetN.Keys -join ',')）" } }

Assert-PortsFree
$art0 = Get-Artifacts
Write-Host "[perf] LEG=$Leg native=$Native targetOffset=$TargetOffset root=$Root ports=$ServerPort/$RconPort"
Write-Host "[perf] dll  = $($art0.dll)"
Write-Host "[perf] jar  = $($art0.jar)"
Write-Host "[perf] deployed(mods) = $($art0.deployed)"

if (-not $SkipBuildCheck) {
  if ($art0.jar -eq '(缺失)') { throw 'build\libs\cava-0.1.0.jar 不存在：先跑 gradlew build' }
  if ($art0.deployed -eq '(缺失)' -or ($art0.deployed -ne $art0.jar)) {
    Write-Host "[perf] 把刚构建的 jar 部署进私有测试服 mods（-LiteralPath）"
    Copy-Item -LiteralPath (Join-Path $repo 'build\libs\cava-0.1.0.jar') -Destination (Join-Path $serverDir 'mods\cava-0.1.0.jar') -Force
  }
  $art0 = Get-Artifacts
}

if (-not $NoRestore) {
  $snapPath = Join-Path $SnapshotRoot $Snapshot
  if (-not (Test-Path -LiteralPath $snapPath)) { throw "快照不存在：$snapPath" }
  if (Test-Path -LiteralPath $worldDir) { Remove-Item -LiteralPath $worldDir -Recurse -Force }
  New-Item -ItemType Directory -Force -Path $worldDir | Out-Null
  Copy-Item -Path (Join-Path $snapPath '*') -Destination $worldDir -Recurse -Force
  Write-Host "[perf] 世界已从 $snapPath 恢复"
}

$propsPath = Join-Path $serverDir 'server.properties'
$props = Get-Content -LiteralPath $propsPath
$props = $props -replace '^server-port=.*', "server-port=$ServerPort"
$props = $props -replace '^query\.port=.*', "query.port=$ServerPort"
$props = $props -replace '^rcon\.port=.*', "rcon.port=$RconPort"
$props = $props -replace '^rcon\.password=.*', 'rcon.password=cava'
$props = $props -replace '^enable-rcon=.*', 'enable-rcon=true'
$props = $props -replace '^max-tick-time=.*', 'max-tick-time=-1'
$props = $props -replace '^spawn-monsters=.*', 'spawn-monsters=false'
$props = $props -replace '^spawn-animals=.*', 'spawn-animals=false'
$props = $props -replace '^spawn-npcs=.*', 'spawn-npcs=false'
$props | Set-Content -Encoding ASCII $propsPath

$javaArgs = @('-Xms2G', '-Xmx4G', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
  '--enable-preview', '--enable-native-access=ALL-UNNAMED',
  "-Dcava.native.enabled=$(if ($Native -eq 'on') { 'true' } else { 'false' })")
if ($Native -eq 'on') { $javaArgs += '-Dcava.pathfind.native=true' }
if ($TargetOffset -ne 0) { $javaArgs += "-Dcava.pathfind.perf.targetOffset=$TargetOffset" }
$javaArgs += '-Dcava.pathfind.probe=false'

$srv = Start-CavaServer -Root $Root -Name $Leg -JavaArgs $javaArgs
Write-Host "[perf] pid=$($srv.Process.Id) log=$($srv.Log)"
$ready = Wait-CavaServerReady -Server $srv -TimeoutSec 300
if (-not $ready.Ready) { Stop-LegServer $srv; throw "服务端没起来：$($ready.Reason)" }
Write-Host "[perf] READY: $($ready.Line)"
Start-Sleep -Seconds 2

Rcon 'tick freeze' | Out-Null
Rcon 'save-off' | Out-Null
foreach ($g in @('doMobSpawning false', 'doFireTick false', 'doWeatherCycle false', 'doDaylightCycle false', 'sendCommandFeedback false')) {
  Rcon "gamerule $g" | Out-Null
}
# /forceload 单条命令最多 256 个区块（实测：'Too many chunks in the specified area (maximum 256,
# specified 484)' —— 而且整条命令被拒 ⇒ 一个区块都没加载）。分成 4 个 11x11=121 区块的象限。
$flTotal = 0
foreach ($q in @(@(-168, -168, 0, 0), @(1, -168, 168, 0), @(-168, 1, 0, 168), @(1, 1, 168, 168))) {
  $fl = Rcon ("forceload add {0} {1} {2} {3}" -f $q[0], $q[1], $q[2], $q[3])
  Write-Host "[perf] forceload $($q -join ','): $fl"
  if ("$fl" -match '(\d+)') { $flTotal += [int]$Matches[1] }
}
Write-Host "[perf] forceload 合计 $flTotal 区块"
# 站点相关的区块必须**真的加载**：原版 findPathToAny 只通过 ChunkCache 看世界，
# 未加载区块在 ChunkCache 里是 EmptyChunk（空气）⇒ 看不到地板就必然"无路"。
$flCheck = Rcon 'forceload query 32 -32'
Write-Host "[perf] forceload query(32,-32): $flCheck"

if ($Probe) {
  # 先把场景铺好（猪要有地板站），再**解冻**跑真实 tick —— 冻结世界里实体不 tick，
  # 冻结状态下的存活数据说明不了"这整合包会不会静默移除生物"。
  Rcon ("cava pathfind site " + $want[0]) | Out-Null
  Rcon 'kill @e[type=minecraft:pig,tag=cava_probe]' | Out-Null
  Rcon 'kill @e[type=minecraft:pig,tag=cava_probe_ai]' | Out-Null
  Rcon 'tick unfreeze' | Out-Null
  Rcon 'summon minecraft:pig 100 71 -32 {NoAI:1b,PersistenceRequired:1b,Silent:1b,Tags:["cava_probe"]}' | Out-Null
  Rcon 'summon minecraft:pig 120 71 -32 {PersistenceRequired:1b,Silent:1b,Tags:["cava_probe_ai"]}' | Out-Null
  $t0 = Rcon 'time query gametime'
  Rcon 'say [probe] summoned 2 pigs (NoAI + AI) at t0' | Out-Null
  Write-Host "[perf] probe t0: $t0"
  Start-Sleep -Seconds $ProbeWaitSec
  $t1 = Rcon 'time query gametime'
  Write-Host "[perf] probe t1: $t1"
  Rcon 'execute if entity @e[type=minecraft:pig,tag=cava_probe] run say [probe] PROBE_NOAI_ALIVE' | Out-Null
  Rcon 'execute unless entity @e[type=minecraft:pig,tag=cava_probe] run say [probe] PROBE_NOAI_GONE' | Out-Null
  Rcon 'execute if entity @e[type=minecraft:pig,tag=cava_probe_ai] run say [probe] PROBE_AI_ALIVE' | Out-Null
  Rcon 'execute unless entity @e[type=minecraft:pig,tag=cava_probe_ai] run say [probe] PROBE_AI_GONE' | Out-Null
  Start-Sleep -Seconds 1
  Write-Host ("[perf] --- 生物存活探测（等待 {0} 秒）---" -f $ProbeWaitSec)
  Select-String -LiteralPath $srv.Log -Pattern 'PROBE_' | ForEach-Object { Write-Host "  $($_.Line.Trim())" }
  Rcon 'execute if entity @e[type=minecraft:pig,tag=cava_probe] run say [probe] aliveNoAI=1' | Out-Null
  Rcon 'execute if entity @e[type=minecraft:pig,tag=cava_probe_ai] run say [probe] aliveAI=1' | Out-Null
  Rcon 'kill @e[type=minecraft:pig,tag=cava_probe]' | Out-Null
  Rcon 'kill @e[type=minecraft:pig,tag=cava_probe_ai]' | Out-Null
  Rcon 'tick freeze' | Out-Null
}

# **回执只能从日志里等**：server.properties 的 sendCommandFeedback=false 会让 RCON 立刻回一个空包，
# 于是"命令回执"变成假信号（本流实测：n=8000 的 bench 还在跑，脚本已经发了下一条命令，
# 后续命令全被 busy 拒掉）。所以：发命令 → 轮询日志等真正的回执行。
function Wait-LogCount([string]$pattern, [int]$afterCount, [int]$timeoutSec) {
  $sw = [System.Diagnostics.Stopwatch]::StartNew()
  while ($sw.Elapsed.TotalSeconds -lt $timeoutSec) {
    $c = (Select-String -LiteralPath $srv.Log -Pattern $pattern -ErrorAction SilentlyContinue | Measure-Object).Count
    if ($c -gt $afterCount) { return $true }
    Start-Sleep -Milliseconds 500
  }
  return $false
}

# --- 真实 AI 负载：这一整合包里"寻路到底多久发生一次"（per-tick 换算的唯一实测来源）---
if ($AiLoad) {
  Rcon ("cava pathfind site " + $want[0]) | Out-Null
  Start-Sleep -Seconds 3
  Rcon 'kill @e[type=minecraft:zombie]' | Out-Null
  Rcon 'kill @e[type=minecraft:villager]' | Out-Null
  Rcon 'tick unfreeze' | Out-Null
  Rcon 'summon minecraft:villager 70 71 -32 {PersistenceRequired:1b,Silent:1b,Tags:["cava_ai"]}' | Out-Null
  for ($i = 0; $i -lt $AiZombies; $i++) {
    $zx = 40 + (($i % 10) * 2)
    $zz = -44 + ([int]($i / 10) * 3)
    Rcon ("summon minecraft:zombie {0} 71 {1} {{PersistenceRequired:1b,Silent:1b,Tags:[""cava_ai""]}}" -f $zx, $zz) | Out-Null
  }
  $stats0 = Rcon 'cava pathfind stats'
  $zBefore = Rcon 'execute if entity @e[type=minecraft:zombie] run say [perf] zombies_present'
  Write-Host "[perf] ai-load: $AiZombies 只僵尸 + 1 村民；sprint $AiTicks tick"
  Rcon 'tick freeze' | Out-Null
  Rcon ("tick sprint " + $AiTicks) | Out-Null
  $swAi = [System.Diagnostics.Stopwatch]::StartNew()
  while ($swAi.Elapsed.TotalSeconds -lt 900) {
    Start-Sleep -Seconds 3
    $q = Rcon 'tick query'
    if ("$q" -notmatch 'sprint') { break }
  }
  Start-Sleep -Seconds 2
  $stats1 = Rcon 'cava pathfind stats'
  Rcon 'say [perf] ai-load sprint done' | Out-Null
  $zAfter = Rcon 'execute if entity @e[type=minecraft:zombie] run say [perf] zombies_after_sprint'
  function CanaryOf([string]$s) { if ("$s" -match 'canary=(\d+)') { return [int]$Matches[1] } return -1 }
  $c0 = CanaryOf $stats0
  $c1 = CanaryOf $stats1
  Write-Host "[perf] ai-load stats0: $stats0"
  Write-Host "[perf] ai-load stats1: $stats1"
  Write-Host ("[perf] ai-load 寻路调用 = {0} 次 / {1} tick ⇒ {2:n4} 次/tick（canary {3} -> {4}）" -f ($c1 - $c0), $AiTicks, (($c1 - $c0) / [double]$AiTicks), $c0, $c1)
  Write-Host "[perf] ai-load zombies before: $zBefore"
  Write-Host "[perf] ai-load zombies after : $zAfter"
  if ($OldBench -gt 0) {
    $b0 = (Select-String -LiteralPath $srv.Log -Pattern 'BENCH id=' -ErrorAction SilentlyContinue | Measure-Object).Count
    Rcon ("cava pathfind bench " + $OldBench) | Out-Null
    [void](Wait-LogCount 'BENCH id=' $b0 600)
    Select-String -LiteralPath $srv.Log -Pattern 'BENCH id=' | ForEach-Object { Write-Host "  $($_.Line.Trim())" }
  }
  Rcon 'kill @e[type=minecraft:zombie]' | Out-Null
  Rcon 'kill @e[type=minecraft:villager]' | Out-Null
  Rcon 'tick freeze' | Out-Null
  Start-Sleep -Seconds 2
}

$modeList = if ($Modes -eq 'both') { @('reuse', 'repush') } else { @($Modes) }
foreach ($preset in $want) {
  # 站点协议（本整合包实测：刚写好的方块会被磁盘副本盖回去 ⇒ 写→存盘→再写→逐格核对）：
  #   site -> save-all flush -> site，要求第二次的 verifyAfterRebuild=PASS
  $sitePat = "SITE .*preset=$preset "
  $stable = $false
  for ($attempt = 1; $attempt -le 4 -and -not $stable; $attempt++) {
    $beforeSite = (Select-String -LiteralPath $srv.Log -Pattern $sitePat -ErrorAction SilentlyContinue | Measure-Object).Count
    Rcon "cava pathfind site $preset" | Out-Null
    [void](Wait-LogCount $sitePat $beforeSite 600)
    Rcon 'save-all flush' | Out-Null
    Start-Sleep -Seconds 3
    $beforeSite2 = (Select-String -LiteralPath $srv.Log -Pattern $sitePat -ErrorAction SilentlyContinue | Measure-Object).Count
    Rcon "cava pathfind site $preset" | Out-Null
    [void](Wait-LogCount $sitePat $beforeSite2 600)
    $last = (Select-String -LiteralPath $srv.Log -Pattern $sitePat | Select-Object -Last 1).Line
    $stable = ($last -match 'verifyAfterRebuild=PASS')
    Write-Host ("[perf] site {0} 第{1}次: {2}" -f $preset, $attempt, $(if ($stable) { 'STABLE' } else { '仍不稳定' }))
    if (-not $stable) { Start-Sleep -Seconds 5 }
  }
  if (-not $stable) { Write-Host "[perf] !! $preset 站点不稳定 ⇒ 这一腿的该 preset 无效" }
  if ($Diag) {
    $diagPat = "DIAG preset=$preset "
    $bd = (Select-String -LiteralPath $srv.Log -Pattern $diagPat -ErrorAction SilentlyContinue | Measure-Object).Count
    Rcon "cava pathfind diag $preset" | Out-Null
    [void](Wait-LogCount $diagPat $bd 300)
    Select-String -LiteralPath $srv.Log -Pattern $diagPat | Select-Object -Last 1 | ForEach-Object { Write-Host "  $($_.Line.Trim())" }
    continue
  }
  foreach ($mode in $modeList) {
    $n = if ($mode -eq 'repush') { $RepushN[$preset] } else { $PresetN[$preset] }
    if ($ForceN -gt 0) { $n = $ForceN }
    $pat = "PERF id=.* preset=$preset mode=$mode "
    $before = (Select-String -LiteralPath $srv.Log -Pattern $pat -ErrorAction SilentlyContinue | Measure-Object).Count
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    Rcon "cava pathfind perf $preset $n $mode" | Out-Null
    $okRecv = Wait-LogCount $pat $before 900
    $sw.Stop()
    Write-Host ("[perf] {0}/{1} n={2} 用时={3:n1}s 回执={4}" -f $preset, $mode, $n, $sw.Elapsed.TotalSeconds, $(if ($okRecv) { 'OK' } else { 'MISSING（无效采样）' }))
  }
}

Start-Sleep -Seconds 1
$lines = Select-String -LiteralPath $srv.Log -Pattern 'PERF id=|PERFDETAIL id=|SITE |DIAG |EXPLORE |BENCH id=|接管条件|pathfind\] 原生接管|PROBE_|ai-load' -ErrorAction SilentlyContinue |
  ForEach-Object { $_.Line.Trim() }
Stop-LegServer $srv

$art1 = Get-Artifacts
$head = @()
$head += "#tag=$Leg"
$head += "#native=$Native"
$head += "#targetOffset=$TargetOffset"
$head += "#presets=$($want -join ',')"
$head += "#modes=$($modeList -join ',')"
$head += "#ports=$ServerPort/$RconPort"
$head += "#snapshot=$Snapshot"
$head += "#serverLog=$($srv.Log)"
$head += "#dll=$($art0.dll)"
$head += "#jar=$($art0.jar)"
$head += "#deployed=$($art0.deployed)"
$head += "#dllAfter=$($art1.dll)"
$head += "#jarAfter=$($art1.jar)"
$head += "#dllStable=$($art0.dll -eq $art1.dll)"
$head += "#javaArgs=$($srv.CommandLine)"
$outFile = Join-Path $resultsDir "$Leg.txt"
($head + $lines) | Set-Content -Encoding UTF8 $outFile
Write-Host "[perf] 结果写入 $outFile（$($lines.Count) 行回执）"
if (-not ($art0.dll -eq $art1.dll)) { Write-Host "[perf] !! 测量期间 DLL 哈希变了 ⇒ 这一腿作废，必须重跑" }
$lines | ForEach-Object { Write-Host "  $_" }
