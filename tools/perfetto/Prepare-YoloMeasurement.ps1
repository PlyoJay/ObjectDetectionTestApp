[CmdletBinding()]
param(
    [string]$OutputDirectory = '',
    [string]$Python = '',
    [string]$Adb = 'adb',
    [string]$Serial = '',
    [switch]$BuildApk,
    [switch]$DeviceCheck
)
. "$PSScriptRoot/Common.ps1"
$repo = (Resolve-Path "$PSScriptRoot/../..").Path
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $repo ('build/yolo-measurements/prepared-' + (Get-Date -Format 'yyyyMMdd-HHmmss')) }
$output = [IO.Directory]::CreateDirectory($OutputDirectory).FullName
if (Test-Path (Join-Path $output 'build-manifest.json')) { throw 'Use a new output directory; provenance must not be overwritten.' }
if (-not $Python) { $Python = Join-Path $repo '.venv/Scripts/python.exe' }
$arguments = @("$PSScriptRoot/yolo_measurement.py", 'identity', '--output', $output)
if ($BuildApk) { $arguments += '--build-apk' }
& $Python @arguments
if ($LASTEXITCODE -ne 0) { throw 'Model/build identity preparation failed' }
Copy-Item "$PSScriptRoot/yolo-short.pbtxt" (Join-Path $output 'yolo-short.pbtxt')
if ($DeviceCheck) {
    Initialize-Adb $Adb $Serial
    $checks = [ordered]@{ serial = $script:DeviceSerial; clock = Get-DeviceClock }
    foreach ($check in @(
        @('perfetto-version', 'perfetto --version'),
        @('perfetto-service', 'perfetto --query --long'),
        @('atrace-categories', 'atrace --list_categories'),
        @('package', 'dumpsys package com.samin.objectdetection'),
        @('device', 'getprop ro.build.fingerprint; getprop ro.product.model; getprop ro.build.version.sdk'),
        @('available-events', 'if test -r /sys/kernel/tracing/available_events; then cat /sys/kernel/tracing/available_events; elif test -r /sys/kernel/debug/tracing/available_events; then cat /sys/kernel/debug/tracing/available_events; else echo N/A_unreadable_tracefs; fi')
    )) {
        Write-Utf8 (Join-Path $output ($check[0] + '.txt')) (Invoke-Adb @('shell', $check[1]))
    }
    $checks.installedApkSha256 = Get-InstalledApkSha
    $manifest = Get-Content (Join-Path $output 'build-manifest.json') -Raw | ConvertFrom-Json
    $checks.matchesPreparedApk = $checks.installedApkSha256 -eq $manifest.apk.sha256
    $checks.traceCapture = 'NOT_RUN; event availability/YOLO slices/GC/data loss require an actual short trace'
    Save-Json (Join-Path $output 'device-check.json') $checks
}
Write-Host "Prepared: $output"
Write-Host 'No app installation, app launch, performance session, or Perfetto capture was started.'
