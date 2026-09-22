# build-pathfind.ps1 -- build the P1 native pathfind kernel + cross-language differential test.
#
# Why not CMake: native/tests/CMakeLists.txt belongs to P0-A (not touched this round), and the
# CMake glob only collects native/src/**, so the test program needs its own build line.
#
# Usage: powershell -NoProfile -File native/tests/build-pathfind.ps1
# NOTE: keep this file ASCII-only. PowerShell 5.1 mis-parses UTF-8-without-BOM scripts whose
#       comments contain non-ASCII bytes (observed: "Unexpected token ')'").
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$gxx  = 'C:\mingw64\bin\g++.exe'
if (-not (Test-Path $gxx)) { Write-Output "MISSING: $gxx"; exit 2 }

$outDir = Join-Path $root 'build\native-pathfind'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

# g++ puts temp .o files in %TEMP%; this machine's %TEMP% path contains non-ASCII characters and
# the assembler cannot create files there (observed: "can't create ...ccXXXXXX.o"). Redirect it.
$tmpDir = Join-Path $outDir 'tmp'
New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null
$env:TMP = $tmpDir
$env:TEMP = $tmpDir

$srcs = @()
$srcs += (Get-ChildItem -Recurse (Join-Path $root 'native\src') -Filter *.cpp | ForEach-Object { $_.FullName })
$srcs += (Join-Path $root 'native\tests\cava_pathfind_vectors.cpp')

# Fixed compile flags, identical to contract 2.1 item 5.
$flags = @(
  '-std=c++17', '-O2', '-fwrapv', '-ffp-contract=off', '-fno-fast-math',
  '-Wall', '-Wextra', '-Wno-unused-parameter',
  # Static runtime so the exe runs without C:\mingw64\bin on PATH (matches the production DLL,
  # whose only imports are KERNEL32 + msvcrt).
  # Full static: MinGW GCC 15 (posix threads) also needs libwinpthread-1.dll otherwise.
  '-static'
)
$exe = Join-Path $outDir 'cava_pathfind_vectors.exe'
Write-Output "srcs=$($srcs.Count)"
& $gxx @flags -o $exe @srcs
if ($LASTEXITCODE -ne 0) { Write-Output "BUILD FAILED exit=$LASTEXITCODE"; exit 1 }
Write-Output "BUILT: $exe ($((Get-Item $exe).Length) bytes)"
