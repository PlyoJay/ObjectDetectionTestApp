[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('Baseline', 'Perfetto')][string]$Mode,
    [Parameter(Mandatory)][string]$BuildManifest,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [Parameter(Mandatory)][string]$ConditionsFile,
    [ValidateSet('Early', 'Late', 'Both')][string]$Window = 'Both',
    [string]$Adb = 'adb',
    [string]$Serial = '',
    [ValidateRange(30, 600)][int]$StartTimeoutSeconds = 180
)
. "$PSScriptRoot/Common.ps1"
$manifest = Get-Content -LiteralPath $BuildManifest -Raw | ConvertFrom-Json
if ($manifest.apk.sourceBinding -ne 'BUILT_FROM_RECORDED_SOURCES') { throw 'Prepare with -BuildApk before measuring; existing APK source revision is unproven.' }
if (-not (Test-Path -LiteralPath $ConditionsFile -PathType Leaf)) { throw 'Create a conditions note before starting.' }
$output = [IO.Directory]::CreateDirectory($OutputDirectory).FullName
if (@(Get-ChildItem -LiteralPath $output -Force).Count) { throw 'Output directory must be empty.' }
Copy-Item -LiteralPath $BuildManifest -Destination (Join-Path $output 'build-manifest.json')
Copy-Item -LiteralPath $ConditionsFile -Destination (Join-Path $output 'conditions.txt')
Initialize-Adb $Adb $Serial
$installedSha = Get-InstalledApkSha
if ($installedSha -ne $manifest.apk.sha256) { throw 'Installed APK differs from prepared build. Install the prepared APK yourself, without clearing app data.' }
$initial = Get-DeviceClock
$existing = @(Get-RemoteLogs)
$record = [ordered]@{
    mode = $Mode; window = $Window; serial = $script:DeviceSerial; initialClock = $initial
    apkSha256 = $installedSha; clockBasis = 'App elapsedRealtimeNanos and Perfetto CLOCK_BOOTTIME on same boot; host UTC is diagnostic only'
    status = 'ARMED'; captures = @(); performanceLog = $null
}
Write-Utf8 (Join-Path $output 'battery-before.txt') (Invoke-Adb @('shell', 'dumpsys battery'))
Save-Json (Join-Path $output 'run.json') $record
Write-Host "ARMED ($Mode): now manually start a NEW 5-minute performance log in the app. Keep the app foreground."
Write-Host 'Start the app/camera after matching the recorded pre-session warmup and thermal conditions.'
try {
    $wait = [Diagnostics.Stopwatch]::StartNew()
    $session = $null
    while (-not $session) {
        if ($wait.Elapsed.TotalSeconds -ge $StartTimeoutSeconds) { throw 'Timed out waiting for a new app performance log.' }
        $newLogs = @(Get-RemoteLogs | Where-Object { $_ -notin $existing })
        if ($newLogs.Count -gt 1) { throw 'Multiple new logs found; use one performance session per run.' }
        if ($newLogs.Count -eq 1) { $session = Read-SessionHeader $newLogs[0] $manifest.model.sha256 }
        if (-not $session) { Start-Sleep -Seconds 1 }
    }
    Write-Utf8 (Join-Path $output 'session-header.txt') $session.header
    $record.session = $session | Select-Object sessionId, startUnixMs, startElapsedNs, remoteLog
    $record.status = 'RECORDING'
    Save-Json (Join-Path $output 'run.json') $record
    $start = $session.startElapsedNs / 1e9
    if ($Mode -eq 'Perfetto') {
        # No perfetto command is executed anywhere in Baseline mode.
        $record.traceSetupHostUtcBefore = [DateTimeOffset]::UtcNow.ToString('o')
        $config = Get-Content "$PSScriptRoot/yolo-short.pbtxt" -Raw
        $events = Invoke-Adb @('shell', 'if test -r /sys/kernel/tracing/available_events; then cat /sys/kernel/tracing/available_events; elif test -r /sys/kernel/debug/tracing/available_events; then cat /sys/kernel/debug/tracing/available_events; else echo N/A_unreadable_tracefs; fi')
        $categories = Invoke-Adb @('shell', 'atrace --list_categories')
        $support = [ordered]@{}
        foreach ($event in @('sched/sched_switch', 'sched/sched_waking', 'power/cpu_frequency', 'power/cpu_idle')) {
            if ($events -match 'N/A_unreadable_tracefs') { $support[$event] = 'REQUESTED; support unverified (tracefs unreadable)' }
            elseif ($events -match ('(?m)^' + [regex]::Escape($event.Replace('/', ':')) + '\r?$')) { $support[$event] = 'ADVERTISED; verify actual trace' }
            else {
                $support[$event] = 'N/A; not in available_events'
                $config = $config.Replace('ftrace_events: "' + $event + '"', '# N/A: ' + $event)
            }
        }
        $support.dalvik = 'ADVERTISED; no GC observed does not imply zero GC overhead'
        if ($categories -notmatch '(?m)^\s*dalvik\s+-') {
            $support.dalvik = 'N/A; category unavailable'
            $config = $config.Replace('atrace_categories: "dalvik"', '# N/A: dalvik')
        }
        Save-Json (Join-Path $output 'support.json') $support
        Write-Utf8 (Join-Path $output 'available-events.txt') $events
        $configPath = Join-Path $output 'effective.pbtxt'
        Write-Utf8 $configPath $config
        $remotePrefix = '/data/local/tmp/yolo-' + $session.sessionId
        $remoteConfig = $remotePrefix + '.pbtxt'
        $null = Invoke-Adb @('push', $configPath, $remoteConfig)
        $record.traceSetupHostUtcAfter = [DateTimeOffset]::UtcNow.ToString('o')
        $targets = @()
        if ($Window -in @('Early', 'Both')) { $targets += @{ name = 'early'; seconds = 5; end = 30 } }
        if ($Window -in @('Late', 'Both')) { $targets += @{ name = 'late'; seconds = 185; end = 210 } }
        foreach ($target in $targets) {
            Write-Host "Waiting for $($target.name) trace at session +$($target.seconds)s (20s capture)."
            $before = Wait-DeviceTime ($start + $target.seconds) $initial.bootId
            if (($before.bootSeconds - $start) -gt ($target.end - 22)) { throw 'Trace window missed: setup/adb delay too large. Repeat with matched conditions.' }
            $remoteTrace = '/data/misc/perfetto-traces/yolo-' + $session.sessionId + '-' + $target.name + '.perfetto-trace'
            $localTrace = Join-Path $output ($target.name + '.perfetto-trace')
            $entry = [ordered]@{ name = $target.name; targetSessionSeconds = $target.seconds; commandBefore = $before; status = 'STARTING'; remoteTrace = $remoteTrace }
            $record.captures += $entry
            Save-Json (Join-Path $output 'run.json') $record
            # Foreground perfetto exits only after normal duration/flush/close. Never pull an active file.
            # shell can read /data/local/tmp; perfetto's SELinux domain may not.
            $commandLog = Invoke-Adb @('shell', "cat '$remoteConfig' | perfetto --txt -c - -o '$remoteTrace'")
            Write-Utf8 (Join-Path $output ($target.name + '-perfetto.txt')) $commandLog
            $entry.commandAfter = Get-DeviceClock
            if ($entry.commandAfter.bootId -ne $initial.bootId) { throw 'Device rebooted during trace' }
            $null = Invoke-Adb @('pull', $remoteTrace, $localTrace)
            if ((Get-Item -LiteralPath $localTrace).Length -eq 0) { throw 'Empty trace' }
            $entry.traceFile = $target.name + '.perfetto-trace'
            $entry.sha256 = (Get-FileHash -LiteralPath $localTrace -Algorithm SHA256).Hash.ToLowerInvariant()
            $entry.status = 'COLLECTED; coverage/data loss/clock require Trace Processor analysis'
            $entry.transferAfter = Get-DeviceClock
            Save-Json (Join-Path $output 'run.json') $record
        }
    }
    Write-Host 'Waiting for the app 5-minute auto-stop and writer drain. Do not stop the app.'
    $null = Wait-DeviceTime ($start + 302) $initial.bootId
    $finalized = $false
    for ($attempt = 0; $attempt -lt 15; $attempt++) {
        $tail = Invoke-Adb @('shell', "tail -n 160 '$($session.remoteLog)'")
        if ($tail -match 'Dropped Log Lines: \d+' -and $tail -match 'CUMULATIVE_SUMMARY \|') {
            $sizeBefore = Invoke-Adb @('shell', "stat -c %s '$($session.remoteLog)'")
            Start-Sleep -Seconds 1
            $sizeAfter = Invoke-Adb @('shell', "stat -c %s '$($session.remoteLog)'")
            if ($sizeBefore -eq $sizeAfter) { $finalized = $true; break }
        }
        Start-Sleep -Seconds 2
    }
    if (-not $finalized) { throw 'Log did not finalize; do not analyze a partial file as a full baseline.' }
    $null = Invoke-Adb @('pull', $session.remoteLog, (Join-Path $output 'performance.txt'))
    $record.performanceLog = 'performance.txt'
    $record.performanceSha256 = (Get-FileHash -LiteralPath (Join-Path $output 'performance.txt') -Algorithm SHA256).Hash.ToLowerInvariant()
    $record.finalClock = Get-DeviceClock
    if ($record.finalClock.bootId -ne $initial.bootId) { throw 'Device rebooted during the session' }
    Write-Utf8 (Join-Path $output 'battery-after.txt') (Invoke-Adb @('shell', 'dumpsys battery'))
    $record.finalInstalledApkSha256 = Get-InstalledApkSha
    if ($record.finalInstalledApkSha256 -ne $installedSha) { throw 'Installed APK changed during measurement.' }
    $record.status = 'COLLECTED; analyze performance log and trace integrity before comparison'
    Write-Host "Collected: $output"
} catch {
    $record.status = 'FAILED'
    $record.error = $_.Exception.Message
    throw
} finally {
    Save-Json (Join-Path $output 'run.json') $record
}
