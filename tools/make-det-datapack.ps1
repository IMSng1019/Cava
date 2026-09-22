<#
Cava P0-E: install a tiny datapack into a world that freezes the game the moment the
world loads. Without it, the number of ticks that elapse between "Done" and the first
RCON command varies between runs, which makes absolute game time (and therefore the
chunk NBT field "LastUpdate") differ between two otherwise identical runs.

The load function runs before the server tick loop starts, so /tick freeze pins the
world at exactly the tick count stored in level.dat.

usage: pwsh -File tools/make-det-datapack.ps1 -WorldDir J:\mc\Cava\testbed\server\world
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory=$true)][string]$WorldDir,
  [string]$Name = 'cava-determinism'
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
New-Item -ItemType Directory -Force -Path $WorldDir | Out-Null
$base = Join-Path $WorldDir ("datapacks\" + $Name)
New-Item -ItemType Directory -Force -Path (Join-Path $base 'data\cava\functions'), (Join-Path $base 'data\minecraft\tags\functions') | Out-Null

# pack_format 26 == 1.20.3/1.20.4 data pack
'{"pack":{"pack_format":26,"description":"Cava P0-E determinism harness"}}' |
  Set-Content -Encoding ASCII (Join-Path $base 'pack.mcmeta')

@'
# runs from the #minecraft:load tag: pin the clock before the tick loop starts
tick freeze
save-off
say [cava-determinism] game frozen at load; autosave off
'@ | Set-Content -Encoding ASCII (Join-Path $base 'data\cava\functions\freeze.mcfunction')

'{"values":["cava:freeze"]}' |
  Set-Content -Encoding ASCII (Join-Path $base 'data\minecraft\tags\functions\load.json')

Write-Host "[det-datapack] installed into $base"
Get-ChildItem -LiteralPath $base -Recurse -File | ForEach-Object { Write-Host ("   " + $_.FullName.Substring($base.Length + 1)) }
