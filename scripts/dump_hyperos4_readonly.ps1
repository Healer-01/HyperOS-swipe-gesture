# HyperOS 4.0.6.0 Read-Only Gesture & Back-Navigation Diagnostic Dump Script
# Strictly read-only: NEVER writes to or modifies any file or setting on the connected device.

param(
    [string]$OutputDir = "$PSScriptRoot\..\hyperos4_dump"
)

$ErrorActionPreference = "Continue"
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
$resolvedOut = (Resolve-Path $OutputDir).Path

Write-Host "==> Checking connected ADB device..." -ForegroundColor Cyan
adb devices

Write-Host "==> Collecting read-only dumpsys snapshots..." -ForegroundColor Cyan
adb shell dumpsys input | Out-File "$resolvedOut\dumpsys_input.txt" -Encoding utf8
adb shell dumpsys window policy | Out-File "$resolvedOut\dumpsys_window_policy.txt" -Encoding utf8
adb shell dumpsys window windows | Out-File "$resolvedOut\dumpsys_window_windows.txt" -Encoding utf8
adb shell dumpsys activity service com.android.systemui/.SystemUIService | Out-File "$resolvedOut\dumpsys_systemui.txt" -Encoding utf8
adb shell "ps -ef | grep -E 'hyos|miui\.home|systemui'" | Out-File "$resolvedOut\procs.txt" -Encoding utf8
adb shell "getprop ro.mi.os.version.name; getprop ro.build.version.sdk; getprop ro.product.model" | Out-File "$resolvedOut\device_props.txt" -Encoding utf8
adb shell "ls -lh /system/framework/services.jar /system/framework/oat/arm64/services.* /system_ext/framework/miui-services.jar /system_ext/framework/oat/arm64/miui-services.* /system_ext/priv-app/MiuiSystemUI/MiuiSystemUI.apk /product/priv-app/MiuiHome/MiuiHome.apk 2>&1" | Out-File "$resolvedOut\paths.txt" -Encoding utf8

Write-Host ""
Write-Host "==> Ready to capture a live normal side-swipe back gesture logcat." -ForegroundColor Yellow
adb logcat -c
Read-Host "请在手机「设置」二级页面做一次正常快速侧滑返回，完成后回到此窗口按回车继续"
adb logcat -d -v time | Out-File "$resolvedOut\back_gesture_logcat.txt" -Encoding utf8

Write-Host "==> Done! All read-only diagnostic files saved to: $resolvedOut" -ForegroundColor Green
