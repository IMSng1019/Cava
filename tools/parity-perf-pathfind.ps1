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
  # P1-NET：测量前的预热 sprint 跳数（JIT 暖机；**不计入**测量窗口）
  [int]$AiWarmup = 400,
  # P1-NET：逐距离换手点扫描（分流阈值的数据出处）；空 = 不跑
  [string]$Sweep = '',
  [int]$SweepN = 1500,
  [ValidateSet('reuse', 'repush')][string]$SweepMode = 'reuse',
  # P1-NET：按规模分流的阈值（方块）。-1 = 用 jar 里的默认值；0 = **关掉分流**（对照）；
  # 很大的值 = "把阈值改坏"的可证伪对照（什么都不接管）。
  [long]$GateMin = -1,
  [int]$OldBench = 0,
  [switch]$SkipBuildCheck,
  # 额外的 JVM 系统属性（**可证伪对照用**）。两种写法等价：
  #   -JavaProp '-Dcava.pathfind.window.guard=false'                       （单个）
  #   -JavaProps '-Dcava.mirror.reuse.crosstick=true,-Dcava.mirror.invalidation=false'  （多个，逗号分隔）
  # 为什么要有逗号版：pwsh -File 传 [string[]] 时，以 '-' 开头的元素会被当成参数名，
  # 实测报"找不到与参数名称 'Dcava...' 匹配的参数"。
  [string[]]$JavaProp = @(),
  [string]$JavaProps = '',
  # 跑缺陷 1 的反面证据：cava pathfind invalidate <preset>（改窗口内方块 ⇒ 下一次求解必须看到）
  [switch]$Invalidate,
  # P1-CROSS：**跨 tick 复用**的失效实测（arm → tick sprint N → check）。
  # 与 -Invalidate 的区别：中间**真的推进了 N 个真实 tick**（tick freeze 下同 tick 与跨 tick 分不开）。
  #   -XTick -XTickArms write,nowrite -XTickGaps 1,5,20,100
  [switch]$XTick,
  [string]$XTickGaps = '1,5,20,100',
  [string]$XTickArms = 'write,nowrite',
  # 只跑 invalidate / diag，不跑 perf 计时循环（对照腿省时间）
  [switch]$SkipPerf
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

# 从 PERFDETAIL 里的复合记号 'site:cells=..,changed=..,hash=0x..,verify=..' 取子键。
# 为什么要单独一个函数：Field() 只认 "空白 + key=" 的形态，'site:cells=' 里的 cells 前面是冒号 ⇒ 取不到。
function SiteField([string]$line, [string]$key) {
  if (-not $line) { return '(缺)' }
  $m = [regex]::Match($line, "site:(\S+)")
  if (-not $m.Success) { return '(缺)' }
  $sub = [regex]::Match($m.Groups[1].Value, "(?:^|,)$([regex]::Escape($key))=([^,]+)")
  if ($sub.Success) { return $sub.Groups[1].Value }
  return '(缺)'
}

# SWEEP 回执行 → 以 "preset/mode/d" 为键的表（P1-NET：换手点曲线的原始数据）。
function Get-Sweep([string]$file) {
  $map = [ordered]@{}
  foreach ($line in Get-Content -LiteralPath $file) {
    if ($line -match 'SWEEP id=d+ preset=(S+) mode=(S+) blockDist=(d+)') {
      $map["$($Matches[1])/$($Matches[2])/$($Matches[3])"] = $line
    }
  }
  $map
}

# 某个模式在文件里最后一次出现的行（AIDIST/TICKSTAT 会打多次：reset 一次、sprint 后一次）。
function LastLine([string]$file, [string]$pattern) {
  $m = Select-String -LiteralPath $file -Pattern $pattern -ErrorAction SilentlyContinue | Select-Object -Last 1
  if ($m) { return $m.Line.Trim() }
  return ''
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
    # 勘误（2026-09-24 P1-FIX）：这里原来写的是 'reachesTargetFlag'，而回执里的键是
    # 'reachedTargetFlag' ⇒ Field() 两边都返回 '(缺)'，**这个字段其实从来没被比过**。
    # 而它恰好是 native 侧填反了的那个字段（见 NativeNodeCodec.reachesTarget 的实测证据）。
    # **P1-NET（R6）**：这三张表就是"比对脚本到底比了哪些字段"的**唯一出处**，
    # 它们与回执键名的机械对拍在 src/test/java/cava/hook/ReceiptFieldParityTest.java 里（少一个/多一个都红）。
    # 为什么要有那条测试：本轮之前 $perfFields 里写的是 'reachesTargetFlag'，而回执里的键是
    # 'reachedTargetFlag' ⇒ Field() 两边都返回 (缺)、两边相等 ⇒ **这个字段从来没被比过**，
    # 而且整体还是绿的。只靠人读发现不了这件事。
    $perfFields = @('ok', 'n', 'nodes_avg', 'nodes_p50', 'nodes_min', 'nodes_max', 'nullPaths', 'endDistinct', 'end', 'manh', 'reachedTargetFlag')
    $detFields = @('sig_coords', 'sig_types', 'sigDistinct', 'analyzeLen', 'startNode', 'endNode', 'collisionNodes', 'firstBadNode',
      'canaryDelta', 'expectDelta', 'analyzeNulls', 'solidNodes', 'params', 'env', 'startProbe')
    # site: 是个复合 token（Field() 取不到子键）⇒ 用专门的抽取器比"两腿测的是不是同一块地".
    $siteFields = @('cells', 'hash', 'verify')
    $diff = @()
    foreach ($f in $perfFields) {
      $a = Field $o.perf $f; $b = Field $n.perf $f
      # **字段缺失必须红**：两边都 (缺) 会"相等"，那正是 R6 那个坑。
      if ($a -eq '(缺)' -or $b -eq '(缺)') { $diff += "$f(字段缺失! off=$a on=$b)"; continue }
      if ($a -ne $b) { $diff += "$f(off=$a on=$b)" }
    }
    foreach ($f in $detFields) {
      $a = Field $o.detail $f; $b = Field $n.detail $f
      if ($a -eq '(缺)' -or $b -eq '(缺)') { $diff += "detail.$f(字段缺失! off=$a on=$b)"; continue }
      if ($a -ne $b) { $diff += "detail.$f(off=$a on=$b)" }
    }
    foreach ($f in $siteFields) {
      $a = SiteField $o.detail $f; $b = SiteField $n.detail $f
      if ($a -eq '(缺)' -or $b -eq '(缺)') { $diff += "site.$f(字段缺失! off=$a on=$b)"; continue }
      if ($a -ne $b) { $diff += "site.$f(off=$a on=$b)" }
    }
    # **接管不变量（2026-09-24 P1-FIX 改）**：原来要求"每次调用都必须接管"；现在原生结果
    # 可以被**窗口截断检测**判回退（缺陷 2 的修复），所以不变量改成
    #   takeovers + fallbacks == expectDelta
    # —— 每一次调用要么接管、要么被**明确计数**地回退。既没接管也没计数 ⇒ 仍然红。
    $tk = Field $n.detail 'takeovers'; $exp = Field $n.detail 'expectDelta'
    $fbn = Field $n.detail 'fallbacks'
    $gtn = Field $n.detail 'gated'
    if ($fbn -eq '(缺)') { $fbn = '0' }   # 旧 jar 里没有这个字段：按 0 处理，保持向后兼容
    if ($gtn -eq '(缺)') { $gtn = '0' }   # 同上（P1-NET 加的"按规模分流"计数）
    if ($tk -ne '(缺)' -and $exp -ne '(缺)' -and ([int]$tk + [int]$fbn + [int]$gtn) -ne [int]$exp) {
      $diff += "on腿调用未被完整记账(takeovers=$tk fallbacks=$fbn gated=$gtn expect=$exp)"
    }
    $rows += [pscustomobject]@{
      key = $k; ns_off = (Field $o.perf 'ns_avg'); ns_on = (Field $n.perf 'ns_avg')
      nodes = (Field $o.perf 'nodes_avg'); fb = $fbn
      fbearly = (Field $n.detail 'fb_earlyStop'); fbstruct = (Field $n.detail 'fb_structural')
      diff = ($diff -join '; ')
    }
    if ($diff.Count) { $red += "$k : $($diff -join ' | ')" }
  }
  Write-Host ''
  Write-Host "--- 每次调用耗时（ns/次）与节点数 ---"
  Write-Host ("  {0,-20} {1,14} {2,14} {3,10} {4,10} {5}" -f 'preset/mode', 'off ns', 'on ns', 'off nodes', 'off/on', '回退(早停/结构)')
  foreach ($r in $rows) {
    $ratio = ''
    if ($r.ns_off -match '^[\d.]+$' -and $r.ns_on -match '^[\d.]+$' -and [double]$r.ns_on -gt 0) {
      $ratio = ('{0:n3}x' -f ([double]$r.ns_off / [double]$r.ns_on))
    }
    Write-Host ("  {0,-20} {1,14} {2,14} {3,10} {4,10} {5}" -f $r.key, $r.ns_off, $r.ns_on, $r.nodes, $ratio, "$($r.fb)/$($r.fbearly)/$($r.fbstruct)")
  }
  Write-Host ''
  Write-Host "--- 缺陷 1 失效钩子实测（-Invalidate 才有）---"
  foreach ($f in @($offFile, $onFile)) {
    $inv = Select-String -LiteralPath $f -Pattern 'INVALIDATE preset=' -ErrorAction SilentlyContinue | ForEach-Object { $_.Line }
    foreach ($l in $inv) { Write-Host ("  {0}: {1}" -f (Split-Path $f -Leaf), $l) }
  }
  Write-Host ''
  Write-Host "--- P1-NET：逐距离换手点（SWEEP；ns/次，off/on 与倍数）---"
  $swOff = Get-Sweep $offFile
  $swOn = Get-Sweep $onFile
  $swKeys = @($swOff.Keys + $swOn.Keys | Sort-Object -Unique)
  if ($swKeys.Count -eq 0) {
    Write-Host "  （本腿没跑 -Sweep）"
  } else {
    Write-Host ("  {0,-22} {1,12} {2,12} {3,9} {4,9} {5,9} {6}" -f 'preset/mode/d', 'off ns', 'on ns', 'off/on', 'off nodes', 'on nodes', 'gate')
    foreach ($k in $swKeys) {
      $a = $swOff[$k]; $b = $swOn[$k]
      $na = Field $a 'ns_avg'; $nb = Field $b 'ns_avg'
      $ratio = ''
      if ($na -match '^[\d.]+$' -and $nb -match '^[\d.]+$' -and [double]$nb -gt 0) {
        $ratio = ('{0:n3}x' -f ([double]$na / [double]$nb))
      }
      Write-Host ("  {0,-22} {1,12} {2,12} {3,9} {4,9} {5,9} {6}" -f $k, $na, $nb, $ratio,
        (Field $a 'nodes_avg'), (Field $b 'nodes_avg'), (Field $b 'gated'))
    }
  }
  Write-Host ''
  Write-Host "--- P1-NET：真实 AI 负载账本（AIDIST；最后一行 = 测量 sprint 之后的累计值）---"
  foreach ($pair in @(@($offFile, 'off'), @($onFile, 'on'))) {
    $l = LastLine $pair[0] 'AIDIST calls='
    if ($l) { Write-Host "  $($pair[1]): $l" } else { Write-Host "  $($pair[1]): （本腿没跑 -AiLoad）" }
  }
  foreach ($pair in @(@($offFile, 'off'), @($onFile, 'on'))) {
    $l = LastLine $pair[0] 'TICKSTAT ticks='
    if ($l) { Write-Host "  $($pair[1]): $l" }
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

# **必须用 @() 强制成数组**：单个元素时 PowerShell 会把管道结果解包成字符串，
# 于是 $want[0] 变成"字符串的第一个字符"（实测：-Presets long128 ⇒ $want[0]='l'
# ⇒ `cava pathfind site l` / `cava pathfind sweep l ...` 都被"未知 preset"拒掉，
# 而且 sendCommandFeedback=false 连错误都看不到 ⇒ 静默什么都没测）。
$want = @($Presets.Split(',') | ForEach-Object { $_.Trim() } | Where-Object { $_ })
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
if ($GateMin -ge 0) { $javaArgs += "-Dcava.pathfind.gate.minBlocks=$GateMin" }
$extraProps = @($JavaProp) + @($JavaProps -split '[,\s]+' | Where-Object { $_ })
$javaArgs += $extraProps
Write-Host "[perf] javaProp: $($extraProps -join ' ')"

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
$aidistAfter = ''
$tickAfter = ''
$sparkAfter = ''
if ($AiLoad) {
  Rcon ("cava pathfind site " + $want[0]) | Out-Null
  Start-Sleep -Seconds 3
  Rcon 'kill @e[type=minecraft:zombie]' | Out-Null
  Rcon 'kill @e[type=minecraft:villager]' | Out-Null
  # 固定的光照/时间（doDaylightCycle 已关）：不让"白天烧僵尸"这类与寻路无关的随机事件
  # 决定测量窗口里的生物数（**两条腿做同样的事**）。
  Rcon 'time set midnight' | Out-Null
  Rcon 'tick unfreeze' | Out-Null
  Rcon 'summon minecraft:villager 70 71 -32 {PersistenceRequired:1b,Silent:1b,Tags:["cava_ai"]}' | Out-Null
  # 村民**不许被打死**：P1-FIX 那条腿是 1200 tick，本流要跑 400 预热 + 2400 测量 = 2800 tick，
  # 村民中途死亡会让"负载"在测量窗口内塌掉（而且死在哪个 tick 由 AI 随机数决定 ⇒ 两次不可比）。
  # resistance 9 级 = 伤害免疫；**两条腿完全一样**。
  Rcon 'effect give @e[type=minecraft:villager,tag=cava_ai] minecraft:resistance 100000 9 true' | Out-Null
  for ($i = 0; $i -lt $AiZombies; $i++) {
    $zx = 40 + (($i % 10) * 2)
    $zz = -44 + ([int]($i / 10) * 3)
    Rcon ("summon minecraft:zombie {0} 71 {1} {{PersistenceRequired:1b,Silent:1b,Tags:[""cava_ai""]}}" -f $zx, $zz) | Out-Null
  }
  $stats0 = Rcon 'cava pathfind stats'
  $zBefore = Rcon 'execute if entity @e[type=minecraft:zombie] run say [perf] zombies_present'
  Write-Host "[perf] ai-load: $AiZombies 只僵尸 + 1 村民；预热 sprint $AiWarmup tick + 测量 sprint $AiTicks tick"
  Rcon 'tick freeze' | Out-Null
  # **预热 sprint**：JIT 暖机。P1-PERF 实测过"同一个 6 格 bench 在冷/热服务端上差 22 倍"
  # ⇒ 不预热的话两条腿的绝对耗时不可比。
  if ($AiWarmup -gt 0) {
    Rcon ("tick sprint " + $AiWarmup) | Out-Null
    $swW = [System.Diagnostics.Stopwatch]::StartNew()
    while ($swW.Elapsed.TotalSeconds -lt 900) {
      Start-Sleep -Seconds 3
      $q = Rcon 'tick query'
      if ("$q" -notmatch 'sprint') { break }
    }
    Start-Sleep -Seconds 2
  }
  # 清账本 + 清 MSPT 记录：**测量窗口 = 下面这一次 sprint 之间的所有 tick**。
  # 两条腿用的是同一段代码、同一个口径 ⇒ 差值就是净收益（含回退的浪费）。
  Rcon 'cava pathfind aidist reset' | Out-Null
  Rcon 'cava pathfind tickstat reset' | Out-Null
  Rcon 'tick freeze' | Out-Null
  Rcon ("tick sprint " + $AiTicks) | Out-Null
  $swAi = [System.Diagnostics.Stopwatch]::StartNew()
  while ($swAi.Elapsed.TotalSeconds -lt 900) {
    Start-Sleep -Seconds 3
    $q = Rcon 'tick query'
    if ("$q" -notmatch 'sprint') { break }
  }
  $swAi.Stop()
  # **先读 TICKSTAT**（越早越好：sprint 结束后服务端回到 20 TPS 的"睡眠 tick"，
  # 那些 tick 的间隔 ~50 ms 会被 TickTimeRecorder 当成慢 tick 滤掉，但少读一秒就少一分噪声）
  $tickAfter = Rcon 'cava pathfind tickstat'
  Start-Sleep -Seconds 2
  $aidistAfter = Rcon 'cava pathfind aidist'
  Write-Host "[perf] ai-load sprint 用时 $('{0:n1}' -f $swAi.Elapsed.TotalSeconds) s"
  Write-Host "[perf] ai-load AIDIST: $aidistAfter"
  Write-Host "[perf] ai-load TICKSTAT: $tickAfter"
  $sparkAfter = Rcon 'spark tps'
  Write-Host "[perf] ai-load spark tps: $sparkAfter"
  Start-Sleep -Seconds 1
  $stats1 = Rcon 'cava pathfind stats'
  Rcon 'say [perf] ai-load sprint done' | Out-Null
  $zAfter = Rcon 'execute if entity @e[type=minecraft:zombie] run say [perf] zombies_after_sprint'
  function CanaryOf([string]$s) { if ("$s" -match 'canary=(\d+)') { return [int]$Matches[1] } return -1 }
  $c0 = CanaryOf $stats0
  $c1 = CanaryOf $stats1
  Write-Host "[perf] ai-load stats0: $stats0"
  Write-Host "[perf] ai-load stats1: $stats1"
  Write-Host ("[perf] ai-load 寻路调用 = {0} 次 / {1} tick ⇒ {2:n4} 次/tick（canary {3} -> {4}）" -f ($c1 - $c0), $AiTicks, (($c1 - $c0) / [double]$AiTicks), $c0, $c1)
  $vAfter = Rcon 'execute if entity @e[type=minecraft:villager,tag=cava_ai] run say [perf] villager_alive'
  Write-Host "[perf] ai-load zombies before: $zBefore"
  Write-Host "[perf] ai-load zombies after : $zAfter"
  Write-Host "[perf] ai-load villager after : $vAfter"
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

# --- P1-NET：逐距离换手点扫描（分流阈值的数据出处）---
if ($Sweep) {
  $swPat = "SWEEP id="
  $b0 = (Select-String -LiteralPath $srv.Log -Pattern $swPat -ErrorAction SilentlyContinue | Measure-Object).Count
  # 注意：Brigadier 的 word() 参数**不接受逗号**（只接受 [a-zA-Z0-9_.+-]）：
  # 用逗号会整条命令被拒，而 sendCommandFeedback=false 连错误都看不到 ⇒ 实测白等 20 分钟。
  # 所以这里把分隔符统一成 '_'（Java 侧三种分隔符都吃）。
  $swArg = $Sweep -replace ',', '_'
  Rcon "cava pathfind sweep $($want[0]) $swArg $SweepN $SweepMode" | Out-Null
  if (-not (Wait-LogCount $swPat $b0 300)) {
    Write-Host "[perf] !! SWEEP 没有回执行（命令被拒？）—— 检查日志里有没有 'Unknown or incomplete command'"
  }
  Select-String -LiteralPath $srv.Log -Pattern 'SWEEP id=' | ForEach-Object { Write-Host "  $($_.Line.Trim())" }
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
  if ($Invalidate) {
    $invPat = "INVALIDATE preset=$preset "
    $bi = (Select-String -LiteralPath $srv.Log -Pattern $invPat -ErrorAction SilentlyContinue | Measure-Object).Count
    Rcon "cava pathfind invalidate $preset" | Out-Null
    [void](Wait-LogCount $invPat $bi 600)
    Select-String -LiteralPath $srv.Log -Pattern $invPat | Select-Object -Last 1 |
      ForEach-Object { Write-Host "  $($_.Line.Trim())" }
  }
  # --- P1-CROSS：跨 tick 复用正确性（arm → **推进真实 tick** → check）---
  if ($XTick) {
    $arms = @($XTickArms.Split(',') | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    $gaps = @($XTickGaps.Split(',') | ForEach-Object { [int]$_.Trim() } | Where-Object { $_ -ge 1 })
    foreach ($arm in $arms) {
      foreach ($gap in $gaps) {
        $armPat = "XTICK phase=arm preset=$preset arm=$arm "
        $b1 = (Select-String -LiteralPath $srv.Log -Pattern $armPat -ErrorAction SilentlyContinue | Measure-Object).Count
        Rcon "cava pathfind xtick arm $preset $arm" | Out-Null
        if (-not (Wait-LogCount $armPat $b1 300)) { Write-Host "[perf] !! XTICK arm 没有回执行（$preset/$arm）" }
        Select-String -LiteralPath $srv.Log -Pattern $armPat | Select-Object -Last 1 |
          ForEach-Object { Write-Host "  $($_.Line.Trim())" }
        # **推进真实 tick**：tick sprint 会真的跑 tick（真实服务器上世界变更只在 tick 内发生）
        Rcon 'tick freeze' | Out-Null
        Rcon ("tick sprint " + $gap) | Out-Null
        $swS = [System.Diagnostics.Stopwatch]::StartNew()
        while ($swS.Elapsed.TotalSeconds -lt 120) {
          Start-Sleep -Milliseconds 500
          $q = Rcon 'tick query'
          if ("$q" -notmatch 'sprint') { break }
        }
        Start-Sleep -Milliseconds 800
        $chkPat = "XTICK phase=check preset=$preset "
        $b2 = (Select-String -LiteralPath $srv.Log -Pattern $chkPat -ErrorAction SilentlyContinue | Measure-Object).Count
        Rcon "cava pathfind xtick check $preset" | Out-Null
        if (-not (Wait-LogCount $chkPat $b2 300)) { Write-Host "[perf] !! XTICK check 没有回执行（$preset/$arm/gap=$gap）" }
        Select-String -LiteralPath $srv.Log -Pattern $chkPat | Select-Object -Last 1 |
          ForEach-Object { Write-Host "  $($_.Line.Trim())" }
      }
    }
  }
  if ($SkipPerf) { continue }
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
$lines = Select-String -LiteralPath $srv.Log -Pattern 'PERF id=|PERFDETAIL id=|SITE |DIAG |EXPLORE |INVALIDATE |XTICK |BENCH id=|SWEEP id=|AIDIST|TICKSTAT ticks=|接管条件|pathfind\] 原生接管|PROBE_|ai-load' -ErrorAction SilentlyContinue |
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
$head += "#javaProp=$($extraProps -join ' ')"
$head += "#aiZombies=$AiZombies aiTicks=$AiTicks aiWarmup=$AiWarmup aiLoad=$AiLoad"
$head += "#sweep=$Sweep sweepN=$SweepN sweepMode=$SweepMode"
$head += "#gateMin=$GateMin"
$head += "#xTick=$XTick xTickArms=$XTickArms xTickGaps=$XTickGaps"
$head += "#aidistA=$aidistAfter"
$head += "#tickstatA=$tickAfter"
$head += "#javaArgs=$($srv.CommandLine)"
$outFile = Join-Path $resultsDir "$Leg.txt"
($head + $lines) | Set-Content -Encoding UTF8 $outFile
Write-Host "[perf] 结果写入 $outFile（$($lines.Count) 行回执）"
if (-not ($art0.dll -eq $art1.dll)) { Write-Host "[perf] !! 测量期间 DLL 哈希变了 ⇒ 这一腿作废，必须重跑" }
$lines | ForEach-Object { Write-Host "  $_" }
