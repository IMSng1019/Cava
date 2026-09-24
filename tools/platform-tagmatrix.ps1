# platform-tagmatrix.ps1 -- thin wrapper around native/tests/platform/tag_matrix.cmake.
#
# Owner: P4-B; path tools/platform-*.ps1 per the task brief.
# NOTE: keep this file ASCII-only. PowerShell 5.1 mis-parses UTF-8-without-BOM scripts whose
#       comments contain non-ASCII bytes (observed twice in this project).
#
# The real logic lives in native/tests/platform/tag_matrix.cmake so the exact same test runs on
# this machine and on all 5 CI runners with nothing but cmake:
#   * part 1 calls cava_configure_platform() directly for 10 (system, processor, osx-arch) combos
#   * part 2 runs a REAL cmake configure (project LANGUAGES NONE, so no toolchain is required)
#     for 7 combos and reads CAVA_PLATFORM_TAG / CAVA_PLATFORM_ID back out of CMakeCache.txt
#
# -RealCrossConfigure additionally runs the strongest check available on this machine: a real
# configure of the WHOLE project with -DCMAKE_SYSTEM_NAME=Linux -DCMAKE_SYSTEM_PROCESSOR=aarch64
# plus -DCMAKE_TRY_COMPILE_TARGET_TYPE=STATIC_LIBRARY (so CMake never links or runs a target
# binary, which is the usual reason a cross configure fails without a cross toolchain). It uses a
# junction-based source view so natives/<tag>/ is created inside the work dir, never in the repo.
#
# Exit: 0 pass, 1 failure, 2 setup error.
param(
    [string]$Cmake = '',
    [string]$WorkDir = '',
    [switch]$RealCrossConfigure,
    [string]$Cc = 'C:/mingw64/bin/gcc.exe',
    [string]$Cxx = 'C:/mingw64/bin/g++.exe',
    [string]$MakeProgram = 'C:/mingw64/bin/mingw32-make.exe'
)
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Continue'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if ($Cmake -eq '') { $Cmake = Join-Path $root 'tools\cmake\cmake-3.31.2-windows-x86_64\bin\cmake.exe' }
if (-not (Test-Path $Cmake)) { Write-Output "cmake not found: $Cmake (use -Cmake <path>)"; exit 2 }
if ($WorkDir -eq '') { $WorkDir = Join-Path $root 'build\platform-tagmatrix' }
New-Item -ItemType Directory -Force -Path $WorkDir | Out-Null
$env:TMP = $WorkDir; $env:TEMP = $WorkDir

$script = Join-Path $root 'native\tests\platform\tag_matrix.cmake'
if (-not (Test-Path $script)) { Write-Output "missing $script"; exit 2 }

& $Cmake ("-DCAVA_TAGMATRIX_WORK=" + $WorkDir) -P $script
$rc = $LASTEXITCODE
if ($rc -ne 0) { Write-Output "TAGMATRIX: FAIL (cmake -P exit=$rc)"; exit 1 }

if ($RealCrossConfigure) {
    Write-Output ""
    Write-Output "=== extra: real cross configure of the whole project (Linux x64 / arm64) ==="
    $view = Join-Path $WorkDir 'srcview'
    if (Test-Path $view) { Remove-Item $view -Recurse -Force -ErrorAction SilentlyContinue }
    New-Item -ItemType Directory -Force -Path $view | Out-Null
    try {
        New-Item -ItemType Junction -Path (Join-Path $view 'native') -Target (Join-Path $root 'native') -ErrorAction Stop | Out-Null
    } catch {
        Copy-Item (Join-Path $root 'native') -Destination $view -Recurse -Force
    }
    Copy-Item (Join-Path $root 'CMakeLists.txt') -Destination $view -Force
    foreach ($spec in @(@('Linux','x86_64','linux-x64'), @('Linux','aarch64','linux-arm64'))) {
        $sys = $spec[0]; $proc = $spec[1]; $want = $spec[2]
        $bd = Join-Path $WorkDir ("realcfg-" + $want)
        if (Test-Path $bd) { Remove-Item $bd -Recurse -Force -ErrorAction SilentlyContinue }
        $log = Join-Path $WorkDir ("realcfg-" + $want + ".log")
        $cfgArgs = @('-S', $view, '-B', $bd, '-G', 'MinGW Makefiles',
                     ("-DCMAKE_SYSTEM_NAME=" + $sys), ("-DCMAKE_SYSTEM_PROCESSOR=" + $proc),
                     '-DCMAKE_TRY_COMPILE_TARGET_TYPE=STATIC_LIBRARY',
                     ("-DCMAKE_C_COMPILER=" + $Cc), ("-DCMAKE_CXX_COMPILER=" + $Cxx),
                     ("-DCMAKE_MAKE_PROGRAM=" + $MakeProgram),
                     '-DCAVA_BUILD_TESTS=OFF')
        & $Cmake @cfgArgs 2>&1 | Out-File -Encoding utf8 -FilePath $log
        $rc2 = $LASTEXITCODE
        $tag = ''
        $cache = Join-Path $bd 'CMakeCache.txt'
        if (Test-Path $cache) {
            foreach ($line in Get-Content $cache) {
                if ($line -match '^CAVA_PLATFORM_TAG:STRING=(.*)$') { $tag = $Matches[1] }
            }
        }
        $hit = (Get-Content $log | Select-String -Pattern 'cava: platform=' | Select-Object -First 1)
        if (($rc2 -eq 0) -and ($tag -eq $want)) {
            Write-Output ("  [ ok ] " + $want + " : real cross configure OK (CAVA_PLATFORM_TAG=" + $tag + ")")
            if ($hit) { Write-Output ("         " + $hit.Line.Trim()) }
        } else {
            Write-Output ("  [FAIL] " + $want + " : rc=" + $rc2 + " tag='" + $tag + "'  (log: " + $log + ")")
            $rc = 1
        }
    }
}

Write-Output ""
$verdict = if ($rc -eq 0) { 'PASS' } else { 'FAIL' }
Write-Output "TAGMATRIX-WRAPPER: $verdict"
exit $rc
