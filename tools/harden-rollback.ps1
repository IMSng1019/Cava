<#
harden-rollback.ps1 -- P4-A item 5: prove that ONE JVM flag returns the process to pure Java.

What it does (no Minecraft, no Fabric, no server needed):
  1. builds the mod classes with Gradle (compileJava -> build/classes/java/main)
  2. runs cava.harden.HardenProbe twice with a real log4j2 backend on the classpath:
       leg A  native ON      (INFO/WARN/ERROR visible -> proves the log channel is alive)
       leg B  -Dcava.native.enabled=false   (the rollback switch)
  3. asserts, and prints, for leg B:
       status      == DISABLED_BY_FLAG
       available() == false
       a P1 entry  == -100 (ERR_NATIVE_UNAVAILABLE)  => hooks do not engage at all
       ERROR lines == 0   (rollback is a normal path, not an error)
  4. prints the exact rollback procedure + how to confirm success

Why JAVA_TOOL_OPTIONS instead of "java -Dk=v": on this machine PowerShell 5.1 mis-parses
"& java -Dfoo=bar ..." (documented in docs/CAVA-dev-toolbox.md section 6). The property is
therefore exported through the environment, which the JVM picks up itself.

Usage:
  pwsh -NoProfile -ExecutionPolicy Bypass -File tools/harden-rollback.ps1
  pwsh -NoProfile -ExecutionPolicy Bypass -File tools/harden-rollback.ps1 -SkipBuild

NOTE: keep this file ASCII-only.
#>
param(
    [switch]$SkipBuild
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Stop'

$root = $PSScriptRoot | Split-Path -Parent
$java = 'C:\Program Files\Java\jdk-21\bin\java.exe'
if (-not (Test-Path $java)) { Write-Output "MISSING: $java"; exit 2 }

$outDir = Join-Path $root 'build\harden'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

if (-not $SkipBuild) {
    Write-Output ">>> gradlew compileJava"
    $env:GRADLE_USER_HOME = Join-Path $root '.gradle-home'
    Push-Location $root
    try {
        & (Join-Path $root 'gradlew.bat') compileJava --console=plain --no-watch-fs 2>&1 |
            Select-Object -Last 5 | ForEach-Object { Write-Output "  | $_" }
        if ($LASTEXITCODE -ne 0) { Write-Output "BUILD FAILED exit=$LASTEXITCODE"; exit 1 }
    } finally { Pop-Location }
}

$classes = Join-Path $root 'build\classes\java\main'
if (-not (Test-Path $classes)) { Write-Output "MISSING: $classes (run without -SkipBuild)"; exit 2 }

function Find-Jar([string]$pattern) {
    $jar = Get-ChildItem -Path (Join-Path $root '.gradle-home') -Recurse -Filter $pattern -File -ErrorAction SilentlyContinue |
           Select-Object -First 1
    if ($null -eq $jar) { throw "cannot find $pattern under .gradle-home" }
    return $jar.FullName
}
$cpParts = @(
    $classes,
    (Find-Jar 'slf4j-api-*.jar'),
    (Find-Jar 'log4j-api-*.jar'),
    (Find-Jar 'log4j-core-*.jar'),
    (Find-Jar 'log4j-slf4j2-impl-*.jar')
)
$cp = $cpParts -join ';'

# a minimal log4j2 config so INFO/WARN are actually printed (the log4j2 default only shows ERROR)
$cfg = Join-Path $outDir 'probe-log4j2.xml'
@'
<?xml version="1.0" encoding="UTF-8"?>
<Configuration status="WARN">
  <Appenders>
    <Console name="C" target="SYSTEM_OUT">
      <PatternLayout pattern="%-5level %logger - %msg%n"/>
    </Console>
  </Appenders>
  <Loggers>
    <Root level="info"><AppenderRef ref="C"/></Root>
  </Loggers>
</Configuration>
'@ | Set-Content -LiteralPath $cfg -Encoding ASCII

$dll = Join-Path $root 'natives\windows-x64\cava.dll'
$baseOpts = "-Dlog4j2.configurationFile=file:///$($cfg -replace '\\','/') -Duser.language=en -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8"
if (Test-Path $dll) { $baseOpts = "$baseOpts -Dcava.native.path=$dll" }

$jvmArgs = @('--enable-preview', '--enable-native-access=ALL-UNNAMED', '-cp', $cp, 'cava.harden.HardenProbe')

# Write-Host (not Write-Output) for everything inside: the function must return ONLY the
# result object, otherwise the caller's variable swallows the transcript (hit once already).
function Invoke-Leg([string]$Label, [string]$ExtraProp, [string]$LogName) {
    $log = Join-Path $outDir $LogName
    $env:JAVA_TOOL_OPTIONS = if ($ExtraProp -eq '') { $baseOpts } else { "$baseOpts $ExtraProp" }
    $env:JAVA_TOOL_OPTIONS = $env:JAVA_TOOL_OPTIONS.Trim()
    Write-Host ""
    Write-Host "=== leg [$Label]"
    Write-Host "    JAVA_TOOL_OPTIONS=$env:JAVA_TOOL_OPTIONS"
    Write-Host "    java $($jvmArgs -join ' ')"
    # full transcript -> $log; only the lines that matter -> console (the struct field table is long)
    $keep = '^(INFO|WARN|ERROR)|^===|^cava\.|^breaker\.|^watchdog\.|^status|^available|^detail|^build_id|^handle|^pathfind|^\s+ok:|^\s+!!|HARDEN-PROBE|^\[cava/native\]|^---|回滚语义'
    & $java @jvmArgs 2>&1 | Tee-Object -FilePath $log | Where-Object { $_ -match $keep } |
        ForEach-Object { Write-Host "  | $_" }
    $rc = $LASTEXITCODE
    $text = Get-Content -LiteralPath $log
    Write-Host "exit=$rc   (log: $log)"
    $errors = @($text | Where-Object { $_ -match '^ERROR' })
    $warns = @($text | Where-Object { $_ -match '^WARN' })
    $infos = @($text | Where-Object { $_ -match '^INFO' })
    Write-Host "counts: INFO=$($infos.Count) WARN=$($warns.Count) ERROR=$($errors.Count)"
    $errors | ForEach-Object { Write-Host "  ! $_" }
    return [pscustomobject]@{ Label = $Label; Exit = $rc; Log = $log; Errors = $errors.Count; Warns = $warns.Count; Infos = $infos.Count; Text = $text }
}

$legA = Invoke-Leg 'native-ON' '' 'probe-native-on.log'
$legB = Invoke-Leg 'rollback' '-Dcava.native.enabled=false' 'probe-native-off.log'

Write-Output ""
Write-Output "================ rollback assertions ================"
$fail = 0
function Check([bool]$ok, [string]$what) {
    if ($ok) { Write-Output "  ok   : $what" } else { Write-Output "  FAIL : $what"; $script:fail++ }
}
Check ($legA.Exit -eq 0) "leg A exit=0 (actual $($legA.Exit))"
Check ($legA.Infos -gt 0) "leg A printed INFO lines (log channel alive): $($legA.Infos)"
Check ($legB.Exit -eq 0) "leg B exit=0 (actual $($legB.Exit))"
Check (($legB.Text -join "\n") -match 'DISABLED_BY_FLAG') "leg B status == DISABLED_BY_FLAG"
Check (($legB.Text -join "\n") -match 'available\(\)\s+: false') "leg B available() == false"
Check (($legB.Text -join "\n") -match 'pathfind\(handle=\d+\) -> -100') "leg B P1 entry returned -100 (ERR_NATIVE_UNAVAILABLE)"
Check ($legB.Errors -eq 0) "leg B ERROR lines == 0 (actual $($legB.Errors))"
Check ($legB.Text -join "\n" -match 'HARDEN-PROBE: PASS') "leg B probe self-check PASS"

Write-Output ""
Write-Output "================ rollback procedure (copy/paste) ================"
Write-Output @'
 1. Stop the server.
 2. Add ONE JVM flag to the launch command:
        -Dcava.native.enabled=false
    (equivalently: set "native.enabled": false in config/cava.json)
 3. Start the server again.
 4. Confirm success by looking for ALL of these in the log:
      [cava/native] -Dcava.native.enabled=false（或 config native.enabled=false）：按设计不加载原生库 —— 这是正常路径，走纯 Java
      native 状态     : DISABLED_BY_FLAG
      回退语义        : 整体回退纯 Java（所有钩子不介入）
    and by the ABSENCE of:
      [native] System.load(  (the library is not even extracted)
      any [cava/native] ERROR line
 5. machine-check (this script): pwsh -NoProfile -File tools/harden-rollback.ps1 -SkipBuild
    -> leg [rollback] must show: status DISABLED_BY_FLAG, available() false,
       pathfind -> -100, counts: ... ERROR=0
 6. runtime counters: CavaNative.hardeningReport() / the startup banner line
    "熔断 : ..." must show tripped=false and unavailableCalls growing only if something
    still asks for native (with the flag off, hooks never ask).
'@

if ($fail -gt 0) { Write-Output "ROLLBACK CHECK: FAIL ($fail)"; exit 1 }
Write-Output "ROLLBACK CHECK: PASS"
exit 0
