# 启动 Android 4.0.4 模拟器（AVD 名默认 legacy403）
# 依赖 ANDROID_HOME；AVD 需要用 sdkmanager/avdmanager 先建好
param([string]$Avd = "legacy403")

$ErrorActionPreference = "Stop"
if (-not $env:ANDROID_HOME) { throw "请先设置 ANDROID_HOME" }
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:ANDROID_AVD_HOME = Join-Path $env:USERPROFILE ".android\avd"

& (Join-Path $env:ANDROID_HOME "emulator\emulator.exe") -avd $Avd -no-audio -no-snapshot `
    -no-boot-anim -gpu swiftshader_indirect -skin 480x800
