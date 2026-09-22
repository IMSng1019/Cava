<#
Cava 差分测试（prompts/03）单元层：**一条命令**跑出「oracle 参照实现 vs 原生内核」的差异报告。

两条腿都要跑，缺一不可（分工见 docs/CAVA-parity-notes.md）：
  腿 1  C++ 侧 cava_pathfind_vectors：直接调内核 solve()，10 条 shard 全字段逐位（含 ABI 导不出的
        pathLength / distance / penalty / visited / expanded / traceHash / manhattan / reachesTarget）。
        本脚本**现场重新编译**它（不信任仓库里任何旧 exe：实测 native/build/mingw 下那个是 14:25 的
        陈旧产物，当时 ABI 还返回 CAVA_ERR_UNIMPLEMENTED —— 拿它当"已通过"是错的）。
  腿 2  Java 侧 cava.parity.PathfindVectorDiff：走生产路径 Java → FFM → C ABI
        （state_table_upload / region_upload / mob_profile_upload / cava_pathfind）。ABI 胶水层
        （palette→状态表、caps 位映射、penalty 索引、node_budget）只有这条腿覆盖。
  腿 3  负控制：故意把 CAN_SWIM 填到错误的 caps 位，腿 2 **必须**报差异（证明比对器不是瞎的）。

用法：
  pwsh -File tools/parity-unit.ps1                 # 全量 10000 组
  pwsh -File tools/parity-unit.ps1 -MaxCases 500   # 快速回归
  pwsh -File tools/parity-unit.ps1 -SkipCxx        # 只跑 Java 腿
#>
[CmdletBinding()]
param(
  [int]$MaxCases = 0,
  [string]$Vectors = '',
  [string]$Dll = '',
  [string]$Slf4j = '',
  [switch]$SkipCxx,
  [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$root = Split-Path $PSScriptRoot -Parent
if (-not $Vectors) { $Vectors = Join-Path $root 'src\test\resources\cava\oracle' }
if (-not $Dll) { $Dll = Join-Path $root 'natives\windows-x64\cava.dll' }
if (-not $Slf4j) { $Slf4j = Join-Path $root 'testbed\server\libraries\org\slf4j\slf4j-api\2.0.7\slf4j-api-2.0.7.jar' }
if (-not (Test-Path -LiteralPath $Slf4j)) {
  $cand = Get-ChildItem -Path (Join-Path $root '.gradle-home') -Recurse -Filter 'slf4j-api-2*.jar' -ErrorAction SilentlyContinue |
    Select-Object -First 1
  if ($cand) { $Slf4j = $cand.FullName } else { throw "找不到 slf4j-api jar（-Slf4j 指定）: $Slf4j" }
}
$javac = 'C:\Program Files\Java\jdk-21\bin\javac.exe'
$java = 'C:\Program Files\Java\jdk-21\bin\java.exe'
$out = Join-Path $root 'build\parity-unit\classes'
New-Item -ItemType Directory -Force -Path $out | Out-Null
$env:JAVA_TOOL_OPTIONS = '-Duser.language=en -Dfile.encoding=UTF-8'
function Fwd([string]$p) { return ($p -replace '\\', '/') }

$verdict = [ordered]@{}

# ---------------- 腿 1：C++ 直接差分（全字段） ----------------
if (-not $SkipCxx) {
  Write-Host ''
  Write-Host '================ 腿 1/3：C++ 直接调 solve()，全字段逐位 ================'
  $exe = Join-Path $root 'build\native-pathfind\cava_pathfind_vectors.exe'
  if (-not $SkipBuild) {
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root 'native\tests\build-pathfind.ps1') 2>&1 |
      Select-Object -Last 3 | ForEach-Object { Write-Host "  $_" }
    if ($LASTEXITCODE -ne 0) { throw 'native/tests/build-pathfind.ps1 失败' }
  }
  if (-not (Test-Path -LiteralPath $exe)) { throw "没有 exe: $exe（去掉 -SkipBuild）" }
  Write-Host "  exe: $exe  ($((Get-Item -LiteralPath $exe).LastWriteTime))"
  $cxxArgs = @($Vectors)
  if ($MaxCases -gt 0) { $cxxArgs += @([string]$MaxCases) }
  $cxxOut = & $exe @cxxArgs 2>&1
  $cxxExit = $LASTEXITCODE
  $cxxOut | Select-Object -Last 8 | ForEach-Object { Write-Host "  $_" }
  $verdict['cxx'] = if ($cxxExit -eq 0) { 'PASS' } else { "FAIL(exit=$cxxExit)" }
} else { $verdict['cxx'] = 'SKIPPED' }

# ---------------- 腿 2/3：Java → FFM → C ABI ----------------
Write-Host ''
Write-Host '================ 腿 2/3：Java → FFM → C ABI（生产路径）================'
if (-not (Test-Path -LiteralPath $Dll)) { throw "没有原生库: $Dll（先 .\gradlew.bat buildNative）" }
$src = @()
$src += (Get-ChildItem -LiteralPath (Join-Path $root 'src\main\java\cava\ffm') -Filter *.java).FullName
$src += (Join-Path $root 'src\main\java\cava\mirror\MirrorFlags.java')
$src += (Join-Path $root 'src\main\java\cava\mirror\PathTypes.java')
$src += (Get-ChildItem -LiteralPath (Join-Path $root 'src\test\java\cava\oracle') -Filter *.java).FullName
$src += (Join-Path $root 'src\test\java\cava\parity\PathfindVectorDiff.java')
# javac 的 @argfile 把反斜杠当转义（实测 "J:mcCava..."），一律写正斜杠
$jarg = Join-Path $root 'build\parity-unit\javac.args'
$lines = @('-encoding', 'UTF-8', '--release', '21', '--enable-preview', '-nowarn',
  '-d', ('"' + (Fwd $out) + '"'), '-cp', ('"' + (Fwd $Slf4j) + '"'))
$lines += ($src | ForEach-Object { '"' + (Fwd $_) + '"' })
Set-Content -LiteralPath $jarg -Value $lines -Encoding UTF8
& $javac ('@' + $jarg) 2>&1 | Select-String -Pattern 'error' | Select-Object -First 20 | ForEach-Object { Write-Host "  $_" }
if ($LASTEXITCODE -ne 0) { throw "javac 失败（argfile: $jarg）" }

$jarg2 = Join-Path $root 'build\parity-unit\java.args'
$lines = @('--enable-preview', '--enable-native-access=ALL-UNNAMED',
  '-Duser.language=en', '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
  '-Dcava.native.enabled=true', ('-Dcava.native.path=' + (Fwd $Dll)),
  '-cp', ('"' + (Fwd $out) + ';' + (Fwd $Slf4j) + '"'),
  'cava.parity.PathfindVectorDiff', ('"' + (Fwd $Vectors) + '"'))
if ($MaxCases -gt 0) { $lines += [string]$MaxCases }
$lines += @('--verbose', '--selftest')
Set-Content -LiteralPath $jarg2 -Value $lines -Encoding UTF8
$sw = [System.Diagnostics.Stopwatch]::StartNew()
$jout = & $java ('@' + $jarg2) 2>&1
$jexit = $LASTEXITCODE
$sw.Stop()
$jout | Where-Object { $_ -notmatch '^SLF4J' } | ForEach-Object { Write-Host "  $_" }
Write-Host ("  （Java 腿耗时 {0:n1}s）" -f $sw.Elapsed.TotalSeconds)
$verdict['java'] = if ($jexit -eq 0) { 'PASS' } else { "FAIL(exit=$jexit)" }
$verdict['negative'] = if ($jout -match '负控制结果: PASS') { 'PASS' } else { 'FAIL（见上）' }

Write-Host ''
Write-Host '================ 汇总 ================'
foreach ($k in $verdict.Keys) { Write-Host ("  {0,-10} {1}" -f $k, $verdict[$k]) }
$allOk = ($verdict.Values | Where-Object { $_ -like 'FAIL*' }).Count -eq 0
Write-Host ("  总判定     " + $(if ($allOk) { 'PASS' } else { 'FAIL' }))
exit $(if ($allOk) { 0 } else { 1 })
