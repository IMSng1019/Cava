<#
Cava 差分测试（prompts/03）场景层：**一条命令**跑出「native on 与 off 的逐 tick 差异报告」。

  pwsh -File tools/parity-diff.ps1 -Ticks 600

它会：
  1. 跑 N 条腿（默认 off-a, off-b, on-a）—— off-a/off-b 是**确定性前置验证**
     （同一配置跑两次；比对器如果在"同配置两次"下都报差异，那么它本身不可信，先修比对器）
  2. 用 cava.parity.TraceDiff 比对：(off-a vs off-b) 与 (off-a vs on-a)
  3. 零差异打印 ZERO DIFF over N ticks；有差异打印首个差异 tick / 子系统 / 实体键 / 区块坐标

腿脚本：tools/parity-scenario.ps1（私有测试服 testbed/parity，私有端口 25591/25592）
#>
[CmdletBinding()]
param(
  [int]$Ticks = 600,
  [string]$Root = '',
  [string[]]$Legs = @('off-a', 'off-b', 'on-a'),
  [string]$LegA = 'off-a',
  [string]$LegB = 'off-b',
  [string]$LegC = 'on-a',
  [switch]$SkipRuns,
  [switch]$IncludeEntities
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$repo = Split-Path $PSScriptRoot -Parent
if (-not $Root) { $Root = Join-Path $repo 'testbed\parity' }
$tracesDir = Join-Path $Root 'traces'

if (-not $SkipRuns) {
  foreach ($leg in $Legs) {
    $isOn = $leg -match '(^|-)on(-|$)'
    $native = if ($isOn) { 'on' } else { 'off' }
    $pf = if ($isOn) { 'on' } else { 'default' }
    Write-Host ''
    Write-Host "################ 腿 $leg （native=$native pathfind=$pf）################"
    & (Join-Path $PSScriptRoot 'parity-scenario.ps1') -Leg $leg -Ticks $Ticks -Root $Root -Native $native -PathfindNative $pf
    if ($LASTEXITCODE -ne 0) { throw "腿 $leg 失败" }
  }
}

# ---- 编译 TraceDiff（它是纯 JDK 的离线比对器，不需要 Gradle）----
$classes = Join-Path $repo 'build\parity-unit\classes'
$mainClasses = Join-Path $repo 'build\classes\java\main'
$testClasses = Join-Path $repo 'build\classes\java\test'
$out = Join-Path $repo 'build\parity-diff\classes'
New-Item -ItemType Directory -Force -Path $out | Out-Null
$env:JAVA_TOOL_OPTIONS = '-Duser.language=en -Dfile.encoding=UTF-8'
function Fwd([string]$p) { return ($p -replace '\\', '/') }
$arg = Join-Path $repo 'build\parity-diff\javac.args'
$lines = @('-encoding', 'UTF-8', '--release', '21', '--enable-preview', '-nowarn', '-d', ('"' + (Fwd $out) + '"'))
$lines += @('"' + (Fwd (Join-Path $repo 'src\main\java\cava\parity\Fnv1a.java')) + '"')
$lines += @('"' + (Fwd (Join-Path $repo 'src\test\java\cava\parity\TraceDiff.java')) + '"')
Set-Content -LiteralPath $arg -Value $lines -Encoding UTF8
& 'C:\Program Files\Java\jdk-21\bin\javac.exe' ('@' + $arg) 2>&1 | Select-String -Pattern 'error' | ForEach-Object { Write-Host "  $_" }
if ($LASTEXITCODE -ne 0) { throw 'TraceDiff 编译失败' }

$java = 'C:\Program Files\Java\jdk-21\bin\java.exe'
$flag = if ($IncludeEntities) { '--entities' } else { '--no-entities' }
$verdicts = @()
# 注意：**不能**叫 Compare —— PowerShell 里 compare 是 Compare-Object 的别名，而别名的优先级高于函数，
# 于是 Compare a b c 会被解析成 Compare-Object（只吃两个位置参数）。本机实测踩过这一次。
function Invoke-TraceCompare([string]$a, [string]$b, [string]$title) {
  $pa = Join-Path $tracesDir "$a\trace-$a.ndjson"
  $pb = Join-Path $tracesDir "$b\trace-$b.ndjson"
  Write-Host ''
  Write-Host "================ $title ================"
  Write-Host "  a=$pa"
  Write-Host "  b=$pb"
  $jarg = Join-Path $repo 'build\parity-diff\java.args'
  $jl = @('--enable-preview', '-Duser.language=en', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
    '-cp', ('"' + (Fwd $out) + '"'), 'cava.parity.TraceDiff', ('"' + (Fwd $pa) + '"'), ('"' + (Fwd $pb) + '"'), $flag)
  Set-Content -LiteralPath $jarg -Value $jl -Encoding UTF8
  $res = & $java ('@' + $jarg) 2>&1
  $code = $LASTEXITCODE
  $res | ForEach-Object { Write-Host "  $_" }
  return [pscustomobject]@{ title = $title; exit = $code; zero = ($res -match 'ZERO DIFF over') }
}

$r1 = Invoke-TraceCompare $LegA $LegB '确定性前置：同一配置（native off）跑两次'
$r2 = Invoke-TraceCompare $LegA $LegC 'native on vs off'
Write-Host ''
Write-Host '================ 汇总 ================'
Write-Host ("  {0,-42} {1}" -f $r1.title, $(if ($r1.zero) { 'ZERO DIFF' } else { "DIFF（exit=$($r1.exit)）" }))
Write-Host ("  {0,-42} {1}" -f $r2.title, $(if ($r2.zero) { 'ZERO DIFF' } else { "DIFF（exit=$($r2.exit)）" }))
Write-Host ''
if (-not $r1.zero) {
  Write-Host '  !! 确定性前置不通过：同一配置两次都不同 ⇒ 先修场景/比对器，on/off 的结论不可用'
  exit 3
}
exit $(if ($r2.zero) { 0 } else { 1 })
