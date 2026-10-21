# 一键构建 + 安装到设备
# 依赖环境变量 JAVA_HOME（JDK 17~21）和 ANDROID_HOME（Android SDK）
#
# 用法:
#   .\build.ps1                                  只编译
#   .\build.ps1 -Install                         编译 + 装到唯一在线设备并启动
#   .\build.ps1 -Install -Serial emulator-5554   指定设备

param(
    [switch]$Install,
    [string]$Serial
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$proj = Join-Path $root "MyApp"
$activity = "com.example.myapp/.MainActivity"

if (-not $env:JAVA_HOME) {
    throw "请先设置 JAVA_HOME 指向 JDK 17~21（AGP 8.7 不支持 JDK 25）"
}
if (-not $env:ANDROID_HOME) {
    throw "请先设置 ANDROID_HOME 指向 Android SDK"
}
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
if (-not $env:GRADLE_USER_HOME) {
    $env:GRADLE_USER_HOME = Join-Path $root ".gradle"
}

& (Join-Path $proj "gradlew.bat") -p $proj assembleDebug --console=plain
if ($LASTEXITCODE -ne 0) { throw "编译失败" }

$apk = Join-Path $proj "app\build\outputs\apk\debug\app-debug.apk"
Write-Host "APK: $apk" -ForegroundColor Green

if ($Install) {
    $adb = Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"
    $devices = @((& $adb devices) | Select-Object -Skip 1 | Where-Object { $_ -match "\sdevice$" })
    if (-not $Serial) {
        if ($devices.Count -gt 1) {
            throw "检测到多个设备，请用 -Serial 指定：`n$($devices -join "`n")"
        }
        if ($devices.Count -eq 0) { throw "没有已连接的设备" }
        $Serial = ($devices[0] -split "\s+")[0]
    }
    & $adb -s $Serial install -r $apk
    & $adb -s $Serial shell am start -n $activity
}
