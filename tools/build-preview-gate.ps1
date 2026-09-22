param(
  [string]$Root = 'J:\mc\Cava\testbed\gate-preview',
  [switch]$SkipCompile
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$env:JAVA_TOOL_OPTIONS = '-Duser.language=en -Dfile.encoding=UTF-8'
$javac = 'C:\Program Files\Java\jdk-21\bin\javac.exe'
$jar   = 'C:\Program Files\Java\jdk-21\bin\jar.exe'

# Compile-time deps come from the LOCAL Gradle module cache (nothing is downloaded here).
$base = 'C:\Users\郁小悟520\.gradle\caches\modules-2\files-2.1'
$loader = Get-ChildItem "$base\net.fabricmc\fabric-loader\0.16.14" -Recurse -File -Filter 'fabric-loader-*.jar' |
          Where-Object { $_.Name -notlike '*-sources.jar' } | Select-Object -First 1
$mixin  = Get-ChildItem "$base\net.fabricmc\sponge-mixin\0.15.5+mixin.0.8.7" -Recurse -File -Filter '*.jar' |
          Where-Object { $_.Name -notlike '*-sources.jar' } | Select-Object -First 1
if (-not $loader) { throw 'fabric-loader jar not found in the local Gradle cache' }
if (-not $mixin)  { throw 'sponge-mixin jar not found in the local Gradle cache' }
$cp = "$($loader.FullName);$($mixin.FullName)"
Write-Host "classpath: $cp"

$srcdir  = Join-Path $Root 'src'
$classes = Join-Path $Root 'classes'
$jarout  = Join-Path $Root 'cava-gate.jar'

if (-not $SkipCompile) {
  if (Test-Path $classes) { Remove-Item -Recurse -Force $classes }
  New-Item -ItemType Directory -Path $classes -Force | Out-Null
  $sources = Get-ChildItem -Recurse -File (Join-Path $srcdir 'java') -Filter *.java | ForEach-Object { $_.FullName }
  Write-Host "compiling $($sources.Count) source file(s): --release 21 --enable-preview"

  # NOTE: host PowerShell is 5.1 and mangles ':' inside a native argument (observed:
  # "error: invalid flag: :"). Pass the classpath through a javac @argfile instead.
  $argFile = Join-Path $Root 'javac.args'
  $lines = New-Object System.Collections.Generic.List[string]
  $lines.Add('--release')
  $lines.Add('21')
  $lines.Add('--enable-preview')
  $lines.Add('-encoding')
  $lines.Add('UTF-8')
  $lines.Add('-proc:none')
  $lines.Add('-cp')
  $lines.Add('"' + $cp.Replace('\', '/') + '"')
  $lines.Add('-d')
  $lines.Add('"' + $classes.Replace('\', '/') + '"')
  foreach ($s in $sources) { $lines.Add('"' + $s.Replace('\', '/') + '"') }
  # UTF8, not ASCII: the Gradle cache path contains non-ASCII characters (user profile name),
  # and an ASCII argfile silently mangles the classpath (observed: 'package net.fabricmc.api does not exist').
  Set-Content -Path $argFile -Value $lines -Encoding UTF8

  & $javac "@$argFile"
  if ($LASTEXITCODE -ne 0) { throw "javac failed: $LASTEXITCODE" }

  Copy-Item -Force (Join-Path $srcdir 'resources\fabric.mod.json') $classes
  if (Test-Path $jarout) { Remove-Item -Force $jarout }
  Push-Location $classes
  & $jar --create --file $jarout .
  $rc = $LASTEXITCODE
  Pop-Location
  if ($rc -ne 0) { throw "jar failed: $rc" }
  Write-Host "built $jarout ($((Get-Item $jarout).Length) bytes)"
}
Write-Host "OK"
