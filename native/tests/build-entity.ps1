# build-entity.ps1 -- build the P2 entity-collision geometry kernel + its differential test.
#
# Why not CMake: native/tests/CMakeLists.txt belongs to P0-A (not touched this round), and the
# CMake glob only collects native/src/**, so the entity test program needs its own build line.
# The kernel itself lives in native/src/entity/** and IS picked up by the CMake glob for the DLL.
#
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File native/tests/build-entity.ps1
# NOTE: keep this file ASCII-only. PowerShell 5.1 mis-parses UTF-8-without-BOM scripts whose
#       comments contain non-ASCII bytes (observed in P1: "Unexpected token ')'").
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$gxx  = 'C:\mingw64\bin\g++.exe'
if (-not (Test-Path $gxx)) { Write-Output "MISSING: $gxx"; exit 2 }

$outDir = Join-Path $root 'build\native-entity'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$vecDir = Join-Path $root 'native\tests\entity\vectors'
New-Item -ItemType Directory -Force -Path $vecDir | Out-Null

# g++ puts temp .o files in %TEMP%; this machine's %TEMP% path contains non-ASCII characters and
# the assembler cannot create files there (observed in P1). Redirect it.
$tmpDir = Join-Path $outDir 'tmp'
New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null
$env:TMP = $tmpDir
$env:TEMP = $tmpDir

$srcs = @()
$srcs += (Join-Path $root 'native\src\entity\cava_entity_kernel.cpp')
$srcs += (Join-Path $root 'native\tests\entity\cava_entity_vectors.cpp')

# Fixed compile flags, identical to contract 2.1 item 5.
$flags = @(
  '-std=c++17', '-O2', '-fwrapv', '-ffp-contract=off', '-fno-fast-math',
  '-I', (Join-Path $root 'native\src\entity'),
  '-I', (Join-Path $root 'native\tests\entity'),
  '-Wall', '-Wextra', '-Wno-unused-parameter', '-Wno-missing-field-initializers',
  '-static'
)
# The ABI proposal probe compiles separately (it includes the frozen cava_abi.h; the kernel does not).
$probeSrc = Join-Path $root 'native\tests\entity\cava_abi_proposal_probe.cpp'
$probeExe = Join-Path $outDir 'cava_abi_proposal_probe.exe'
& $gxx @flags -I (Join-Path $root 'native\include') -o $probeExe $probeSrc
if ($LASTEXITCODE -ne 0) { Write-Output "PROBE BUILD FAILED exit=$LASTEXITCODE"; exit 1 }
& $probeExe
if ($LASTEXITCODE -ne 0) { Write-Output "PROBE FAILED exit=$LASTEXITCODE"; exit 1 }

$exe = Join-Path $outDir 'cava_entity_vectors.exe'
Write-Output "srcs=$($srcs.Count)"
& $gxx @flags -o $exe @srcs
if ($LASTEXITCODE -ne 0) { Write-Output "BUILD FAILED exit=$LASTEXITCODE"; exit 1 }
Write-Output "BUILT: $exe ($((Get-Item $exe).Length) bytes)"

# Run from the repo root so the test can find native/tests/entity/vectors.
Push-Location $root
try {
  & $exe
  $rc = $LASTEXITCODE
} finally { Pop-Location }
Write-Output "exit=$rc"
exit $rc
