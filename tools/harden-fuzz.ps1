<#
harden-fuzz.ps1 -- P4-A item 3 driver: run the frozen-ABI fuzz suite and summarize it.

It is a thin wrapper over native/tests/fuzz/build-fuzz.ps1 (which builds the driver, builds a
release + a CAVA_SAFE=1 library into build/native-fuzz/, and runs the same suite against the
shipped artifact, the release rebuild and the SAFE build). This script's job is to make the
ACCEPTANCE NUMBERS easy to read: total cases, crashes, undefined returns, state mutations,
overruns -- plus the exact seed and the exact reproduction command.

Usage:
  pwsh -NoProfile -ExecutionPolicy Bypass -File tools/harden-fuzz.ps1
  pwsh -NoProfile -ExecutionPolicy Bypass -File tools/harden-fuzz.ps1 -Cases 20000
  pwsh -NoProfile -ExecutionPolicy Bypass -File tools/harden-fuzz.ps1 -SkipBuild -Quick

NOTE: keep this file ASCII-only.
#>
param(
    [int]$Cases = 100000,
    [string]$Seed = '0x5EEDC0DE5EEDC0DE',
    [switch]$SkipBuild,
    [switch]$Quick
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot | Split-Path -Parent
$script = Join-Path $root 'native\tests\fuzz\build-fuzz.ps1'
$logDir = Join-Path $root 'build\native-fuzz\logs'

if ($Quick) {
    & pwsh -NoProfile -ExecutionPolicy Bypass -File $script -Cases 200 -Seed $Seed -SkipBuild 2>&1 | Out-Host
    $rc = $LASTEXITCODE
} elseif ($SkipBuild) {
    & pwsh -NoProfile -ExecutionPolicy Bypass -File $script -Cases $Cases -Seed $Seed -SkipBuild 2>&1 | Out-Host
    $rc = $LASTEXITCODE
} else {
    & pwsh -NoProfile -ExecutionPolicy Bypass -File $script -Cases $Cases -Seed $Seed 2>&1 | Out-Host
    $rc = $LASTEXITCODE
}

Write-Host ""
Write-Host "================ harden-fuzz summary (parsed from the logs) ================"
$targets = @(
    @{ Name = 'shipped'; File = 'fuzz-shipped.log' },
    @{ Name = 'release-rebuild'; File = 'fuzz-release.log' },
    @{ Name = 'safe-build'; File = 'fuzz-safe.log' }
)
$totalCases = 0
foreach ($t in $targets) {
    $path = Join-Path $logDir $t.File
    if (-not (Test-Path $path)) { Write-Host ("  {0,-16} (no log)" -f $t.Name); continue }
    $summary = (Select-String -Path $path -Pattern '^SUMMARY ' | Select-Object -Last 1).Line
    $result = (Select-String -Path $path -Pattern '^RESULT: ' | Select-Object -Last 1).Line
    if ($summary -match 'cases=(\d+)') { $totalCases += [int]$Matches[1] }
    Write-Host ("  {0,-16} {1}" -f $t.Name, $summary)
    Write-Host ("  {0,-16} {1}" -f '', $result)
}
Write-Host ""
Write-Host "total cases across all three targets = $totalCases"
Write-Host "seed = $Seed   (deterministic xorshift64*; same seed + same dll => same cases)"
Write-Host ""
Write-Host "reproduce exactly:"
Write-Host "  pwsh -NoProfile -ExecutionPolicy Bypass -File native/tests/fuzz/build-fuzz.ps1 -Cases $Cases -Seed $Seed"
Write-Host "  pwsh -NoProfile -ExecutionPolicy Bypass -File tools/harden-fuzz.ps1 -Cases $Cases -Seed $Seed -SkipBuild"
Write-Host "  logs: $logDir"
Write-Host ""
if ($rc -ne 0) { Write-Host "HARDEN-FUZZ: FAIL (exit=$rc)"; exit $rc }
Write-Host "HARDEN-FUZZ: PASS"
exit 0
