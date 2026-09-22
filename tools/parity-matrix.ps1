<#
Cava 差分测试（prompts/03）**mod 组合矩阵**：至少三档
  ① 纯原版（fabric-api + cava，无任何优化 mod）
  ② +整合包（copy-mods.ps1 -Profile full-minus-must-off）
  ③ +整合包且 native on/off  ← ② 的 on/off 就是 ③

每一档都跑「off × 2（确定性前置）+ on × 1」，再由 TraceDiff 出报告。
世界**共用同一个 snapshot**（parity-base）：区块是预生成好的，所以换 mod 集不会改变世界内容，
两侧比的就真的只是 "native on/off + mod 集"。

用法：
  pwsh -File tools/parity-matrix.ps1 -Profile vanilla -Ticks 300 -Prefix van-
  pwsh -File tools/parity-matrix.ps1 -Profile modpack -Ticks 300 -Prefix mp-
#>
[CmdletBinding()]
param(
  [ValidateSet('vanilla', 'modpack')][string]$Profile = 'modpack',
  [int]$Ticks = 300,
  [string]$Prefix = '',
  [string]$Root = '',
  [switch]$SkipStage,
  [switch]$SkipRuns
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$repo = Split-Path $PSScriptRoot -Parent
if (-not $Root) { $Root = Join-Path $repo 'testbed\parity' }
if (-not $Prefix) { $Prefix = if ($Profile -eq 'vanilla') { 'van-' } else { 'mp-' } }
$modsDir = Join-Path $Root 'server\mods'
$cavaJar = Join-Path $repo 'build\libs\cava-0.1.0.jar'
if (-not (Test-Path -LiteralPath $cavaJar)) { throw "没有 cava jar：$cavaJar（先 .\gradlew.bat build）" }

if (-not $SkipStage) {
  Write-Host "### 档 $Profile：重新铺 mods"
  if ($Profile -eq 'vanilla') {
    & (Join-Path $PSScriptRoot 'copy-mods.ps1') -Wave 0 -Dest $modsDir | Select-Object -Last 6 | ForEach-Object { Write-Host "  $_" }
  } else {
    & (Join-Path $PSScriptRoot 'copy-mods.ps1') -Profile 'full-minus-must-off' -Dest $modsDir | Select-Object -Last 6 | ForEach-Object { Write-Host "  $_" }
  }
  Copy-Item -LiteralPath $cavaJar -Destination $modsDir -Force
  Write-Host ("  mods = {0} 个 jar（含 cava）" -f (Get-ChildItem -LiteralPath $modsDir -Filter *.jar).Count)
}

$legA = $Prefix + 'off-a'
$legB = $Prefix + 'off-b'
$legC = $Prefix + 'on-a'
if (-not $SkipRuns) {
  foreach ($leg in @($legA, $legB, $legC)) {
    $isOn = $leg -match '(^|-)on(-|$)'
    Write-Host ''
    Write-Host "################ [$Profile] 腿 $leg ################"
    $nat = if ($isOn) { 'on' } else { 'off' }
    $pf = if ($isOn) { 'on' } else { 'default' }
    & (Join-Path $PSScriptRoot 'parity-scenario.ps1') -Root $Root -Leg $leg -Ticks $Ticks -Snapshot 'parity-base' -Native $nat -PathfindNative $pf
    if ($LASTEXITCODE -ne 0) { throw "腿 $leg 失败" }
  }
}

& (Join-Path $PSScriptRoot 'parity-diff.ps1') -Root $Root -SkipRuns -Legs @($legA, $legB, $legC) -LegA $legA -LegB $legB -LegC $legC
exit $LASTEXITCODE
