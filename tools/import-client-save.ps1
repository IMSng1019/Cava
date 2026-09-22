<#
Cava P0-E: try to reuse an existing CLIENT save as the testbed server world.
This is an experiment, not a supported path: the save under
  J:\mc\mods\run\saves\新的世界
is a client save directory (no session.lock copy, worldgen settings live in level.dat).

usage:
  pwsh -File tools/import-client-save.ps1
  pwsh -File tools/import-client-save.ps1 -Source 'J:\mc\mods\run\saves\新的世界'
#>
[CmdletBinding()]
param(
  [string]$Source = 'J:\mc\mods\run\saves\新的世界',
  [string]$Root = '',
  [switch]$KeepExisting
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
if (-not $Root) { $Root = [System.IO.Path]::GetFullPath((Join-Path (Split-Path $PSScriptRoot -Parent) 'testbed')) }
if (-not (Test-Path -LiteralPath $Source)) { throw "source save not found: $Source" }
$worldDir = Join-Path $Root 'server\world'

Write-Host "[import] source = $Source"
Write-Host "[import] target = $worldDir"
$srcInfo = Get-ChildItem -LiteralPath $Source -Recurse -File | Measure-Object -Property Length -Sum
Write-Host ("[import] source has {0} files / {1:n1} MB" -f $srcInfo.Count, ($srcInfo.Sum / 1MB))

# digest the source first -- this is the "what did we actually copy" evidence
$node = 'C:\Program Files\nodejs\node.exe'
& $node (Join-Path $PSScriptRoot 'worldhash.cjs') $Source --json (Join-Path $Root 'hashes\client-save-source.json') --quiet
Write-Host "[import] source digest written to $Root\hashes\client-save-source.json"

if (Test-Path -LiteralPath $worldDir) {
  if ($KeepExisting) { Write-Host '[import] world exists, -KeepExisting -> aborting'; exit 0 }
  Write-Host '[import] removing existing world'
  Remove-Item -LiteralPath $worldDir -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $worldDir | Out-Null

# copy everything except session.lock (a lock file of the client's session)
$copied = 0; $bytes = 0
foreach ($item in (Get-ChildItem -LiteralPath $Source -Force)) {
  if ($item.Name -eq 'session.lock') { Write-Host '[import] skipping session.lock'; continue }
  $dst = Join-Path $worldDir $item.Name
  if ($item.PSIsContainer) {
    Copy-Item -LiteralPath $item.FullName -Destination $dst -Recurse -Force
  } else {
    Copy-Item -LiteralPath $item.FullName -Destination $dst -Force
  }
}
$dstInfo = Get-ChildItem -LiteralPath $worldDir -Recurse -File | Measure-Object -Property Length -Sum
Write-Host ("[import] copied {0} files / {1:n1} MB -> $worldDir" -f $dstInfo.Count, ($dstInfo.Sum / 1MB))
Get-ChildItem -LiteralPath $worldDir | Select-Object Name, Length | Format-Table -AutoSize | Out-String | Write-Host
