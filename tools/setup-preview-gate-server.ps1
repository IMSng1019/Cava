$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$env:JAVA_TOOL_OPTIONS = '-Duser.language=en -Dfile.encoding=UTF-8'
$root = 'J:\mc\Cava\testbed\gate-preview'
New-Item -ItemType Directory -Force -Path (Join-Path $root 'mods') | Out-Null
Copy-Item -Force (Join-Path $root 'cava-gate.jar') (Join-Path $root 'mods\cava-gate.jar')
Copy-Item -Force (Join-Path $root 'cache\server.jar') (Join-Path $root 'server.jar')
Set-Content -Path (Join-Path $root 'eula.txt') -Value 'eula=true' -Encoding ASCII
Remove-Item -Force (Join-Path $root 'server.properties') -ErrorAction SilentlyContinue
Write-Host 'gate server files prepared'
Get-ChildItem $root | Select-Object Name
