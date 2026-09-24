<#
crash-probe.ps1 -- P4-C item 1: produce REAL hs_err logs and prove the report can tell them apart.

What it does:
  1. builds the deliberately broken probes        (native/tests/crashprobe/build-crashprobe.ps1)
  2. compiles the Java FFM caller                 (tools/CrashProbe.java + tools/CavaArtifactProbe.java)
  3. runs the JVM three times, each run crashing on purpose through a java.lang.foreign downcall:
       (A) crashprobe.dll            -> problem frame in the probe        -> NOT_CAVA_PROBE
       (B) othermod.dll              -> problem frame in another module   -> NOT_CAVA
       (C) crashprobe_cava_proxy.dll -> problem frame in a module named
                                        cava.dll                         -> CAVA_NATIVE_FAULT
     Each run MUST exit non-zero (0xC0000005 = 3221225477 on Windows) and MUST leave exactly one
     hs_err_pid<PID>.log behind. A run that exits 0 means the probe did not crash and every
     conclusion drawn from the logs would be worthless, so this script fails in that case.
  4. runs tools/hs-err-report.ps1 on all three logs plus one deliberately corrupt fixture and
     asserts the four verdicts DIFFER (a parser that says the same thing for every input is not
     a parser, it is a rubber stamp).

WER / hang note (asked for explicitly): the runs use "-XX:-CreateCoredumpOnCrash" so the Windows
Error Reporting dialog cannot appear, and every run is wrapped in WaitForExit(<timeout>). If a run
does hang, the script kills it and reports "TIMED OUT" rather than waiting forever.

Usage:
  pwsh -NoProfile -ExecutionPolicy Bypass -File tools/crash-probe.ps1
  pwsh -NoProfile -ExecutionPolicy Bypass -File tools/crash-probe.ps1 -SkipBuild

NOTE: keep this file ASCII-only.
#>
param(
    [switch]$SkipBuild,
    [int]$TimeoutSec = 60
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Stop'

$root = $PSScriptRoot | Split-Path -Parent
$javac = 'C:\Program Files\Java\jdk-21\bin\javac.exe'
$java  = 'C:\Program Files\Java\jdk-21\bin\java.exe'
foreach ($p in @($javac, $java)) { if (-not (Test-Path $p)) { Write-Output "MISSING: $p"; exit 2 } }

$out    = Join-Path $root 'build\p4c-crash'
$tmp    = Join-Path $out 'tmp'
$cls    = Join-Path $out 'classes'
$errDir = Join-Path $root 'build\p4c-hs-err'
New-Item -ItemType Directory -Force -Path $out, $tmp, $cls, $errDir | Out-Null
$env:TMP = $tmp
$env:TEMP = $tmp
# The "cava.dll frame" probe loads its stub by name; that lookup does not search the DLL's own
# directory (measured), so the probe directory goes on PATH for the child JVM.
$env:PATH = $out + ';' + $env:PATH

function Fail([string]$why) { Write-Output "CRASH-PROBE: FAIL -- $why"; exit 1 }

if (-not $SkipBuild) {
    Write-Output ">>> build the broken probes"
    & pwsh -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root 'native\tests\crashprobe\build-crashprobe.ps1')
    if ($LASTEXITCODE -ne 0) { Fail "probe build failed" }

    Write-Output ">>> compile tools/CrashProbe.java + tools/CavaArtifactProbe.java"
    $af = Join-Path $tmp 'crash-probe-javac.txt'
    [System.IO.File]::WriteAllLines($af, [string[]]@(
        '-encoding','UTF-8','--release','21','--enable-preview','-d',$cls,
        (Join-Path $root 'tools\CavaArtifactProbe.java'),
        (Join-Path $root 'tools\CrashProbe.java')), (New-Object System.Text.UTF8Encoding($false)))
    & $javac "@$af" 2>&1 | ForEach-Object { Write-Output "  | $_" }
    if ($LASTEXITCODE -ne 0) { Fail "javac failed" }
}

$cavaDll = Join-Path $root 'natives\windows-x64\cava.dll'
if (-not (Test-Path $cavaDll)) { Write-Output "WARNING: $cavaDll not found -- --load-cava is skipped" }

# ---------------------------------------------------------------- one crash run
function Invoke-CrashRun([string]$Label, [string]$ProbeDll, [string]$Symbol) {
    $logFile   = Join-Path $out ("run-" + $Label + ".log")
    $stdoutFile = Join-Path $out ("run-" + $Label + ".stdout.txt")
    $stderrFile = Join-Path $out ("run-" + $Label + ".stderr.txt")
    $errPattern = Join-Path $errDir ("hs_err_pid%p_" + $Label + ".log")
    Get-ChildItem $errDir -Filter ("hs_err_pid*_" + $Label + ".log") -ErrorAction SilentlyContinue | Remove-Item -Force

    # JAVA_TOOL_OPTIONS must be EMPTY: it would be prepended and would also fight -XX:ErrorFile.
    $env:JAVA_TOOL_OPTIONS = $null
    $args = @(
        '--enable-preview', '--enable-native-access=ALL-UNNAMED',
        ('-XX:ErrorFile=' + $errPattern),
        '-XX:-CreateCoredumpOnCrash',
        '-cp', $cls, 'CrashProbe', $ProbeDll
    )
    if ($Symbol -ne '') { $args += ('--symbol=' + $Symbol) }
    if (Test-Path $cavaDll) { $args += ('--load-cava=' + $cavaDll) }

    # Write-Host (not Write-Output) for everything inside this function: Write-Output would be
    # captured into the caller's "$runA = Invoke-CrashRun ..." and never reach the console.
    Write-Host ""
    Write-Host "=== crash run [$Label] ==="
    Write-Host ("    dll      : " + $ProbeDll)
    Write-Host ("    symbol   : " + $(if ($Symbol -ne '') { $Symbol } else { 'cava_crash_probe_null_deref' }))
    Write-Host ("    errorfile: " + $errPattern)
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    # System.Diagnostics.Process (not Start-Process -PassThru): after a hard native fault
    # Start-Process reports ExitCode as unavailable, which would hide the single most important
    # fact of the run -- that the process really did die with 0xC0000005 (3221225477).
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $java
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.Arguments = (($args | ForEach-Object { if ($_ -match '[ "]') { '"' + $_ + '"' } else { $_ } }) -join ' ')
    $psi.WorkingDirectory = $tmp
    $proc = New-Object System.Diagnostics.Process
    $proc.StartInfo = $psi
    $proc.Start() | Out-Null
    $stdoutTask = $proc.StandardOutput.ReadToEndAsync()
    $stderrTask = $proc.StandardError.ReadToEndAsync()
    $finished = $proc.WaitForExit($TimeoutSec * 1000)
    $sw.Stop()
    $timedOut = $false
    if (-not $finished) {
        $timedOut = $true
        Write-Host ("    *** TIMED OUT after $($sw.ElapsedMilliseconds) ms -- killing pid $($proc.Id) ***")
        try { $proc.Kill(); $proc.WaitForExit(10000) | Out-Null } catch { }
    }
    [System.IO.File]::WriteAllText($stdoutFile, $stdoutTask.Result)
    [System.IO.File]::WriteAllText($stderrFile, $stderrTask.Result)
    $code = $null
    try { $code = $proc.ExitCode } catch { $code = $null }
    $logs = @(Get-ChildItem $errDir -Filter ("hs_err_pid*_" + $Label + ".log") -ErrorAction SilentlyContinue)
    Write-Host ("    wallclock: $($sw.ElapsedMilliseconds) ms   timedOut=$timedOut")
    Write-Host ("    exit code: " + $(if ($null -eq $code) { '(unavailable -- killed?)' } else { "$code (0x$('{0:X8}' -f $code))" }))
    Write-Host ("    hs_err   : " + $(if ($logs.Count -gt 0) { "$($logs[0].Name) $($logs[0].Length) B" } else { 'NONE' }))
    Write-Host "    --- stdout ---"
    Get-Content $stdoutFile -ErrorAction SilentlyContinue | ForEach-Object { Write-Host "    | $_" }
    Write-Host "    --- stderr ---"
    Get-Content $stderrFile -ErrorAction SilentlyContinue | ForEach-Object { Write-Host "    | $_" }

    return [pscustomobject]@{
        Label = $Label; Exit = $code; TimedOut = $timedOut
        Log = $(if ($logs.Count -gt 0) { $logs[0].FullName } else { '' })
    }
}

$runA = Invoke-CrashRun 'probe'      (Join-Path $out 'crashprobe.dll')
$runB = Invoke-CrashRun 'othermod'   (Join-Path $out 'othermod.dll')
$runC = Invoke-CrashRun 'cavaframe'  (Join-Path $out 'crashprobe_cava_proxy.dll') 'crashprobe_through_cava'

# ---------------------------------------------------------------- corrupt fixture
$corruptDir = Join-Path $root 'native\tests\vectors'
New-Item -ItemType Directory -Force -Path $corruptDir | Out-Null
$corrupt = Join-Path $corruptDir 'hs_err_truncated_fixture.log'
if (-not (Test-Path $corrupt)) { Fail "missing the hand-made corrupt fixture: $corrupt" }

# ---------------------------------------------------------------- verdicts
function Invoke-Report([string]$Label, [string]$LogPath) {
    Write-Host ""
    Write-Host ("--- report [$Label] on " + $(if ($LogPath -eq '') { '(NO LOG -- the crash run produced none)' } else { $LogPath }) + " ---")
    if ($LogPath -eq '') {
        return [pscustomobject]@{ Label = $Label; Rc = -1; Verdict = '(no log)' }
    }
    $reportScript = Join-Path $root 'tools\hs-err-report.ps1'
    $lines = @(& pwsh -NoProfile -ExecutionPolicy Bypass -File $reportScript -Log $LogPath 2>&1)
    $rc = $LASTEXITCODE
    foreach ($l in $lines) { Write-Host ("  | " + $l) }
    # the script's own last line is "VERDICT <name>"; match the bare word after it
    $verdict = ''
    foreach ($l in $lines) {
        if ($l -match '^VERDICT[ ]+([A-Za-z_]+)[ ]*$') { $verdict = $Matches[1] }
        if ($l -match '^verdict[ ]+:[ ]+([A-Za-z_]+)') { $verdict = $Matches[1] }
    }
    if ($verdict -eq '') {
        foreach ($l in $lines) { if ($l -match 'verdict[ ]+verdict[ ]+([A-Za-z_]+)') { $verdict = $Matches[1] } }
    }
    return [pscustomobject]@{
        Label = $Label; Rc = $rc
        Verdict = $(if ($verdict -ne '') { $verdict } else { '(none)' })
    }
}

Write-Host ""
Write-Host "================= crash forensics verdicts ================="
$vA = Invoke-Report 'A: probe frame'      $runA.Log
$vB = Invoke-Report 'B: other module'     $runB.Log
$vC = Invoke-Report 'C: cava.dll frame'   $runC.Log
$vD = Invoke-Report 'D: corrupt fixture'  $corrupt

$fail = 0
function Check([bool]$ok, [string]$what) {
    if ($ok) { Write-Output "  ok   : $what" } else { Write-Output "  FAIL : $what"; $script:fail++ }
}

Write-Output ""
Write-Output "================= assertions ================="
Check ($runA.Exit -ne 0) "run A exited non-zero (the JVM really died): $($runA.Exit)"
Check (-not $runA.TimedOut) "run A did not hit the $TimeoutSec s timeout"
Check ($runA.Log -ne '') "run A produced an hs_err log"
Check ($runB.Exit -ne 0) "run B exited non-zero: $($runB.Exit)"
Check ($runB.Log -ne '') "run B produced an hs_err log"
Check ($runC.Exit -ne 0) "run C exited non-zero: $($runC.Exit)"
Check ($runC.Log -ne '') "run C produced an hs_err log"
Check ($vA.Verdict -eq 'NOT_CAVA_PROBE') "A verdict NOT_CAVA_PROBE (actual $($vA.Verdict)), exit=$($vA.Rc)"
Check ($vB.Verdict -eq 'NOT_CAVA')       "B verdict NOT_CAVA (actual $($vB.Verdict)), exit=$($vB.Rc)"
Check ($vC.Verdict -eq 'CAVA_NATIVE_FAULT') "C verdict CAVA_NATIVE_FAULT (actual $($vC.Verdict)), exit=$($vC.Rc)"
Check ($vD.Verdict -eq 'PARSE_FAILED')   "D verdict PARSE_FAILED (actual $($vD.Verdict)), exit=$($vD.Rc)"
Check ($vD.Rc -eq 2) "D exited with code 2 (non-zero for unusable input), actual $($vD.Rc)"
$distinct = @($vA.Verdict, $vB.Verdict, $vC.Verdict, $vD.Verdict) | Select-Object -Unique
Check ($distinct.Count -eq 4) "the four verdicts are all DIFFERENT: $($distinct -join ', ')"

Write-Output ""
if ($fail -gt 0) { Write-Output "CRASH-PROBE: FAIL ($fail)"; exit 1 }
Write-Output "CRASH-PROBE: PASS"
exit 0
