# run-platform-suite.ps1 -- build + run the platform numerical-consistency suite.
#
# Why not CMake: mirrors native/tests/entity/build-entity.ps1 -- an independent test program
# that must be runnable on all 5 CI platforms with nothing but a compiler. The CMake entry
# point (native/tests/platform/CMakeLists.txt) exists too and uses the same source.
#
# Usage:
#   pwsh -File native/tests/platform/run-platform-suite.ps1
#   pwsh -File native/tests/platform/run-platform-suite.ps1 -Lib natives/windows-x64/cava.dll
#   pwsh -File native/tests/platform/run-platform-suite.ps1 -StrictNan
#
# Exit codes: 0 pass, 1 check failure, 2 usage/IO, 3 golden vectors missing.
# NOTE: keep this file ASCII-only. PowerShell 5.1 mis-parses UTF-8-without-BOM scripts whose
#       comments contain non-ASCII bytes (observed twice in this project).
param(
    [string]$Lib = '',
    [string]$Golden = '',
    [long]$ExpectRows = 15456,
    [switch]$StrictNan,
    [switch]$Quiet,
    [string]$OutDir = '',
    [string]$Cxx = ''
)
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Stop'

$here = $PSScriptRoot
$root = (Resolve-Path (Join-Path $here '..\..\..')).Path
if ($OutDir -eq '') { $OutDir = Join-Path $root 'build\platform-suite' }
if ($Golden -eq '') { $Golden = Join-Path $root 'native\tests\vectors\fp_probe.txt' }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

# g++ writes intermediate .o into %TEMP%; on this machine that path contains non-ASCII
# characters and the assembler cannot create files there (observed in P1). Redirect it.
$env:TMP = $OutDir
$env:TEMP = $OutDir

function Find-Cxx {
    param([string]$Explicit)
    if ($Explicit -ne '') { return $Explicit }
    foreach ($c in @('C:\mingw64\bin\g++.exe', 'g++', 'c++', 'clang++')) {
        if (Test-Path $c) { return $c }
        $cmd = Get-Command $c -ErrorAction SilentlyContinue
        if ($cmd) { return $cmd.Source }
    }
    return ''
}

$cxx = Find-Cxx -Explicit $Cxx
if ($cxx -eq '') { Write-Output 'MISSING: no C++ compiler (tried C:\mingw64\bin\g++.exe, g++, c++, clang++)'; exit 2 }
Write-Output "compiler: $cxx"

$isWin = ($env:OS -eq 'Windows_NT') -or ($PSVersionTable.PSVersion.Major -le 5)
$exeName = if ($isWin) { 'cava_platform_suite.exe' } else { 'cava_platform_suite' }
$exe = Join-Path $OutDir $exeName
$src = Join-Path $here 'cava_platform_suite.cpp'
$inc = Join-Path $root 'native\include'

# Hardened flags: MUST stay identical to what native/cmake/CavaFlags.cmake produces.
# -fno-math-errno is there because without it MinGW's std::sqrt silently falls back to
# msvcrt's non-correctly-rounded sqrt -- measured on this machine; see docs/CAVA-platform-notes.md.
$flags = @(
    '-std=c++17', '-O2', '-fwrapv', '-ffp-contract=off', '-fno-fast-math', '-fno-math-errno',
    '-Wall', '-Wextra'
)
if ($isWin) { $flags += '-static' }
$flags += @('-I', $inc, '-o', $exe, $src)

Write-Output "srcs=1"
Write-Output "build: $cxx $($flags -join ' ')"
& $cxx @flags
if ($LASTEXITCODE -ne 0) { Write-Output "BUILD FAILED exit=$LASTEXITCODE"; exit 2 }
$size = (Get-Item $exe).Length
Write-Output "BUILT: $exe ($size bytes)"

$runArgs = @('--golden', $Golden, '--expect-rows', "$ExpectRows")
if ($Lib -ne '') { $runArgs += @('--lib', $Lib) }
if ($StrictNan) { $runArgs += '--strict-nan' }
if ($Quiet) { $runArgs += '--quiet' }

# Run from the repo root: the suite's default golden path is repo-root-relative.
Push-Location $root
try {
    & $exe @runArgs
    $rc = $LASTEXITCODE
} finally { Pop-Location }
Write-Output "exit=$rc"
exit $rc
