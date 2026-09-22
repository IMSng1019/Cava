# native/tests/push/build-push.ps1
# Build + run the P2 push kernel selftest (no Minecraft, no CMake, no ABI header).
#   pwsh -NoProfile -File native/tests/push/build-push.ps1
$ErrorActionPreference = 'Stop'
$root = 'J:\mc\Cava'
$src  = Join-Path $root 'native\src\entity\push\cava_push_kernel.cpp'
$test = Join-Path $root 'native\tests\push\cava_push_vectors.cpp'
$outDir = Join-Path $root 'build\native-push'
$vecDir = Join-Path $root 'native\tests\push\vectors'
New-Item -ItemType Directory -Force -Path $outDir, $vecDir | Out-Null
$exe = Join-Path $outDir 'cava_push_vectors.exe'
$gxx = 'C:\mingw64\bin\g++.exe'
$env:TMP = Join-Path $root 'native\build\tmp'
$env:TEMP = $env:TMP
New-Item -ItemType Directory -Force -Path $env:TMP | Out-Null
$env:TMPDIR = $env:TMP
& $gxx -std=c++17 -O2 -fwrapv -ffp-contract=off -fno-fast-math -Wall -Wextra -c $src -o (Join-Path $outDir 'kernel-only.o')
if ($LASTEXITCODE -ne 0) { throw 'kernel-only compile failed' }
Write-Output 'kernel-only compile: exit=0 (no Minecraft headers)'
& $gxx -std=c++17 -O2 -fwrapv -ffp-contract=off -fno-fast-math -Wall -Wextra -static -o $exe $src $test
if ($LASTEXITCODE -ne 0) { throw 'test build failed' }
Write-Output ('BUILT: ' + $exe + ' (' + (Get-Item -LiteralPath $exe).Length + ' bytes)')
& $exe --out $vecDir
$rc = $LASTEXITCODE
Write-Output ('exit=' + $rc)
if ($rc -ne 0) { throw ('selftest failed exit=' + $rc) }
Write-Output '--- vector hashes ---'
foreach ($n in @('push-00.bin','plan-00.bin')) {
  $p = Join-Path $vecDir $n
  $h = (Get-FileHash -LiteralPath $p -Algorithm SHA256).Hash
  Write-Output ($n + ' bytes=' + (Get-Item -LiteralPath $p).Length + ' sha256=' + $h)
}
exit 0
