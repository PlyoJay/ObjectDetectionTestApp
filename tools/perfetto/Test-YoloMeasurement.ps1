# Offline contract checks; never talks to adb or starts a measurement.
. "$PSScriptRoot/Common.ps1"
$script:FixtureHeader = @'
Session ID: test-session
Start Unix Ms: 1792975853302; Start Elapsed Ns: 100000000000
Model SHA256: cc189262fd705e7d0b48246ebe42f868ff9b7370d97a0a6d7a7f756f16d4bad1
Input Tensor: [1, 3, 512, 512] (FLOAT32)
Output Tensor: [1, 15, 5376] (FLOAT32)
configuredDurationMs=300000; timerClock=elapsedRealtimeNanos
'@
function Invoke-Adb([string[]]$AdbArgs) { return $script:FixtureHeader }
$session = Read-SessionHeader '/sdcard/Documents/GOTORO/performance/perf_20261026_120000.txt' 'cc189262fd705e7d0b48246ebe42f868ff9b7370d97a0a6d7a7f756f16d4bad1'
if ($session.startElapsedNs -ne 100000000000 -or $session.sessionId -ne 'test-session') { throw 'Header clock/session parsing failed' }
$rejected = $false
try { Read-SessionHeader $session.remoteLog ('0' * 64) } catch { $rejected = $true }
if (-not $rejected) { throw 'Wrong model accepted' }
$script:FixtureHeader = $script:FixtureHeader.Replace('[1, 15, 5376]', '[1, 13, 5376]')
$rejected = $false
try { Read-SessionHeader $session.remoteLog 'cc189262fd705e7d0b48246ebe42f868ff9b7370d97a0a6d7a7f756f16d4bad1' } catch { $rejected = $true }
if (-not $rejected) { throw 'Nine-class output accepted' }
function Get-DeviceClock { return [pscustomobject]@{ bootId = 'new-boot'; bootSeconds = 185 } }
$rejected = $false
try { Wait-DeviceTime 185 'old-boot' } catch { $rejected = $true }
if (-not $rejected) { throw 'Reboot was not detected' }
Write-Host 'PASS: live header syntax, wrong model/output rejection, reboot rejection (4 checks).'

# Exercise the full runner with a fake adb and virtual device time. No real waits/adb.
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('yolo-runner-' + [guid]::NewGuid())
$null = [IO.Directory]::CreateDirectory($testRoot)
$mock = Join-Path $testRoot 'Mock-Adb.ps1'
Write-Utf8 $mock @'
$global:LASTEXITCODE = 0
$root = $env:YOLO_RUNNER_TEST_ROOT
$mode = $env:YOLO_RUNNER_TEST_MODE
$statePath = Join-Path $root 'mock-state.json'
$state = if (Test-Path $statePath) { Get-Content $statePath -Raw | ConvertFrom-Json } else { [pscustomobject]@{ clocks=0; finds=0 } }
$call = $args[2..($args.Count-1)]
$cmd = $call -join ' '
Add-Content (Join-Path $root 'adb-calls.txt') $cmd
switch -Regex ($cmd) {
  '^get-state$' { 'device'; break }
  '^shell pm path' { 'package:/data/app/mock/base.apk'; break }
  '^shell sha256sum' { ('a' * 64) + '  /data/app/mock/base.apk'; break }
  '^shell cat /proc/sys/kernel/random/boot_id;' {
    $times = if ($mode -eq 'Baseline') { @(100,402,402) } else { @(100,105.1,125.2,125.3,285.1,305.2,305.3,402,402) }
    'mock-boot'
    ([string]$times[[Math]::Min($state.clocks,$times.Count-1)]) + ' 0.00'
    '1792975853'
    $state.clocks++; break
  }
  '^shell find ' { if ($state.finds++ -gt 0) { '/sdcard/Documents/GOTORO/performance/perf_20261026_120000.txt' }; break }
  '^shell head -n 160' { Get-Content (Join-Path $root 'fixture.txt') -Raw; break }
  '^shell tail -n 160' { 'Dropped Log Lines: 0'; 'CUMULATIVE_SUMMARY | successfulFrames=0'; break }
  '^shell stat -c' { '1234'; break }
  '^shell if test -r' { 'N/A_unreadable_tracefs'; break }
  '^shell atrace --list_categories' { 'dalvik - Dalvik VM'; break }
  '^shell cat .*perfetto --txt' { 'Trace written'; break }
  '^pull ' { [IO.File]::WriteAllText($call[-1], 'mock trace/log bytes'); break }
  '^push ' { 'pushed'; break }
  '^shell dumpsys battery' { 'level: 80'; break }
  default { throw "Unexpected mock adb call: $cmd" }
}
$state | ConvertTo-Json | Set-Content $statePath
'@
Write-Utf8 (Join-Path $testRoot 'fixture.txt') ($script:FixtureHeader.Replace('[1, 13, 5376]', '[1, 15, 5376]'))
Save-Json (Join-Path $testRoot 'manifest.json') @{ apk=@{sha256=('a'*64); sourceBinding='BUILT_FROM_RECORDED_SOURCES'}; model=@{sha256='cc189262fd705e7d0b48246ebe42f868ff9b7370d97a0a6d7a7f756f16d4bad1'} }
Write-Utf8 (Join-Path $testRoot 'conditions.txt') 'Offline mock conditions'
$oldRoot = $env:YOLO_RUNNER_TEST_ROOT; $oldMode = $env:YOLO_RUNNER_TEST_MODE
try {
    $env:YOLO_RUNNER_TEST_ROOT = $testRoot
    foreach ($mode in @('Baseline', 'Perfetto')) {
        $env:YOLO_RUNNER_TEST_MODE = $mode
        # Only remove these two exact temporary files between mock runs.
        foreach ($name in @('mock-state.json', 'adb-calls.txt')) {
            $testFile = Join-Path $testRoot $name
            if (Test-Path -LiteralPath $testFile) { Remove-Item -LiteralPath $testFile }
        }
        $runOutput = Join-Path $testRoot $mode
        & "$PSScriptRoot/Start-YoloMeasurement.ps1" -Mode $mode -BuildManifest (Join-Path $testRoot 'manifest.json') -OutputDirectory $runOutput -ConditionsFile (Join-Path $testRoot 'conditions.txt') -Adb $mock -Serial 'mock-1'
        $result = Get-Content (Join-Path $runOutput 'run.json') -Raw | ConvertFrom-Json
        $calls = Get-Content (Join-Path $testRoot 'adb-calls.txt') -Raw
        if ($result.status -notlike 'COLLECTED*') { throw "Runner did not finish: $mode" }
        if ($mode -eq 'Baseline' -and $calls.Contains('perfetto')) { throw 'Baseline unexpectedly invoked Perfetto' }
        if ($mode -eq 'Perfetto' -and $result.captures.Count -ne 2) { throw 'Expected exactly two short traces' }
    }
    Write-Host 'PASS: full Baseline runner invokes no Perfetto; full replay collects two traces (virtual time).'
} finally {
    $env:YOLO_RUNNER_TEST_ROOT = $oldRoot; $env:YOLO_RUNNER_TEST_MODE = $oldMode
}
