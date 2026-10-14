# 경량 시스템 성능 진단 구현 및 검증

기준: 2026-10-13 원격 최신 `main`과 동일한 `5ba8951c353f167e28e5fc7a35412fcf15ce5dd6`. 기존 [성능 분석 보고서](/D:/develop/Projects/TestProjects/ObjectDetection/docs/yolo-realtime-performance-analysis-20261013.md)와 제공된 `perf_20261013_171159.txt`를 확인하고 구현했다. 모델·backend·CPU threads 기본값·입력 크기는 변경하지 않았다.

기존 로그에서는 성능 저하를 확인할 수 있으나 발열 원인은 확정할 수 없다. 새 진단은 시스템 지표와 30초 프레임 통계를 같은 단조 시계에 연결해 다음 장시간 실행에서 원인을 조사할 수 있도록 한다. 진단은 기본 OFF이며 성능 로그 기록 중에만 활성화된다.

## 1. 변경 파일 목록

| 파일 | 변경 내용 |
|---|---|
| [AndroidSystemMetricSource.kt](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/metrics/AndroidSystemMetricSource.kt) | 신규. API level별 Thermal 조회·리스너, 프로세스 CPU 시간, 비루팅 read-only CPU 정책 주파수 |
| [SystemPerformanceDiagnostics.kt](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/metrics/SystemPerformanceDiagnostics.kt) | 신규. 독립 수집 스레드, CPU 사용률 계산, headroom rate limit, 안전한 종료 및 늦은 콜백 차단 |
| [PerformanceIntervalCollector.kt](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/metrics/PerformanceIntervalCollector.kt) | 신규. 완료 시각 기준 30초 구간·전체 누적 exact 평균/P95/FPS |
| [PerformanceLogSession.kt](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/metrics/PerformanceLogSession.kt) | 신규. 프레임 통계·시스템 진단·기존 순차 writer 연결, 종료 drain |
| [PerformanceLogRecorder.kt](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/metrics/PerformanceLogRecorder.kt) | 기존 MediaStore 경로·시작/종료 기능에 세션 연결 |
| [PerformanceLogFormatter.kt](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/metrics/PerformanceLogFormatter.kt) | SYSTEM/THERMAL_STATUS/구간·누적 요약과 지표 범위·N/A 설명 추가 |
| [MainActivity.kt](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/MainActivity.kt:874) | 성능 로그 header에 새 설정 전달만 추가 |
| [AppSettings.kt](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/settings/AppSettings.kt) | 독립 PerformanceLogSettings, 기본 OFF/5000ms, 범위 검증 |
| [AppSettingsStore.kt](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/settings/AppSettingsStore.kt) | 새 설정 저장/복원. 이전 저장 설정의 누락 값은 OFF/5000ms로 유지 |
| [SettingsActivity.kt](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/settings/SettingsActivity.kt) | Performance Log 설정 영역의 시스템 진단 toggle 및 주기 입력 |
| [PerformanceIntervalCollectorTest.kt](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/test/java/com/samin/objectdetection/metrics/PerformanceIntervalCollectorTest.kt) | 신규. 경계, 부분/빈 구간, 실제 표본 P95, 실패 제외, 지연 제출 |
| [SystemPerformanceDiagnosticsTest.kt](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/test/java/com/samin/objectdetection/metrics/SystemPerformanceDiagnosticsTest.kt) | 신규. 미지원/권한 제한/NaN, CPU 계산, headroom 제한, listener·종료 |
| [PerformanceLogSessionTest.kt](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/test/java/com/samin/objectdetection/metrics/PerformanceLogSessionTest.kt) | 신규. ON/OFF, frame/overlay 연결, 큐 포화 시 통계 보존, IO 실패·drain |
| [AppSettingsTest.kt](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/test/java/com/samin/objectdetection/settings/AppSettingsTest.kt) | 기존 설정 호환, 새 설정 round-trip 및 detector 설정 독립성 |
| [SystemPerformanceDiagnosticsInstrumentedTest.kt](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/androidTest/java/com/samin/objectdetection/metrics/SystemPerformanceDiagnosticsInstrumentedTest.kt) | 신규. 실제 API 수집·종료, YOLO ON/OFF 비교, MediaStore 저장 내용·종료 확인 |
| [실기기 비교 결과](/D:/develop/Projects/TestProjects/ObjectDetection/docs/validation/system-diagnostics-device-20261013.txt) | SM-S921N에서 생성한 검증 결과 보존 |

이 문서도 추가했다. 이전 성능 분석 보고서는 보존했다. CameraController, ImageProxyExt, detector/Letterbox/tensor writer/NMS/클래스 정책, 영상 녹화·캡처 구현, OrderedLogWriter 및 AndroidManifest는 수정하지 않았다. 추가 권한·의존성도 없다.

## 2. 구현 내용

### 수집과 정확한 시간 구간

- System Performance Diagnostics 기본값은 OFF, Sample Interval은 5000ms다. 설정 범위는 1000~60000ms이며 저장·적용하면 다음 성능 로그에 반영된다. 기존 성능 로그 시작/종료 버튼과 상세 Debug 설정은 유지한다.
- ON일 때 시작 직후 한 번 수집하고 이후 별도 `PerformanceDiagnostics` 스레드에서 지정 간격의 fixed-delay로 수집한다. 조회 시간만큼 다음 수집이 늦어질 수 있으므로 실제 sample 시각과 CPU window 길이를 기록한다. 매 프레임 시스템 조회를 하지 않는다.
- OFF일 때 Thermal 리스너·PowerManager·CPU API·sysfs 조회는 수행하지 않는다. 성능 로그의 프레임 통계는 계속 계산하고, 30초 구간 preview를 위한 가벼운 timer만 실행한다.
- Thermal 상태 초기 관측과 변경에 wall/elapsed 시각 및 이전/새 상태를 기록한다. listener 시각은 콜백 관측 시각이며 센서가 실제로 변경된 순간의 하드웨어 timestamp가 아니다. 리스너 등록 실패 시 주기 polling으로 확인한다.
- Headroom은 forecastSeconds=0으로 읽고 최소 10초 간격을 적용한다. 설정 주기를 1초로 줄여도 headroom 호출은 늘리지 않는다. 빠른 성능 로그 재시작·Activity 재생성에도 프로세스 공통 제한을 적용한다. 캐시 값의 측정 시각·age를 함께 기록하고 NaN/예외/미지원/호출 제한에서는 N/A를 사용한다.
- 프레임에는 기존 trace와 함께 `elapsedSeconds`가 추가된다. `sessionId/frameId`, FRAME/OVERLAY 연결과 기존 값은 보존한다.
- 프레임 통계는 header의 `startedElapsedNs`를 기준으로 성공 완료 시각이 속한 `[0,30)`, `[30,60)` 등의 구간에 배정한다. 마지막 부분 구간과 빈 구간도 종료 요약에 포함한다. 실패 프레임은 성공 FPS·latency 통계에 포함하지 않는다.
- 평균과 nearest-rank P95는 각 구간의 실제 유효 프레임 값으로 계산한다. unknown인 -1은 0ms로 바꾸지 않는다. 전체 누적 P95도 전체 분포로 계산하며 구간별 평균/P95의 평균을 사용하지 않는다.
- FPS는 해당 구간 성공 수/실제 구간 경과시간이다. 마지막 구간은 실제 짧은 길이를 사용한다. 빈 구간의 latency는 N/A, 양의 경과시간에서 성공 0건의 FPS는 0이다.

### 로그 레코드의 의미

| 레코드 | 범위/해석 |
|---|---|
| 기존 FRAME/OVERLAY | 기존 프레임·표시 연결. FRAME의 elapsedSeconds는 성공 완료 시각 기준 |
| SYSTEM | 기본 약 5초마다 시스템 snapshot. `elapsedSeconds`, thermalStatus, thermalHeadroom, processCpuUsage, cpuFrequency, 수집 시간 및 age |
| THERMAL_STATUS | 초기 관측/상태 변화 시각. listener 또는 polling 출처 |
| INTERVAL_PREVIEW | 완료된 30초 구간을 실행 중 기록. timer가 늦으면 늦게 출력될 수 있음. 지연 제출 프레임은 이후 최종 요약에 반영 |
| 기존 FINAL SUMMARY | 기존 metrics collector의 누적 요약과 최대 100000개 latency 표본 정책 유지 |
| CUMULATIVE_SUMMARY | recorder에 제출된 전체 실제 성공 프레임의 exact 누적 통계 |
| INTERVAL_SUMMARY | 종료 시 모든 30초 구간과 마지막 부분/빈 구간의 authoritative 통계 |

구간·누적 요약에는 `inferenceAvgMs`, `inferenceP95Ms`, `preprocessAvgMs`, `pipelineAvgMs`, `pipelineP95Ms`, `inferenceFps`와 각 지표 표본 수가 들어간다. 해당 구간의 마지막 시스템 snapshot도 첨부한다. `systemSampleElapsedSeconds`로 snapshot 시각을 명시하며, 시스템 값은 구간 평균·최댓값을 의미하지 않는다. 시스템 진단 OFF 또는 그 구간에 수집된 표본이 없으면 N/A다.

프레임 통계는 writer enqueue 전에 작은 histogram 갱신으로 저장하므로 큐 포화로 FRAME 행이 생략돼도 통계의 실제 프레임 수는 유지한다. dropped count도 계속 기록한다. 구간 histogram 복사 이외의 정렬·P95·formatting·파일 I/O는 worker에서 실행한다. histogram 메모리는 시간 구간 수와 서로 다른 ms 값 수에 따라 증가한다.

종료 시 새 진단 이벤트를 차단하고 진행 중 수집 종료·리스너 해제를 기다린 다음 기존 OrderedLogWriter가 승인한 작업을 drain한다. 기존 행별 flush와 최종 footer flush 정책은 변경하지 않았다. IO 실패는 기존 failure callback에 전달하고 수집기도 종료한다. 파일을 쓰지 못한 경우 footer 저장 자체를 보장할 수는 없지만, 실패가 CameraX/Interpreter에 전파되는 구조는 없다.

## 3. Android API 호환성 및 수집 제한

| 지표 | API/범위 | 제한 및 N/A 처리 |
|---|---|---|
| Thermal Status/리스너 | API 29+ PowerManager | 낮은 API에서는 조회/등록하지 않음. service unavailable, 예외, unknown status는 N/A |
| Thermal Headroom | API 30+ PowerManager | 지원되지 않으면 NaN일 수 있음. non-finite/negative/예외/호출 제한은 N/A. 1.0은 SEVERE 경계이며 온도가 아님 |
| Process CPU | API 1+ `Process.getElapsedCpuTime()` | 프로세스 누적 CPU ms 차이/단조 경과시간 ×100. 첫 표본·counter reset·읽기 실패는 N/A |
| CPU Frequency | read-only `/sys/devices/system/cpu/cpufreq/policy*` 또는 `cpu*/cpufreq` | 비공식 device/driver sysfs이며 접근 보장 없음. root·shell 권한을 사용하지 않음. 권한 거부 속성은 해당 세션에서 재조회하지 않음 |

현재 앱 minSdk는 33이므로 설치 가능한 모든 Android 버전에서 Thermal API의 level 조건은 만족하지만 vendor 지원·권한·서비스 상태는 별개다. lower API guard는 구현했으며, API 28/29 기기에 이 minSdk33 APK를 설치해 검증한 것은 아니다. 미지원·권한 거부·NaN·등록 실패 환경은 단위 테스트로 모의 검증했다.

`processCpuUsage`는 **앱 프로세스의 전체 thread CPU 합, 코어 1개 100% 기준**이다. 여러 코어를 쓰면 100%를 초과할 수 있다. 시스템 전체 점유율이나 YOLO thread만의 점유율이 아니다. 프로세스 내 전처리·UI·GC·로그 worker 등도 포함한다.

주파수는 **기기의 CPU 정책/cluster snapshot**이다. 현재 앱이 어느 코어에서 실행됐는지나 그 순간의 실제 effective clock을 증명하지 않는다. `scaling_cur_freq`를 우선하고 읽을 수 있으면 `cpuinfo_cur_freq`로 대체하며 출처를 기록한다. `scaling_max_freq`, `cpuinfo_max_freq`, related CPUs, governor도 함께 기록한다. 현재 주파수만 읽히지 않으면 `currentKHz=N/A`로 표시하고 읽힌 관련 상태는 보존한다. 최대 주파수를 현재 주파수로 대체하지 않는다. 모든 정보가 막히면 `cpuFrequency=N/A`다.

Thermal Status NONE은 OS가 해당 상태를 보고했다는 뜻이다. 개별 CPU 정책의 DVFS, 스케줄링, 모델 연산 병렬도 또는 vendor-specific 제한이 없다는 증거는 아니다. Headroom과 단순 주파수 snapshot의 상관관계만으로 발열 원인을 확정하지 않는다. [PowerManager 공식 API](https://developer.android.com/reference/android/os/PowerManager), [Process CPU 시간 공식 API](https://developer.android.com/reference/android/os/Process#getElapsedCpuTime()).

## 4. 성능 오버헤드 및 검증 결과

### 제공된 필드 로그 재집계

모델 SHA는 현재 asset과 동일했다. 로그에는 Detailed Debug/PNG/ML Kit OFF, CPU 4, Letterbox, 실제 분석 크기 1080×1440이 기록돼 있다. 성공 499개, 실패/skip/dropped log 0이다. 전체 요약은 추론 평균490/P95 566ms, 전처리83ms, Pipeline590/P95 684ms, 성공 FPS1.6106, Overlay711/P95 806ms다.

완료 monotonic timestamp로 직접 30초 구간을 재집계한 결과:

| 구간(초) | 프레임 | 추론 평균/P95(ms) | 전처리 평균(ms) | Pipeline 평균/P95(ms) |
|---|---:|---:|---:|---:|
| 0–30 | 64 | 362.25 / 388 | 63.05 | 437.86 / 471 |
| 30–60 | 57 | 413.79 / 461 | 71.47 | 499.14 / 553 |
| 60–90 | 52 | 454.62 / 468 | 77.35 | 547.17 / 566 |
| 90–120 | 49 | 481.53 / 503 | 82.57 | 579.86 / 601 |
| 120–150 | 45 | 532.31 / 548 | 89.56 | 639.33 / 657 |
| 150–180 | 41 | 579.02 / 724 | 97.05 | 693.88 / 861 |
| 180–210 | 44 | 539.89 / 546 | 90.98 | 649.11 / 658 |
| 210–240 | 44 | 539.27 / 543 | 90.30 | 647.36 / 651 |
| 240–270 | 45 | 539.16 / 541 | 90.24 | 646.89 / 649 |
| 270–300 | 43 | 545.40 / 572 | 92.79 | 655.84 / 691 |
| 300–종료 | 15 | 537.13 / 546 | 90.53 | 645.27 / 654 |

전처리와 추론이 함께 늘고 150–180초에는 추가 spike도 있다. CPU 성능 변화와 일치할 가능성은 있지만 이 파일에 Thermal/주파수/CPU 표본이 없어 발열·클럭 제한·스케줄링·GC 등 원인 간 구분은 불가능하다.

### 빌드와 회귀

- 기존 77개를 포함한 **단위 테스트 96개 통과**, failure/error/skipped 0.
- `assembleDebug`, `assembleDebugAndroidTest`, `compileReleaseKotlin`, `lintDebug` 성공. Lint error 0, warning 171개(기존 UI 문자열 등의 warning 포함).
- 연결된 **SM-S921N, API 36에서 Android 테스트 8개 전부 통과**. 기존 모델 RGB/NCHW, portrait Letterbox padding, 9클래스 출력, positive fixture, 실패 후 회복 및 녹화 로그 handshake 회귀도 포함한다.
- 모델 SHA-256은 `32a44bc7432ef2d7ca5de802dd8e714a26568aea16566bad3fd7fcd2b85bcee2`로 유지했다.
- 큐 포화 중에도 실제 프레임 통계 보존, IO 실패 callback/종료, close 재호출, listener 해제, 늦은 콜백, 등록 실패 polling, 권한 거부 cache, NaN 및 첫 CPU 표본 N/A를 테스트했다.

### 실제 시스템 수집과 ON/OFF 비교

실제 앱 UID에서 Thermal Status/Headroom과 policy0/4/7/9의 주파수·최대 주파수·governor를 읽을 수 있었다. 별도 0/5/10초 수집 테스트에서 3회 조회 소요시간은 **18.69 / 11.95 / 16.57ms**였다. 이 시간은 전용 worker의 실제 조회 경과시간이며 Analyzer pause 시간이나 CPU 사용 시간으로 해석하면 안 된다.

실제 YOLO/640/CPU4를 유지하고 고정 이미지를 1080×1440 Bitmap으로 만들어 5회 워밍업 후 OFF/ON 각15프레임씩 3회 순서를 교차했다. 성능 로그 파일 저장/flush/종료 내용도 실제 MediaStore에서 확인했다. 테스트가 만든 MediaStore 로그만 확인 후 삭제했으며 사용자 기존 로그/설정은 유지했다.

| Trial | OFF 추론 평균/P95(ms) | ON 추론 평균/P95(ms) | OFF 완료 FPS | ON 완료 FPS |
|---|---:|---:|---:|---:|
| 0 | 549.00 / 551 | 550.73 / 554 | 1.590 | 1.604 |
| 1 | 556.00 / 573 | 550.73 / 553 | 1.592 | 1.604 |
| 2 | 556.80 / 574 | 550.67 / 555 | 1.591 | 1.604 |

이 비교의 FPS는 **CameraX/UI/영상 녹화를 제외한 고정 이미지 detector 완료 처리량**이다. ON이 빨라졌다는 결론이나 “오버헤드 0”을 주장하지 않는다. 짧은 구간·clock/thermal 변동·초기화 시간 차이가 포함되어 있으며 30분 이상 live A/B 시험은 별도로 필요하다. 이번 범위에서는 큰 악화가 관찰되지 않았다는 수준이다.

ON 비교에서 OS 상태는 NONE, headroom은 일부0.58~0.68, 프로세스 CPU는 약99.5%가 관측됐다. 일부 짧은 재시작 구간의 headroom은 프로세스 공통10초 제한 때문에 N/A였다. CPU4 요청에도 process CPU가 이 수준이었다는 사실은 후속 병렬도 profiling에 유용하지만, thread 설정이 무시됐거나 특정 연산이 원인이라고 확정할 수는 없다.

실제 CameraX live 30분 A/B, 물리 화면 지연 재측정, OS MediaProjection 승인·H264 영상 실제 녹화/캡처 수동 검증은 이번 자동 테스트에서 수행하지 않았다. 해당 구현 파일은 변경하지 않았고 기존 녹화 로그 회귀는 통과했다.

## 5. 앱에서 테스트하는 방법

1. 최신 debug APK는 [app-debug.apk](/D:/develop/Projects/TestProjects/ObjectDetection/app/build/outputs/apk/debug/app-debug.apk)이며 연결 단말에 데이터 보존 방식으로 설치했다. 설정의 Performance Log 영역에서 System Performance Diagnostics를 OFF, Sample Interval을5000ms로 두고 적용·저장한다.
2. 모델/CPU4/640/Letterbox/카메라 해상도/후처리 설정을 고정한다. Detailed Debug, PNG, legacy Debug Image, ML Kit, 영상 녹화는 이번 기준선에서 OFF로 둔다. 이 설정들은 시스템 진단 toggle과 독립적이다.
3. 같은 장면·경로, 비슷한 시작 온도·배터리·전원 연결·밝기 조건에서 Performance Log를 시작하고 최소10분, 장시간 확인에는30분 이상 기록한다. 종료 버튼으로 정상 저장한다.
4. System Performance Diagnostics만 ON으로 적용한 뒤 동일 조건에서 반복한다. 순서를 교차하고 가능하면3회씩 실행한다. 종료/빠른 재시작/설정 재적용/Activity 종료도 확인한다.
5. 기존 `Documents/GOTORO/performance/perf_*.txt`에서 SYSTEM/THERMAL_STATUS가 ON에만 있는지, OFF는 시스템 값이N/A인지 확인한다. footer의 CUMULATIVE_SUMMARY와 모든 INTERVAL_SUMMARY, 마지막 부분 구간과 dropped count를 확인한다.
6. `(sessionId, elapsedSeconds)`로 추론/Pipeline 상승과 Thermal 상태 변화, headroom 상승, `scalingMaxKHz` 변화, process CPU 사용률을 비교한다. FRAME/OVERLAY는 기존 `(sessionId, frameId)`로 연결한다. CPU 사용률 하락·주파수 하락만으로 원인을 단정하지 않는다.
7. `collectionDurationMs`, latency P95, 성공 FPS 및 실제 Overlay 지연을 ON/OFF로 비교해 수집 비용을 확인한다. 로그 저장 실패 시 안내만 발생하고 검출이 계속되는지 확인한다. 실패한 로그에서는 정상 footer 저장을 기대하지 않는다.

로컬 검증 명령:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest compileReleaseKotlin lintDebug --console=plain
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.samin.objectdetection.test/androidx.test.runner.AndroidJUnitRunner
```

## 6. 다음 GPU 가속 실험을 위한 권장 작업

1. 먼저 새 진단 ON으로 CPU4/640의 장시간 기준선을 확보한다. 냉간→정상 상태→열 안정화 단계, 단말/충전/카메라 및 녹화 조건을 기록한다.
2. process CPU 사용률이 실제 모델 병렬도를 반영하는지 Perfetto/Android profiler와 동일 버전 model benchmark로 확인한다. XNNPACK 위임 coverage·worker threads·CONV/BATCH_MATMUL/TRANSPOSE 등 op별 시간을 측정한다. 이번 구현에서는 threads를 변경하지 않았다.
3. 현재 LiteRT2.1.1의 Interpreter는 CPU 경로다. GPU 실험은 해당 버전의 CompiledModel/TensorBuffer 또는 검증된 별도 Interpreter GPU 런타임 조합으로 분리해 준비한다. 기존 Interpreter 옵션에 예전 GpuDelegate만 추가해 충분하다고 가정하지 않는다. [LiteRT v2.1.0+ Interpreter CPU-only 안내](https://github.com/google-ai-edge/LiteRT/releases/tag/v2.1.0).
4. 동일 모델·입력·후처리로 CPU와 GPU를 비교하고 graph 지원/partition/fallback, 입출력 transfer, 최초 compile/warmup, 장시간 thermal 및 실제 overlay 지연을 확인한다. GPU/NPU에서는 process CPU 사용률만으로 전체 accelerator 사용률을 해석할 수 없다.
5. 이번 System/구간 로그 구조를 동일하게 재사용하되 backend/모델 변경을 별도 header에 명시한다. Golden RGB/tensor/Letterbox/NMS/클래스 회귀를 통과한 뒤 정확도와 sustained 성능을 함께 평가한다.
