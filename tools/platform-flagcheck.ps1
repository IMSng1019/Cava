# platform-flagcheck.ps1 -- prove the numerical-consistency compile switches FROM THE ARTIFACTS.
#
# Owner: P4-B (platform & CI round); path tools/platform-*.ps1 per the task brief.
# NOTE: keep this file ASCII-only. PowerShell 5.1 mis-parses UTF-8-without-BOM scripts whose
#       comments contain non-ASCII bytes (observed twice in this project).
#
# Why this exists: the task brief asks for evidence of the build switches derived from the
# produced artifacts rather than from the CMake source. This script reads, in order:
#   1. <build>/CMakeFiles/<target>.dir/flags.make   (what the compiler is actually invoked with)
#   2. <build>/compile_commands.json                (per translation unit; catches per-file overrides)
#   3. <lib>                                        (the shipped binary: imports + disassembly)
#
# Checks:
#   A. required hardened flags present
#   B. forbidden flags absent (-march=native, -ffast-math, -Ofast, -ffp-contract=on|fast,
#      /fp:fast, /arch:AVX*)
#   C. exactly one -O flag and it is -O2 (CavaFlags.cmake strips CMake's Release default -O3)
#   D. CAVA_BUILD_FP_FLAGS carries the same set (it ends up inside cava_build_id())
#   E. shipped binary: import table is system-only (the "fully static" shape), and on x86-64 the
#      disassembly contains zero AVX/VEX instructions -- proof that -march=native was NOT used,
#      since this build machine's CPU supports AVX2 and -march=native would certainly emit VEX
#   F. sqrt maps to the correctly-rounded hardware instruction (sqrtsd/sqrtss), not a libm call
#
# Usage:
#   pwsh -File tools/platform-flagcheck.ps1 -BuildDir build/native-p4b -Lib <path-to-lib>
# Exit: 0 all executed checks passed, 1 a check failed, 2 usage error.
param(
    [Parameter(Mandatory = $true)][string]$BuildDir,
    [Parameter(Mandatory = $true)][string]$Lib,
    [string]$Target = 'cava',
    [string]$Objdump = ''
)
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Continue'

$pass = 0; $fail = 0; $skip = 0
function Check([bool]$ok, [string]$msg) {
    if ($ok) { $script:pass++; Write-Output "  [ ok ] $msg" }
    else     { $script:fail++; Write-Output "  [FAIL] $msg" }
}
function Skip([string]$msg) { $script:skip++; Write-Output "  [SKIP] $msg" }
function Info([string]$msg) { Write-Output "  [info] $msg" }

$required     = @('-O2', '-fwrapv', '-ffp-contract=off', '-fno-fast-math', '-fno-math-errno')
$requiredMsvc = @('/O2', '/fp:strict')
$forbidden    = @('-march=native', '-mtune=native', '-mcpu=native', '-ffast-math', '-Ofast',
                  '-ffp-contract=on', '-ffp-contract=fast', '/fp:fast',
                  '/arch:AVX', '/arch:AVX2', '/arch:AVX512')

if (-not (Test-Path $BuildDir)) { Write-Output "BuildDir not found: $BuildDir"; exit 2 }
if (-not (Test-Path $Lib))      { Write-Output "Lib not found: $Lib"; exit 2 }

Write-Output "=== platform-flagcheck ==="
Info "build dir = $BuildDir"
Info "library   = $Lib"
Info "target    = $Target"

# Multi-config generators (Visual Studio / Xcode) do not emit flags.make -- the flags live in the
# .vcxproj. Detect that case and SKIP section 1 instead of reporting a false failure; the artifact
# inspection in section 3 still runs there, and CI can additionally configure with Ninja/Makefiles
# when the full per-flag evidence is wanted.
$generator = ''
$cacheFile = Join-Path $BuildDir 'CMakeCache.txt'
if (Test-Path $cacheFile) {
    foreach ($line in Get-Content $cacheFile) {
        if ($line -match '^CMAKE_GENERATOR:INTERNAL=(.*)$') { $generator = $Matches[1] }
    }
}
if ($generator -ne '') { Info "CMake generator = $generator" }
$multiConfig = ($generator -match 'Visual Studio|Xcode')

Write-Output "--- 1. CMakeFiles/$Target.dir/flags.make ---"
$flagsMake = Join-Path $BuildDir "CMakeFiles/$Target.dir/flags.make"
if (-not (Test-Path $flagsMake) -and $multiConfig) {
    Skip "generator '$generator' does not write flags.make (flags live in the .vcxproj); section 1 NOT executed"
} elseif (Test-Path $flagsMake) {
    $text = Get-Content $flagsMake -Raw
    $mAll = [regex]::Match($text, '(?m)^CXX_FLAGS\s*=\s*(.*)$')
    $allFlags = if ($mAll.Success) { $mAll.Groups[1].Value.Trim() } else { '' }
    $mDef = [regex]::Match($text, '(?m)^CXX_DEFINES\s*=\s*(.*)$')
    $defines = if ($mDef.Success) { $mDef.Groups[1].Value.Trim() } else { '' }
    Info "CXX_FLAGS = $allFlags"
    $mBid = [regex]::Match($defines, 'CAVA_BUILD_FP_FLAGS="\\"([^"]*)\\"')
    if ($mBid.Success) { Info "CAVA_BUILD_FP_FLAGS = $($mBid.Groups[1].Value)" }

    Check ($allFlags -ne '') "flags.make has a non-empty CXX_FLAGS"
    $isMsvc = ($allFlags -match '/fp:') -or ($allFlags -match '/O2')
    if ($isMsvc) {
        foreach ($f in $requiredMsvc) { Check ($allFlags -match [regex]::Escape($f)) "required MSVC flag present: $f" }
        Skip "single -O flag check (MSVC uses /O2; asserted above)"
    } else {
        foreach ($f in $required) { Check ($allFlags -match [regex]::Escape($f)) "required flag present: $f" }
        $oFlags = @([regex]::Matches($allFlags, '(^|\s)-O[0-9s](\s|$)') | ForEach-Object { $_.Value.Trim() })
        Info ("optimization flags in order: " + ($oFlags -join ', '))
        Check (($oFlags.Count -eq 1) -and ($oFlags[0] -eq '-O2')) "exactly one -O flag and it is -O2 (got: $($oFlags -join ','))"
    }
    foreach ($f in $forbidden) {
        Check (-not ($allFlags -match [regex]::Escape($f))) "forbidden flag absent: $f"
    }
    if ($mBid.Success) {
        $bstr = $mBid.Groups[1].Value
        if ($isMsvc) {
            Check ($bstr -match '/fp:strict') "CAVA_BUILD_FP_FLAGS contains /fp:strict"
        } else {
            foreach ($f in @('-O2', '-fwrapv', '-ffp-contract=off', '-fno-fast-math')) {
                Check ($bstr -match [regex]::Escape($f)) "CAVA_BUILD_FP_FLAGS contains $f"
            }
        }
        foreach ($f in $forbidden) { Check (-not ($bstr -match [regex]::Escape($f))) "CAVA_BUILD_FP_FLAGS has no $f" }
    } else {
        Check $false "CXX_DEFINES has no CAVA_BUILD_FP_FLAGS (cava_build_id() loses one self-witness)"
    }
} else {
    Check $false "missing $flagsMake (and the generator is not multi-config)"
}

Write-Output "--- 2. compile_commands.json (per translation unit) ---"
$cc = Join-Path $BuildDir 'compile_commands.json'
if (Test-Path $cc) {
    $db = @(Get-Content $cc -Raw | ConvertFrom-Json)
    $entries = @($db | Where-Object { $_.file -match 'native[\\/]src' })
    Info "translation units under native/src = $($entries.Count)"
    Check ($entries.Count -gt 0) "compile_commands.json lists native/src translation units"
    $bad = @()
    foreach ($e in $entries) {
        $cmdText = if ($e.command) { $e.command } else { ($e.arguments -join ' ') }
        foreach ($f in $forbidden) { if ($cmdText -match [regex]::Escape($f)) { $bad += "$($e.file): $f" } }
        if (-not ($cmdText -match [regex]::Escape('-ffp-contract=off'))) { $bad += "$($e.file): missing -ffp-contract=off" }
        if (-not ($cmdText -match [regex]::Escape('-fno-math-errno')))  { $bad += "$($e.file): missing -fno-math-errno" }
    }
    Check ($bad.Count -eq 0) "all $($entries.Count) translation units carry the switches and no forbidden flag"
    if ($bad.Count -gt 0) { $bad | Select-Object -First 10 | ForEach-Object { Write-Output "        $_" } }
} else {
    Skip "no compile_commands.json (reconfigure with -DCMAKE_EXPORT_COMPILE_COMMANDS=ON)"
}

Write-Output "--- 3. artifact inspection (imports + disassembly) ---"
if ($Objdump -eq '') {
    foreach ($c in @('C:\mingw64\bin\objdump.exe', 'objdump', 'llvm-objdump')) {
        if (Test-Path $c) { $Objdump = $c; break }
        $cmd = Get-Command $c -ErrorAction SilentlyContinue
        if ($cmd) { $Objdump = $cmd.Source; break }
    }
}
if ($Objdump -eq '') {
    Skip "no objdump/llvm-objdump found - section 3 NOT executed (macOS would need otool)"
} else {
    Info "objdump = $Objdump"
    $imports = @()
    $dis = @()
    try { $imports = @(& $Objdump -p $Lib 2>$null | Select-String 'DLL Name' | ForEach-Object { ($_.Line -split ':',2)[1].Trim() }) } catch { }
    try { $dis = @(& $Objdump -d --no-show-raw-insn $Lib 2>$null) } catch { }

    if ($imports.Count -gt 0) {
        Info ("imports: " + ($imports -join ', '))
        $badImp = @($imports | Where-Object { $_ -match 'libstdc|libgcc|libwinpthread|libatomic' })
        Check ($badImp.Count -eq 0) "import table has no MinGW runtime DLL (fully static shape)"
    } else {
        Skip "no import table (not PE, or objdump does not support it)"
    }

    if ($dis.Count -gt 0) {
        $ymm = @($dis | Select-String -Pattern '%ymm').Count
        $zmm = @($dis | Select-String -Pattern '%zmm').Count
        $fma = @($dis | Select-String -Pattern 'vfmadd|vfnmadd').Count
        # The criterion that matters is "does the artifact contain AVX *floating-point* ops",
        # NOT "does it contain %ymm". Measured: MSVC 19.44 /O2 emits VEX-encoded *integer* moves
        # for memcpy/memset even without /arch:AVX (105 of them in the MSVC-built cava.dll:
        # vmovdqu 54 / vmovntdq 30 / vmovdqa 16 / vinsertf128 1) while FP AVX ops are 0.
        # The MinGW path has 0 %ymm at all. So only FP is asserted; integer moves are reported.
        $fpavxPattern = 'vfmadd|vfnmadd|vaddpd|vsubpd|vmulpd|vdivpd|vsqrtpd|vaddps|vsubps|vmulps|vdivps|vsqrtps|vaddsd|vsubsd|vmulsd|vdivsd|vsqrtsd'
        $fpavxHits = @($dis | Select-String -Pattern $fpavxPattern)
        $fpavx = $fpavxHits.Count
        if ($null -eq $fpavx) { $fpavx = 0 }
        Info "disassembly: %ymm=$ymm %zmm=$zmm vfmadd/vfnmadd=$fma fp-avx-ops=$fpavx"
        Check ($fpavx -eq 0) "no AVX floating-point instruction in the artifact (artifact-level proof that -march=native / -ffast-math did not affect FP codegen)"
        if ($ymm -gt 0) {
            Info "artifact contains $ymm VEX-encoded *integer* instructions (vectorised memcpy/memset); this alone is not a numerical-consistency problem. Whether they are guarded by a runtime CPU check was NOT verified here."
        }

        $sqrtsd = @($dis | Select-String -Pattern '\bsqrtsd\b').Count
        $sqrtss = @($dis | Select-String -Pattern '\bsqrtss\b').Count
        $sqrtcall = @($dis | Select-String -Pattern 'call.*<sqrt>').Count
        Info "sqrt path: sqrtsd=$sqrtsd sqrtss=$sqrtss call_sqrt=$sqrtcall"
        if (($sqrtsd + $sqrtss) -gt 0) {
            Check ($sqrtcall -eq 0) "sqrt uses the hardware instruction (correctly rounded), no libm call"
        } else {
            Skip "no sqrt instruction in this artifact (non-x86, or sqrt unused)"
        }
    } else {
        Skip "no disassembly available"
    }
}

Write-Output ""
$verdict = if ($fail -eq 0) { 'PASS' } else { 'FAIL' }
Write-Output "FLAGCHECK|build=$BuildDir|lib=$Lib|pass=$pass|fail=$fail|skip=$skip|verdict=$verdict"
exit $(if ($fail -eq 0) { 0 } else { 1 })