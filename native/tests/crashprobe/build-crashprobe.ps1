param(
    [int]$TimeoutSec = 60
)
<#
build-crashprobe.ps1 -- build the deliberately broken probe DLLs (P4-C crash forensics).

Deliberately NOT part of CMake: native/CMakeLists.txt and native/tests/CMakeLists.txt are owned
by other streams, and this artifact must never end up inside cava.dll or in natives/<tag>/.

Builds into build/p4c-crash/ (a scratch directory):
  crashprobe.dll               faults in its own code            -> verdict NOT_CAVA_PROBE
  crashprobe_null2.dll         byte-identical copy, other name   -> verdict NOT_CAVA
  crashprobe_cava_proxy.dll    faults inside a module named
                               cava.dll (a copy of the probe)    -> verdict CAVA_NATIVE_FAULT
  cava.dll                     the copy the proxy calls (NEVER copied into natives/)

Usage:
  pwsh -NoProfile -ExecutionPolicy Bypass -File native/tests/crashprobe/build-crashprobe.ps1
#>

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$gxx  = 'C:\mingw64\bin\g++.exe'
if (-not (Test-Path $gxx)) { Write-Output "MISSING: $gxx"; exit 2 }

$out = Join-Path $root 'build\p4c-crash'
$tmp = Join-Path $out 'tmp'
New-Item -ItemType Directory -Force -Path $out | Out-Null
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
# g++'s assembler cannot create temporaries under a %TEMP% path that contains non-ASCII bytes
# (this machine's does) -- redirect TMP/TEMP into the ASCII build directory.
$env:TMP = $tmp
$env:TEMP = $tmp

$src     = Join-Path $root 'native\tests\crashprobe\crashprobe.c'
$proxy   = Join-Path $root 'native\tests\crashprobe\crashprobe_through_cava.c'
$dll     = Join-Path $out 'crashprobe.dll'
$proxyDll = Join-Path $out 'crashprobe_cava_proxy.dll'

& $gxx -O0 -g -shared -o $dll $src
if ($LASTEXITCODE -ne 0) { Write-Output "PROBE BUILD FAILED exit=$LASTEXITCODE"; exit 1 }
& $gxx -O0 -g -shared -o $proxyDll $proxy
if ($LASTEXITCODE -ne 0) { Write-Output "PROXY BUILD FAILED exit=$LASTEXITCODE"; exit 1 }

Copy-Item $dll (Join-Path $out 'crashprobe_null2.dll') -Force
# stale diagnostics from a previous (possibly broken) probe run must not be mistaken for this one
Remove-Item (Join-Path $out 'crashprobe_error.log') -Force -ErrorAction SilentlyContinue
# The "cava.dll" the proxy calls. Same bytes as the probe; the FILE NAME is what makes the module
# table say cava.dll. This file lives in build/, never in the shared natives/ directory.
Copy-Item $dll (Join-Path $out 'cava.dll') -Force

$objdump = 'C:\mingw64\bin\objdump.exe'
$exports = @()
if (Test-Path $objdump) {
    $exports = @(& $objdump -p $dll 2>&1 | Select-String -Pattern 'cava_crash_probe' | ForEach-Object { $_.Line.Trim() })
}

Write-Output "=== crash probe artifacts (build/p4c-crash) ==="
Get-ChildItem $out -Filter '*.dll' | Sort-Object Name | ForEach-Object {
    $sha = (Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).Hash
    Write-Output ("  {0,-30} {1,8} B  sha256={2}" -f $_.Name, $_.Length, $sha.Substring(0, 32))
}
Write-Output "exports of crashprobe.dll:"
$exports | ForEach-Object { Write-Output "  $_" }
if ($exports.Count -lt 2) {
    Write-Output "*** the probe does not export the expected symbols -- the crash test would be vacuous ***"
    exit 1
}
Write-Output "RESULT: PROBE BUILD OK"
exit 0
