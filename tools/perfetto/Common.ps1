Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Write-Utf8([string]$Path, [string]$Text) {
    [IO.File]::WriteAllText($Path, $Text, [Text.UTF8Encoding]::new($false))
}

function Save-Json([string]$Path, $Value) {
    Write-Utf8 $Path ($Value | ConvertTo-Json -Depth 30)
}

function Invoke-Adb([string[]]$AdbArgs) {
    $output = & $script:AdbExecutable -s $script:DeviceSerial @AdbArgs 2>&1
    if ($LASTEXITCODE -ne 0) { throw "adb $($AdbArgs -join ' '): $($output -join "`n")" }
    return ($output -join "`n")
}

function Initialize-Adb([string]$Adb, [string]$Serial) {
    $script:AdbExecutable = (Get-Command $Adb -ErrorAction Stop).Source
    if (-not $Serial) {
        $listing = & $script:AdbExecutable devices
        if ($LASTEXITCODE -ne 0) { throw 'adb devices failed' }
        $devices = @($listing | Where-Object { $_ -match '^\S+\s+device$' } | ForEach-Object { ($_ -split '\s+')[0] })
        if ($devices.Count -ne 1) { throw 'Specify -Serial; exactly one authorized device is required.' }
        $Serial = $devices[0]
    }
    if ($Serial -notmatch '^[A-Za-z0-9_.:\[\]-]+$') { throw 'Invalid device serial' }
    $script:DeviceSerial = $Serial
    if ((Invoke-Adb @('get-state')).Trim() -ne 'device') { throw 'Device is not authorized/online' }
}

function Get-DeviceClock {
    $before = [DateTimeOffset]::UtcNow.ToString('o')
    $value = Invoke-Adb @('shell', 'cat /proc/sys/kernel/random/boot_id; cat /proc/uptime; date +%s')
    $after = [DateTimeOffset]::UtcNow.ToString('o')
    $lines = @($value.Trim() -split '\r?\n')
    if ($lines.Count -ne 3) { throw "Unexpected device clock reply: $value" }
    $uptime = [double]::Parse(($lines[1] -split ' ')[0], [Globalization.CultureInfo]::InvariantCulture)
    return [pscustomobject]@{
        bootId = $lines[0]; bootSeconds = $uptime; deviceUnixSeconds = [long]$lines[2]
        hostUtcBefore = $before; hostUtcAfter = $after
        clock = '/proc/uptime: approximate CLOCK_BOOTTIME (centisecond resolution); date: wall seconds; host: UTC brackets only'
    }
}

function Get-RemoteLogs {
    $listing = Invoke-Adb @('shell', 'find /sdcard/Documents/GOTORO/performance -maxdepth 1 -type f -name "perf_*.txt" 2>/dev/null; true')
    return @($listing -split '\r?\n' | Where-Object { $_ -match '^/sdcard/Documents/GOTORO/performance/perf_[A-Za-z0-9_().-]+\.txt$' })
}

function Read-SessionHeader([string]$RemoteLog, [string]$ModelSha) {
    if ($RemoteLog -notmatch '^/sdcard/Documents/GOTORO/performance/perf_[A-Za-z0-9_().-]+\.txt$') { throw 'Unexpected log path' }
    $header = Invoke-Adb @('shell', "head -n 160 '$RemoteLog'")
    if ($header -notmatch 'Start Unix Ms: (\d+); Start Elapsed Ns: (\d+)') { return $null }
    $wall = [long]$Matches[1]; $elapsed = [long]$Matches[2]
    if ($header -notmatch '(?m)^Session ID: ([\w-]+)\r?$') { return $null }
    $session = $Matches[1]
    if ($header -notmatch '(?m)^Model SHA256: ([a-fA-F0-9]{64})\r?$') { throw 'Missing model SHA256' }
    if ($Matches[1].ToLowerInvariant() -ne $ModelSha) { throw 'Model mismatch: this is not the prepared 11-class 512 model.' }
    $compactHeader = $header.Replace(' ', '')
    foreach ($required in @('configuredDurationMs=300000;', 'InputTensor:[1,3,512,512](FLOAT32)', 'OutputTensor:[1,15,5376](FLOAT32)')) {
        if (-not $compactHeader.Contains($required)) { throw "Unexpected measurement contract: $required (check actual log header)" }
    }
    return [pscustomobject]@{ sessionId = $session; startUnixMs = $wall; startElapsedNs = $elapsed; remoteLog = $RemoteLog; header = $header }
}

function Wait-DeviceTime([double]$TargetBootSeconds, [string]$BootId) {
    while ($true) {
        $sample = Get-DeviceClock
        if ($sample.bootId -ne $BootId) { throw 'Device rebooted; session clock is invalid.' }
        $remaining = $TargetBootSeconds - $sample.bootSeconds
        if ($remaining -le 0) { return $sample }
        # Keep waits responsive without issuing periodic adb calls during the gap.
        $hostWait = [Diagnostics.Stopwatch]::StartNew()
        while ($hostWait.Elapsed.TotalSeconds -lt $remaining) {
            $hostRemaining = $remaining - $hostWait.Elapsed.TotalSeconds
            Start-Sleep -Milliseconds ([int]([Math]::Min(30000, [Math]::Max(50, $hostRemaining * 1000))))
        }
    }
}

function Get-InstalledApkSha {
    $paths = Invoke-Adb @('shell', 'pm path com.samin.objectdetection')
    $base = @($paths -split '\r?\n' | Where-Object { $_ -match '^package:/data/app/[^\s'']+/base.apk$' })
    if ($base.Count -ne 1) { throw 'Expected one installed base APK' }
    $path = $base[0].Substring(8)
    $hash = Invoke-Adb @('shell', "sha256sum '$path'")
    if ($hash -notmatch '^([a-fA-F0-9]{64})\s') { throw 'Cannot fingerprint installed APK' }
    return $Matches[1].ToLowerInvariant()
}
