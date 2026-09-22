<#
Cava P0-E: shared helpers for launching / driving the testbed server.
Dot-source this file:  . "$PSScriptRoot\lib-server.ps1"
No stdin pipe is used (RCON drives the server) -> works under the workspace-write sandbox.
#>

function Get-CavaRoot {
  param([string]$Root)
  if ($Root) { return [System.IO.Path]::GetFullPath($Root) }
  return [System.IO.Path]::GetFullPath((Join-Path (Split-Path $PSScriptRoot -Parent) 'testbed'))
}

function Start-CavaServer {
  param(
    [Parameter(Mandatory=$true)][string]$Root,
    [Parameter(Mandatory=$true)][string]$Name,
    [string]$JavaExe = 'C:\Program Files\Java\jdk-21\bin\java.exe',
    [string[]]$JavaArgs = @('-Xms2G','-Xmx4G','-Dstdout.encoding=UTF-8','-Dstderr.encoding=UTF-8','--enable-preview','--enable-native-access=ALL-UNNAMED'),
    [switch]$Vanilla
  )
  $serverDir = Join-Path $Root 'server'
  $runDir = Join-Path $Root ("runs\" + $Name)
  New-Item -ItemType Directory -Force -Path $runDir | Out-Null
  $log  = Join-Path $runDir 'server.log'
  $elog = Join-Path $runDir 'server.err.log'
  foreach ($f in @($log, $elog)) { if (Test-Path $f) { Remove-Item $f -Force } }
  $empty = Join-Path $runDir 'stdin.empty'
  if (Test-Path $empty) { Remove-Item $empty -Force }
  New-Item -ItemType File -Path $empty | Out-Null

  # pre-flight: a server that cannot bind leaves a confusing log, and on this host other
  # agents run their own servers -- fail loudly and immediately instead.
  $propsFile = Join-Path $serverDir 'server.properties'
  if (Test-Path $propsFile) {
    $propLine = Select-String -Path $propsFile -Pattern '^server-port=' | Select-Object -First 1
    if ($propLine) {
      $gamePort = [int]($propLine.Line -replace '^server-port=', '')
      $busy = Get-NetTCPConnection -State Listen -LocalPort $gamePort -ErrorAction SilentlyContinue | Select-Object -First 1
      if ($busy) { throw "server-port $gamePort is already LISTENING (pid $($busy.OwningProcess)). Another server is running - stop it or pick another port." }
    }
  }

  $launcher = (Get-ChildItem (Join-Path $Root 'dl') -Filter 'fabric-server-*.jar' | Select-Object -First 1).FullName
  if (-not $launcher) { throw "no fabric server launcher in $Root\dl (run tools/setup-testbed.ps1)" }
  $jar = if ($Vanilla) { Join-Path $serverDir 'server.jar' } else { $launcher }

  $argList = @() + $JavaArgs + @('-jar', $jar, 'nogui')
  $cmdLine = ($argList | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } }) -join ' '

  $proc = Start-Process -FilePath $JavaExe -ArgumentList $argList -WorkingDirectory $serverDir `
    -RedirectStandardOutput $log -RedirectStandardError $elog -RedirectStandardInput $empty `
    -NoNewWindow -PassThru
  $proc.Id | Set-Content -Encoding ASCII (Join-Path $runDir 'server.pid')

  [pscustomobject]@{
    Process = $proc; Log = $log; ErrLog = $elog; RunDir = $runDir
    ServerDir = $serverDir; CommandLine = $cmdLine; Jar = $jar; Name = $Name
    Started = Get-Date
  }
}

function Wait-CavaServerReady {
  param([Parameter(Mandatory=$true)]$Server, [int]$TimeoutSec = 240, [string]$Pattern = 'Done \(')
  $sw = [System.Diagnostics.Stopwatch]::StartNew()
  while ($sw.Elapsed.TotalSeconds -lt $TimeoutSec) {
    if ($Server.Process.HasExited) { return [pscustomobject]@{ Ready=$false; Reason='process exited'; Line=$null; Seconds=$sw.Elapsed.TotalSeconds } }
    if (Test-Path $Server.Log) {
      $hit = Select-String -Path $Server.Log -Pattern $Pattern -ErrorAction SilentlyContinue | Select-Object -First 1
      if ($hit) { return [pscustomobject]@{ Ready=$true; Reason='ok'; Line=$hit.Line.Trim(); Seconds=$sw.Elapsed.TotalSeconds } }
    }
    Start-Sleep -Milliseconds 500
  }
  [pscustomobject]@{ Ready=$false; Reason="timeout after ${TimeoutSec}s"; Line=$null; Seconds=$sw.Elapsed.TotalSeconds }
}

function Wait-CavaLogMatch {
  param([Parameter(Mandatory=$true)]$Server, [Parameter(Mandatory=$true)][string]$Pattern, [int]$TimeoutSec = 60)
  $sw = [System.Diagnostics.Stopwatch]::StartNew()
  while ($sw.Elapsed.TotalSeconds -lt $TimeoutSec) {
    if (Test-Path $Server.Log) {
      $hit = Select-String -Path $Server.Log -Pattern $Pattern -ErrorAction SilentlyContinue | Select-Object -Last 1
      if ($hit) { return [pscustomobject]@{ Matched=$true; Line=$hit.Line.Trim(); Seconds=$sw.Elapsed.TotalSeconds } }
    }
    Start-Sleep -Milliseconds 500
  }
  [pscustomobject]@{ Matched=$false; Line=$null; Seconds=$sw.Elapsed.TotalSeconds }
}

function Stop-CavaServer {
  param([Parameter(Mandatory=$true)]$Server, [int]$TimeoutSec = 120)
  if ($Server.Process.HasExited) { return [pscustomobject]@{ ExitCode=$Server.Process.ExitCode; Forced=$false } }
  try { & (Join-Path $PSScriptRoot 'rcon.ps1') -Command 'stop' -NoLog | Out-Null } catch { Write-Host "[lib] rcon stop failed: $_" }
  if (-not $Server.Process.WaitForExit($TimeoutSec * 1000)) {
    Write-Host "[lib] did not stop within ${TimeoutSec}s, killing"
    $Server.Process.Kill(); $Server.Process.WaitForExit(30000)
    $Server.Process.Refresh()
    return [pscustomobject]@{ ExitCode=$Server.Process.ExitCode; Forced=$true }
  }
  $Server.Process.Refresh()
  [pscustomobject]@{ ExitCode=$Server.Process.ExitCode; Forced=$false }
}

function Invoke-CavaRcon {
  param([Parameter(Mandatory=$true)][string]$Command, [int]$Retry = 3)
  for ($i = 1; $i -le $Retry; $i++) {
    try { return (& (Join-Path $PSScriptRoot 'rcon.ps1') -Command $Command -NoLog) } catch {
      if ($i -eq $Retry) { throw }
      Start-Sleep -Seconds 1
    }
  }
}

function Invoke-CavaWorldHash {
  param([Parameter(Mandatory=$true)][string]$Root, [Parameter(Mandatory=$true)][string]$Label, [string]$WorldDir)
  if (-not $WorldDir) { $WorldDir = Join-Path $Root 'server\world' }
  $hashDir = Join-Path $Root 'hashes'
  New-Item -ItemType Directory -Force -Path $hashDir | Out-Null
  $json = Join-Path $hashDir ($Label + '.json')
  $tsv  = Join-Path $hashDir ($Label + '.chunks.tsv')
  $node = 'C:\Program Files\nodejs\node.exe'
  $out = & $node (Join-Path $PSScriptRoot 'worldhash.cjs') $WorldDir --json $json --chunks $tsv --quiet
  $j = Get-Content $json -Raw | ConvertFrom-Json
  $line = "{0}`t{1}`t{2}`t{3}" -f $Label, $j.digestPayload, $j.chunks, $j.levelDat.scalars.Time
  Add-Content -Encoding UTF8 (Join-Path $hashDir 'digests.tsv') $line
  [pscustomobject]@{ Label=$Label; Digest=$j.digestPayload; Chunks=$j.chunks; Ticks=$j.levelDat.scalars.Time; Json=$json; Tsv=$tsv }
}
