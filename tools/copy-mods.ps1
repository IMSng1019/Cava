<#
Cava P0-E: stage the 49-jar server pack into the testbed with the "three tier" policy
from docs/CAVA-服务器模组清单.md section 5.

Tiers
  keep      - copied into testbed\server\mods\
  noise     - MUST OFF per doc section 5 (heavy non-optimisation noise / mirror threat)
  suggested - SUGGESTED OFF per doc section 5 (needs a client / skews entity load)
  clientenv - env=client, never loaded by a dedicated server (mis-installed)

Waves (cumulative, so each wave can be smoke-tested before adding the next):
  0  fabric-api + spark                                  (E1 smoke test)
  1  + libraries
  2  + core generic optimisation mods
  3  + carpet trio (carpet / gca / tis-addition)
  4  + functional mods               == the full "keep" set
  all == keep + suggested + a single extra probe set (see -Probe)

Usage:
  pwsh -File tools/copy-mods.ps1 -Wave 4
  pwsh -File tools/copy-mods.ps1 -Profile full-minus-must-off
  pwsh -File tools/copy-mods.ps1 -Probe easyauth,voicechat   # keep set + these
#>
[CmdletBinding()]
param(
  [int]$Wave = 4,
  [string]$Profile = '',
  [string]$Probe = '',
  [string]$Source = '',
  [string]$Dest = '',
  [switch]$PassThru
)

$ErrorActionPreference = 'Stop'
if (-not $Source) { $Source = Join-Path $PSScriptRoot '..\优化模组\服务端模组' }
if (-not $Dest)   { $Dest   = Join-Path $PSScriptRoot '..\testbed\server\mods' }
$Source = (Resolve-Path $Source).Path
$DisabledRoot = Join-Path (Split-Path $Dest -Parent | Split-Path -Parent) 'disabled'

# --- classification table -------------------------------------------------
# match = substring of the jar file name; tier/wave/reason are recorded verbatim in docs/CAVA-baseline.md
$table = @(
  # wave 0 - the minimum needed to boot + profile
  @{ m='fabric-api-';             tier='keep'; wave=0; note='硬前置' }
  @{ m='server-spark-';           tier='keep'; wave=0; note='P0 基线剖析工具' }
  # wave 1 - libraries / prerequisites
  @{ m='fabric-language-kotlin';  tier='keep'; wave=1; note='库：Kotlin 运行时' }
  @{ m='architectury-';           tier='keep'; wave=1; note='库：跨平台抽象' }
  @{ m='cloth-config-';           tier='keep'; wave=1; note='库：getittogetherdrops 硬依赖' }
  @{ m='YetAnotherConfigLib';     tier='keep'; wave=1; note='库：YACL' }
  @{ m='Necronomicon-';           tier='keep'; wave=1; note='库：配置/文本/NBT' }
  @{ m='Jupiter-';                tier='keep'; wave=1; note='库：配置同步，零 mixin' }
  # wave 2 - generic optimisation nine + friends
  @{ m='lithium-fabric';          tier='keep'; wave=2; note='优化：寻路/实体/红石/镜像 均有占用' }
  @{ m='servercore-fabric';       tier='keep'; wave=2; note='优化：寻路体内补丁 + 实体短路' }
  @{ m='vmp-fabric';              tier='keep'; wave=2; note='优化：Entity.move 短路 + 调色板去锁' }
  @{ m='krypton-';                tier='keep'; wave=2; note='优化：仅网络栈' }
  @{ m='ferritecore-';            tier='keep'; wave=2; note='优化：方块状态去重（镜像契约相关）' }
  @{ m='memoryleakfix';           tier='keep'; wave=2; note='修复：1.20.4 只生效 Biome 温度缓存' }
  @{ m='c2me-fabric';             tier='keep'; wave=2; note='优化：区块线程与调度' }
  @{ m='noisium-fabric';          tier='keep'; wave=2; note='优化：生成期（调用点）' }
  @{ m='starlight-';              tier='keep'; wave=2; note='优化：光照，压 LevelChunk.setBlockState 调用点' }
  @{ m='packetfixer-';            tier='keep'; wave=2; note='修复：网络栈' }
  @{ m='Icterine-';               tier='keep'; wave=2; note='优化：进度判定' }
  @{ m='getittogetherdrops';      tier='keep'; wave=2; note='优化：掉落物合并（影响实体数量）' }
  @{ m='server-Chunky-';          tier='keep'; wave=2; note='工具：预生成（本次不执行预生成）' }
  # wave 3 - carpet trio
  @{ m='fabric-carpet-';          tier='keep'; wave=3; note='调试+规则：/tick /player /log 全靠它' }
  @{ m='gugle-carpet-addition';   tier='keep'; wave=3; note='Carpet 附加：假人/交互便利' }
  @{ m='carpet-tis-addition';     tier='keep'; wave=3; note='核心 logger：/log microTiming /log movement' }
  # wave 4 - functional
  @{ m='LuckPerms-Fabric';        tier='keep'; wave=4; note='权限（EasyAuth/Geyser 的软依赖）' }
  @{ m='minimotd-fabric';         tier='keep'; wave=4; note='服务器列表 MOTD，开销≈0' }
  @{ m='NoChatReports-FABRIC';    tier='keep'; wave=4; note='功能：聊天签名' }
  @{ m='server-customname-';      tier='keep'; wave=4; note='功能：实体自定义名' }
  @{ m='vanilla-permissions-';    tier='keep'; wave=4; note='功能：原版权限开关' }
  @{ m='easybot-fabric';          tier='keep'; wave=4; note='功能：QQ 桥（非假人 mod，不 tick 实体）' }
  @{ m='servux-fabric';           tier='keep'; wave=4; note='功能：给 masa 客户端供结构数据' }
  @{ m='fix-mc-stats-';           tier='keep'; wave=4; note='修复：统计（碰 ServerPlayerEntity.travel）' }
  @{ m='fuji-';                   tier='keep'; wave=4; note='功能+2 性能模块（tick_chunk_cache 需注意）' }
  # --- must off (doc section 5) ---
  @{ m='server-BlueMap-';         tier='noise'; wave=-1; note='清单5必须关：网页地图，最大 CPU/IO 噪声源，零交互' }
  @{ m='server-Axiom-';           tier='noise'; wave=-1; note='清单5必须关：直写 ChunkSection + 非主线程光照，镜像唯一真威胁' }
  @{ m='server-ledger-1';         tier='noise'; wave=-1; note='清单5必须关：每次方块变更加派发入队 + 持续 DB 写' }
  @{ m='server-ledger-databases'; tier='noise'; wave=-1; note='清单5必须关：Ledger 的 JDBC 打包依赖，随 Ledger 一起关' }
  @{ m='server-geyser-';          tier='noise'; wave=-1; note='清单5必须关：第二套协议栈与额外实体' }
  @{ m='automodpack-';            tier='noise'; wave=-1; note='清单5必须关：启动期扫描 + 内嵌 Netty 分发' }
  @{ m='server-randomtp-';        tier='noise'; wave=-1; note='清单5必须关：一执行就同步加载数百区块' }
  @{ m='server-fabric-MCMOD-';    tier='noise'; wave=-1; note='清单5必须关：定时 System.gc() 污染 tick 基线' }
  # --- suggested off (doc section 5) ---
  @{ m='server-floodgate-';       tier='suggested'; wave=-1; note='清单5建议关：基岩版支持，无基岩客户端时零收益' }
  @{ m='server-syncmatica-';      tier='suggested'; wave=-1; note='清单5建议关：投影共享，测试期无 litematica 客户端' }
  @{ m='voicechat-fabric';        tier='suggested'; wave=-1; note='清单5建议关：语音需 UDP 与真实客户端' }
  @{ m='server-easyauth-';        tier='suggested'; wave=-1; note='清单5建议关：未登录玩家 playerTick 被整体取消，扭曲实体负载' }
  # --- client env mis-installed (doc section 3.3) ---
  @{ m='server-sodium-fabric';    tier='clientenv'; wave=-1; note='清单3.3：env=client，专用服务端不加载' }
  @{ m='server-continuity-';      tier='clientenv'; wave=-1; note='清单3.3：env=client，专用服务端不加载' }
  @{ m='server-CustomSkinLoader'; tier='clientenv'; wave=-1; note='清单3.3：env=client，专用服务端不加载' }
  @{ m='server-malilib-';         tier='clientenv'; wave=-1; note='清单3.3：env=client，专用服务端不加载' }
)

function Get-Tier($name) {
  foreach ($e in $table) { if ($name -like ('*' + $e.m + '*')) { return $e } }
  return $null
}

# extra probe matches (keep tier, forced on regardless of wave)
$probeList = @()
if ($Probe) { $probeList = $Probe.Split(',') | ForEach-Object { $_.Trim() } | Where-Object { $_ } }

$jars = Get-ChildItem $Source -Filter *.jar | Sort-Object Name
$plan = @()
foreach ($j in $jars) {
  $e = Get-Tier $j.Name
  if (-not $e) { $plan += [pscustomobject]@{ name=$j.Name; tier='UNCLASSIFIED'; on=$false; note='!! not in table !!' }; continue }
  $on = $false
  switch ($Profile) {
    'full-minus-must-off' { $on = ($e.tier -ne 'noise' -and $e.tier -ne 'clientenv') }
    'keep-only'           { $on = ($e.tier -eq 'keep') }
    ''                    { }
    default               { throw "unknown -Profile '$Profile'" }
  }
  if (-not $Profile -and $e.tier -eq 'keep' -and $e.wave -le $Wave -and $e.wave -ge 0) { $on = $true }
  foreach ($p in $probeList) { if ($j.Name -like ('*' + $p + '*')) { $on = $true } }
  $plan += [pscustomobject]@{ name=$j.Name; tier=$e.tier; on=$on; note=$e.note }
}

# --- apply ----------------------------------------------------------------
New-Item -ItemType Directory -Force -Path $Dest | Out-Null
# wipe the staging dir so a wave is exactly what the table says
Get-ChildItem $Dest -Filter *.jar -ErrorAction SilentlyContinue | Remove-Item -Force

foreach ($p in $plan) {
  $src = Join-Path $Source $p.name
  # -LiteralPath is mandatory here: two pack jars start with "[地毯] " / "[Gugle的Carpet附加包] ",
  # and Copy-Item -Path silently does NOTHING for a path containing [ ] (measured 2026-09-22).
  if ($p.on) {
    Copy-Item -LiteralPath $src -Destination (Join-Path $Dest $p.name) -Force
  } elseif ($p.tier -ne 'keep') {
    # a policy decision -> keep a copy of the jar outside mods/ so the decision is auditable
    $tierDir = Join-Path $DisabledRoot $p.tier
    New-Item -ItemType Directory -Force -Path $tierDir | Out-Null
    if (-not (Test-Path -LiteralPath (Join-Path $tierDir $p.name))) { Copy-Item -LiteralPath $src -Destination (Join-Path $tierDir $p.name) -Force }
  }
  # tier=keep but above the current wave -> simply not staged (not a policy decision)
}

# --- verify ---------------------------------------------------------------
# a silent copy failure is worse than a loud one; compare sizes for every staged jar
$bad = @()
foreach ($p in ($plan | Where-Object { $_.on })) {
  $dstFile = Join-Path $Dest $p.name
  if (-not (Test-Path -LiteralPath $dstFile)) { $bad += ("MISSING " + $p.name); continue }
  if ((Get-Item -LiteralPath $dstFile).Length -ne (Get-Item -LiteralPath (Join-Path $Source $p.name)).Length) { $bad += ("SIZE MISMATCH " + $p.name) }
}
if ($bad.Count) { $bad | ForEach-Object { Write-Host ("[copy-mods] !! " + $_) }; throw ("copy-mods: " + $bad.Count + " staged jar(s) failed verification") }

# --- report ---------------------------------------------------------------
$on  = $plan | Where-Object { $_.on }
$off = $plan | Where-Object { -not $_.on }
$held = $off | Where-Object { $_.tier -ne 'keep' }
$later = $off | Where-Object { $_.tier -eq 'keep' }
Write-Host ("[copy-mods] wave={0} profile='{1}' probe='{2}'" -f $Wave, $Profile, $Probe)
Write-Host ("[copy-mods] staged {0} / held-back {1} / not-yet-in-wave {2} / total {3}" -f $on.Count, $held.Count, $later.Count, $plan.Count)
Write-Host "[copy-mods] ENABLED:"
$on | ForEach-Object { Write-Host ("   + [{0}] {1}  -- {2}" -f $_.tier, $_.name, $_.note) }
Write-Host "[copy-mods] HELD BACK (policy):"
$held | ForEach-Object { Write-Host ("   - [{0}] {1}  -- {2}" -f $_.tier, $_.name, $_.note) }
Write-Host ("[copy-mods] NOT YET IN WAVE {0}: {1} jar(s)" -f $Wave, $later.Count)

# machine readable plan for docs / diffs
$planPath = Join-Path (Split-Path $Dest -Parent | Split-Path -Parent) 'mod-plan.tsv'
$plan | ForEach-Object { "{0}`t{1}`t{2}`t{3}" -f $_.name, $_.tier, $(if ($_.on) { 'ON' } else { 'OFF' }), $_.note } | Set-Content -Encoding UTF8 $planPath
Write-Host ("[copy-mods] plan written to {0}" -f $planPath)
if ($PassThru) { $plan }
