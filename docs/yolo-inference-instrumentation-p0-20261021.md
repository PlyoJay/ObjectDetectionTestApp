# P0 — 성능 로그 오류 수정 및 프레임·추론·경고 지연 계측

작성일: 2026-10-21, Asia/Seoul. 기준 HEAD: `53560e44e2b01c6d3bee88b44147e90a7dc2261d`.
참고: [YOLO 추론 최적화 분석](yolo-inference-optimization-analysis-20261020.md).

**회전 시간/각도의 로그 키 충돌을 수정하고, CameraX 수신부터 추론·callback/close·ML Kit·경고 출력까지 같은 `sessionId`, `frameId`, `elapsedRealtimeNanos`로 연결했다.** 기존 성공 프레임 수·FPS·pipeline/totalProcessing 통계는 유지하고, 비동기 관측은 별도 `LATENCY` 행으로 기록한다. 추론 속도 최적화나 새로운 출력/최신성 정책은 적용하지 않았다.

512×512/2 Threads 기준의 사용자 설정을 변경하지 않았다. 기본 설정·저장 설정·모델·labels·ROI·Letterbox·NMS·ML Kit 입력/인식 주기·경고 판단/cooldown·beep 패턴·TTS 메시지/발화율/QUEUE_FLUSH·진동 패턴·추론 백엔드는 유지했다. 기기에 APK를 설치하거나 설정을 적용하지 않았다. 기존 분석 문서와 성능 로그/validation 자료를 보존했고 커밋·푸시하지 않았다.

## 1. 기존 오류와 수정 방식

과거 FRAME 행에는 다음 두 값이 공존했다.

```text
... rotation=8.958ms ... frame=1080x1440 rotation=90 roi=[108,288,972,1440] ...
```

앞은 회전 처리 시간, 뒤는 이미지 각도다. `tools/analyze_performance_logs.py::fields()`가 정규식 결과를 dict로 변환하면서 앞의 시간을 뒤의 각도로 덮어썼고, 시간 통계에 **90ms**가 들어갔다.

새 `PerformanceLogFormatter.formatFrame()`은 아래처럼 이름과 단위를 분리한다. `MainActivity.processBitmap()`의 `roiDescription`에서는 각도를 제거하고 명시적인 `PerformanceFrameRecord.rotationDegrees`로 전달한다.

```text
... cameraToBitmap=1.796ms | rotationMs=10.240 | rotationDegrees=90 ...
... frame=1080x1440 roi=[108,288,972,1440] ...
```

- `rotationMs`: ms 단위 소수 세 자리. 이름에 단위가 있으므로 값에는 `ms`를 붙이지 않는다. 미측정 `-1.000`, 회전 생략 `0.000`은 구분한다.
- `rotationDegrees`: 각도 정수. 미측정 `N/A`.
- 수정 분석기는 dict로 만들기 **전 원문 key/value 쌍**에서 과거 `rotation=...ms`와 단위 없는 각도를 각각 회수한다. 과거 키의 등장 순서가 바뀌어도 읽는다.
- 새 명시적 키가 있으면 우선한다. 각도만 있는 과거 로그에서 각도를 시간으로, 시간만 있는 로그에서 시간을 각도로 간주하지 않는다. `N/A`, `null`, 음수, NaN은 유효 시간 표본에 넣지 않는다.
- 분석 JSON의 기존 `details.rotation`은 **회전 시간 통계의 호환 키**로 유지한다. `measurement.observedFrameRois[].rotation`도 기존 각도 metadata 키를 유지한다. 앱의 새 원문 키는 `rotationMs/rotationDegrees`다.

호환성 보장 범위는 **수정 분석기가 과거 로그와 새 로그를 함께 읽는 것**이다. 기존 FRAME·ERROR·OVERLAY·SYSTEM·BATTERY·확정 요약 행과 통계 의미를 유지하며 새 행/필드를 추가했다. 외부 도구가 구 `rotation` 키를 직접 참조한다면 새 명시적 이름을 지원해야 한다.

## 2. 기록 구조와 세션 수명

`metrics/FrameLatency.kt`의 `FrameLatencyRecorder`는 성능 기록 중 CameraX analyzer 진입 때만 생성한다. 원본 `FrameTrace`와 **해당 `PerformanceLogSession`에 묶인 sink**를 갖고, 이 context를 ML Kit와 경고 출력에 전달한다. 이후 활성 recorder를 다시 조회해 비동기 콜백을 기록하지 않는다.

이 때문에 오래된 TTS/ML Kit 콜백이 다음 성능 로그에 섞이지 않는다. 기존 session을 닫은 뒤 도착한 이벤트는 기록하지 않으며, 로그 종료를 기다리려고 출력·인식 작업을 대기/취소시키지도 않는다. 기존 stop/drain은 이미 수락한 writer 작업만 마무리한다.

새 행의 공통 형태:

```text
LATENCY | sessionId=<원본 프레임의 세션> frameId=<원본 frameId> eventElapsedNs=<관측 시각> stage=<단계> ...
```

헤더에 `Latency Schema: 1`과 시계·중첩 범위·관측 한계를 설명한다. 이 버전은 **로그 계측 형식**의 버전이며 앱 설정 스키마는 변경하지 않았다.

새 이벤트는 성공 추론 표본이 아니므로 `successfulFrames`, FPS, 구간 P95 histogram에 추가하지 않는다. 각 이벤트의 `eventElapsedNs`가 기록 창 `[start, deadline)`에 속하고 session이 열려 있을 때만 수락한다. 이벤트의 원본 analyzer 수신도 해당 창 안이어야 context가 생성된다.

FRAME은 기존처럼 **성공 pipeline 완료 시각**으로 창을 분류한다. 따라서 기록 시작 직전에 이미 처리 중이던 첫 FRAME에는 `LATENCY` 수신/종료 이벤트가 없을 수 있고, 종료 직전에 시작한 비동기 작업은 완료 콜백이 기록 창 밖으로 나갈 수 있다. 누락된 span을 0ms/실패로 대체하지 않는다. 파일 줄 순서는 여러 스레드의 제출 순서이며 실제 시각 순서와 다를 수 있다.

## 3. 새 필드와 관측 의미

모든 새 `*ElapsedNs`는 `SystemClock.elapsedRealtimeNanos()` 기준이다. Unix ms는 기존 사람이 읽는 기록에만 유지하며 시간차 계산에는 사용하지 않는다. 카메라 timestamp는 기존 `captureTimestampReliable=true`인 경우에만 capture age 계산에 사용한다.

### FRAME / ERROR

| 필드 | 의미 |
|---|---|
| `analyzerElapsedNs` | 기존 CameraX analyzer 수신 단조 시각 |
| `inferenceStartElapsedNs` | 기존 Java TFLite Interpreter 호출 직전 |
| **`inferenceEndElapsedNs`** | Interpreter 호출이 반환하거나 Exception을 던진 직후. 실패 시에도 이미 호출한 경우 기록 |
| `completedElapsedNs` | 기존 성공 pipeline 완료/오류 관측 시각. 기존 의미 유지 |
| **`pipelineCompletedElapsedNs`** | **성공 FRAME에서만** `completedElapsedNs`의 명시적인 별칭. ERROR에서는 `-1`로 두어 오류 처리 시각과 혼동 방지 |
| **`rotationMs` / `rotationDegrees`** | 회전 처리 시간 / 이미지 회전 각도 |

미측정 optional 시각은 새 텍스트 로그에서 `-1`로 출력한다. 분석기는 과거의 `null` 표기도 읽고, 절대 완료 시각이 없으면 기존 `elapsedSeconds`가 있는 경우에만 창 배치에 사용한다. 평가 JSON의 기존 JSON null 규칙은 유지하면서 `inferenceEndedElapsedNs`, `pipelineCompletedElapsedNs`를 추가했다.

`DetectorFrameDiagnostics.inferenceEndedElapsedNs`를 detector에서 전달한다. `inferenceTimeMs`는 같은 시작/종료 ns 차이를 기존처럼 정수 ms 내림한다. `InferenceException`에도 종료 시각을 추가하되 기존 예외/복구 동작은 유지한다.

이 시작/종료는 **Java Interpreter API 경계**다. input set/output copy·JNI/스케줄링 영향을 포함할 수 있으며 C++ kernel의 절대 시작/종료가 아니다. 기존 `nativeInvoke`는 소요시간 정보이며 이 절대 시각과 합산하지 않는다.

### `stage=CAMERA_RECEIVED`

| 필드 | 의미 |
|---|---|
| `analyzerReceivedElapsedNs` | analyzer에 프레임이 전달된 시각. `eventElapsedNs`와 동일 |
| `cameraTimestampNs`, `captureTimestampReliable` | 원본 촬영 시각과 비교 가능 여부 |

수신 행을 먼저 기록하여, 추론·변환 실패 또는 callback 미복귀 시에도 프레임 수신 사실을 확인할 수 있다. CameraX 내부에서 버린 센서 프레임은 이 이벤트의 대상이 아니다.

### `stage=ANALYZER`

| 필드 | 의미 |
|---|---|
| `analyzerWorkEndElapsedNs` | `processBitmap()` 및 bitmap recycle/오류 처리 등 analyzer 본문 작업 뒤, ImageProxy close 전 |
| `imageProxyCloseStartElapsedNs` | `ImageProxy.close()` 호출 직전 |
| `imageProxyCloseEndElapsedNs` | close 호출의 반환/throw 관측 시각 |
| `analyzerCallbackEndElapsedNs` | close 뒤 callback exit를 관측한 시각. `eventElapsedNs`와 동일 |
| `closeSucceeded` | close 호출 정상 반환 여부 |
| `outcome` | `DELIVERED`, `INTERVAL_SKIPPED`, `BITMAP_UNAVAILABLE`, `ERROR`, `CLOSED` |

`finishAnalyzerFrame()`은 기존 finally의 close를 **정확히 한 번** 실행하며 close 예외를 삼키지 않는다. callback end는 telemetry enqueue **직전**의 관측값이다. enqueue와 최종 return 명령어까지 포함한 실행 종료를 뜻하지 않는다. `DELIVERED`도 이 callback 전달 결과이며 별도의 성공 추론/FPS로 세지 않는다.

### `stage=MLKIT`

| 필드 | 의미 |
|---|---|
| `mlKitDispatchedElapsedNs` | 기존 `captureWork.launch`에 작업을 전달하기 직전 |
| `mlKitRequestElapsedNs` | `MlKitObjectDetector.detect()`에서 `detector.process(image)` 호출 직전 |
| `mlKitCompletedElapsedNs` | Task 완료 리스너가 실행된 관측 시각. `eventElapsedNs`와 동일 |
| `completionBasis` | 정상 경로 `TASK_COMPLETION_LISTENER`; 예외 대체 경로 `BEFORE_PROCESS_REQUEST` / `AWAIT_RETURN_FALLBACK` |
| `outcome` | Task의 `SUCCESS` / `CANCELLED` / `ERROR` |

성능 기록 중에만 direct executor 완료 리스너를 추가하여 coroutine `await()`의 dispatcher 재개 지연과 구분한다. 리스너는 작은 immutable 이벤트와 bounded enqueue만 수행하며 Bitmap/결과를 복사하지 않는다. 리스너 등록 실패 시 기존 await 경로를 그대로 실행하고 대체 관측 기준을 명시한다. 원래 ROI·640×360 입력·STREAM_MODE·1500ms 설정·single-in-flight·결과 매핑·에러 반환 방식은 바꾸지 않았다.

SDK 내부 실제 연산 종료와 완료 리스너의 실행은 다를 수 있다. Task가 리스너 등록 전 이미 완료됐다면 리스너 등록 때 관측한다. `MLKIT` 행은 완료 시 한 번 기록하므로 영구 미완료 Task의 완료 시각을 제공하지 않는다.

### `stage=WARNING`

| 필드 | 의미 |
|---|---|
| `warningCandidateFinalizedElapsedNs` | 기존 후보/군중 선택 로직이 후보 하나 또는 없음으로 결정된 직후 |
| `warningSubmittedElapsedNs` | 기존 `WarningOutputController.submit()` 호출 직전 |
| `warningSubmitReturnedElapsedNs` | submit 반환 직후. `eventElapsedNs`와 동일 |
| `candidatePresent`, `accepted`, `skippedReason` | 후보 유무, 기존 제출 결과와 기존 skip 이유 |

submit 수락은 실제 재생 시작이 아니다. 새 age 제한·경고 자동 취소·스케줄링 변경 없이, 기존 context만 출력 작업까지 전달한다.

### `stage=OUTPUT`

| 필드 | 의미 |
|---|---|
| `kind` | `TTS`, `BEEP`, `VIBRATION` |
| `observation=API_REQUEST` | 실제 `speak/startTone/vibrate` 호출 지점의 관측 |
| `outputRequestElapsedNs`, `outputRequestReturnedElapsedNs` | 해당 API 호출 전/반환 또는 throw 관측. TTS는 bounded callback 연결 준비 직후 시각부터 speak 반환까지 |
| `accepted` | TTS 반환 코드 / startTone Boolean. vibrate는 void API 정상 반환을 true로 기록하며 실제 진동 보장은 아님. API 예외는 `N/A`, `reason=API_EXCEPTION` |
| `observation=TTS_ON_START` | `UtteranceProgressListener.onStart()` 수신 관측. `outputStartedElapsedNs`에 기록 |
| `observation=TTS_START_UNAVAILABLE` | 시작 callback 전에 done/error/stop/flush/해제/연결 eviction이 관측됨. `reason`에 원인 기록 |
| `outputStartedElapsedNs` | TTS onStart callback에서만 관측값을 제공. 그 외 `-1` |
| `physicalOutputStartedElapsedNs` | **항상 `-1`**. 스피커의 실제 소리/모터의 실제 움직임 시작은 공개 API로 측정하지 못함 |
| `pulseIndex` | Beep 패턴의 0부터 시작하는 pulse 번호. TTS/진동 요청은 0 |

Beep은 `handler.postDelayed()` 예약 시각이 아니라 **각 Runnable 안의 실제 `startTone()` 호출**을 감싼다. 진동은 effect 준비 뒤 **실제 `vibrate()` 호출**을 감싼다. 패턴/반복/기존 stop 동작은 그대로다.

TTS는 API 반환과 별개로 onStart를 원본 frame에 연결한다. 기록 중 utteranceId에 session/frame을 붙여 서로 다른 프레임의 callback이 충돌하지 않게 했다. 발화 내용·QUEUE_FLUSH·중복 억제·force 판단은 동일하다. callback 연결은 최대 32개이며 onStart/terminal 때 제거한다. 시작 callback 중복·동시 도착은 한 번만 기록한다. **연결 제거/eviction은 계측 정보만 정리하며 음성·경고를 취소하지 않는다.** 기존 stop/release 호출 뒤에도 아직 시작을 관측하지 못한 연결만 정리한다.

TTS onStart는 오디오 재생 직전의 callback이다. 스피커에서 실제 소리가 나기 시작한 hardware 시각과 같다고 주장하지 않는다. ToneGenerator/Vibrator에도 대응하는 물리적 시작 callback이 없어 API 요청 경계를 근사 관측으로 남긴다. [Android TTS callback](https://developer.android.com/reference/android/speech/tts/UtteranceProgressListener#onStart(java.lang.String)), [ToneGenerator](https://developer.android.com/reference/android/media/ToneGenerator#startTone(int,int)), [Vibrator](https://developer.android.com/reference/android/os/Vibrator#vibrate(android.os.VibrationEffect)).

## 4. 분석 결과의 의미와 중첩 방지

기존 `pipeline`은 `DetectionPipeline.process()`의 측정 반환 시점, `totalProcessing`은 analyzer 수신부터 pipeline 호출이 복귀한 직후까지다. 두 값은 추론을 포함한다. callback 전체 span은 추가 경고/metrics 작업·Bitmap recycle·ImageProxy close까지 포함한다. **이 값들을 더하지 않는다.**

수정 분석기의 새 `latency` 항목:

- `inferenceCall`: Interpreter end − start. 시작/종료를 모두 측정한 성공 FRAME만 사용.
- `inferenceEndToPipelineComplete`, `analyzerToInferenceStart`, `captureToPipelineComplete`.
- `analyzerCallback`, `imageProxyClose` — close 성공 span만 close 통계에 포함.
- `mlKitDispatchToRequest`, `mlKitRequestToCompletionObservation`.
- `warningFinalizeToSubmit`, `warningSubmitCall`.
- `outputs.TTS/BEEP/VIBRATION`: API 요청 관측 수, callback/시작 미확인 관측 수, API call 시간, warning 제출→첫 요청, TTS 요청→onStart/capture→onStart. Beep은 모든 pulse 요청 수를 세지만 첫 요청 지연은 pulse 0만 사용.
- `eventCounts`, `unplacedEventLines`: stage별 기록 수와 clock 원점이 없어 창에 배치하지 못한 행 수. FRAME도 완료 시각과 elapsedSeconds가 모두 없으면 `unplacedFrameLines`를 증가시키고 `frameLogComplete=false`, FPS=null로 두어 잘못된 0 FPS 판정을 막는다.

event는 `(sessionId, frameId)` **둘 다** 사용해 join하고, 파일 줄 순서와 무관하게 필드 간 ns 차이를 계산한다. 잘못된 역순 시각·음수·누락은 제외한다. `captureTimestampReliable=false`면 capture 지연을 만들지 않는다. 표본이 없으면 `samples=0`, 평균/P95는 JSON `null`이며 physicalStart는 `N/A`다. 정수 완료 ns로 창을 나누므로 소수 세 자리 `elapsedSeconds`의 경계 반올림 오류도 피한다.

기존 로그에서 추론 종료를 `completedElapsedNs`나 `nativeInvoke`로 추정해 채우지 않는다. 완료 시각이 없는 이벤트도 다른 시계로 억지 배치하지 않는다. 창 부분 분석에서 원본 수신/FRAME이 제외되면 join 기반 capture 지연은 미측정으로 남을 수 있다. 시작 callback 누락/창 밖/큐 누락을 정상 재생 시작 0ms로 바꾸지 않는다.

## 5. 예상 계측 부하와 남은 제약

| 항목 | 설계 / 한계 |
|---|---|
| OFF 경로 | 기록 OFF일 때 새로 수신한 프레임에는 recorder/event를 만들지 않는다. Camera listener의 nullable 분기와 진단 종료 시각 전달은 남는다. Interpreter 종료 ns는 기존 duration 종료 clock 읽기를 재사용한다. TTS listener는 초기화 때 한 번 등록되며 callback 시 작은 lookup/clock 비용은 남음. 이전 기록 중 시작한 작업의 늦은 callback은 이벤트 생성 뒤 닫힌 원래 sink에서 폐기될 수 있음 |
| ON hot path | source context와 작은 typed 이벤트, 단조 clock 읽기, 짧은 session lock/큐 예약만 수행. 문자열 포맷은 기존 writer 스레드에서 지연 생성 |
| writer | 기존 `OrderedLogWriter` 최대 512개 큐, `tryAcquire`, 단일 writer 및 줄별 flush 유지. 디스크 완료를 analyzer/출력이 기다리지 않음. 포화 시 기존 dropped 수 증가, 성공 FRAME histogram은 유지 |
| 로그량 | 일반 성공 프레임마다 CAMERA_RECEIVED/WARNING/ANALYZER **3행 추가**, ML Kit 시도마다 완료 1행, 실제 출력 API 요청/각 beep pulse·TTS onStart에 행 추가. 2.6 FPS 기준 기본 추가 이벤트 약 **7.8행/초 + ML Kit/출력 이벤트**라는 산술 예상이며 CPU/ms 비용 측정값은 아님 |
| 추가 메모리 | source/event/queue closure 할당은 ON에서 생김. callback 연결 최대 32개, 파일 writer 큐 최대 512개. native tensor·Bitmap 사본이나 후보 리스트는 추가하지 않음 |
| 실행 간격 설정 | interval skip이 많으면 수신/ANALYZER 행은 성공 추론보다 많아짐. 센서 전달률에 비례해 기록/flush 부담이 커질 수 있음. 주기/설정을 자동 변경하거나 이벤트를 몰래 샘플링하지 않음 |
| 세션 종료 | 기존 시간 창·종료 drain 유지. 창 밖/종료 뒤 callback은 버림. 기다림·새 타이머·추론/음성 자동 취소 없음. 시작 callback 미도착을 로그 끝에서 0으로 보정하지 않음 |
| 완료 행 방식 | 추론 시작/종료 필드는 FRAME/ERROR 생성 때 기록하고 ML Kit span은 완료 때 기록. 강제 종료/영구 미복귀에서는 그 span이 없을 수 있음. 수신 행은 남을 수 있으나 무한 대기의 내부 위치까지 진단하지는 못함 |
| 실측 한계 | 최종 APK의 FPS·CPU·발열·전력 영향과 실제 TTS engine callback은 **실기기 미검증**. 기존 로그 재분석은 새 APK 성능 개선/계측 무부하 증거가 아님 |

물리적 출력 시작을 검증하려면 마이크/가속도 등 독립 계측과 시스템 로그의 동기화를 별도로 설계해야 한다. 이번에는 기능/권한/하드웨어 계측을 추가하지 않았다.

## 6. 수정 파일

프로덕션 공통 접두사: `app/src/main/java/com/samin/objectdetection/`.

| 파일 | 변경 내용 / 핵심 함수 |
|---|---|
| `metrics/FrameLatency.kt` **신규** | typed event/context, `finishAnalyzerFrame`, `observeOutputRequest`, bounded `TtsStartTracker` |
| `metrics/PerformanceLogFormatter.kt` | `PerformanceFrameRecord.rotationDegrees`, `formatFrame/formatTrace/formatLatency`, schema/의미 헤더 |
| `metrics/PerformanceLogSession.kt`, `PerformanceLogRecorder.kt` | `beginFrameLatency`, originating session/window에 고정한 비동기 sink |
| `metrics/FrameTrace.kt` | `inferenceEndedElapsedNs` optional 추가 |
| `camera/CameraController.kt` | listener의 optional `onAnalyzerStarted`, `CameraFrameTiming.latency`, finally close/exit 계측 |
| `detector/VisionStyleYoloDetector.kt`, `DetectionDiagnostics.kt`, `InferenceException.kt` | Interpreter 반환/throw 단조 시각 전달. 추론 API/버퍼/입력/후처리 동일 |
| `MainActivity.kt` | `processBitmap`, `maybeRunMlKitDetection`, `recordFrameFailure`, camera listener 연결·회전 각도 분리·경고 finalized/submit 관측 |
| `mlkit/MlKitObjectDetector.kt` | optional context와 실제 process 요청/Task 완료 관측 |
| `warning/output/WarningOutputController.kt` | `submit/runOutput`에 원본 계측 context만 전달 |
| `warning/output/TtsWarningPlayer.kt` | `speak`, 초기화 onStart/terminal listener, 기존 stop/release 뒤 계측 연결 정리 |
| `warning/output/BeepWarningPlayer.kt` | 각 pulse의 `startTone` API 경계 |
| `warning/output/VibrationWarningPlayer.kt` | 기존 effect/legacy `vibrate` API 경계 |
| `evaluation/FrameTraceJson.kt` | 새 종료/성공 완료 시각을 평가 JSON에도 추가 |
| `tools/analyze_performance_logs.py` | `fields/detail_stats` 구/신 회전 키 정규화, `latency_analysis`, optional 시각의 null/-1 호환 |

테스트 변경:

- `app/src/test/java/com/samin/objectdetection/metrics/FrameLatencyTest.kt` **신규**: close 정상/throw, OFF, 기록 실패 격리, 실제 API 요청/물리 시작 미측정, TTS callback 선도착/중복/동시 도착/실패/누락·eviction, session 고정/창 경계/큐 포화.
- 같은 metrics 경로의 `PerformanceLogFormatterTest.kt`: 새 필드·중복 키 없음·미측정·추론 종료/pipeline 완료 범위.
- `app/src/test/java/com/samin/objectdetection/evaluation/FrameTraceJsonTest.kt`: 종료/성공 alias/오류 null.
- `app/src/androidTest/java/com/samin/objectdetection/detector/DetectorPreprocessingRegressionTest.kt`: 종료 ns 순서·기존 inference ms와 일치 assertion 추가. **컴파일만 검증, 기기 실행 미실시.**
- `tools/test_latency_analysis.py` **신규**: legacy/new 회전, 결측·NaN, old null/new -1, 여러 clock 경계, sparse/역순 행, session 오접합 방지, 다중 pulse, 창 경계, 기존 FPS 보존.
- 이 Markdown 문서 **신규**. 이전 분석 문서는 이번 P0에서 수정하지 않았다.

`settings/AppSettings.kt`, `camera/DetectionConfig.kt`, `pipeline/DetectionPipeline.kt`, `detector/YoloTensorContract.kt`, `YoloResizeTransform.kt`, 모델/labels와 경고 정책 파일은 수정하지 않았다.

## 7. 실행한 검증

| 검증 | 결과 |
|---|---|
| 변경 전 `testDebugUnitTest --offline` | 기존 캐시 기준 UP-TO-DATE 성공. Python 기존 테스트 12개 통과 |
| 변경 후 최종 `testDebugUnitTest` | **158개 실행, 실패 0, 오류 0, skipped 0** |
| Python 전체 회귀 | **26개 통과** (`tools/test_*.py`) |
| `assembleDebug`, `assembleDebugAndroidTest` | 성공. 실제 앱/AndroidTest 소스 호환성 확인 |
| `lintDebug` | 성공, **0 errors / 181 warnings**. baseline lint와 경고 개수를 대조한 것은 아니므로 모두 기존 경고라고 단정하지 않음 |
| 실기기 | `adb devices` 목록 비어 있음. 기기 설치/instrumentation/실제 음성·진동·성능 시험 미실시 |
| 자료 보존 | 모델/labels와 제공 로그 SHA 일치, 설정/인식/정책 파일 변경 없음, 기존 docs/validation 변경 없음 |

최종 실행 명령(저장소 root, Windows PowerShell):

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug --offline --console=plain
& '.\.venv\Scripts\python.exe' -B -m unittest discover -s tools -p 'test_*.py'
```

초기 sandbox 실행은 사용자 Gradle 캐시의 wrapper lock 접근이 차단됐다. 같은 로컬 캐시를 쓰는 테스트 실행 권한 확장 후 위 작업이 성공했다. 테스트 결과는 `app/build/test-results/testDebugUnitTest/`, lint 보고서는 `app/build/reports/lint-results-debug.html`에 생성됐다. 기존 `docs/validation` 결과를 덮어쓰지 않았다.

기존 원본 재집계 결과:

| 원본 | 성공 FRAME / FPS | inference 평균 / P95 ms | totalProcessing 평균 ms | 복원 회전 시간 평균 / P95 ms |
|---|---:|---:|---:|---:|
| `C:/Users/SAMIN/Downloads/perf_20261020_171606.txt` | 1,232 / 2.05319 | 420.612 / 655 | 469.220 | **22.720 / 33.119** |
| `C:/Users/SAMIN/Downloads/perf_20261020_174051.txt` | 1,560 / 2.59985 | 339.128 / 390 | 373.333 | **15.179 / 17.965** |

FRAME 수·FPS·추론/pipeline/totalProcessing 값과 확정 요약은 기존 결과와 일치하며 회전 시간 해석만 수정됐다. 두 로그에는 새 이벤트/추론 종료 필드가 없으므로 새 `latency.eventCounts`는 0, `latency.inferenceCall.samples=0`이고 평균/P95는 null이다. 실제 339ms 추론을 측정하지 않았다는 뜻이 아니라 **구 로그에 절대 종료 timestamp가 없다는 뜻**이다.

보존한 SHA-256:

```text
best.tflite                32a44bc7432ef2d7ca5de802dd8e714a26568aea16566bad3fd7fcd2b85bcee2
best_512.tflite            f312b541c86426c53c02ef1674ee539c6f87fcf97102061fade628317b3c10b9
labels.txt                 ef1d20e200c6ec8a7a6e888c823abfb86b5b0fb92f67dab022aaf3b35309edc4
perf_20261020_171606.txt    82d9dc83f1b05461ffb3314986b7cf79ce7493eba5d59fe4ffb662dc433bbc9e
perf_20261020_174051.txt    2566b0b54df66d93a86eb2c5466f0656af5bf051255eae028fcfc36caadcaaef
```

## 8. 다음 실기기 검증 절차와 통과 기준

1. **설정 확인만 수행:** 모델 SHA·512×512/2T·XNNPACK 요청 ON·현재 ROI/Letterbox·confidence/NMS·ML Kit/경고·진단·Preview 상태를 로그 헤더/설정 snapshot과 대조한다. 계측 검증을 위해 설정을 몰래 바꾸지 않는다.
2. **새 로그 수집:** 기존 기능과 동일한 보행/정지 시나리오에서 10분 측정한다. Preview OFF에서 OVERLAY 없음은 정상으로 처리한다. 새 rotation 키가 한 번씩이고 FRAME/LATENCY의 source ID가 일치하는지 확인한다.
3. **기본 시간 순서:** 정상 프레임에서 `analyzer ≤ inferenceStart ≤ inferenceEnd ≤ pipelineCompleted ≤ analyzerWorkEnd ≤ closeStart ≤ closeEnd ≤ callbackEnd`. warning finalized/submit은 pipeline 완료 뒤에 와야 한다. 비동기 ML Kit/출력은 다른 스레드/프레임과 겹칠 수 있으므로 행 순서로 판정하지 않는다.
4. **실제 출력 관측:** 경고 수락 후 기존 패턴대로 beep/진동/TTS가 동작하는지 확인한다. beep pulse 번호/호출 간격, TTS API 반환과 onStart callback의 frame 연결, stop/flush·중복 억제/CRITICAL interrupt 시 계측 결과를 확인한다. 물리 시작 -1은 오류로 취급하지 않는다.
5. **종료/재시작:** 성능 로그를 종료·재시작한 동안 이전 frame의 TTS/ML Kit callback이 새 파일에 들어가지 않는지 확인한다. close/drain/설정 재시작이 기존처럼 동작하고, 새 age gate/자동 취소가 없음을 확인한다.
6. **회귀/오버헤드:** 동일 시작 온도·장면·기능 상태에서 변경 전/후 APK를 순서 교차로 반복한다. FPS·native/total/callback P95, 프로세스 CPU·GC·온도, writer 누락을 비교한다. **계측 무부하를 현재 결과로 주장하지 않는다.** 예비 재검토 기준은 지속 FPS 또는 P95가 5% 넘게 악화하거나 새 dropped 로그/ANR/오류가 생기는 경우다. 전류 신뢰성 경고가 있으면 mW 절감/증가를 앱 전력으로 판정하지 않는다.

새 로그 분석은 기존 CLI를 그대로 사용한다.

```powershell
# 마지막 인자에는 실제 새 성능 로그 경로를 넣는다.
& '.\.venv\Scripts\python.exe' -B tools\analyze_performance_logs.py 'C:\실제경로\새성능로그.txt' --interval-seconds 60
```

통과 기준은 탐지/경고 동작과 설정 동일, 오류/ANR/교차 세션 오접합/시작 callback 중복 0, 관측된 시각 순서와 scope 일치, 측정 불가 값의 명시, 정상 조건 dropped 로그 0이다. 누락 이벤트가 있다면 deadline·로그 stop·writer drop·API skip·callback 미지원 중 어느 범위인지 먼저 구분한다. 이번 P0는 이후 최적화의 측정 기반을 마련한 것이며, 성능·정확도·배터리 개선을 검증한 결과는 아니다.
