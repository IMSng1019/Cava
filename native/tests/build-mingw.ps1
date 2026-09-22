<#
build-mingw.ps1 —— P0-B 原生核心的 MinGW 构建/自测脚本（不依赖 CMake，随时可用）。

它做这些事（全部用实测可用的绝对路径；pwsh 每次都是新进程，脚本内自带全部路径）：
  1. release 旗标编 cava_selftest.exe（源码直编进可执行文件，不走动态链接）
  2. -DCAVA_SAFE=1 再编一个 cava_selftest_safe.exe（验证 SAFE 断言行为）
  3. -std=c++20 再编一个 cava_selftest_cxx20.exe（根 CMakeLists 用的是 CXX_STANDARD 20）
  4. 编 cava_fp_probe.exe，生成 native/tests/vectors/fp_probe.txt
  5. 编 natives/windows-x64/cava.dll（交付物），并检查导入表里没有 MinGW 运行时 DLL
  6. 编 cava_dll_loadtest.exe，用**干净 PATH** 动态加载 cava.dll 调一遍全部 ABI
  7. cava_selftest --dump-layout -> native/tests/vectors/layout_expected.txt（Java 侧黄金参考）
  8. 跑 JDK 21 的 FpProbeJava 做 + - * / sqrt 的逐位比对

固定编译旗标（契约 2.1，禁止改动）：
  -O2 -fwrapv -ffp-contract=off -fno-fast-math     （禁 -march=native / -ffast-math / -Ofast）

用法：
  pwsh -File native/tests/build-mingw.ps1            # 编 + 跑全套
  pwsh -File native/tests/build-mingw.ps1 -SkipRun   # 只编
#>
param(
    [switch]$SkipRun
)

$ErrorActionPreference = 'Stop'

$Gpp      = 'C:\mingw64\bin\g++.exe'
$Objdump  = 'C:\mingw64\bin\objdump.exe'
$Java     = 'C:\Program Files\Java\jdk-21\bin\java.exe'
$Root     = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$SrcDir   = Join-Path $Root 'native\src'
$TestDir  = Join-Path $Root 'native\tests'
$BuildDir = Join-Path $Root 'native\build\mingw'
$Natives  = Join-Path $Root 'natives\windows-x64'
$VecDir   = Join-Path $TestDir 'vectors'

New-Item -ItemType Directory -Force -Path $BuildDir | Out-Null
New-Item -ItemType Directory -Force -Path $Natives  | Out-Null
New-Item -ItemType Directory -Force -Path $VecDir   | Out-Null

# g++ 会把中间 .o 写到 TMP/TEMP；本机沙箱下系统 temp 目录不可用（实测 as.exe 报
# "can't create C:\Users\...\Temp\dsh-xxxx\ccXXXX.o: No such file or directory"），
# 所以显式指到工作区内。
$TmpDir = Join-Path $Root 'native\build\tmp'
New-Item -ItemType Directory -Force -Path $TmpDir | Out-Null
$env:TMP  = $TmpDir
$env:TEMP = $TmpDir

# -static*：cava.dll 必须**自带** libstdc++/libgcc/winpthread，否则 Java 侧在没装 MinGW 的
# 机器上 LoadLibrary 会失败（本机实测：干净 PATH 下报 error 126）。
$Common = @(
    '-std=c++17',
    '-O2',
    '-fwrapv',
    '-ffp-contract=off',
    '-fno-fast-math',
    '-Wall',
    '-Wextra',
    '-static',
    '-static-libgcc',
    '-static-libstdc++'
)
$Release   = $Common + @('-DNDEBUG')
$Safe      = $Common + @('-DCAVA_SAFE=1')
$Release20 = @('-std=c++20') + ($Common | Where-Object { $_ -ne '-std=c++17' }) + @('-DNDEBUG')

# 递归收集：native/src 下任何子目录的 .cpp 都算（P0-A 的 CMake 用 GLOB_RECURSE）
$SrcFiles = @(Get-ChildItem -Path $SrcDir -Recurse -Filter '*.cpp' | ForEach-Object { $_.FullName })
if ($SrcFiles.Count -eq 0) { throw "no sources under $SrcDir" }
Write-Host "sources: $($SrcFiles.Count) files"
$SrcFiles | ForEach-Object { Write-Host "  $_" }

function Invoke-Step([string]$Label, [string[]]$Argv) {
    Write-Host ""
    Write-Host ">>> $Label"
    Write-Host "    $Gpp $($Argv -join ' ')"
    & $Gpp @Argv
    if ($LASTEXITCODE -ne 0) { throw "$Label failed with exit code $LASTEXITCODE" }
}

Invoke-Step 'selftest (release/c++17)'  ($Release   + @('-o', (Join-Path $BuildDir 'cava_selftest.exe'),        (Join-Path $TestDir 'cava_selftest.cpp'))    + $SrcFiles)
Invoke-Step 'selftest (CAVA_SAFE=1)'    ($Safe      + @('-o', (Join-Path $BuildDir 'cava_selftest_safe.exe'),   (Join-Path $TestDir 'cava_selftest.cpp'))    + $SrcFiles)
Invoke-Step 'selftest (c++20)'          ($Release20 + @('-o', (Join-Path $BuildDir 'cava_selftest_cxx20.exe'),  (Join-Path $TestDir 'cava_selftest.cpp'))    + $SrcFiles)
Invoke-Step 'fp_probe'                  ($Release   + @('-o', (Join-Path $BuildDir 'cava_fp_probe.exe'),       (Join-Path $TestDir 'cava_fp_probe.cpp'))    + $SrcFiles)
Invoke-Step 'dll loadtest'              ($Release   + @('-o', (Join-Path $BuildDir 'cava_dll_loadtest.exe'),   (Join-Path $TestDir 'cava_dll_loadtest.cpp')))

$Dll = Join-Path $Natives 'cava.dll'
Invoke-Step 'cava.dll (shared)'         ($Release   + @('-shared', '-o', $Dll) + $SrcFiles)

# ---- 导入表检查：交付物不能依赖 MinGW 运行时 DLL ----
Write-Host ""
Write-Host ">>> import table check: $Dll"
$importLines = & $Objdump -p $Dll | Select-String 'DLL Name' | ForEach-Object { $_.Line.Trim() }
$importLines | ForEach-Object { Write-Host "    $_" }
$badImports = @($importLines | Where-Object { $_ -match 'libstdc|libgcc|libwinpthread|libatomic' })
if ($badImports.Count -gt 0) {
    throw "cava.dll 依赖 MinGW 运行时 DLL（Java 在没装 MinGW 的机器上会加载失败）：$($badImports -join '; ')"
}
Write-Host "    OK: 只有系统 DLL"

if ($SkipRun) { Write-Host ""; Write-Host '-SkipRun: 只编译，不跑。'; exit 0 }

Write-Host ""
Write-Host '=== run cava_selftest.exe (release) ==='
& (Join-Path $BuildDir 'cava_selftest.exe')
$rcRelease = $LASTEXITCODE
Write-Host "exit code = $rcRelease"

Write-Host ""
Write-Host '=== run cava_selftest_safe.exe (CAVA_SAFE=1) ==='
& (Join-Path $BuildDir 'cava_selftest_safe.exe')
$rcSafe = $LASTEXITCODE
Write-Host "exit code = $rcSafe"

Write-Host ""
Write-Host '=== run cava_selftest_cxx20.exe (c++20) ==='
& (Join-Path $BuildDir 'cava_selftest_cxx20.exe') | Select-Object -Last 3
$rc20 = $LASTEXITCODE
Write-Host "exit code = $rc20"

$vec = Join-Path $VecDir 'fp_probe.txt'
Write-Host ""
Write-Host "=== run cava_fp_probe.exe -> $vec ==="
& (Join-Path $BuildDir 'cava_fp_probe.exe') $vec
$rcProbe = $LASTEXITCODE
Write-Host "exit code = $rcProbe"

$layoutRef = Join-Path $VecDir 'layout_expected.txt'
Write-Host ""
Write-Host "=== dump layout reference -> $layoutRef ==="
& (Join-Path $BuildDir 'cava_selftest.exe') --dump-layout $layoutRef
$rcLayout = $LASTEXITCODE
Write-Host "exit code = $rcLayout"

# ---- 动态加载测试：故意用干净 PATH，证明 DLL 自包含 ----
Write-Host ""
Write-Host "=== run cava_dll_loadtest.exe (clean PATH) $Dll ==="
$savedPath = $env:PATH
$env:PATH = 'C:\Windows\system32;C:\Windows'
& (Join-Path $BuildDir 'cava_dll_loadtest.exe') $Dll
$rcLoad = $LASTEXITCODE
$env:PATH = $savedPath
Write-Host "exit code = $rcLoad"

$javaSrc = Join-Path $TestDir 'java\FpProbeJava.java'
$rcJava = 0
if (Test-Path $javaSrc) {
    Write-Host ""
    Write-Host '=== run FpProbeJava (JDK 21) ==='
    & $Java $javaSrc $vec
    $rcJava = $LASTEXITCODE
    Write-Host "exit code = $rcJava"
}

if ($rcRelease -ne 0 -or $rcSafe -ne 0 -or $rc20 -ne 0 -or $rcProbe -ne 0 -or $rcLoad -ne 0) {
    throw "one of the runs failed: release=$rcRelease safe=$rcSafe cxx20=$rc20 probe=$rcProbe load=$rcLoad"
}
Write-Host ""
Write-Host "ALL DONE. (FpProbeJava exit=$rcJava —— 1 表示存在 NaN 载荷差异，见 docs/CAVA-native-notes.md)"
