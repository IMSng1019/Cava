<#
build-fuzz.ps1 -- build + run the Cava frozen-ABI fuzz driver (P4-A).

Why not CMake: native/tests/CMakeLists.txt is owned by another stream; this driver needs its
own build line anyway (it is not a ctest target). It follows native/tests/build-entity.ps1:
g++ directly, no CMake.

What it does:
  1. builds native/tests/fuzz/cava_fuzz_abi.cpp (x86-64, fixed contract flags)
  2. builds a RELEASE rebuild and a CAVA_SAFE=1 build of the library from native/src into
     build/native-fuzz/ -- deliberately NOT into natives/<tag>/, because that directory is a
     shared output location (one build stream at a time; see docs/CAVA-gates.md)
  3. runs the same fuzz suite against:
       a) the SHIPPED artifact   natives/windows-x64/cava.dll
       b) the release rebuild    build/native-fuzz/cava_release.dll
       c) the SAFE build         build/native-fuzz/cava_safe.dll
  4. runs the SAFE assertion probe against (b) and (c) and prints the captured stderr, so the
     difference between "release: silent" and "SAFE: one stderr line + same error code" is
     visible in the transcript.

Usage:
  pwsh -NoProfile -ExecutionPolicy Bypass -File native/tests/fuzz/build-fuzz.ps1
  pwsh -NoProfile -ExecutionPolicy Bypass -File native/tests/fuzz/build-fuzz.ps1 -Cases 100000
  pwsh -NoProfile -ExecutionPolicy Bypass -File native/tests/fuzz/build-fuzz.ps1 -SkipBuild -Dll <path>

NOTE: keep this file ASCII-only (PowerShell 5.1 mis-parses UTF-8-without-BOM scripts whose
      comments contain non-ASCII bytes -- observed in an earlier round of this project).
#>
param(
    [int]$Cases = 20000,
    [string]$Seed = '0x5EEDC0DE5EEDC0DE',
    [switch]$SkipBuild,
    [string]$Dll = '',
    [switch]$EveryCase,
    [switch]$NoThreads,
    [int]$ThreadsPerRole = 2
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Stop'

# this script lives at native/tests/fuzz/ -> three levels up is the repo root
$root    = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$gxx     = 'C:\mingw64\bin\g++.exe'
if (-not (Test-Path $gxx)) { Write-Output "MISSING: $gxx"; exit 2 }

$outDir  = Join-Path $root 'build\native-fuzz'
$logDir  = Join-Path $outDir 'logs'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

# g++ writes its temporary .o files to TMP/TEMP; this machine's %TEMP% path contains
# non-ASCII characters and the assembler cannot create files there. Redirect it.
$tmpDir = Join-Path $outDir 'tmp'
New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null
$env:TMP = $tmpDir
$env:TEMP = $tmpDir

$flags = @(
    '-std=c++17', '-O2', '-fwrapv', '-ffp-contract=off', '-fno-fast-math',
    '-Wall', '-Wextra', '-Wno-unused-parameter', '-Wno-missing-field-initializers',
    '-static', '-static-libgcc', '-static-libstdc++'
)

$exe = Join-Path $outDir 'cava_fuzz_abi.exe'
$driverSrc = Join-Path $root 'native\tests\fuzz\cava_fuzz_abi.cpp'
$incDir = Join-Path $root 'native\include'

if (-not $SkipBuild) {
    Write-Output ">>> build driver"
    & $gxx @flags -I $incDir -o $exe $driverSrc
    if ($LASTEXITCODE -ne 0) { Write-Output "DRIVER BUILD FAILED exit=$LASTEXITCODE"; exit 1 }
    Write-Output "BUILT: $exe ($((Get-Item $exe).Length) bytes)"

    $srcs = @(Get-ChildItem -Path (Join-Path $root 'native\src') -Recurse -Filter '*.cpp' |
              ForEach-Object { $_.FullName })
    Write-Output "sources: $($srcs.Count) files under native/src"

    $releaseDll = Join-Path $outDir 'cava_release.dll'
    Write-Output ">>> build release rebuild -> $releaseDll"
    & $gxx @flags -DNDEBUG -shared -o $releaseDll @srcs
    if ($LASTEXITCODE -ne 0) { Write-Output "RELEASE BUILD FAILED exit=$LASTEXITCODE"; exit 1 }
    Write-Output "BUILT: $releaseDll ($((Get-Item $releaseDll).Length) bytes)"

    $safeDll = Join-Path $outDir 'cava_safe.dll'
    Write-Output ">>> build CAVA_SAFE=1 -> $safeDll"
    & $gxx @flags -DCAVA_SAFE=1 -shared -o $safeDll @srcs
    if ($LASTEXITCODE -ne 0) { Write-Output "SAFE BUILD FAILED exit=$LASTEXITCODE"; exit 1 }
    Write-Output "BUILT: $safeDll ($((Get-Item $safeDll).Length) bytes)"
} else {
    $releaseDll = Join-Path $outDir 'cava_release.dll'
    $safeDll = Join-Path $outDir 'cava_safe.dll'
}

$every = if ($EveryCase) { 1 } else { 1 }

# NOTE: helper functions use Write-Host (bypasses the pipeline) so that
# "$rc = Invoke-Fuzz ..." returns ONLY the exit code -- Write-Output inside would be
# captured into the variable instead of being displayed (classic PowerShell trap, hit once).
function Invoke-Fuzz([string]$Label, [string]$DllPath, [string]$LogName) {
    if (-not (Test-Path $DllPath)) {
        Write-Host ""
        Write-Host "=== fuzz [$Label] SKIPPED: $DllPath does not exist ==="
        return -1
    }
    $log = Join-Path $logDir $LogName
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $DllPath).Hash
    Write-Host ""
    Write-Host "=== fuzz [$Label] ==="
    Write-Host "dll  = $DllPath"
    Write-Host "sha256 = $($hash.Substring(0,32))..."
    Write-Host "log  = $log"
    # | Out-Host: the driver's stdout must NOT enter this function's output stream, otherwise
    # the caller's "$rc = Invoke-Fuzz ..." would receive an array instead of the exit code.
    $mtArgs = if ($NoThreads) { @() } else { @('--threads', "$ThreadsPerRole") }
    & $exe $DllPath --cases $Cases --seed $Seed --every $every @mtArgs --log $log 2>&1 | Out-Host
    $rc = $LASTEXITCODE
    Write-Host "exit=$rc"
    if (Test-Path $log) {
        Get-Content $log -TotalCount 9 | ForEach-Object { Write-Host "  | $_" }
        Write-Host "  | ..."
        Get-Content $log -Tail 3 | ForEach-Object { Write-Host "  | $_" }
        # the multi-threaded phase has its own summary; surface it or it is invisible in CI
        Select-String -Path $log -Pattern '^MT SUMMARY|^MT RESULT|^mt-cases|^mt-rc-hist|^mt-torn|^mt-contract|^mt-overruns|^mt-region' |
            ForEach-Object { Write-Host "  | $($_.Line)" }
    }
    if ($rc -ne 0) {
        Write-Host "*** [$Label] RESULT: FAIL (exit=$rc) -- failure lines: ***"
        Select-String -Path $log -Pattern 'UNDEFINED|MUTATION|OVERRUN|UNEXPECTED|TORN|CONTRACT' | Select-Object -First 20 |
            ForEach-Object { Write-Host "  ! $($_.Line)" }
    } else {
        Write-Host "[$Label] RESULT: PASS"
    }
    return $rc
}

function Invoke-SafeProbe([string]$Label, [string]$DllPath) {
    if (-not (Test-Path $DllPath)) { return }
    Write-Host ""
    Write-Host "=== SAFE assertion probe [$Label] : cava_layout_report(NULL) ==="
    $log = Join-Path $logDir ("safe-probe-" + $Label + ".log")
    # 2>&1 merges the native stderr (the SAFE build writes its assertion there) into the capture
    $captured = & $exe $DllPath --safe-probe --log $log 2>&1
    Write-Host "exit=$LASTEXITCODE"
    $captured | ForEach-Object { Write-Host "  | $_" }
    Write-Host "  --- driver log ---"
    Get-Content $log | ForEach-Object { Write-Host "  | $_" }
}

$results = @()
$shipped = if ($Dll -ne '') { $Dll } else { Join-Path $root 'natives\windows-x64\cava.dll' }
$r1 = Invoke-Fuzz 'shipped' $shipped 'fuzz-shipped.log'
if ($null -ne $r1) { $results += @{ Label = 'shipped'; Rc = $r1 } }
$r2 = Invoke-Fuzz 'release-rebuild' $releaseDll 'fuzz-release.log'
if ($null -ne $r2) { $results += @{ Label = 'release-rebuild'; Rc = $r2 } }
$r3 = Invoke-Fuzz 'safe-build' $safeDll 'fuzz-safe.log'
if ($null -ne $r3) { $results += @{ Label = 'safe-build'; Rc = $r3 } }

Invoke-SafeProbe 'release-rebuild' $releaseDll
Invoke-SafeProbe 'safe-build' $safeDll

Write-Output ""
Write-Output "================ fuzz summary ================"
$bad = 0
foreach ($r in $results) {
    Write-Output ("  {0,-16} exit={1}" -f $r.Label, $r.Rc)
    if ($r.Rc -ne 0) { $bad++ }
}
Write-Output "cases per dll = $Cases random + systematic families; seed = $Seed"
if ($NoThreads) { Write-Output "multi-threaded phase: SKIPPED (-NoThreads)" }
else { Write-Output "multi-threaded phase: $($ThreadsPerRole * 4) threads (roles unload/read/path/tables) x 4000 rounds" }
Write-Output "logs under $logDir"
if ($bad -gt 0) { Write-Output "OVERALL: FAIL ($bad target(s))"; exit 1 }
Write-Output "OVERALL: PASS"
exit 0
