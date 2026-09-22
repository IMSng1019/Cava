<#
Cava P0-E: reproducible scenario runner for the testbed server.

  pwsh -File tools/run-scenario.ps1 -Scenario tools/scenarios/determinism.txt -Name det-run1

Scenario file format (UTF-8, '#' comments, blank lines ignored):

  [pre]                       # runs before the server starts, no server needed
  setup -Wave 4               # invokes tools/setup-testbed.ps1 with these arguments
  restore base                # copy testbed/snapshots/base -> testbed/server/world
  snapshot base               # copy testbed/server/world -> testbed/snapshots/base
  deleteworld
  note  <free text>
  hash  <label>               # world digest -> testbed/hashes/<label>.json (+ .chunks.tsv)
  [steps]                     # runs while the server is up
  ready 240                   # wait for "Done (" in the server log
  sleep 5
  cmd   spark tps             # RCON command, response is captured
  waitlog 60 Profiling
  hash  after-200-ticks
  stop                        # explicit stop (otherwise the runner stops it at the end)
  [post]                      # runs after the server stopped
  hash  final

Everything is tee'd to testbed/runs/<Name>/scenario.log; a manifest.json records the
JVM command line, the exact mod set (name+sha256) and the digest table.
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory=$true)][string]$Scenario,
  [string]$Name = '',
  [string]$Root = '',
  [string]$JavaExe = 'C:\Program Files\Java\jdk-21\bin\java.exe',
  [string[]]$JavaArgs = @('-Xms2G','-Xmx4G','-Dstdout.encoding=UTF-8','-Dstderr.encoding=UTF-8','--enable-preview','--enable-native-access=ALL-UNNAMED'),
  # jcmd cannot attach to the server on this host (the sandbox denies the attach pipe), so a
  # JFR recording has to be armed on the JVM command line instead.
  [string]$JfrFile = '',
  [int]$JfrDelaySec = 60,
  [int]$JfrDurationSec = 60,
  [switch]$KeepRunning
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
. (Join-Path $PSScriptRoot 'lib-server.ps1')
$Root = Get-CavaRoot -Root $Root
if (-not (Test-Path $Scenario)) { $Scenario = Join-Path $PSScriptRoot $Scenario }
if (-not (Test-Path $Scenario)) { throw "scenario not found: $Scenario" }
if (-not $Name) { $Name = [System.IO.Path]::GetFileNameWithoutExtension($Scenario) }

# ---------- parse ----------
$sections = @{ pre = @(); steps = @(); post = @() }
$cur = 'steps'
foreach ($raw in (Get-Content -Encoding UTF8 $Scenario)) {
  $line = $raw.Trim()
  if (-not $line) { continue }
  if ($line.StartsWith('#')) { continue }
  if ($line -match '^\[(pre|steps|post)\]$') { $cur = $Matches[1]; continue }
  $sections[$cur] += $line.Replace('%NAME%', $Name)
}
Write-Host "[scenario] $Scenario  pre=$($sections.pre.Count) steps=$($sections.steps.Count) post=$($sections.post.Count)"

$runDir = Join-Path $Root ("runs\" + $Name)
New-Item -ItemType Directory -Force -Path $runDir | Out-Null
$transcript = Join-Path $runDir 'scenario.log'
if (Test-Path $transcript) { Remove-Item $transcript -Force }
function Say([string]$msg) { Write-Host $msg; Add-Content -Encoding UTF8 $transcript $msg }
function SayRaw([string]$msg) { Write-Host $msg; if ($msg) { Add-Content -Encoding UTF8 $transcript $msg } }

$worldDir = Join-Path $Root 'server\world'
$snapDir = Join-Path $Root 'snapshots'

function Do-Snapshot([string]$label) {
  $dst = Join-Path $snapDir $label
  if (Test-Path $dst) { Remove-Item $dst -Recurse -Force }
  New-Item -ItemType Directory -Force -Path $dst | Out-Null
  Copy-Item (Join-Path $worldDir '*') $dst -Recurse -Force
  Say "[scenario] snapshot $label -> $dst"
}
function Do-Restore([string]$label) {
  $src = Join-Path $snapDir $label
  if (-not (Test-Path $src)) { throw "snapshot not found: $src" }
  if (Test-Path $worldDir) { Remove-Item $worldDir -Recurse -Force }
  New-Item -ItemType Directory -Force -Path $worldDir | Out-Null
  Copy-Item (Join-Path $src '*') $worldDir -Recurse -Force
  Say "[scenario] restored world from snapshot $label"
}

$srv = $null
$doneLine = $null
$errorAction = 'Continue'

# ---------- pre ----------
foreach ($step in $sections.pre) {
  $parts = $step -split '\s+', 2
  $verb = $parts[0]; $rest = if ($parts.Count -gt 1) { $parts[1] } else { '' }
  switch ($verb) {
    'note'      { Say "[pre] note: $rest" }
    'setup'     { Say "[pre] setup $rest"; $a = $rest -split '\s+'; & (Join-Path $PSScriptRoot 'setup-testbed.ps1') @a }
    'snapshot'  { Do-Snapshot $rest }
    'restore'   { Do-Restore $rest }
    'deleteworld' {
      if (Test-Path $worldDir) { Remove-Item $worldDir -Recurse -Force }
      # recreate empty: an absent world dir makes the server generate a fresh world from
      # level-seed, and the datapack/loadpack steps below need a directory to write into
      New-Item -ItemType Directory -Force -Path $worldDir | Out-Null
      Say '[pre] world deleted (empty dir recreated; server will regenerate from level-seed)'
    }
    'datapack'  { & (Join-Path $PSScriptRoot 'make-det-datapack.ps1') -WorldDir $worldDir | Out-Null; Say '[pre] determinism datapack installed' }
    'loadpack'  {
      $n = if ($rest) { [int]$rest } else { 200 }
      & (Join-Path $PSScriptRoot 'make-load-datapack.ps1') -WorldDir $worldDir -Count $n | Out-Null
      Say "[pre] entity-load datapack installed ($n mobs)"
    }
    'hash'      { $h = Invoke-CavaWorldHash -Root $Root -Label ($Name + '-' + $rest); Say ("[pre] hash {0} = {1} chunks={2} ticks={3}" -f $h.Label, $h.Digest, $h.Chunks, $h.Ticks) }
    'sleep'     { Start-Sleep -Seconds ([int]$rest) }
    default     { throw "unknown [pre] step: $step" }
  }
}

# ---------- start ----------
if ($JfrFile) {
  $JfrFile = [System.IO.Path]::GetFullPath($JfrFile)
  $JavaArgs = $JavaArgs + ("-XX:StartFlightRecording=name=cava,settings=profile,delay={0}s,duration={1}s,filename={2}" -f $JfrDelaySec, $JfrDurationSec, $JfrFile)
  Say ("[scenario] JFR armed: delay=${JfrDelaySec}s duration=${JfrDurationSec}s -> $JfrFile")
}
$srv = Start-CavaServer -Root $Root -Name $Name -JavaExe $JavaExe -JavaArgs $JavaArgs
Say "[scenario] started pid=$($srv.Process.Id) at $(Get-Date -Format o)"
Say "[scenario] cmdline: $($srv.CommandLine)"

# ---------- steps ----------
# A failure inside [steps] (typically "server crashed while loading mods/world") must not
# hide the [post] diagnostics, so the error is captured, reported and re-thrown at the end.
$stepError = $null
try {
  foreach ($step in $sections.steps) {
    $parts = $step -split '\s+', 2
    $verb = $parts[0]; $rest = if ($parts.Count -gt 1) { $parts[1] } else { '' }
    switch ($verb) {
      'note' { Say "[step] note: $rest" }
      'ready' {
        $to = if ($rest) { [int]$rest } else { 240 }
        $r = Wait-CavaServerReady -Server $srv -TimeoutSec $to
        if (-not $r.Ready) { throw "server not ready: $($r.Reason)" }
        $doneLine = $r.Line
        Say ("[step] READY after {0:n1}s: {1}" -f $r.Seconds, $r.Line)
      }
      'sleep' { Start-Sleep -Seconds ([int]$rest); Say "[step] slept $rest s" }
      'cmd' {
        $resp = Invoke-CavaRcon -Command $rest
        Say "[step] > $rest"
        if ($resp) { foreach ($l in ($resp -split "`n")) { SayRaw ("[step] | " + $l) } } else { SayRaw '[step] | (empty RCON response - see server.log for the formatted output)' }
      }
      'waitlog' {
        $p = $rest -split '\s+', 2
        $r = Wait-CavaLogMatch -Server $srv -Pattern $p[1] -TimeoutSec ([int]$p[0])
        if ($r.Matched) { Say ("[step] log matched after {0:n1}s: {1}" -f $r.Seconds, $r.Line) } else { Say "[step] !! log pattern not matched: $($p[1])" }
      }
      'hash' { $h = Invoke-CavaWorldHash -Root $Root -Label ($Name + '-' + $rest); Say ("[step] hash {0} = {1} chunks={2} ticks={3}" -f $h.Label, $h.Digest, $h.Chunks, $h.Ticks) }
      'snapshot' { Do-Snapshot $rest }
      'jfr' {
        $secs = if ($rest) { [int]$rest } else { 30 }
        $serverPid = (Get-Content (Join-Path $runDir 'server.pid') -Raw).Trim()
        $jfrOut = Join-Path $runDir 'cava.jfr'
        $jcmd = 'C:\Program Files\Java\jdk-21\bin\jcmd.exe'
        $r = & $jcmd $serverPid JFR.start ("name=cava,settings=profile,duration={0}s,filename={1}" -f $secs, $jfrOut) 2>&1
        Say ("[step] JFR.start -> " + ($r -join ' '))
      }
      'stop' {
        $res = Stop-CavaServer -Server $srv
        Say ("[step] stopped exitCode={0} forced={1}" -f $res.ExitCode, $res.Forced)
      }
      default { throw "unknown [steps] step: $step" }
    }
    if ($srv.Process.HasExited -and $verb -ne 'stop') { Say '[step] !! server exited early'; break }
  }
} catch {
  $stepError = $_
  Say ("[scenario] STEP ERROR: " + $_.Exception.Message)
} finally {
  if (-not $srv.Process.HasExited -and -not $KeepRunning) {
    $res = Stop-CavaServer -Server $srv
    Say ("[scenario] final stop exitCode={0} forced={1}" -f $res.ExitCode, $res.Forced)
  }
}

# ---------- post ----------
foreach ($step in $sections.post) {
  $parts = $step -split '\s+', 2
  $verb = $parts[0]; $rest = if ($parts.Count -gt 1) { $parts[1] } else { '' }
  switch ($verb) {
    'note'     { Say "[post] note: $rest" }
    'hash'     { $h = Invoke-CavaWorldHash -Root $Root -Label ($Name + '-' + $rest); Say ("[post] hash {0} = {1} chunks={2} ticks={3}" -f $h.Label, $h.Digest, $h.Chunks, $h.Ticks) }
    'snapshot' { Do-Snapshot $rest }
    'restore'  { Do-Restore $rest }
    'deleteworld' { if (Test-Path $worldDir) { Remove-Item $worldDir -Recurse -Force }; Say '[post] world deleted' }
    'sleep'    { Start-Sleep -Seconds ([int]$rest) }
    'jfrprint' {
      $jfrFile = Join-Path $runDir 'cava.jfr'
      if (-not (Test-Path $jfrFile)) { Say '[post] no cava.jfr'; }
      else {
        $jfrExe = 'C:\Program Files\Java\jdk-21\bin\jfr.exe'
        $samples = Join-Path $runDir 'jfr-samples.txt'
        & $jfrExe print --events jdk.ExecutionSample --stack-depth 40 $jfrFile 2>&1 | Set-Content -Encoding UTF8 $samples
        Say ("[post] jfr samples -> $samples (" + (Get-Item $samples).Length + ' bytes)')
      }
    }
    default    { throw "unknown [post] step: $step" }
  }
}

# ---------- manifest ----------
$mods = @()
foreach ($m in (Get-ChildItem (Join-Path $Root 'server\mods') -Filter *.jar | Sort-Object Name)) {
  $mods += [pscustomobject]@{ name = $m.Name; bytes = $m.Length; sha256 = (Get-FileHash -LiteralPath $m.FullName -Algorithm SHA256).Hash }
}
$propsPath = Join-Path $Root 'server\server.properties'
$manifest = [ordered]@{
  name = $Name
  scenario = (Resolve-Path $Scenario).Path
  startedAt = $srv.Started.ToString('o')
  finishedAt = (Get-Date).ToString('o')
  cmdline = $srv.CommandLine
  javaExe = $JavaExe
  doneLine = $doneLine
  serverPropertiesSha256 = (Get-FileHash $propsPath -Algorithm SHA256).Hash
  modCount = $mods.Count
  mods = $mods
}
$manifest | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 (Join-Path $runDir 'manifest.json')
Say "[scenario] manifest: $(Join-Path $runDir 'manifest.json')  mods=$($mods.Count)"
Say "[scenario] transcript: $transcript"
if ($stepError) { throw $stepError }
