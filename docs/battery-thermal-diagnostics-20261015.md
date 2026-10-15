# 배터리·발열 진단 — 2026-10-15

## 목적과 기존 구조

YOLO 실시간 성능 로그에 **단말 전체 배터리** 측정을 추가했다. 앱 단독 소비 전력이나 CPU/GPU 칩 온도 측정 기능은 아니다. 모델·입출력·추론 경로를 수정하지 않고 기존 진단 경로를 확장했다.

- `AppSettings.performanceLog` → `AppSettingsCodec` → SharedPreferences(`object_detection_app_settings`)에 설정을 저장한다. 기존 적용/복원/프리셋/rollback 흐름을 유지한다.
- MainActivity가 로그 시작 시 고정 설정을 `PerformanceLogHeader`로 전달하고, `PerformanceLogRecorder`가 MediaStore `Documents/GOTORO/performance/perf_*.txt`를 생성한다.
- `PerformanceLogSession` → `SystemPerformanceDiagnostics`의 단일 `PerformanceDiagnostics` scheduled executor에서 시스템 지표를 읽는다. CameraX 분석/YOLO 실행 스레드에서는 배터리 API를 호출하지 않는다.
- 기존 CPU 측정은 프로세스 CPU 시간 차분(100%=한 코어, 100% 초과 가능)과 읽기 가능한 cpufreq policy를 사용한다. 이 방식은 그대로다.
- 기존 `SystemMetricsReader`의 Thermal Status polling/listener, `THERMAL_STATUS` 변화 로그, Thermal Headroom 캐시 및 최소 10초 API 제한을 그대로 사용한다. Android source의 세션 간 호출 제한도 유지한다.
- `OrderedLogWriter`의 비동기 bounded queue와 drain을 재사용한다. 30초 `INTERVAL_PREVIEW`, 확정 `INTERVAL_SUMMARY`, `CUMULATIVE_SUMMARY`, FRAME/OVERLAY/ERROR 형식을 유지하고 별도 BATTERY 레코드를 추가한다.

## 설정 UI와 활성 조건

기존 **성능 측정 및 로그** 카테고리에서 `System Performance Diagnostics` 아래에 **배터리·발열 진단** 스위치를 추가했다. 기본값은 OFF이며 기존 설정에 새 키가 없으면 OFF로 읽는다. 새 화면이나 별도 샘플링 간격 입력은 없다.

| 시스템 진단 | 배터리 설정 | 로그 기록 중 동작 |
|---|---|---|
| OFF | OFF/ON | 시스템·배터리 API 수집 없음. 기존 프레임/구간 통계 유지 |
| ON | OFF | 기존 CPU/Thermal Status/Headroom 수집 |
| ON | ON | 기존 시스템 지표 + 배터리·발열 확장 정보 |

시스템 진단 OFF일 때 배터리 스위치는 비활성화되며 저장된 선택은 보존한다. 시스템 진단을 다시 켜면 기존 배터리 선택을 복원한다. 설정은 다음 로그 세션에 적용된다. 로그 기록을 종료한 뒤 설정을 적용하면 기존 Activity 재생성/설정 복원 흐름을 따른다. 배터리 설정은 `toDetectionConfig()`에 영향을 주지 않는다.

## 수집 구조와 lifecycle

`AndroidSystemMetricSource.battery()`가 lazy `AndroidBatteryMetricSource`를 사용한다. `registerReceiver(null, IntentFilter(ACTION_BATTERY_CHANGED))`로 sticky snapshot을 읽는다. **실제 BroadcastReceiver를 등록하지 않으므로 등록/해제 짝이나 늦은 배터리 콜백이 생기지 않는다.** applicationContext만 보관한다. 배터리·충전 변화는 샘플 시점에 관찰하며 샘플 사이의 짧은 변화는 놓칠 수 있다.

`BatteryMetricsReader`는 기존 시스템 tick에서 실행하고 5초 미만 재조회를 억제한다. 기본 설정에서는 약 0/5/10/15초에 수집한다. 시스템 간격이 5초보다 길면 그 간격을 따르고, 예를 들어 3초 tick이면 약 6초 간격으로 수집한다. `scheduleWithFixedDelay`이므로 수집 시간만큼 간격이 늘어날 수 있다. 별도 타이머/스레드/프레임당 조회는 없다.

sticky intent 수치는 마지막 배터리 broadcast의 snapshot이며 CURRENT_NOW와 원자적으로 동시 측정된 값은 아니다. 전력은 이 점에서도 추정치다.

정상 stop 시 동일 executor에서 최종 배터리 endpoint를 한 번 읽고, 기존 thermal listener 해제 및 executor shutdown 후 writer를 drain한다. 이 마지막 조회는 5초 제한의 예외다. 추가 Headroom API 호출은 하지 않고 마지막 시스템 샘플을 재사용한다. `headroomAgeMs`로 캐시 나이를 표시한다. 정지 요청 시각과 마지막 API 조회 시각은 약간 다를 수 있어 session duration, first/last sample 시각, sample span을 함께 기록한다. 프로세스 강제 종료/파일 쓰기 실패에서는 최종 footer가 보장되지 않는다(기존 writer 동작).

## API, 단위, 유효성

| 로그 필드 | 원본 | 처리 |
|---|---|---|
| batteryLevelPct | EXTRA_LEVEL / EXTRA_SCALE | scale>0, 0≤level≤scale에서 100×level/scale |
| chargingStatus | EXTRA_STATUS | Charging / Discharging / Full / NotCharging / Unknown |
| pluggedType | EXTRA_PLUGGED | None / USB / AC / Wireless / Dock, bit 조합은 `+`로 연결, 알 수 없는 bit는 Unknown |
| batteryTemperatureC | EXTRA_TEMPERATURE | 0.1°C → °C. -100~200°C 범위 밖은 N/A |
| batteryVoltageMv | EXTRA_VOLTAGE | mV 그대로, 1~100000 이외 N/A |
| batteryCurrentMa | CURRENT_NOW | µA ÷1000 → signed mA |
| batteryCurrentAverageMa | CURRENT_AVERAGE | µA ÷1000 → signed mA. API의 평균 창은 기기마다 다름 |
| batteryChargeCounterUah | CHARGE_COUNTER | µAh 그대로, 음수/최댓값은 N/A |
| batteryEnergyCounterNwh | ENERGY_COUNTER | nWh 그대로(Long), 음수/최댓값은 N/A |
| estimatedBatteryPowerMw | currentMa × voltageMv /1000 | signed **추정치**, 두 입력이 모두 유효한 경우만 계산 |

누락된 extra, BatteryManager 서비스 없음, API 예외/LinkageError, Int.MIN_VALUE/Long.MIN_VALUE 미지원 sentinel은 N/A다. 전류는 음수도 유효하며 Int.MAX_VALUE는 이상값으로 거른다. 배터리 없음(EXTRA_PRESENT=false)은 수치 지표 N/A로 처리한다. 실제 0 전류/카운터는 유효하며 지원되지 않은 값을 0으로 대체하지 않는다. 제조사가 미지원을 잘못 0으로 보고하는 경우 공개 API만으로 실제 0과 구분할 수 없다는 한계가 있다.

공개 API의 전류 부호(+배터리 유입/-배터리 유출)를 그대로 기록하며 chargingStatus/pluggedType을 함께 남긴다. 기기 보고값이 상태와 다르더라도 부호를 임의로 뒤집지 않는다. 충전 중 전류·추정 전력은 앱 소비 전력으로 해석하지 않는다. 배터리 온도는 CPU/GPU 칩 온도가 아니고, Headroom은 온도도 백분율도 아니다.

API 근거: [BatteryManager](https://developer.android.com/reference/android/os/BatteryManager), [PowerManager](https://developer.android.com/reference/android/os/PowerManager), [Thermal API 호출 주기](https://developer.android.com/games/optimize/adpf/thermal).

## 로그와 집계

아래 숫자는 **형식 설명용 예시이며 실제 측정 결과가 아니다**.

```text
BATTERY_SAMPLE | sessionId=example elapsedSeconds=5.000 batteryLevelPct=80.000 batteryTemperatureC=35.500 batteryVoltageMv=3900 batteryCurrentMa=-450.000 batteryCurrentAverageMa=N/A batteryChargeCounterUah=3000000 batteryEnergyCounterNwh=N/A chargingStatus=Discharging pluggedType=None estimatedBatteryPowerMw=-1755.000 thermalStatus=NONE thermalHeadroom=0.400 headroomAgeMs=5000.000 batteryCollectionDurationMs=1.000 scope=device_battery power=estimated_signed temperature=battery_not_cpu_gpu
BATTERY_SUMMARY | sessionId=example measurementDurationSeconds=720.000 firstSampleSeconds=0.010 lastSampleSeconds=720.005 sampleSpanSeconds=719.995 startBatteryLevelPct=80.000 endBatteryLevelPct=77.000 batteryLevelDeltaPct=-3.000 startBatteryTemperatureC=33.000 endBatteryTemperatureC=38.000 maxBatteryTemperatureC=38.500 batteryTemperatureDeltaC=5.000 samples=145 validSamples=0 unavailableSamples=145 currentValidSamples=145 currentUnavailableSamples=0 powerValidSamples=145 powerUnavailableSamples=0 temperatureValidSamples=145 chargingStatusChanged=false pluggedTypeChanged=false thermalStatusChanged=true scope=device_battery power=estimated_signed sampleMean=true
BATTERY_STATUS_SUMMARY | sessionId=example chargingStatus=Discharging averageBatteryCurrentMa=-450.000 currentValidSamples=145 averageEstimatedBatteryPowerMw=-1755.000 powerValidSamples=145 scope=device_battery
```

`BatterySummary`는 첫/마지막 관측값, 최대 온도, 상태 변화 여부, 개수, 상태별 합/개수만 보유한다. 배터리 진단용 메모리는 측정 길이에 비례해 증가하지 않는다. Thermal 이력은 기존 `THERMAL_STATUS`, Headroom 추이는 기존 SYSTEM 및 BATTERY_SAMPLE로 남긴다.

- 변화량은 **종료−시작**이며 잔량 변화 단위는 percentage points다. 끝값이 N/A이면 이전 값을 끝값으로 대체하지 않는다.
- 시작/종료 온도는 처음/마지막 관측값, 최고 온도는 **관측된 샘플 중 최고**다. 첫 관측 전이나 샘플 사이의 최고 온도는 알 수 없다.
- `validSamples`는 모든 배터리 필드와 상태/연결이 지원된 샘플 수다. `unavailableSamples`는 일부라도 누락된 샘플 수로, 모든 수치가 없다는 뜻은 아니다. 전류/전력은 각각 별도의 유효/미지원 개수를 확인한다.
- 전류와 전력은 각각 유효 샘플만 산술 평균한다. 시간 가중 평균이나 에너지 적분은 아니다. Charging/Discharging/Full/NotCharging/Unknown별 BATTERY_STATUS_SUMMARY를 출력하므로 충전과 방전 평균을 섞지 않는다.
- chargingStatusChanged/pluggedTypeChanged/thermalStatusChanged는 관측된 변화다. 알려진 thermal 상태끼리의 실제 변화를 집계하고 미지원→지원만으로 발열 변화라고 판단하지 않는다.
- **짧은 측정에서 잔량 변화 0%는 소비 전력 0이라는 의미가 아니다.** 프레임 지연, CPU, 온도, 방전 전류/추정 전력을 함께 비교한다.
- bounded queue가 샘플 행을 버려도 집계는 행 enqueue 전에 수행되어 정상 footer의 배터리 요약은 보존된다. Dropped Log Lines와 분석기 sampleLogComplete를 확인한다.

## 분석 도구

기존 사용법을 유지한다.

```powershell
python tools/analyze_performance_logs.py perf_on_1.txt perf_off.txt perf_on_2.txt
python tools/analyze_performance_logs.py perf_on_1.txt --start-seconds 60 --exclude-tail-seconds 30
```

기존 FPS/실제 FRAME 추론 평균·P95 계산을 유지하며 `diagnostics`에 CPU, Thermal Status 이력/Headroom 추이, 배터리 잔량·온도 변화, 상태별 평균, 평균 방전 전류 및 추정 전력을 추가했다. 지원되지 않거나 배터리 기능이 없는 기존 로그의 배터리 수치는 문자열 `N/A`다. 기존 프레임 통계의 JSON null 규약은 바꾸지 않았다.

구간 필터는 배터리/시스템 지표에도 적용한다. 전체 로그 분석일 때만 stop 후 최종 endpoint를 포함하고 authoritative fullSessionSummary/fullSessionStatusSummaries를 함께 제공한다. 잘라낸 구간에서는 해당 구간의 관측 샘플만 사용한다. 일부 로그 행이 누락되면 샘플 기반 평균에 편향이 생길 수 있으므로 전체 측정은 footer 요약과 대조한다. 배터리 샘플이 없는 구간의 수치를 보간하지 않는다. cached Headroom 평균은 독립적인 API 측정 평균이 아니다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| app/src/main/java/com/samin/objectdetection/metrics/BatteryDiagnostics.kt | 단위/유효성 처리, throttled reader, 샘플 formatter, O(1) 요약 |
| app/src/main/java/com/samin/objectdetection/metrics/AndroidBatteryMetricSource.kt | sticky intent와 BatteryManager 조회 |
| app/src/main/java/com/samin/objectdetection/metrics/AndroidSystemMetricSource.kt | 배터리 source lazy 연결 |
| app/src/main/java/com/samin/objectdetection/metrics/SystemPerformanceDiagnostics.kt | 기존 executor/tick/stop에 배터리 연결 |
| app/src/main/java/com/samin/objectdetection/metrics/PerformanceLogSession.kt | 활성 조건, 집계 및 최종 drain |
| app/src/main/java/com/samin/objectdetection/metrics/PerformanceLogFormatter.kt | 헤더/해석 정보, 설정 값 |
| app/src/main/java/com/samin/objectdetection/settings/AppSettings.kt | 기본 OFF 설정 추가 |
| app/src/main/java/com/samin/objectdetection/settings/AppSettingsStore.kt | 기존 codec/저장 경로 확장 |
| app/src/main/java/com/samin/objectdetection/settings/SettingsActivity.kt | 기존 카테고리 내 스위치·의존성·요약 |
| app/src/main/java/com/samin/objectdetection/MainActivity.kt | 로그 시작 시 배터리 설정 전달 1행 |
| tools/analyze_performance_logs.py | 선택적 배터리/시스템 분석 |
| app/src/test/java/com/samin/objectdetection/metrics/BatteryDiagnosticsTest.kt | 단위/예외/상태별 평균/활성 조건/종료/대량 집계 테스트 |
| app/src/androidTest/java/com/samin/objectdetection/metrics/BatteryDiagnosticsInstrumentedTest.kt | 공개 API, 반복 종료, ON/OFF/ON 고정 이미지 결과 검증 |
| app/src/androidTest/java/com/samin/objectdetection/settings/SettingsActivityTest.kt | 추가 제어 접근성·설정 재생성/저장/재진입 검증 |
| tools/test_analyze_performance_logs.py | ON/OFF/ON·구간·N/A·미지원 회귀 검증 |

작업 시작 전 존재한 detector/benchmark/inspect_tflite 변경은 이번 구현 범위 밖이며 보존했다. 이번 작업은 best.tflite, 모델 입출력, CPU Threads=4, XNNPACK=ON, Letterbox, Confidence=0.2, NMS=0.45, Geometry Filter, ML Kit, Overlay/TTS 및 기존 프레임/추론 구조를 변경하지 않았다.

## 성능 영향과 검증 결과

배터리 수집은 기존 진단 executor에서만 수행하고 OFF 시 battery reader/source 조회를 하지 않는다. 추가 데이터 객체는 샘플당 생성하며 frame당 생성하지 않는다. 수치 formatting과 파일 I/O는 기존 writer에서 처리한다. 배터리 source를 읽는 동안 session lock을 잡지 않으며 집계/enqueue 시에만 기존 lock을 짧게 사용한다. 스레드 분리만으로 실제 기기의 FPS 감소가 없다고 보장할 수는 없다. `batteryCollectionDurationMs`와 장시간 ON/OFF/ON 비교로 추가 비용을 확인한다.

| 검증 | 결과 | 근거 |
|---|---|---|
| 전체 JVM 단위 테스트 | **110개 통과, 실패 0** | `:app:testDebugUnitTest`, `validation/battery-thermal-ui-final-tests-20261015.txt` 및 `app/build/test-results/testDebugUnitTest` |
| Python 분석기 | **6개 통과** | `validation/battery-thermal-analyzer-tests-20261015.txt` |
| 신규 배터리 instrumentation | **3개 통과** | 실제 공개 API/반복 start-stop, 고정 이미지 ON/OFF/ON 인식 결과 동일, MediaStore ON/OFF/ON과 정상 footer/drain |
| 설정 UI instrumentation | **10개 통과** | 신규 스위치 포함 61개 제어 접근, OFF/ON 저장·복원·비활성화, 재생성/재진입, 기존 프리셋/rollback/기타 설정 회귀 |
| 기존 시스템 진단 instrumentation | **2개 통과** | `validation/battery-thermal-final-tests-20261015.txt`에서 실패한 테스트는 SettingsActivityTest에 한정. 기존 공개 API/CPU/thermal 수집과 YOLO/MediaStore 테스트는 통과 |
| 실기기 perf ON/OFF/ON 분석 | **3개 로그 정상 처리** | `validation/battery-thermal-device-perf-*.txt`, `validation/battery-thermal-device-analysis-20261015.json` |
| 빌드 및 diff 검사 | **성공** | debug 앱/AndroidTest APK 빌드, `git diff --check` |

최종 배터리+설정 기기 검증은 `validation/battery-thermal-ui-final-tests-20261015.txt`의 **13개/실패 0, BUILD SUCCESSFUL**이다. 초기/재시도 실패 로그도 삭제하지 않고 남겨 원인과 재검증 이력을 보존했다. 단위 테스트는 미지원 sentinel/예외, signed µA→mA 및 mV 전력 계산, 잔량/온도 단위, 혼합 충전 상태별 유효 평균, OFF 시 무조회, 종료 endpoint/drain, 10만 샘플 집계를 검증한다.

실기기 수집 시간 10회는 약 **2.374~5.261ms, 평균 3.562ms**였다(`validation/battery-thermal-device-samples-final-20261015.txt`). 이는 진단 worker에서 수행한 API 조회 비용이며 CameraX FPS 영향이나 소비 전력에 대한 검증 결과는 아니다.

실기기 환경은 **SM-S921N / Android 16(API 36)**이다. USB 충전 상태에서 배터리 잔량·온도·전압·전류·평균 전류·charge counter를 읽었고 energy counter 미지원은 N/A로 기록했다. Charging 상태에서도 음수 전류가 보고된 샘플이 있어 원래 부호와 상태를 함께 남기는 동작을 확인했다. 이 값으로 앱 소비 전력을 추정하지 않았다.

UI 검증 초기에 Android 시스템의 **16KB 네이티브 라이브러리 호환성 경고**가 앱 포커스를 가로채 NoActivityResumed/RootViewWithoutFocus 실패가 발생했다. 스크린샷은 `validation/battery-thermal-test-popup-20261015.png`에 있다. 기존 네이티브 의존성 경고를 확인했으며 이번 작업에서는 라이브러리나 런타임 호환성 옵션을 변경하지 않았다. 설정 UI 테스트는 알려진 Android 시스템 ELF 정렬 경고에서 현지화된 ‘확인’ 버튼만 누른 후 계속하도록 보완했다. 영구 ‘다시 표시 안 함’은 선택하지 않는다. Espresso 실행 중 애니메이션 scale만 임시 0으로 바꿨고 finally에서 기존 값(모두 1.0)으로 복원한다.

미검증 항목은 **10~15분 실시간 CameraX FPS/전력 비교**, 실제 케이블 연결·해제/무선 충전의 상태 전환, 프로세스 강제 종료 후 복원, 다른 제조사/미지원 단말, 장시간 메모리·스레드 누수 profiling이다. 10만 개의 가상 샘플 집계와 짧은 반복 start/stop 테스트는 실제 장시간 누수 검증을 대신하지 않는다. 실제 ON/OFF/ON MediaStore smoke 로그는 수십 ms의 종료/저장 검증용이며 프레임 0개이므로 FPS·전력 효율 벤치마크로 사용하지 않는다.

## 실제 기기 10~15분 권장 절차

1. 동일한 best.tflite, Threads=4, XNNPACK=ON, Letterbox, Confidence=0.2, NMS=0.45를 유지한다. Geometry Filter/ML Kit/Overlay/TTS는 비교 세션마다 동일하게 유지한다. 상세 Debug 로그와 PNG 저장은 OFF 권장이다.
2. **시스템 진단 ON, 배터리·발열 ON, Sample Interval=5000ms**로 저장한다. 앱 종료/재실행 후 설정을 다시 확인한다. FPS 표시 여부도 세션 간 동일하게 유지한다.
3. 화면 밝기/주사율/화면 켜짐, 카메라 장면·움직임, 네트워크와 백그라운드 앱, 실내 온도를 일정하게 맞춘다. 동일한 시작 배터리 온도와 비슷한 잔량(예: 50~80%)을 맞춘다. 필요하면 충전 후 식힌다.
4. 전력 효율 측정은 **USB/AC/Wireless 충전을 끊고** 수행한다. 디버깅이 필요하면 무선 ADB를 사용하되 통신 조건도 통일한다. Charging/NotCharging/Full 샘플을 Discharging 평균에 합치지 않는다.
5. 짧게 워밍업하고 성능 로그를 시작하여 **12분(허용 10~15분)** 같은 장면에서 측정한 후 정상 종료한다. 앱을 강제 종료하지 말고 로그 저장 완료를 확인한다.
6. perf 파일의 BATTERY_SAMPLE, BATTERY_SUMMARY, BATTERY_STATUS_SUMMARY(Discharging), THERMAL_STATUS와 30초 INTERVAL_SUMMARY/CUMULATIVE_SUMMARY를 확인한다. 최종 endpoint와 Dropped Log Lines도 확인한다.
7. 잔량/온도 변화, 최고 온도, 유효 current/power 수, 평균 signed 방전 전류·추정 전력, CPU, Headroom 추이, 추론 평균/P95와 FPS를 함께 비교한다. 미지원은 N/A로 남긴다. 짧은 잔량 변화만으로 mWh나 앱 단독 전력을 계산하지 않는다.
8. 진단 비용 비교는 시스템 진단을 ON으로 고정한 채 배터리 **ON → OFF → ON**을 각각 같은 시간으로 측정한다. 장시간 비교는 각 10~15분이며 세션 사이 충분히 냉각한다. 순서를 반대로 한 반복도 수행한다. 기기 온도/OS 백그라운드 상태가 다른 세션을 단순 비교하지 않는다.
9. 충전 변화 검증은 별도의 기능 테스트에서 케이블 연결/해제를 샘플 간격보다 길게 유지하여 Charging/Discharging, pluggedType, 변경 여부, 분리된 평균을 확인한다. 이 세션은 방전 효율 비교에서 제외한다.
10. 30분 이상 반복 시작/종료, 백그라운드 전환, 설정 적용/Activity 재생성 후에도 이전 세션 샘플이 계속 기록되지 않는지 확인한다. 메모리/스레드 수 증가, 온도 상승에 따른 FPS 변화, 지원되지 않는 API를 가진 다른 기기의 N/A를 확인한다.
