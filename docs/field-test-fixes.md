# 필드 테스트 전 수정 보고서

분석 기준: 원격 main과 동일한 `451010d`. 수정은 작업 트리에 적용했으며 모델 파일, 클래스 매핑, 텐서 작성, Letterbox 및 NMS 알고리즘은 유지했다.

## 1. 수정 파일과 변경 내용

Kotlin 파일은 `app/src/main/java/com/samin/objectdetection/` 아래에 있다.

| 파일 | 변경 |
|---|---|
| [MainActivity.kt](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/MainActivity.kt) | 성공/실패 프레임 처리, UI와 독립된 성능 기록, 녹화 응답 처리, 로그 drain 및 비동기 종료 연결 |
| [CameraController.kt](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/camera/CameraController.kt), [ImageProxyExt.kt](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/camera/ImageProxyExt.kt), 신규 [AnalyzerShutdown.kt](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/camera/AnalyzerShutdown.kt) | Frame ID, 단조 시계 측정, 카메라 timestamp 기준 확인, 변환 오류 원인 전달, analyzer 완료 future |
| [VisionStyleYoloDetector.kt](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/detector/VisionStyleYoloDetector.kt), [DetectionDiagnostics.kt](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/detector/DetectionDiagnostics.kt), 신규 [InferenceException.kt](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/detector/InferenceException.kt) | 실제 Interpreter.run 시작 시각 기록, 실패를 빈 결과로 숨기지 않고 단계·원인 전달 |
| [DetectionDebugRecorder.kt](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/detector/DetectionDebugRecorder.kt), [DetectionPipeline.kt](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/pipeline/DetectionPipeline.kt) | 카메라 Frame ID와 Session ID 연계, 추론 시작 시각 전달 |
| 신규 [FrameTrace.kt](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/metrics/FrameTrace.kt), [DetectionMetricsCollector.kt](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/metrics/DetectionMetricsCollector.kt) | 수신/성공 FPS 분리, 성공·실패 개수, 실제 경과시간, 독립된 overlay 지연 표본 |
| [PerformanceLogRecorder.kt](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/metrics/PerformanceLogRecorder.kt), [PerformanceLogFormatter.kt](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/metrics/PerformanceLogFormatter.kt), 신규 [OrderedLogWriter.kt](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/logging/OrderedLogWriter.kt) | 제한된 순차 큐, 행별 flush, 종료 drain, IO 실패 통지, 신규 로그 필드 |
| [EvaluationDataRecorder.kt](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/evaluation/EvaluationDataRecorder.kt), [DetectionFrameSnapshot.kt](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/evaluation/DetectionFrameSnapshot.kt), 신규 [FrameTraceJson.kt](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/evaluation/FrameTraceJson.kt) | 탐지 JSON 기존 필드 유지, 녹화 시작 handshake 버퍼, 독립된 timeline 및 오류 JSONL |
| [ScreenRecordService.kt](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/evaluation/ScreenRecordService.kt), 신규 [RecordingState.kt](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/evaluation/RecordingState.kt) | 실제 시작/MP4 종료 응답, 세션 검증, MediaRecorder 오류, 갤러리 복사 오류, 영상 시간 기준 sidecar |
| [BoundingBoxOverlay.kt](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/ui/BoundingBoxOverlay.kt) | 성공 FPS·Analyzer FPS·Frame ID 표시, 실제 draw 이벤트, 프레임 중단 시 FPS 갱신 |
| [WarningCandidateSelector.kt](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/warning/WarningCandidateSelector.kt) | 클래스 confidence 정책 준수 후 위험도 순 정렬 및 개수 제한. Crowd도 경고 정책 통과 객체로 계산 |
| [LogShareManager.kt](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/settings/LogShareManager.kt) | 최근 영구 오류 로그도 공유 ZIP에 포함 |

테스트 파일: 신규 `FrameRateMeterTest`, `OrderedLogWriterTest`, `RecordingStateTest`, `FrameTraceJsonTest`, `AnalyzerShutdownTest`; 기존 `DetectionMetricsCollectorPerformanceTest`, `PerformanceLogFormatterTest`, `WarningCandidateSelectorCrowdTest` 보강. Android 테스트는 기존 `DetectorGoldenTest`의 실패 후 회복 검사와 신규 `RecordingLogTest`를 추가했다.

## 2. 문제 해결 여부와 로그 계약

| 문제 | 결과 |
|---|---|
| Analyzer FPS와 성공 추론 FPS 혼동 | 분리. 이벤트 개수를 실제 단조 시계 경과시간으로 나누고 화면에서도 구분한다. 정상 빈 탐지는 성공에 포함한다. |
| UI 교체로 성능 표본 누락 | 성능 표본은 analyzer에서 수집한다. 표시되지 않은 성공 프레임도 포함하고 draw 지연은 별도 기록한다. |
| 추론 시작 지연 부정확 | Interpreter.run 직전의 wall/elapsed 시각을 기록한다. 카메라 clock이 REALTIME임을 확인할 수 없으면 캡처 지연은 -1이다. |
| 추론 오류가 미탐지로 저장됨 | ERROR 상태, 오류 단계 및 stack trace를 영구 오류 JSONL에 기록하고 측정/녹화 로그에도 연계한다. 실패한 pipeline은 성공 FPS·추론 평균/P95 표본에 들어가지 않는다. |
| 녹화 시작/저장 성공 조기 표시 | STARTED를 수신해야 녹화 중으로 전환한다. STOPPED/ERROR와 탐지·timeline drain 완료 후 종료 상태와 결과를 표시한다. |
| 영상·탐지 로그 시간 비교 | Session/Frame ID, recorder 시작 기준, FRAME 완료 시각 및 OVERLAY_DRAW 시각으로 대응할 수 있다. |
| 로그 순서/종료 데이터 손실 | 단일 analyzer가 제출한 FRAME 행은 순차 writer가 처리한다. 큐는 writer별 512개로 제한하며 정상 종료는 승인된 작업을 drain한다. 초과 누락은 timeline/성능 최종 요약에 기록한다. |
| 로그 IO 실패·앱 종료 | 오류를 UI/Logcat에 전달한다. writer 실패 시 close가 예외로 완료되어 종료 대기가 무한 send에 걸리지 않는다. analyzer 완료 대기는 백그라운드에서 수행하고 완료 후 detector를 해제한다. |
| 잘못된 경고 후보 | 클래스 confidence를 먼저 적용하고 위험도·우선순위·거리·위치·confidence 순으로 정렬한 뒤 개수를 제한한다. 표시용 객체 목록은 유지한다. |

### 신규 필드의 의미

| 필드 | 의미 / 시간 기준 |
|---|---|
| `sessionId` | MainActivity 실행별 UUID. 설정 적용으로 재생성되면 새 UUID |
| `frameId` | 해당 실행의 analyzer 수신 순번. throttle로 건너뛴 번호가 있을 수 있으며 debug·성능·탐지·overlay에서 공통 사용 |
| `recordingSessionId` | 녹화 요청별 UUID. 늦게 도착한 이전 세션 응답을 거부 |
| `status`, `errorStage`, `error` | FRAME의 SUCCESS/ERROR, 실패 단계, 원인. 성공 빈 탐지는 SUCCESS + 빈 detections |
| `analyzerFps`, `inferenceFps` | 최근 약 1초 창의 실제 경과시간 기준 수신/성공 pipeline 완료 FPS. 최종 요약은 세션 전체 완료 수/경과시간 |
| `fps` / `Average FPS` | 기존 정수 필드 유지. 이제 성공 pipeline FPS를 뜻하며 정밀 값은 inferenceFps 사용 |
| `frameTimestampMs`, `timestampMs` | 기존 캡처 wall ms 필드 유지. 캡처 clock 비교 불가 시 analyzer 수신 wall ms로 대체하고 reliable=false 표시 |
| `cameraTimestampNs`, `captureTimestampReliable` | 센서 원본 ns, elapsedRealtimeNanos와 비교 가능한 REALTIME clock 여부 |
| `analyzerReceivedMs`, `inferenceStartedAtMs`, `completedAtMs` | Unix wall milliseconds. completedAtMs는 pipeline 완료 시각 |
| `*ElapsedNs`, `drawElapsedNs` | Android elapsedRealtimeNanos. 시간차 계산은 이 값으로 수행 |
| `analyzerToInferenceMs` | analyzer 수신부터 실제 Interpreter.run 시작까지의 지연 |
| `frameAgeAtInferenceMs` | 검증 가능한 캡처부터 실제 run까지 지연. 비교 불가이면 -1 |
| `videoElapsedMs` | recorder.start 반환 직후 anchor부터 FRAME 완료 또는 OVERLAY_DRAW까지의 단조 시계 ms |

성공 FRAME의 기존 bbox·metadata·frameSummary·탐지 개수 필드를 유지했다. `_detections.jsonl`에는 FRAME만 저장하고 녹화 시작/종료·draw 이벤트는 별도 `_timeline.jsonl`로 분리했다. 분석 시 `status=ERROR`는 정상 미탐지와 성능 표본에서 제외한다. 구버전의 status 없는 FRAME은 기존 정상 레코드로 취급할 수 있다.

성능 로그의 기존 `FRAME ... time=`은 UI 갱신 시각에서 pipeline 완료 시각으로 변경했다. `frameAgeOverlay=-1`인 FRAME에는 아직 draw가 없으며, 별도 OVERLAY 행을 `(sessionId, frameId)`로 연결한다. 최종 overlay 지연 통계는 실제 표시된 성공 프레임만 사용한다. Dropped Frames는 앱의 명시적 skip이며 CameraX 내부 폐기 프레임 수를 뜻하지 않는다. 메모리를 제한하기 위해 평균/P95는 최대 최근 100,000개 표본을 사용한다.

Processed Frames는 pipeline 처리 시도 수, Failed Frames는 변환 오류를 포함한 프레임 실패 수다. 변환 실패는 explicit skip에도 집계되므로 두 지표는 일부 겹친다. Overlay Latency Samples가 0이면 해당 평균/P95는 관측값이 없는 상태다.

영상 옆의 `_screen_session.json`은 실제 녹화 시작 전/후 및 종료 시각과 상태를 저장한다. `videoElapsedMs`는 인코딩된 첫 프레임 PTS 자체는 아니므로 작은 인코더 시작 오차가 있을 수 있다. 영상의 `F=<frameId>` 표시와 `_timeline.jsonl`을 함께 사용해 프레임을 대응한다.

## 3. 빌드 및 테스트

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat testDebugUnitTest assembleDebug compileDebugAndroidTestKotlin lintDebug --console=plain
```

- 단위 테스트: 기존 63개 + 신규 14개 = 77개, 실패/오류/skip 0.
- debug APK 생성, Android 테스트 코드 컴파일, lintDebug 완료. Lint 오류 0, 경고 165개이며 UI 문자열·기존 설정/위젯 관련 경고 등을 별도 HTML/XML에 남긴다.
- Android instrumentation, MediaProjection 및 실제 야외/장시간 실행은 아직 수행하지 않았다.
- 모델 asset·라벨·텐서 작성·Letterbox 파일 변경 없음. detector의 NMS 및 bbox 계산 본문도 유지했다.

결과물: [debug APK](/D:/develop/Projects/TestProjects/ObjectDetection/app/build/outputs/apk/debug/app-debug.apk), [단위 테스트 결과](/D:/develop/Projects/TestProjects/ObjectDetection/app/build/reports/tests/testDebugUnitTest/index.html), [lint 보고서](/D:/develop/Projects/TestProjects/ObjectDetection/app/build/reports/lint-results-debug.html).

## 4. 실제 단말 확인

1. 9클래스 라벨 및 기존 bbox 위치를 확인하고, 모델 SHA256 `32a44bc7432ef2d7ca5de802dd8e714a26568aea16566bad3fd7fcd2b85bcee2`를 확인한다.
2. confidence 0.3의 차량은 표시될 수 있으나 경고하지 않고, 뒤 순번의 고위험 객체가 후보 제한 때문에 빠지지 않는지 확인한다.
3. 녹화 시작/즉시 중지/반복 세션/OS 녹화 중지를 시험한다. MP4 재생, JSONL Frame ID 순서, timeline 및 sidecar 상태를 확인한다.
4. 성공 빈 탐지와 의도적으로 유발한 실패를 구분하고, 오류 후 다음 프레임이 회복되는지 확인한다. connectedDebugAndroidTest의 추가된 두 Android 테스트도 실행한다.
5. 설정 재적용·앱 종료 중 로그/녹화를 검사한다. 저장 실패, 갤러리 복사 실패에서는 실패 안내와 남은 앱 전용 파일을 확인한다.
6. 30분 이상 실행해 FPS, 추론 P95, 발열, 메모리, droppedLogLines를 비교한다. 녹화 ON/OFF를 따로 측정한다.

정상 Activity 종료는 비동기 drain과 MP4 종료 응답을 기다린다. 프로세스 강제 종료/전원 차단에서는 콜백과 MP4 최종화를 보장할 수 없으며, 이미 flush된 로그를 보존하는 범위다.

## 5. 필드 테스트 권장 설정

- LETTERBOX / Full Frame / Center ROI OFF, confidence 0.20, NMS 0.45, CPU threads 4.
- Size DISABLED, Geometry OFF, Temporal OFF, ML Kit OFF.
- SIMPLE overlay + Confidence/FPS 표시 ON. `FPS`는 성공 pipeline, `A`는 Analyzer, `F`는 Frame ID다.
- 모델 정확성 확인 구간에서는 상세 debug·입력 PNG와 녹화를 짧게 켠다.
- 장시간 성능 측정은 상세 debug·PNG OFF, 성능 로그 ON. 내장 FIELD TEST 프리셋은 상세 진단이 켜지므로 이 항목을 별도 변경한다.
- Max Candidates는 기존 100을 유지했다. 다객체 장면에서는 100과 상향 설정을 별도 비교해 기존 후보 제한에 의한 누락 여부를 확인한다.
