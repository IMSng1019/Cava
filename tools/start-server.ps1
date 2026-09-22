<#
Cava P0-E: launch the testbed Fabric server with a deterministic JVM command line and block
until it exits (or until -MaxSeconds, after which it is stopped cleanly over RCON).

usage:
  pwsh -File tools/start-server.ps1 -Name smoke -MaxSeconds 180
  pwsh -File tools/start-server.ps1 -Name vanilla-probe -Vanilla -MaxSeconds 60
#>
[CmdletBinding()]
param(
  [string]$Name = 'run',
  [string]$Root = '',
  [int]$MaxSeconds = 0,
  [string]$JavaExe = 'C:\Program Files\Java\jdk-21\bin\java.exe',
  # -Dstdout/stderr.encoding keeps redirected logs readable; --enable-preview + --enable-native-access
  # are the two flags Cava will need at runtime (P0-E verifies vanilla+Fabric tolerate them).
  [string[]]$JavaArgs = @('-Xms2G','-Xmx4G','-Dstdout.encoding=UTF-8','-Dstderr.encoding=UTF-8','--enable-preview','--enable-native-access=ALL-UNNAMED'),
  [switch]$Vanilla
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
. (Join-Path $PSScriptRoot 'lib-server.ps1')
$Root = Get-CavaRoot -Root $Root

$srv = Start-CavaServer -Root $Root -Name $Name -JavaExe $JavaExe -JavaArgs $JavaArgs -Vanilla:$Vanilla
Write-Host "[start-server] name=$Name"
Write-Host "[start-server] cwd=$($srv.ServerDir)"
Write-Host "[start-server] args=$($srv.CommandLine)"
Write-Host "[start-server] log=$($srv.Log)"

$sw = [System.Diagnostics.Stopwatch]::StartNew()
$ready = $null
while (-not $srv.Process.HasExited) {
  if ($null -eq $ready -and $MaxSeconds -gt 0 -and $sw.Elapsed.TotalSeconds -gt $MaxSeconds) { break }
  if ($null -eq $ready) {
    $r = Wait-CavaServerReady -Server $srv -TimeoutSec 1
    if ($r.Ready) { $ready = $r; Write-Host ("[start-server] READY after {0:n1}s: {1}" -f $r.Seconds, $r.Line) }
    if ($MaxSeconds -gt 0 -and $sw.Elapsed.TotalSeconds -gt $MaxSeconds) { break }
  } else {
    Start-Sleep -Milliseconds 500
  }
  $srv.Process.Refresh()
}
$sw.Stop()
if (-not $srv.Process.HasExited) {
  Write-Host '[start-server] stopping over RCON'
  $res = Stop-CavaServer -Server $srv
} else { $srv.Process.Refresh(); $res = [pscustomobject]@{ ExitCode=$srv.Process.ExitCode; Forced=$false } }
Write-Host ("[start-server] exitCode={0} forced={1} wall={2:n1}s" -f $res.ExitCode, $res.Forced, $sw.Elapsed.TotalSeconds)
if ($ready) { Write-Host ("[start-server] DONE LINE: " + $ready.Line) } else { Write-Host '[start-server] !! no "Done (" line !!' }
