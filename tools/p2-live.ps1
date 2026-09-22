<#
Cava P2 live 接管轮：**私有**服务端编排（端口 25621 / RCON 25622 / query 25623
—— 刻意避开 p2-wire 的 25601-25603 与 p3-redstone 的 25611-25612）。

用法（每条都是独立进程，状态放在 testbed/p2-live/ 下）：
  pwsh -File tools/p2-live.ps1 -Action start -Mode live -Tag live1
  pwsh -File tools/p2-live.ps1 -Action build              # 铺一次竞技场（进 world-template）
  pwsh -File tools/p2-live.ps1 -Action snapshot           # world -> world-template
  pwsh -File tools/p2-live.ps1 -Action reset              # world-template -> world
  pwsh -File tools/p2-live.ps1 -Action scenario -Steps 1200 -OutFile out/sc1.txt
  pwsh -File tools/p2-live.ps1 -Action stop
  pwsh -File tools/p2-live.ps1 -Action rcon -Command "list"
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)]
  [ValidateSet('start', 'stop', 'build', 'snapshot', 'reset', 'scenario', 'rcon')]
  [string]$Action,
  [string]$Mode = 'off',
  [string]$Tag = 'run',
  [string]$Canary = 'off',
  [switch]$Verify,
  [int]$Steps = 1200,
  [int]$BatchSteps = 25,
  [int]$PerBatch = 10,
  [int]$Batches = 12,
  [string]$OutFile = '',
  [string]$Command = '',
  [switch]$NoDump,
  [ValidateSet('stand', 'item')]
  [string]$Kind = 'stand',
  [int]$IdleMs = 120
)
$ErrorActionPreference = 'Stop'
$root = 'J:\mc\Cava'
$dir = Join-Path $root 'testbed\p2-live'
$outDir = Join-Path $dir 'out'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$rconPort = 25622
$javaExe = 'C:\Program Files\Java\jdk-21\bin\java.exe'

# ---------------- 内联 RCON（不 spawn 子进程，避免每条命令 1 秒开销） ----------------
function Read-Exact([System.IO.Stream]$s, [int]$n) {
  $buf = New-Object byte[] $n
  $got = 0
  while ($got -lt $n) {
    $r = $s.Read($buf, $got, $n - $got)
    if ($r -le 0) { throw 'rcon: connection closed' }
    $got += $r
  }
  , $buf
}
function Rcon([string]$cmd, [int]$idle = 0) {
  if ($idle -le 0) { $idle = $IdleMs }
  $client = New-Object System.Net.Sockets.TcpClient
  $client.ReceiveTimeout = $idle
  $client.Connect('127.0.0.1', $rconPort)
  $stream = $client.GetStream()
  try {
    $body = [System.Text.Encoding]::ASCII.GetBytes('cava')
    $len = 4 + 4 + $body.Length + 2
    $ms = New-Object System.IO.MemoryStream
    $bw = New-Object System.IO.BinaryWriter($ms)
    $bw.Write([int]$len); $bw.Write([int]1); $bw.Write([int]3); $bw.Write($body)
    $bw.Write([byte]0); $bw.Write([byte]0); $bw.Flush()
    $a = $ms.ToArray(); $stream.Write($a, 0, $a.Length); $stream.Flush()
    $null = Read-Exact $stream 4
    $null = Read-Exact $stream 10
    $cmdBytes = [System.Text.Encoding]::ASCII.GetBytes($cmd)
    $ms2 = New-Object System.IO.MemoryStream
    $bw2 = New-Object System.IO.BinaryWriter($ms2)
    $bw2.Write([int](4 + 4 + $cmdBytes.Length + 2)); $bw2.Write([int]2); $bw2.Write([int]2)
    $bw2.Write($cmdBytes); $bw2.Write([byte]0); $bw2.Write([byte]0); $bw2.Flush()
    $a2 = $ms2.ToArray(); $stream.Write($a2, 0, $a2.Length); $stream.Flush()
    $sb = New-Object System.Text.StringBuilder
    while ($true) {
      try {
        $lenBuf = Read-Exact $stream 4
        $len = [System.BitConverter]::ToInt32($lenBuf, 0)
        $pkt = Read-Exact $stream $len
        [void]$sb.Append([System.Text.Encoding]::ASCII.GetString($pkt, 8, $len - 10))
      } catch { break }
    }
    return $sb.ToString().TrimEnd()
  } finally {
    $stream.Close(); $client.Close()
  }
}
function Quiet([string]$cmd) { $null = Rcon $cmd }
function Assert-Rcon([string]$cmd, [string]$expect) {
  $r = Rcon $cmd
  if ($r -notmatch $expect) { throw ("rcon 断言失败: '" + $cmd + "' -> '" + $r + "'（期望匹配 " + $expect + "）") }
  return $r
}

switch ($Action) {

  'rcon' { Write-Host (Rcon $Command) }

  'snapshot' {
    $w = Join-Path $dir 'world'; $t = Join-Path $dir 'world-template'
    if (Test-Path -LiteralPath $t) { Remove-Item -LiteralPath $t -Recurse -Force }
    Copy-Item -LiteralPath $w -Destination $t -Recurse -Force
    Write-Host ("snapshot: {0} -> {1}" -f $w, $t)
  }

  'reset' {
    $w = Join-Path $dir 'world'; $t = Join-Path $dir 'world-template'
    if (-not (Test-Path -LiteralPath $t)) { throw 'world-template 不存在' }
    if (Test-Path -LiteralPath $w) { Remove-Item -LiteralPath $w -Recurse -Force }
    Copy-Item -LiteralPath $t -Destination $w -Recurse -Force
    Write-Host 'reset: world-template -> world'
  }

  'start' {
    # 端口占用检查：上一次没停干净的服务器会让本次启动 BindException 崩溃，
    # 而"崩溃的服务器 + 还活着的老服务器"会让后续 RCON 命令打到错的实例上（踩过一次）。
    $probe = New-Object System.Net.Sockets.TcpClient
    try {
      $probe.Connect('127.0.0.1', 25621)
      $probe.Close()
      throw '端口 25621 已被占用：先跑 -Action stop（或有别的实例在跑）'
    } catch [System.Net.Sockets.SocketException] {
      # 连不上 = 端口空闲 = 正是我们要的
    } finally {
      $probe.Close()
    }
    $log = Join-Path $outDir ("$Tag.log")
    $err = Join-Path $outDir ("$Tag.err")
    if (Test-Path -LiteralPath $log) { Remove-Item -LiteralPath $log -Force }
    $verifyFlag = ''
    if ($Verify) { $verifyFlag = ' -Dcava.entity.move.verify=true' }
    $env:JAVA_TOOL_OPTIONS = "-Duser.language=en -Dfile.encoding=UTF-8 -Dcava.entity.move=$Mode -Dcava.entity.move.canary=$Canary$verifyFlag --enable-preview --enable-native-access=ALL-UNNAMED"
    $p = Start-Process -FilePath $javaExe -ArgumentList '-Xmx2G', '-jar', 'fabric-server-launch.jar', 'nogui' `
      -WorkingDirectory $dir -RedirectStandardOutput $log -RedirectStandardError $err -WindowStyle Hidden -PassThru
    Set-Content -LiteralPath (Join-Path $dir 'server.pid') -Value $p.Id
    Write-Host ("started pid={0} mode={1} canary={2} log={3}" -f $p.Id, $Mode, $Canary, $log)
    $deadline = (Get-Date).AddSeconds(180)
    while ((Get-Date) -lt $deadline) {
      Start-Sleep -Milliseconds 700
      if (Test-Path -LiteralPath $log) {
        $tail = Get-Content -LiteralPath $log -Tail 400 -ErrorAction SilentlyContinue
        if ($tail -match 'Done \(') { Write-Host 'ready: Done ('; break }
        if ($tail -match 'FAILED TO START|Exception in thread "main"') { throw "服务端启动失败，见 $log" }
      }
      if ($p.HasExited) { throw "服务端进程已退出（exit=$($p.ExitCode)），见 $log" }
    }
    $tail = Get-Content -LiteralPath $log -Tail 400 -ErrorAction SilentlyContinue
    if (-not ($tail -match 'Done \(')) { throw '服务端 180 秒内没有就绪' }
    Write-Host ('ready-confirmed ' + $Tag)
  }

  'stop' {
    $pidFile = Join-Path $dir 'server.pid'
    try { Quiet 'stop' } catch { Write-Host "stop 命令失败（可能已经退出）: $_" }
    if (Test-Path -LiteralPath $pidFile) {
      $serverPid = [int](Get-Content -LiteralPath $pidFile -Raw).Trim()
      $deadline = (Get-Date).AddSeconds(90)
      while ((Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 500
        if (-not (Get-Process -Id $serverPid -ErrorAction SilentlyContinue)) { Write-Host "stopped pid=$serverPid"; break }
      }
      if (Get-Process -Id $serverPid -ErrorAction SilentlyContinue) {
        Stop-Process -Id $serverPid -Force
        Write-Host "force killed pid=$serverPid"
      }
      Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
    }
  }

  'build' {
    # 竞技场：蓝冰平台（摩擦小 -> 假人一直滑）+ 石墙 + 柱子 + 台阶。
    Quiet 'gamerule doMobSpawning false'
    Quiet 'gamerule randomTickSpeed 0'
    Quiet 'gamerule doWeatherCycle false'
    Quiet 'gamerule doDaylightCycle false'
    Quiet 'gamerule doFireTick false'
    Quiet 'gamerule mobGriefing false'
    Quiet 'gamerule doEntityDrops false'
    Quiet 'gamerule sendCommandFeedback false'
    Quiet 'gamerule logAdminCommands false'
    Quiet 'gamerule commandBlockOutput false'
    Quiet 'gamerule maxEntityCramming 0'
    Quiet 'time set 6000'
    Quiet 'weather clear'
    Quiet 'forceload add -48 -48 48 48'
    Quiet 'kill @e[type=!player]'
    Quiet 'fill -32 -61 -32 32 -61 32 minecraft:blue_ice'
    Quiet 'fill -32 -60 -32 32 -50 -32 minecraft:stone'
    Quiet 'fill -32 -60 32 32 -50 32 minecraft:stone'
    Quiet 'fill -32 -60 -32 -32 -50 32 minecraft:stone'
    Quiet 'fill 32 -60 -32 32 -50 32 minecraft:stone'
    # 柱子（碰撞源）
    for ($i = -3; $i -le 3; $i++) {
      $x = $i * 8
      Quiet ("fill {0} -60 -8 {0} -57 -8 minecraft:stone" -f $x)
      Quiet ("fill {0} -60 8 {0} -57 8 minecraft:stone" -f $x)
    }
    # 台阶（触发台阶分支守卫）
    for ($s = 0; $s -lt 3; $s++) {
      Quiet ("fill -24 -60 {0} -24 -{1} {2} minecraft:stone" -f (14 + $s), (60 + $s), (14 + $s))
    }
    Quiet 'save-all flush'
    Write-Host 'arena built'
  }

  'scenario' {
    # 确定性场景：冻结 tick -> 分批 spawn 带初速的盔甲架 -> tick step -> 逐实体 dump。
    Quiet 'gamerule doMobSpawning false'
    Quiet 'gamerule randomTickSpeed 0'
    Quiet 'gamerule sendCommandFeedback false'
    Quiet 'kill @e[type=!player]'
    Quiet 'tick freeze'
    $spawnStart = Get-Date
    $spawned = 0
    for ($b = 0; $b -lt $Batches; $b++) {
      for ($i = 0; $i -lt $PerBatch; $i++) {
        $n = $b * $PerBatch + $i
        # 稀疏网格：相互间距 >= 4.5 格（不互相推挤）。原先把同一列的实体放在相隔 0.75 格的位置上，
        # 它们会互推 —— 那让**纯原版**两次运行的 dump 也不一致（off vs off2 有 10 行不同），
        # 于是"live vs off 有差异"根本不能归因给 live。
        $x = -27.0 + ($n % 10) * 6.0
        $z = -29.0 + [Math]::Floor($n / 10) * 4.5
        $y = -59.0
        # 固定公式的初速（不用随机数：跨 run 必须逐位可复现）
        $mx = [Math]::Round(0.12 + (($n * 37) % 23) * 0.01, 4)
        $my = [Math]::Round(0.02 + (($n * 11) % 7) * 0.01, 4)
        $mz = [Math]::Round(-0.15 + (($n * 53) % 29) * 0.01, 4)
        if ($Kind -eq 'item') {
          # 物品实体每 tick 无条件走 Entity.move（ItemEntity.tick 末尾就是 this.move），
          # 落地后仍有微小速度 -> 每一次调用都是"非零位移"，是性能采样的理想负载。
          $nbt = '{Tags:["c' + $n + '"],Motion:[' + $mx + ',' + $my + ',' + $mz + '],Age:0s,PickupDelay:32767s,Invulnerable:1b,Item:{id:"minecraft:stone",Count:1b}}'
          Quiet ("summon minecraft:item {0} {1} {2} {3}" -f $x, $y, $z, $nbt)
        } else {
          $nbt = '{Tags:["c' + $n + '"],Motion:[' + $mx + ',' + $my + ',' + $mz + '],NoGravity:0b,Invulnerable:1b}'
          Quiet ("summon minecraft:armor_stand {0} {1} {2} {3}" -f $x, $y, $z, $nbt)
        }
        $spawned++
      }
      Quiet ("tick step {0}" -f $BatchSteps)
      if ($b % 10 -eq 0) { Write-Host ("  批次 {0}/{1}（{2:n0}s）" -f $b, $Batches, ((Get-Date) - $spawnStart).TotalSeconds) }
    }
    $t0 = Get-Date
    Quiet ("tick step {0}" -f $Steps)
    Quiet 'tick freeze'
    Write-Host ("spawned={0} steps={1} 用时={2:n1}s" -f $spawned, $Steps, ((Get-Date) - $t0).TotalSeconds)
    if ($NoDump) { Write-Host 'no-dump'; return }
    $lines = New-Object System.Collections.Generic.List[string]
    for ($n = 0; $n -lt $spawned; $n++) {
      $p = Rcon ("data get entity @e[tag=c{0},limit=1] Pos" -f $n)
      $m = Rcon ("data get entity @e[tag=c{0},limit=1] Motion" -f $n)
      $o = Rcon ("data get entity @e[tag=c{0},limit=1] OnGround" -f $n)
      $fl = Rcon ("data get entity @e[tag=c{0},limit=1] FallDistance" -f $n)
      $lines.Add(("c{0} | {1} | {2} | {3} | {4}" -f $n, $p, $m, $o, $fl))
    }
    if ($OutFile -eq '') { $OutFile = Join-Path $outDir ("scenario-$Tag.txt") }
    elseif (-not [System.IO.Path]::IsPathRooted($OutFile)) { $OutFile = Join-Path $root $OutFile }
    Set-Content -LiteralPath $OutFile -Value ($lines -join "`n") -Encoding UTF8
    Write-Host ("dump -> {0}（{1} 行）" -f $OutFile, $lines.Count)
  }
}
