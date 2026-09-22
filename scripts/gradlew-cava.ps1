<#
Cava —— 本机 Gradle 包装脚本（文件所有权：P0-A 构建基建流）

为什么需要它（本机实测事实，见 docs/CAVA-launch-notes.md §1）：
  会话文件沙箱是 workspace-write，工作区 = J:\mc\Cava；
  Gradle 默认的 GRADLE_USER_HOME（J:\mc\mods\.gradle-home）**可读不可写**，
  wrapper 连 gradle-9.7.1-bin.zip.lck 都建不了，构建必失败。
  所以必须把 GRADLE_USER_HOME 指到工作区内 —— 但**不能**把绝对路径写进 build.gradle /
  gradle.properties / settings.gradle（那样别的机器和 CI 会挂），所以放在这个脚本里。

用法（在仓库根或任意目录都行）：
  .\scripts\gradlew-cava.ps1 build --console=plain
  .\scripts\gradlew-cava.ps1 buildNative        # 顺便编原生库（需要 MinGW/CMake）
  .\scripts\gradlew-cava.ps1 --stop             # 停 daemon

可选优化：如果本机存在只读依赖缓存 J:\mc\mods\.gradle-home\caches，
就设 GRADLE_RO_DEP_CACHE 复用它（少下几百 MB）。不存在就跳过，不影响正确性。
#>
[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $GradleArgs
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$gradleHome = Join-Path $root '.gradle-home'
if (-not (Test-Path $gradleHome)) {
    New-Item -ItemType Directory -Force -Path $gradleHome | Out-Null
}
$env:GRADLE_USER_HOME = $gradleHome
Write-Host "GRADLE_USER_HOME = $env:GRADLE_USER_HOME"

$roCache = 'J:\mc\mods\.gradle-home\caches'
if ((Test-Path $roCache) -and -not $env:GRADLE_RO_DEP_CACHE) {
    $env:GRADLE_RO_DEP_CACHE = $roCache
    Write-Host "GRADLE_RO_DEP_CACHE = $env:GRADLE_RO_DEP_CACHE (只读复用，可选)"
}

if (-not $GradleArgs -or $GradleArgs.Count -eq 0) {
    $GradleArgs = @('build', '--console=plain')
}

Push-Location $root
try {
    & (Join-Path $root 'gradlew.bat') @GradleArgs
    exit $LASTEXITCODE
}
finally {
    Pop-Location
}
