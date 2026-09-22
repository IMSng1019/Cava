<#
Cava 场景层：给世界装一个**脚本化合成场景** + 确定性前置（tick freeze / save-off）。

为什么要自己写：docs/CAVA-parity-fixtures.md 实测「TIS/Carpet 的 logger 在专用服务端不落盘」，
所以黄金轨迹只能我们自己采；而实体层又**不能用自然刷怪**（captain 的确定性报告结论），
必须"固定数量/位置/属性 + 脚本驱动"。

装进 world/datapacks/cava-parity/：
  data/cava/functions/freeze.mcfunction    #minecraft:load：tick freeze + save-off + 固定时间/天气
  data/cava/functions/scenario.mcfunction 布场（平台 + 红石装置 + 两组实体）
  data/cava/functions/tick_loop.mcfunction 每 2 tick 翻转一个红石块（脚本驱动的红石激励源）

用法: pwsh -File tools/make-parity-datapack.ps1 -WorldDir <world> [-PlatformX 200] [-PlatformZ 0]
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][string]$WorldDir,
  [int]$PlatformX = 200,
  [int]$PlatformZ = 0,
  [int]$GroundY = 63,
  [int]$SprintTicks = 0,
  [string]$Name = 'cava-parity'
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
New-Item -ItemType Directory -Force -Path $WorldDir | Out-Null
$base = Join-Path $WorldDir ("datapacks\" + $Name)
New-Item -ItemType Directory -Force -Path (Join-Path $base 'data\cava\functions'), (Join-Path $base 'data\minecraft\tags\functions') | Out-Null

'{"pack":{"pack_format":26,"description":"Cava parity scripted scenario"}}' |
  Set-Content -Encoding ASCII (Join-Path $base 'pack.mcmeta')

# --- 确定性前置：世界一加载就冻结，之后所有推进都由 /tick sprint 精确给出 ---
@'
# 由 tools/make-parity-datapack.ps1 生成。作用：把世界钉死在 level.dat 里的 tick 数。
tick freeze
save-off
gamerule doDaylightCycle false
gamerule doWeatherCycle false
gamerule doMobSpawning false
gamerule doFireTick false
gamerule randomTickSpeed 3
gamerule sendCommandFeedback false
time set midnight
weather clear
say [cava-parity] frozen at load; autosave/spawning/weather off
'@ | Set-Content -Encoding ASCII (Join-Path $base 'data\cava\functions\freeze.mcfunction')

$px = $PlatformX
$pz = $PlatformZ
$gy = $GroundY

# --- 场景本体 ---
$scenario = @"
# 由 tools/make-parity-datapack.ps1 生成 —— **脚本化合成场景**（固定数量/位置/属性）
# 平台（远离出生点，避开 captain 实测的非确定区域）
fill $($px - 12) $gy $($pz - 8) $($px + 12) $gy $($pz + 8) minecraft:stone
fill $($px - 12) $($gy + 1) $($pz - 8) $($px + 12) $($gy + 6) $($pz + 8) minecraft:air

# 红石：脚本驱动的方块更新源（tick_loop 每 2 tick 翻转），推活塞 + 红石线 + 灯
setblock $($px - 2) $($gy + 1) $pz minecraft:redstone_block
setblock $($px - 1) $($gy + 1) $pz minecraft:redstone_wire
setblock $px $($gy + 1) $pz minecraft:redstone_wire
setblock $($px + 1) $($gy + 1) $pz minecraft:piston[facing=east]
setblock $($px + 2) $($gy + 1) $pz minecraft:sand
setblock $($px - 2) $($gy + 2) $pz minecraft:redstone_lamp
setblock $($px - 2) $($gy + 1) $($pz + 2) minecraft:barrel
setblock $($px - 2) $($gy + 2) $($pz + 2) minecraft:comparator[facing=north]
setblock $($px - 2) $($gy + 3) $($pz + 2) minecraft:chest

# A 组：NoAI 实体（确定性实体层基线：属性固定、不参与 AI 随机）
summon minecraft:pig $($px - 6).5 $($gy + 1) $($pz - 4).5 {NoAI:1b,PersistenceRequired:1b,Silent:1b,Tags:["cava_static"]}
summon minecraft:pig $($px - 6).5 $($gy + 1) $($pz - 3).5 {NoAI:1b,PersistenceRequired:1b,Silent:1b,Tags:["cava_static"]}
summon minecraft:pig $($px - 6).5 $($gy + 1) $($pz - 2).5 {NoAI:1b,PersistenceRequired:1b,Silent:1b,Tags:["cava_static"]}
summon minecraft:cow $($px - 7).5 $($gy + 1) $($pz - 4).5 {NoAI:1b,PersistenceRequired:1b,Silent:1b,Tags:["cava_static"]}
summon minecraft:cow $($px - 7).5 $($gy + 1) $($pz - 3).5 {NoAI:1b,PersistenceRequired:1b,Silent:1b,Tags:["cava_static"]}
summon minecraft:cow $($px - 7).5 $($gy + 1) $($pz - 2).5 {NoAI:1b,PersistenceRequired:1b,Silent:1b,Tags:["cava_static"]}

# B 组：AI 实体（真正的寻路驱动：僵尸追村民）
summon minecraft:villager $($px + 6).5 $($gy + 1) $($pz + 3).5 {PersistenceRequired:1b,Silent:1b,Tags:["cava_ai"]}
summon minecraft:villager $($px + 6).5 $($gy + 1) $($pz + 4).5 {PersistenceRequired:1b,Silent:1b,Tags:["cava_ai"]}
summon minecraft:zombie $($px - 6).5 $($gy + 1) $($pz + 3).5 {PersistenceRequired:1b,Silent:1b,Tags:["cava_ai"]}
summon minecraft:zombie $($px - 6).5 $($gy + 1) $($pz + 4).5 {PersistenceRequired:1b,Silent:1b,Tags:["cava_ai"]}
summon minecraft:zombie $($px - 8).5 $($gy + 1) $($pz + 3).5 {PersistenceRequired:1b,Silent:1b,Tags:["cava_ai"]}

scoreboard objectives add cava_t dummy
scoreboard players set #t cava_t 0
schedule function cava:tick_loop 1t
say [cava-parity] scenario placed at $px,$gy,$pz
"@
$scenario | Set-Content -Encoding ASCII (Join-Path $base 'data\cava\functions\scenario.mcfunction')

@'
# 脚本驱动的红石激励源：每 2 tick 在 redstone_block / air 之间翻转（全 tick 对齐，无随机）
scoreboard players add #t cava_t 1
execute if score #t cava_t matches 2.. run scoreboard players set #t cava_t 0
execute if score #t cava_t matches 0 run setblock %PX% %GY1% %PZ% minecraft:redstone_block
execute if score #t cava_t matches 1 run setblock %PX% %GY1% %PZ% minecraft:air
schedule function cava:tick_loop 1t
'@.Replace('%PX%', [string]($px - 2)).Replace('%GY1%', [string]($gy + 1)).Replace('%PZ%', [string]$pz) |
  Set-Content -Encoding ASCII (Join-Path $base 'data\cava\functions\tick_loop.mcfunction')

# --- load 链：freeze -> scenario ->（可选）tick sprint ---
# 为什么把 scenario 与 sprint **都放进 load 函数**（而不是 RCON 之后再发）：
# 采样器在 SERVER_STARTED 就开始逐 tick 落盘，而 RCON 到达的时刻相对"第几个采样 tick"是不确定的
# （实测：off-a 与 off-b 的实体在第 2 tick 就差了 3 只僵尸）。放进 load 函数后，
# 第 0 个采样 tick 时场景已经在位，sprint 的 600 tick 就是采样器看到的全部内容。
$loadLines = @('function cava:scenario')
if ($SprintTicks -gt 0) { $loadLines += "tick sprint $SprintTicks" }
@'
# runs from the #minecraft:load tag
tick freeze
save-off
gamerule doDaylightCycle false
gamerule doWeatherCycle false
gamerule doMobSpawning false
gamerule doFireTick false
gamerule randomTickSpeed 0
gamerule sendCommandFeedback false
time set midnight
weather clear
'@.Trim() | Set-Content -Encoding ASCII (Join-Path $base 'data\cava\functions\freeze.mcfunction')
Add-Content -Encoding ASCII (Join-Path $base 'data\cava\functions\freeze.mcfunction') $loadLines

'{"values":["cava:freeze"]}' |
  Set-Content -Encoding ASCII (Join-Path $base 'data\minecraft\tags\functions\load.json')

Write-Host "[parity-datapack] installed into $base"
Get-ChildItem -LiteralPath $base -Recurse -File | ForEach-Object { Write-Host ("   " + $_.FullName.Substring($base.Length + 1)) }
