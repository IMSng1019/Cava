# hs-err-report.ps1 -- P4-C item 1: turn an hs_err_pid*.log into a "whose bug is this" verdict.
#
# WHY THIS EXISTS
#   P4 hardening lists "crash forensics: keep the hs_err parser" and P4-A left it undone
#   (docs/CAVA-hardening-notes.md section 7 item 2). A parser that has only ever been run on a
#   hand-written sample proves nothing, so this one is exercised against three inputs whose
#   verdicts MUST differ (tools/crash-probe.ps1 runs all three):
#     A) a REAL crash produced by crashprobe.dll          -> NOT_CAVA_PROBE   (exit 1)
#     B) a real crash with the frame in another module    -> NOT_CAVA          (exit 1)
#     C) a truncated / syntactically broken log           -> PARSE_FAILED      (exit 2)
#   and the same parser is also run against a crash whose problem frame IS in cava.dll
#   (crashprobe.dll forwards the call into cava.dll) -> CAVA_NATIVE_FAULT      (exit 0).
#
# WHAT IT DECIDES
#   The ONLY question that matters for triage: is the problem frame inside a module that came
#   from Cava? That is a MODULE question, not a TEXT question -- crashprobe.dll exports
#   "cava_crash_probe_null_deref", so a grep-based tool would wrongly blame Cava. This script
#   resolves the frame's module from the "Dynamic libraries" section first and only then looks
#   for cava_* symbols.
#
# OUTPUT: a report on stdout; the verdict also goes to the last line for grep-ability.
#
# Usage:
#   pwsh -NoProfile -ExecutionPolicy Bypass -File tools/hs-err-report.ps1 -Log <hs_err path> [-CavaDll <path>] [-Json]
#
# Exit codes:  0 = CAVA_NATIVE_FAULT (problem frame in cava.dll)
#              1 = NOT_CAVA*        (problem frame is in some other module)
#              2 = PARSE_FAILED     (input is not a usable hs_err log -- never a silent "no problem")
#
# NOTE: keep this file ASCII-only. HARD REQUIREMENT: the log is read as Windows-1252 because a
#       real hs_err file contains the raw environment block, and this machine's %PATH% holds
#       non-UTF-8 bytes; [IO.File]::ReadAllText with a UTF-8 default throws on that (observed).

param(
    [Parameter(Mandatory = $true)][string]$Log,
    [string]$CavaDll = '',
    [switch]$Json,
    [switch]$Quiet
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Stop'

$root = $PSScriptRoot | Split-Path -Parent
if ($CavaDll -eq '') { $CavaDll = Join-Path $root 'natives\windows-x64\cava.dll' }

function Write-Line([string]$service, [string]$key, [string]$value) {
    if ($Quiet) { return }
    Write-Output ("{0,-16} {1,-24} {2}" -f $service, $key, $value)
}

function Fail-Parse([string]$why) {
    if (-not $Quiet) {
        Write-Output "===================== crash forensics report ====================="
        Write-Output ("input            : " + $Log)
        Write-Output ("PARSE FAILED     : " + $why)
        Write-Output "verdict          : PARSE_FAILED"
        Write-Output "reason           : the input is not a usable hs_err log, so NO verdict is rendered."
        Write-Output "                   (this is deliberate: an unparsable log must never look like 'no"
        Write-Output "                    problem found'. Collect the real hs_err file and re-run.)"
        Write-Output "exit             : 2"
    }
    exit 2
}

if (-not (Test-Path -LiteralPath $Log)) { Fail-Parse "file does not exist: $Log" }
$fi = Get-Item -LiteralPath $Log
if ($fi.Length -eq 0) { Fail-Parse "file is empty (0 bytes): $Log" }

$text  = [System.IO.File]::ReadAllText($fi.FullName, [System.Text.Encoding]::GetEncoding(1252))
$lines = $text -split "\r?\n"

# ---------------------------------------------------------------- required sections
$hasBanner = $text -match 'A fatal error has been detected by the Java Runtime Environment'
$hasFrame  = $text -match '(?m)^#\s*Problematic frame:'
$hasLibs   = $text -match '(?m)^Dynamic libraries:'
if (-not $hasBanner) { Fail-Parse "missing the '# A fatal error has been detected...' banner -- this is not an hs_err log" }
if (-not $hasFrame)  { Fail-Parse "missing the '# Problematic frame:' block -- the log is truncated or corrupt" }
if (-not $hasLibs)   { Fail-Parse "missing the 'Dynamic libraries:' section -- cannot resolve which module the frame belongs to" }

# ---------------------------------------------------------------- header fields
$sigLine   = ($lines | Where-Object { $_ -match '^\#\s+(SIG[A-Z]+|EXCEPTION_[A-Z_]+)\s+\(' } | Select-Object -First 1)
$sigText   = ''
$sigName   = ''
$pcRaw     = ''
if ($sigLine) {
    $sigText = $sigLine.TrimStart('#').Trim()
    if ($sigLine -match '^\#\s+([A-Z_]+)\s+\(') { $sigName = $Matches[1] }
    if ($sigLine -match 'pc=0x([0-9a-fA-F]+)') { $pcRaw = $Matches[1] }
}
$jreLine   = ($lines | Where-Object { $_ -match '^\#\s*JRE version:' } | Select-Object -First 1)
$vmLine    = ($lines | Where-Object { $_ -match '^\#\s*Java VM:' } | Select-Object -First 1)
$cmdLine   = ($lines | Where-Object { $_ -match '^Command Line:' } | Select-Object -First 1)
$jvmArgs   = ($lines | Where-Object { $_ -match '^jvm_args:' } | Select-Object -First 1)
$javaCmd   = ($lines | Where-Object { $_ -match '^java_command:' } | Select-Object -First 1)
$javaCp    = ($lines | Where-Object { $_ -match '^java_class_path \(initial\):' } | Select-Object -First 1)

# problematic frame: the line(s) right after the header, before the next blank comment line
$frameHeader = -1
for ($i = 0; $i -lt $lines.Count; $i++) { if ($lines[$i] -match '^#\s*Problematic frame:') { $frameHeader = $i; break } }
$frameText = ''
for ($i = $frameHeader + 1; $i -lt $lines.Count; $i++) {
    $l = $lines[$i]
    if ($l -match '^#\s*$') { break }
    $frameText = $frameText + ' ' + ($l -replace '^#\s*', '')
}
$frameText = $frameText.Trim()

# ---------------------------------------------------------------- module resolution
# "0x00007ffb38c10000 - 0x00007ffb38d37000 \tJ:\...\cava.dll"
$modules = @()
$inLibs = $false
foreach ($l in $lines) {
    if ($l -match '^Dynamic libraries:') { $inLibs = $true; continue }
    if ($inLibs) {
        if ($l -match '^\s*$') { continue }
        if ($l -match '^0x([0-9a-fA-F]+)\s*-\s*0x([0-9a-fA-F]+)\s+(.+)$') {
            $modules += [pscustomobject]@{
                Start = [uint64]::Parse($Matches[1], [System.Globalization.NumberStyles]::HexNumber)
                End   = [uint64]::Parse($Matches[2], [System.Globalization.NumberStyles]::HexNumber)
                Path  = $Matches[3].Trim()
                Name  = (Split-Path -Leaf $Matches[3].Trim())
            }
        } elseif ($l -match '^\w') { break }   # a new top-level section starts
    }
}
if ($modules.Count -eq 0) { Fail-Parse "the 'Dynamic libraries:' section contains no parseable module lines" }

function Resolve-Module([string]$text) {
    # frame text forms: "C  [cava.dll+0x1234]", "V  [jvm.dll+0x1234]", "J 1234 c2 ...", "j  pkg.Class.m()V+1"
    if ($text -match '\[([^\[\]]+)\+0x([0-9a-fA-F]+)\]') {
        $name = $Matches[1]; $off = $Matches[2]
        $hit = $modules | Where-Object { $_.Name -ieq $name } | Select-Object -First 1
        return [pscustomobject]@{ Kind = 'c'; Module = $name; Offset = ('0x' + $off); Path = $(if ($hit) { $hit.Path } else { '' }); Base = $(if ($hit) { $hit.Start } else { 0 }) }
    }
    if ($text -match '^\s*J\s+\d+\s') { return [pscustomobject]@{ Kind = 'j'; Module = 'JIT-compiled Java'; Offset = ''; Path = ''; Base = 0 } }
    if ($text -match '^\s*j\s')     { return [pscustomobject]@{ Kind = 'j'; Module = 'interpreted Java'; Offset = ''; Path = ''; Base = 0 } }
    if ($text -match '^\s*V\s')     { return [pscustomobject]@{ Kind = 'v'; Module = 'JVM internal'; Offset = ''; Path = ''; Base = 0 } }
    return [pscustomobject]@{ Kind = '?'; Module = $text; Offset = ''; Path = ''; Base = 0 }
}
$problem = Resolve-Module $frameText

# ---------------------------------------------------------------- cava symbols / frames
$cavaSymLines = @($lines | Where-Object { $_ -match 'cava_' })
# native frames block
$nfStart = -1
for ($i = 0; $i -lt $lines.Count; $i++) { if ($lines[$i] -match '^Native frames:') { $nfStart = $i; break } }
$nativeFrames = @()
if ($nfStart -ge 0) {
    for ($i = $nfStart + 1; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match '^\s*$' -or $lines[$i] -match '^Java frames:') { break }
        $nativeFrames += $lines[$i]
    }
}
$cavaFrames = @($nativeFrames | Where-Object { $_ -match 'cava\.dll' })

# ---------------------------------------------------------------- module fingerprint
$cavaModule = $modules | Where-Object { $_.Name -ieq 'cava.dll' } | Select-Object -First 1
$cavaOnDisk = $null
$cavaSha    = ''
$cavaBuild  = ''
$cavaLayout = ''
$cavaEntries = ''
if ($cavaModule) {
    $cavaOnDisk = $cavaModule.Path
    if (Test-Path -LiteralPath $cavaOnDisk) {
        $cavaSha = (Get-FileHash -Algorithm SHA256 -LiteralPath $cavaOnDisk).Hash
        $probeSrc = Join-Path $PSScriptRoot 'CavaArtifactProbe.java'
        $probeCls = Join-Path $root 'build\p4c-crash\classes'   # shared with tools/crash-probe.ps1
        $javaExe  = 'C:\Program Files\Java\jdk-21\bin\java.exe'
        $probeOut = ''
        $javacExe = 'C:\Program Files\Java\jdk-21\bin\javac.exe'
        if ((Test-Path $probeSrc) -and (Test-Path $javaExe)) {
            $tmp = Join-Path $root 'build\p4c-crash\tmp'
            New-Item -ItemType Directory -Force -Path $tmp | Out-Null
            if (-not (Test-Path (Join-Path $probeCls 'CavaArtifactProbe.class')) -and (Test-Path $javacExe)) {
                # compile on demand so this script keeps working on a clean checkout
                New-Item -ItemType Directory -Force -Path $probeCls | Out-Null
                $caf = Join-Path $tmp 'hs-err-probe-javac.txt'
                [System.IO.File]::WriteAllLines($caf, [string[]]@('--release','21','--enable-preview','-d',$probeCls,$probeSrc), (New-Object System.Text.UTF8Encoding($false)))
                try { & $javacExe "@$caf" 2>&1 | Out-Null } catch { }
            }
            $af = Join-Path $tmp 'hs-err-probe-args.txt'
            [System.IO.File]::WriteAllLines($af, [string[]]@('--enable-preview','--enable-native-access=ALL-UNNAMED','-cp',$probeCls,'CavaArtifactProbe',$cavaOnDisk), (New-Object System.Text.UTF8Encoding($false)))
            try { $probeOut = (& $javaExe "@$af" 2>&1 | Select-Object -Last 1) } catch { $probeOut = '' }
            if ($probeOut -match 'build_id=(.*?) abi_version=')    { $cavaBuild   = $Matches[1] }
            if ($probeOut -match 'layout_entries=(\d+)')           { $cavaEntries = $Matches[1] }
            if ($probeOut -match 'layout_sum=0x([0-9A-Fa-f]+)')    { $cavaLayout  = '0x' + $Matches[1].ToUpper() }
        }
    }
}

# does the dump show an address inside cava.dll? (the "#" pc= is a runtime VA and does not match
# the dump-time mappings, so only the register/stack blocks are searched)
$cavaInStack  = ($cavaFrames.Count -gt 0)
$cavaAddrHit  = ''
if ($cavaModule) {
    foreach ($l in $lines) {
        foreach ($m in [regex]::Matches($l, '0x([0-9a-fA-F]{6,16})')) {
            $v = [uint64]::Parse($m.Groups[1].Value, [System.Globalization.NumberStyles]::HexNumber)
            if ($v -ge $cavaModule.Start -and $v -lt $cavaModule.End) {
                $cavaAddrHit = ('0x{0:x} (cava.dll+0x{1:x})' -f $v, ($v - $cavaModule.Start)); break
            }
        }
        if ($cavaAddrHit -ne '') { break }
    }
}

# ---------------------------------------------------------------- verdict
# The decision rule (one sentence): the problem frame's MODULE decides.
$verdict = 'INCONCLUSIVE'
$reason  = ''
switch -Regex ($problem.Module) {
    '^cava\.dll$' {
        $verdict = 'CAVA_NATIVE_FAULT'
        $reason  = 'the problem frame is INSIDE cava.dll -- this is a Cava native bug; collect the dll + this log'
    }
    '^crashprobe' {
        $verdict = 'NOT_CAVA_PROBE'
        $reason  = 'the problem frame is in the crash-probe module (a deliberately broken test dll), not in cava.dll'
    }
    default {
        $verdict = 'NOT_CAVA'
        $reason  = ('the problem frame is in module "{0}", which is not cava.dll' -f $problem.Module)
    }
}
if ($cavaModule -and $problem.Module -ieq 'cava.dll') {
    $reason = 'problem frame resolves to cava.dll -- Cava native fault confirmed by the module table'
}
if ($cavaSymLines.Count -gt 0 -and $problem.Module -inotmatch 'cava\.dll') {
    $reason = $reason + ('; CAUTION: {0} line(s) in this log mention "cava_" but the frame is NOT in cava.dll -- ' -f $cavaSymLines.Count) +
              'do not blame Cava from a text match alone'
}

# ---------------------------------------------------------------- report
if (-not $Quiet) {
    Write-Output "===================== crash forensics report ====================="
    Write-Line 'input'      'log'         $fi.FullName
    Write-Line 'input'      'size/bytes'  $fi.Length
    Write-Line 'header'     'signal'      ($(if ($sigText) { $sigText } else { '(not found)' }))
    Write-Line 'header'     'signal-name' ($(if ($sigName) { $sigName } else { '(not found)' }))
    Write-Line 'header'     'jre'         ($(if ($jreLine) { ($jreLine -replace '^#\s*JRE version:\s*','') } else { '(not found)' }))
    Write-Line 'header'     'vm'          ($(if ($vmLine) { ($vmLine -replace '^#\s*Java VM:\s*','') } else { '(not found)' }))
    Write-Line 'header'     'java-cmd'    ($(if ($javaCmd) { ($javaCmd -replace '^java_command:\s*','') } else { '(not found)' }))
    Write-Line 'header'     'jvm-args'    ($(if ($jvmArgs) { ($jvmArgs -replace '^jvm_args:\s*','') } else { '(not found)' }))
    Write-Line 'header'     'classpath'   ($(if ($javaCp) { ($javaCp -replace '^java_class_path \(initial\):\s*','') } else { '(not found)' }))
    Write-Line 'header'     'full-cmdline' ($(if ($cmdLine) { ($cmdLine -replace '^Command Line:\s*','') } else { '(not found)' }))
    Write-Output ''
    Write-Line 'problem-frame' 'raw'      $frameText
    Write-Line 'problem-frame' 'module'   $problem.Module
    Write-Line 'problem-frame' 'offset'   ($(if ($problem.Offset) { $problem.Offset } else { '(n/a)' }))
    Write-Line 'problem-frame' 'module-path' ($(if ($problem.Path) { $problem.Path } else { '(not in the dynamic libraries table)' }))
    Write-Output ''
    Write-Line 'cava' 'in-stack'          ($(if ($cavaInStack) { "YES ($($cavaFrames.Count) frame(s))" } else { 'no' }))
    foreach ($f in $cavaFrames) { Write-Line 'cava' 'stack-frame' ($f.Trim()) }
    Write-Line 'cava' 'symbols-matching-cava_' ($cavaSymLines.Count)
    foreach ($l in ($cavaSymLines | Select-Object -First 5)) { Write-Line 'cava' 'symbol-line' ($l.Trim()) }
    Write-Line 'cava' 'module-in-dump'    ($(if ($cavaModule) { ('0x{0:x} - 0x{1:x}' -f $cavaModule.Start, $cavaModule.End) } else { 'NOT LOADED in this process' }))
    Write-Line 'cava' 'module-path'       ($(if ($cavaModule) { $cavaModule.Path } else { '(n/a)' }))
    Write-Line 'cava' 'address-in-cava'   ($(if ($cavaAddrHit) { $cavaAddrHit } else { 'none found in the dumped registers/stack' }))
    Write-Line 'cava' 'on-disk-sha256'    ($(if ($cavaSha) { $cavaSha } else { '(not found at the path named in the log)' }))
    Write-Line 'cava' 'build-id'          ($(if ($cavaBuild) { $cavaBuild } else { '(probe unavailable)' }))
    Write-Line 'cava' 'layout'            ($(if ($cavaLayout) { "$cavaEntries entries, sum=$cavaLayout" } else { '(probe unavailable)' }))
    Write-Output ''
    Write-Line 'verdict' 'verdict'        $verdict
    Write-Line 'verdict' 'reason'         $reason
    Write-Line 'verdict' 'exit'           $(if ($verdict -eq 'CAVA_NATIVE_FAULT') { 0 } else { 1 })
    Write-Output "================================================================="
    Write-Output ("VERDICT " + $verdict)
}
if ($Json) {
    [pscustomobject]@{
        log = $fi.FullName; signal = $sigName; frame = $frameText; module = $problem.Module
        cavaInStack = $cavaInStack; cavaBuildId = $cavaBuild; cavaLayoutSum = $cavaLayout
        cavaSha256 = $cavaSha; verdict = $verdict; reason = $reason
    } | ConvertTo-Json -Compress | Write-Output
}

if ($verdict -eq 'CAVA_NATIVE_FAULT') { exit 0 }
exit 1
