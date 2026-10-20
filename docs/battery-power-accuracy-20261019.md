# 배터리 전력 측정 정확성 검증 및 개선

검증일: 2026-10-19. 대상: 현재 작업 트리의 ObjectDetectionTestApp.

## 결론

**기존 µA → mA 변환 `/ 1000.0`은 Android API 규약에 맞다. 그러나 제공된 두 로그의 전류와 충전량 카운터 변화는 약 1,200배 모순되므로, 기존 약 5~6mW를 신뢰할 수 있는 소비전력으로 해석하면 안 된다.**

가장 유력한 가설은 단말 전류 반환값이 규약의 µA 대신 mA 규모로 제공되는 제조사/펌웨어 경로 문제다. 이전 로그에는 RAW가 없어 이 가설을 확정할 수 없고, 카운터 오차·샘플링 편향도 배제할 수 없다. 이번 수정은 전류를 임의로 보정하지 않고 원시 반환값과 규약상 환산값을 보존하며, 신뢰성 미확인/모순 시 전류·전력 결과를 N/A로 남긴다.

**모든 배터리 값은 단말 전체 배터리 기준이다. 앱 단독 소비전력이나 충전기 입력 전력이 아니다.** USB 연결 중 배터리 전류가 작거나 음수여도 앱 소비가 작다는 뜻은 아니다. 배터리 온도 또한 CPU/GPU 온도가 아니다.

## 1. 확인한 코드와 측정 경로

- `AndroidBatteryMetricSource.kt`: sticky `ACTION_BATTERY_CHANGED`에서 잔량·전압·온도·충전 상태를 읽고 BatteryManager에서 전류·충전량·에너지를 읽는다. 각 값은 원자적으로 동시에 측정되는 값이 아니다.
- `BatteryDiagnostics.kt`: 단위 변환, 최소 5초 간격 수집, 표본 평균·세션 요약을 담당한다. 강제 마지막 표본은 5초 미만일 수 있다.
- `SystemPerformanceDiagnostics.kt`: 시스템 진단 executor에서 배터리를 수집한다. CPU/FPS/추론 스레드에 배터리 조회를 추가하지 않았다.
- `PerformanceLogSession.kt`: 첫/최종 표본과 종료 drain을 유지한다. 배터리 검증 결과를 수집 시점에 계산해 불변 값으로 writer에 전달하므로 지연 기록 중 다음 표본의 상태로 바뀌지 않는다.
- `PerformanceLogFormatter.kt`: 단위·범위·추정 방식과 N/A 의미를 헤더에 명시한다.
- `tools/analyze_performance_logs.py`: 기존 로그와 새 필드를 모두 읽는다. 기존 로그의 모순도 탐지하고, 카운터 기반 구간 분석을 추가했다.

기존 Preview/Overlay 관련 미커밋 변경을 보존했다. 이번 작업에서 Preview ON/OFF 바인딩, 탐지·추론·후처리 알고리즘, FPS/CPU/발열 수집 경로는 수정하지 않았다.

## 2. 공식 단위 검증

| 항목 | Android 규약 | 현재 처리 및 판정 |
| --- | --- | --- |
| `BATTERY_PROPERTY_CURRENT_NOW` | 정수 µA, +는 배터리 유입 / −는 방전 | `/1000.0` → mA. 변환식 정상 |
| `BATTERY_PROPERTY_CURRENT_AVERAGE` | 정수 µA, 평균 기간은 하드웨어 설정에 의존 | `/1000.0` → mA. 변환식 정상. 앱 세션 평균과 다름 |
| `BATTERY_PROPERTY_CHARGE_COUNTER` | 정수 µAh | 기존 µAh 보존 정상. mAh 차분은 `/1000.0` |
| `BATTERY_PROPERTY_ENERGY_COUNTER` | 정수 nWh | 기존 nWh 보존 정상. 해당 단말 로그에서는 N/A |
| 배터리 전압 | mV | Intent 값을 mV로 보존. 전력은 `mA × mV / 1000 = mW` |
| 배터리 온도 | 0.1°C | `/10.0` → °C |

공식 근거: [BatteryManager API](https://developer.android.com/reference/android/os/BatteryManager), [AOSP HealthInfo 단위 정의](https://android.googlesource.com/platform/hardware/interfaces/+/master/health/aidl/android/hardware/health/HealthInfo.aidl).

이 앱은 targetSdk 36이므로 `getIntProperty()` 미지원/오류는 `Int.MIN_VALUE`, `getLongProperty()`는 `Long.MIN_VALUE`가 공식 미지원 값이다. 기존 변환은 이들을 N/A로 처리한다. 기존의 MAX_VALUE 방어 필터도 유지했다. 전류 0 자체는 미지원 sentinel로 취급하지 않는다. 다만 0이 카운터 변화와 모순되면 별도로 경고한다.

## 3. 제공 로그의 정량 결과

원본은 사용자가 지정한 `C:\Users\SAMIN\OneDrive\Documents\카카오톡 받은 파일`에서 읽었다. 원본을 수정하지 않았다. 파일 SHA-256, 첫/마지막 표본과 전체 구간 계산은 [분석 JSON](validation/battery-accuracy-log-analysis-20261019.json)에 저장했다.

| 항목 | Preview OFF: `perf_20261019_164442.txt` | Preview ON: `perf_20261019_164821.txt` |
| --- | ---: | ---: |
| 녹화 길이 | 206.946초 | 178.681초 |
| 실제 배터리 표본 간격 합 | 206.818초 | 178.645초 |
| 표본 수 | 43 | 37 |
| 잔량 | 92 → 89% | 89 → 87% |
| 온도 | 32.7 → 35.7°C | 35.7 → 36.2°C |
| 충전량 카운터 | 3,604,520 → 3,499,760µAh | 3,488,120 → 3,406,640µAh |
| 카운터 감소량 | 104.76mAh | 81.48mAh |
| 기록된 CURRENT_NOW 표본 평균 | −1.533837mA | −1.297054mA |
| 기록된 CURRENT_AVERAGE 표본 평균 | −1.548047mA | −1.295297mA |
| 기록된 전력 표본 평균 | −5.929814mW | −5.084378mW |
| 기록된 CURRENT_NOW 시간 적분 | 0.088004mAh | 0.065136mAh |
| **카운터 기반 평균 방전 전류(양수)** | **1,823.516mA** | **1,641.960mA** |
| 카운터·구간 전압 기반 추정 방전 전력 | 7,076.358mW | 6,433.943mW |
| 카운터 감소량 / 전류 적분량 | 1,190.406배 | 1,250.920배 |

두 로그 모두 기록된 충전 상태는 Discharging, 전원은 None으로 유지됐으며 dropped log lines는 0이다. 배터리 표본 수는 최종 요약과 일치한다. 마지막 강제 표본까지 포함했으며, 프레임 로그 길이 대신 배터리 표본 시간을 분모로 사용했다.

계산식:

```text
ΔQ_mAh = (Q_before_uAh - Q_after_uAh) / 1000
I_counter_mA = ΔQ_mAh × 3600 / Δt_seconds
I_integrated_mAh = Σ[-(I_before_mA + I_after_mA)/2 × Δt_seconds/3600]
P_counter_interval_mW = I_counter_mA × (V_before_mV + V_after_mV)/2 / 1000
P_counter_total_mW = Σ[ΔQ_mAh × V_mean_mV/1000] × 3600 / ΣΔt_seconds
```

1~2mA가 3분간 흐르면 약 0.05~0.10mAh에 해당한다. 수십~100mAh 감소를 설명하지 못한다. 이는 단순 부호나 mW 환산식 문제도 아니다. 두 전류 경로(CURRENT_NOW/AVERAGE)가 모두 비슷하게 작게 보고되며, 충전량은 잔량 변화와 대략 같은 규모다.

전류를 1,000배 하면 규모는 가까워지지만 위 비율은 정확히 1,000이 아니다. 드라이버 단위 위반, fuel gauge 보정·양자화·갱신 지연, 5초 표본 사이의 부하 변동을 추가 확인해야 한다. 카운터 기반 값도 보정된 실측 전력으로 확정하지 않는다.

두 실행은 시작 잔량·온도·실행 순서가 다르다. 이 표로 Preview ON/OFF의 전력 차이나 인과관계를 결론 내리지 않는다.

## 4. 단말에서 확인한 추가 증거와 한계

연결 단말은 Galaxy S24 SM-S921N, Android 16, build `BP4A.251205.006.S921NKSSHDZH3`이다.

- 배터리 서비스 `dumpsys battery`에 `current now: 250` 등 수백 규모 전류값이 관찰됐다. 해당 제조사 dumpsys 출력 자체의 단위는 확인되지 않았으므로 이를 mA라고 단정하지 않는다.
- 새 단말 테스트에서는 실제 API 반환 `currentNowRaw=-56`, `currentAverageRaw=-100`, 환산값 `−0.056mA`, `−0.100mA`, 전압 4033mV를 확인했다. `/1000`이 한 번만 적용됨을 테스트로 검증했다. [RAW 증거](validation/battery-accuracy-device-raw-20261019.txt)
- `/sys/class/power_supply/battery/{current_now,current_avg,charge_counter,voltage_now}`는 Permission denied였다. 제조사 Health HAL/커널에서 어떤 단위가 사용되는지는 확인하지 못했다.
- 단말 회귀 테스트는 USB 연결 상태였다. 정확한 방전량, 절대 전력, Preview 간 전력 비교를 검증한 시험은 아니다.

확인된 코드 문제는 **RAW와 신뢰도 검증을 생략하고 규약상 환산값을 그대로 전력으로 발행한 것**이다. 펌웨어 단위 위반은 유력한 원인 가설이며 아직 확정되지 않았다.

## 5. 수정 내용과 로그 해석

### RAW·규약상 환산·검증 결과 분리

| 새/기존 필드 | 의미 |
| --- | --- |
| `batteryCurrentNowRaw`, `batteryCurrentAverageRaw` | API의 정확한 정수 반환값. 미지원 sentinel도 그대로 보존. 조회 실패는 N/A |
| `currentRawUnit=API_DECLARED_uA` | Android 규약이 µA라는 뜻. 단말 구현 단위 확인을 의미하지 않음 |
| `batteryCurrentNowApiMa`, `batteryCurrentAverageApiMa` | RAW를 `/1000.0`한 증거용 수학 환산값. 실제 전류의 신뢰성은 미확인 |
| `batteryVoltageMv` | 배터리 전압 mV, 기존 필드 유지 |
| `apiEstimatedBatteryPowerMw` | 규약상 환산 전류 × 전압의 검증 전 계산값 |
| `batteryCurrentMa`, `estimatedBatteryPowerMw` | 검증 전/모순 시 N/A. 아래 조건을 충족한 경우에만 일관성이 있는 추정값 |
| `batteryCurrentAverageMa` | N/A. 제조사 평균 기간이 알려지지 않아 세션/구간 평균으로 검증할 수 없음. API 환산값은 별도 필드에 보존 |
| `batteryCurrentReliability`, `batteryWarnings` | 판단과 경고 코드 |

전류는 Android 부호를 보존한다. CURRENT_NOW 표본 평균과 CURRENT_AVERAGE 속성을 구별했다. 최종 `averageApiBatteryCurrentMa`는 CURRENT_NOW의 표본 평균이다.

`BatteryDischargeTracker`는 충전·전원 상태가 같은 **연속된 배터리 미연결 방전 표본**에서 전류를 사다리꼴 적분하고 카운터 변화와 비교한다. 간격은 0초 초과 120초 이하, 유효 비교 시간은 60초 이상, 카운터 감소는 1mAh 이상이어야 한다. 전류 적분/카운터 감소 비율이 `[0.1, 10]` 밖이면 `SUSPECT_CURRENT_COUNTER_MISMATCH`를 세션 끝까지 유지한다. 방전 중 양의 CURRENT_NOW는 `SUSPECT_CURRENT_SIGN`이다.

`CONSISTENT_ESTIMATE`는 큰 모순이 없는 추정값이라는 뜻이다. 이 10배 기준은 이상 징후 탐지용 휴리스틱이며 교정이나 정확도 보장이 아니다. 빠진/제외된 방전 표본 구간이 있으면 세션 평균을 검증된 값으로 발행하지 않는다. USB 연결/충전 세션과 짧은 측정은 N/A가 정상이다. 모순이 나중에 발견되면 이미 쓴 표본을 되쓰지 않지만, 최종 상태 평균은 N/A로 출력하고 API 산술 평균을 별도로 남긴다.

### 충전량 기반 구간·세션 추정

각 BATTERY_SAMPLE에 이전 표본과의 `counterIntervalSeconds`, `counterChargeDropMah`, `counterAverageDischargeCurrentMa`, `counterEstimatedDischargePowerMw`를 기록한다. 방전은 **양수**이므로 음수인 Android API 전류와 부호를 혼동하면 안 된다.

- 충전 상태/연결 변경, 누락 카운터, 카운터 증가/리셋, 비양수 간격, 120초 초과 간격: 구간 결과 N/A와 경고. 누락 표본을 건너뛰어 연결하지 않는다.
- 카운터 변화 없음: 실제 무소비로 간주하지 않고 구간 전류·전력 N/A, `COUNTER_UNCHANGED_OR_QUANTIZED`. 누적 평균에는 해당 유효 구간의 경과시간도 포함한다.
- 30초 미만: `SHORT_INTERVAL_COUNTER_QUANTIZATION`. 5초 구간 값은 계단형일 수 있으므로 긴 구간/세션 평균으로 판단한다.
- 전류 속성이 없어도 카운터 추정은 가능하다. 전압이 하나라도 없으면 해당 구간 전력은 N/A이고 전류 추정만 제공한다. 누적 전압 경로가 불완전하면 누적 전력도 N/A다.
- `counterReliability=UNVERIFIED_FUEL_GAUGE_ESTIMATE`: 카운터 단위 규약에 따른 추정이며 독립 교정된 기준값이 아님을 명시한다.

BATTERY_SUMMARY에는 유효 구간 수·시간, 누적 감소량, 시간 기준 평균 전류·전력, 전류 적분량, 비율, 관찰된 경고를 기록한다. 일부 구간이 제외되면 전체 녹화 시간 평균이 아니라 기록된 유효 구간 평균이다. API 값의 유효 개수는 단순 조회/산술 가능 개수이며 정확도 보장이 아니다.

분석기는 기존 로그 수치를 `byChargingStatus` 등에 증거로 보존한다. 모순이 검출되면 상위 `averageDischargingCurrentMa`와 `averageEstimatedDischargingPowerMw`는 N/A다. 새 로그의 검증 결과 N/A 때문에 원인 분석이 막히지 않도록 별도 API 환산 필드로 교차 검증한다.

## 6. 검증 결과

| 검증 | 결과 |
| --- | --- |
| 전체 JVM 단위 테스트 | **133개 통과**, 실패/오류/skip 0 |
| 신규 배터리 추적 단위 테스트 | 위 테스트 중 **7개 통과** |
| 로그 분석기 Python 테스트 | **11개 통과**. 카운터 독립 계산/새 로그·기존 모순 처리 테스트 2개 추가 |
| Galaxy S24 배터리 instrumented 테스트 | **3개 통과**, 실패/오류/skip 0 |
| `assembleDebug`, `assembleDebugAndroidTest` | 성공 |
| `lintDebug` | 오류/Fatal 0, 경고 179 |

신규 JVM 테스트는 RAW와 환산값 분리·sentinel·부호·무보정, 3분 카운터 변화 계산, 모순 시 N/A와 세션 latch, 검증 최소 시간/감소량, 충전 전환/누락/리셋/시간 오류, 카운터 정체·양자화, 전압 미지원 경로를 검증한다. 기존 세션 종료·메모리 제한·배터리 OFF gating·프레임/시스템 로그 테스트도 통과했다.

단말 테스트는 실제 RAW/환산값 일치, 반복 start/stop과 최종 표본, MediaStore의 진단 ON→OFF→ON 로그 저장 및 새 필드, **동일 이미지의 YOLO 결과가 배터리 진단 ON/OFF/ON에서 동일함**을 확인했다. 이번에는 Preview 전환 단말 테스트 전체를 재실행하지 않았으며 해당 코드는 수정하지 않았다.

증거: [결과 JSON](validation/battery-accuracy-results-20261019.json), [단말·빌드·lint 로그](validation/battery-accuracy-device-build-20261019.txt), [최종 소스 단위 테스트·빌드·lint 재확인](validation/battery-accuracy-final-build-20261019.txt), [단말 JUnit XML](validation/battery-accuracy-device-tests-20261019.xml), [Python 테스트](validation/battery-accuracy-python-20261019.txt). 테스트 runner 준비 중 `androidx.test.services` UID가 없다는 appops 경고가 있었으나 실제 3개 테스트와 Gradle 빌드는 성공했다.

실행 명령(PowerShell, JDK는 설치된 Android Studio JBR 사용):

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:connectedDebugAndroidTest :app:lintDebug '-Pandroid.testInstrumentationRunnerArguments.class=com.samin.objectdetection.metrics.BatteryDiagnosticsInstrumentedTest' --offline --console=plain
.venv\Scripts\python.exe -m unittest discover -s tools -p test_analyze_performance_logs.py -v
```

## 7. 다음 단말 시험 방법

1. 수정본 debug APK를 설치한다. 시스템 성능 진단과 배터리/발열 진단을 모두 켜고 샘플 간격은 두 실행에서 같게 맞춘다. 모델·스레드·입력 크기·ML Kit·탐지 간격을 고정한다.
2. **USB/무선 충전을 해제**하고 밝기·화면 주사율·네트워크·백그라운드 앱·장면·기기 방향을 동일하게 맞춘다. 비슷한 잔량과 시작 온도를 기록하고 충분히 식힌다. 소프트웨어 `dumpsys battery unplug`로 상태만 위장하지 않는다.
3. Preview OFF/ON 각각 최소 5~10분 기록한다. 종료 후 로그가 최종 요약까지 저장되는지 확인한다. ON/OFF 순서를 바꿔 여러 번 반복한다. 비교용 설정 이외의 알고리즘은 바꾸지 않는다.
4. RAW, API 환산값, 전압, `batteryCurrentReliability`, `batteryWarnings`, 카운터 감소량을 확인한다. 이 단말에서 RAW가 −1,000~−2,000 정도인데 카운터가 약 1~2A 방전을 가리키면 `SUSPECT_CURRENT_COUNTER_MISMATCH`와 전력 N/A가 기대 동작이다. 이를 근거로 자동 1,000배 보정하지 않는다.
5. 최종 표본 수·drop 수·`counterCoveredSeconds`를 확인한다. 카운터의 계단형 갱신을 고려해 5초 구간 피크보다 수분 누적 평균을 사용한다. 제외된 구간·충전 전환이 있는 실행은 같은 시간 범위의 비교에 사용하지 않는다.
6. 무선 ADB 등으로 측정 중 전원 조건을 유지하면서 RAW API, `dumpsys battery`, 접근 가능한 Health HAL/커널 자료를 같은 시간대에 비교한다. Permission denied이면 그 한계를 기록한다. dumpsys 수치 일치만으로 서로 독립된 계측이라고 보지 않는다.
7. 절대 전력/단말 단위 확인이 필요하면 제조사 자료나 독립된 배터리 측 계측으로 교정한다. USB 전력계만으로 방전 중 배터리 전력을 검증했다고 해석하지 않는다. 검증 전에는 API 환산 전력의 Preview 비교를 결론으로 사용하지 않는다.

분석 예:

```powershell
.venv\Scripts\python.exe tools\analyze_performance_logs.py 'OFF 로그 경로' 'ON 로그 경로' --interval-seconds 30
```

최종적으로 RAW의 실제 단위와 카운터 정확도가 확인되기 전까지 앱 단독 전력, 정밀 절대 전력 또는 Preview의 전력 절감률을 주장하지 않는다.
