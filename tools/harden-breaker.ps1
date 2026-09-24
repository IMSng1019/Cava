<#
harden-breaker.ps1 -- P4-A item 1 evidence: the circuit breaker on the REAL native library.

Runs cava.harden.HardenProbe breaker (native ON, real cava.dll, real log4j2 backend) and checks:
  A) soft failures  : 6x pathfind(forged handle) -> CAVA_ERR_NULL  => counted, NEVER trips, 0 ERROR
                      (this is the corrected policy: ABI-mandated rejections are not faults;
                       the first implementation tripped here and turned an existing ABI test red)
  B) hard failures  : inject CAVA_ERR_INTERNAL(-6) N times          => trips once, exactly 1 ERROR
  C) after the trip : 100x real pathfind(valid handle)              => -100 every time,
                      native attempt counter frozen, fallback counter grows

Usage:
  pwsh -NoProfile -ExecutionPolicy Bypass -File tools/harden-breaker.ps1
NOTE: keep this file ASCII-only.
#>
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot | Split-Path -Parent
$java = 'C:\Program Files\Java\jdk-21\bin\java.exe'
$outDir = Join-Path $root 'build\harden'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$classes = Join-Path $root 'build\classes\java\main'
if (-not (Test-Path $classes)) { Write-Output "MISSING: $classes (run gradlew compileJava first)"; exit 2 }

function Find-Jar([string]$pattern) {
    $jar = Get-ChildItem -Path (Join-Path $root '.gradle-home') -Recurse -Filter $pattern -File -ErrorAction SilentlyContinue |
           Select-Object -First 1
    if ($null -eq $jar) { throw "cannot find $pattern under .gradle-home" }
    return $jar.FullName
}
$cp = @($classes, (Find-Jar 'slf4j-api-*.jar'), (Find-Jar 'log4j-api-*.jar'),
        (Find-Jar 'log4j-core-*.jar'), (Find-Jar 'log4j-slf4j2-impl-*.jar')) -join ';'

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
if (-not (Test-Path $dll)) { Write-Output "MISSING: $dll"; exit 2 }
$env:JAVA_TOOL_OPTIONS = "-Dlog4j2.configurationFile=file:///$($cfg -replace '\\','/') -Duser.language=en -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -Dcava.native.path=$dll"

$log = Join-Path $outDir 'probe-breaker.log'
$keep = '^(INFO|WARN|ERROR)|^===|^cava\.|^breaker\.|^watchdog\.|^status|^available|^detail|^build_id|^handle|^pathfind|^\s+ok:|^\s+!!|HARDEN-PROBE|^\[cava/native\]|^---'
Write-Output "java --enable-preview --enable-native-access=ALL-UNNAMED -cp <classes;deps> cava.harden.HardenProbe breaker"
Write-Output "JAVA_TOOL_OPTIONS=$env:JAVA_TOOL_OPTIONS"
& $java --enable-preview --enable-native-access=ALL-UNNAMED -cp $cp cava.harden.HardenProbe breaker 2>&1 |
    Tee-Object -FilePath $log | Where-Object { $_ -match $keep } | ForEach-Object { Write-Output "  | $_" }
$rc = $LASTEXITCODE
$text = Get-Content -LiteralPath $log
$errors = @($text | Where-Object { $_ -match '^ERROR' })
Write-Output "exit=$rc   ERROR lines=$($errors.Count)   log=$log"

$fail = 0
function Check([bool]$ok, [string]$what) {
    if ($ok) { Write-Output "  ok   : $what" } else { Write-Output "  FAIL : $what"; $script:fail++ }
}
Check ($rc -eq 0) "probe exit=0 (actual $rc)"
Check ($errors.Count -eq 1) "exactly ONE ERROR line for the whole demo (actual $($errors.Count))"
Check (($text -join "\n") -match 'HARDEN-PROBE: PASS') "probe self-check PASS"
Check (($text -join "\n") -match 'A\) 软失败') "soft-failure section ran"
Check (($text -join "\n") -match 'B\) 硬失败') "hard-failure section ran"
Check (($text -join "\n") -match 'C\) 熔断之后') "post-trip section ran"
if ($fail -gt 0) { Write-Output "BREAKER CHECK: FAIL ($fail)"; exit 1 }
Write-Output "BREAKER CHECK: PASS"
exit 0
