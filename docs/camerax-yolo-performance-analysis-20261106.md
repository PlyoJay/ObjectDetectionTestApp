# CameraX + YOLO(TFLite) 성능 분석 및 최소 변경 개선 계획

작성일: 2026-11-06 (Asia/Seoul)  
분석 기준: `26354217718e7e9a3b421c3b523d68c4950608c6`의 현재 작업 트리  
범위: 소스·로컬 의존성 소스 확인과 개선안 작성. 앱 코드, 설정, 모델은 수정하지 않았으며 새 기기 측정도 수행하지 않았다.

**현재 구조에는 Interpreter·텐서 버퍼·모델 입력 Bitmap 재사용과 최신 프레임 처리 최적화가 이미 적용되어 있다.** 우선 검토할 최소 변경은 불필요한 상세 로그 문자열 생성과 진단 연산의 절감이다. 다음은 회전·ROI Bitmap의 반복 할당 절감이다. Preview OFF에서 Preview와 프레임별 Overlay 갱신을 제거하는 작업은 이미 구현되어 있으므로 다시 제안하지 않는다.

본문에서 **확인**은 소스에서 실행 경로·할당·설정을 확인했다는 뜻이다. 해당 경로가 실제 기기의 최대 병목이거나 성능 저하의 원인이라는 뜻은 아니다. **사용자 관측**은 제공된 3분 수치이며, **추정**은 추가 측정이 필요한 원인 가설이다. 해당 3분 세션의 원본 파일은 이번 분석 입력에 없으므로 단계별 시간·열 상태·실제 적용 설정을 재집계한 결과로 표현하지 않는다. 저장소의 과거 ARCore 및 다른 모델·해상도 실험 수치도 현재 세션의 실측치로 대체하지 않는다.

## 1. 현재 객체 인식 처리 흐름

### 1.1 프레임과 실행 스레드

```text
MainActivity.onCreate [main]
  ├─ 저장된 applied 설정 로드
  ├─ VisionStyleYoloDetector 1개 생성, 모델 mmap/텐서 계약 확인/버퍼 준비
  ├─ ML Kit, 정책·추적, 경고 출력, 선택형 기록기 생성
  └─ CameraController.start → CameraX bindToLifecycle(MainActivity)

카메라 YUV 이미지
  → CameraX 내부 YUV→RGBA 변환 [앱 analyzer 콜백 이전]
  → ImageAnalysis: RGBA_8888 / KEEP_ONLY_LATEST
  → 단일 analyzer executor
      ├─ 수신 시각·선택형 LATENCY 기록
      ├─ ImageProxy.toBitmap() → 전체 프레임 Bitmap 새 할당
      ├─ 회전 각도 ≠ 0이면 Matrix + 회전 Bitmap 새 할당
      └─ MainActivity.processBitmap() [동기 호출]
          ├─ ML Kit 사용·주기·in-flight 검사
          │   └─ 해당 프레임만 입력 준비 → SDK 비동기 처리 [별도 작업]
          ├─ DetectionPipeline.process()
          │   ├─ 회전 후 좌표에서 ROI 결정 → 필요할 때만 crop Bitmap 할당
          │   ├─ detector.detect()
          │   │   ├─ Letterbox/Resize → 재사용 입력 Bitmap/Canvas
          │   │   ├─ getPixels → RGB /255 → 재사용 텐서 버퍼
          │   │   ├─ 동일 Interpreter.runForMultipleInputsOutputs()
          │   │   └─ 출력 복사 → 후보 scan → 정렬/cap → 클래스별 NMS
          │   ├─ 전체 프레임 좌표 복원 → 크기·Geometry·클래스·Temporal 정책
          │       → 움직임 추적 → 경고 대상 생성
          │   └─ finally: 부분 ROI Bitmap 회수·선택형 debug 기록 제출
          ├─ 여기에서 성공 완료/FPS 및 FRAME의 completedNs 확정
          ├─ 경고 후보·혼잡도·cooldown 판단 → TTS/진동/Beep 제출
          ├─ 통계, 선택형 캡처/녹화·성능 기록 제출
          └─ Preview ON일 때만 최신 YOLO UI 작업 제출 [main]
      → 카메라 프레임 Bitmap recycle → ImageProxy.close() [finally]
```

근거: [CameraController.bindUseCases():100][camera-bind], [analyzer:118][camera-frame], [ImageProxyExt.toBitmapSafe():12][bitmap], [MainActivity.processBitmap():731][main-process], [DetectionPipeline.process():35][pipeline]. CameraX 의존성은 `1.3.4`, Interpreter 런타임 의존성은 `com.google.ai.edge.litert:litert:2.1.1`이다([app/build.gradle.kts:55][gradle]).

CameraX 1.3.4의 **실제 로컬 sources.jar**도 확인했다. `ImageAnalysisAbstractAnalyzer.analyzeImage():170`의 `convertYUVToRGB():218`은 사용자 analyzer 호출 이전에 실행된다. `ImageProxy.toBitmap():153` → `ImageUtil.createBitmapFromRgbaImage():436`은 `Bitmap.createBitmap()` 후 rowStride를 전달하는 복사를 수행한다. 따라서 앱에서 별도의 YUV→JPEG→Bitmap 변환을 하고 있다는 설명은 현재 소스와 맞지 않는다. RGBA 요청 시 CameraX 내부 색 변환이 있다는 점은 [Android 공식 ImageAnalysis 문서](https://developer.android.com/media/camera/camerax/analyze)와도 일치한다.

### 1.2 Interpreter 생성·초기화·재사용·종료

| 단계 | 확인된 동작 | 성능·안정성 판단 |
| --- | --- | --- |
| 생성 | `MainActivity.createYoloDetectorWithRecovery():306`에서 Activity당 detector 생성. 초기화 실패 시 이전 applied 설정으로 복구 시도 | 매프레임 생성이나 실패 프레임마다 재생성하는 구조가 아니다 |
| 모델 로드 | `ModelAssets.load():23`의 READ_ONLY mmap. `.tflite` noCompress 적용 | 매프레임 파일 읽기·모델 복사를 제거하라는 제안은 중복 |
| 초기화 | `VisionStyleYoloDetector.init:92`에서 Options, Interpreter, 텐서 shape/layout/dtype/quantization 검증, 버퍼 생성. 모델 SHA 계산도 초기화 때 수행 | 고정 텐서 사용. 명시적인 별도 warm-up 호출은 없으나 3분 후 저하와 연결할 근거는 없다 |
| 실행 | `detect():181`이 `@Synchronized`. 같은 Interpreter와 입력 array·출력 map으로 `runForMultipleInputsOutputs()` 호출 | 이미 호출 컨테이너까지 재사용. 같은 Interpreter로 여러 프레임 동시 실행을 제안하지 않음 |
| 설정 변경 | `restartForSettings():579`에서 analyzer 종료를 기다린 뒤 ML Kit·Interpreter 종료, Activity 재생성 | 기존 자원이 실행 중인 상태로 겹쳐 교체되는 것을 피함 |
| 종료 | `stopAsync():221` → analyzer drain 완료 → `onDestroy():1687`의 detector.close. detector.close는 synchronized·중복 호출 방어 및 입력 Bitmap 해제 | 이 순서와 잠금을 유지. 종료를 빠르게 하려고 추론 중 Interpreter를 닫지 않음 |
| 초기화 실패 정리 | 텐서 계약/Bitmap 초기화 실패 시 Canvas·Bitmap·Interpreter 정리 | 정상 실행 중 누수가 확인된 것은 아님 |

근거: [MainActivity detector 생성:306][main-init], [VisionStyleYoloDetector 초기화:92][detector-init], [ModelAssets:23][model-assets], [AnalyzerShutdown:8][shutdown], [설정 재시작:579][main-restart], [종료:1687][main-close], [detector.close():711][detector-close]. 초기화를 main에서 하므로 시작 시 UI 지연 가능성은 있지만 지속 FPS 저하와는 별도 문제다.

### 1.3 XNNPACK·CPU 설정과 실제 적용값

- `Interpreter.Options.setNumThreads(interpreterThreadCount.coerceAtLeast(1))`와 `setUseXNNPACK(useXnnpack)`을 **명시적으로 호출한다**. 기본 `YoloSettings`는 threads=4, XNNPACK=true다. GPU/NNAPI delegate 생성 경로는 없다.
- 실제 실행은 `settingsStore.loadApplied()`의 저장값을 따르므로 사용자 세션이 반드시 4 threads였다고 단정할 수 없다. `YoloSettings.model` 기본값은 `YOLO_512` / `best_512.tflite`다. detector 생성자의 독립 기본 모델 `best.tflite`와 앱의 선택 모델을 혼동하지 않아야 한다.
- `InferenceExecutionInfo`는 `cpuThreads`, `xnnpackRequested`, `runtimeVersion`, 오류 수를 남긴다. `delegateVerification=UNAVAILABLE_JAVA_API`이므로 **XNNPACK ON 요청 확인과 실제 모든 연산의 delegate 처리 확인은 다르다**. `cpuFallbackCount=0`도 모든 연산의 XNNPACK 처리를 증명하지 않는다.
- 스레드 증설이 장기 처리량을 높인다는 보장은 없다. 기존 설정으로 2/4 threads를 먼저 비교하고 필요한 경우 1 thread를 추가하는 실험은 코드 수정 없이 가능하다. XNNPACK 기본 ON은 유지한다.

근거: [YoloSettings:26][settings], [옵션 설정:95][detector-init], [InferenceExecutionInfo:3][execution], [설정→DetectionConfig 매핑:182][settings-map].

### 1.4 이미 적용된 메모리·처리 최적화

| 대상 | 이미 최적화된 내용 | 남아 있는 비용 |
| --- | --- | --- |
| 입력·출력 Tensor | `allocateDirect(numBytes)` 1회, native byte order, FloatBuffer view 재사용 | 입력 채널 전개와 출력 복사는 매프레임 필요 |
| 입력 픽셀·Float 배열 | `IntArray`, FLOAT32 scratch `FloatArray`를 detector가 소유 | `getPixels`와 scratch→direct-buffer 전송 |
| FLOAT32 입력 | `/255.0f` 256개 LUT, NCHW/NHWC 지원, bulk `FloatBuffer.put` | 이미 bulk 처리. 다시 bulk writer로 바꾸라는 제안 불필요 |
| 출력 배열 | `Array<FloatArray>` 재사용. FLOAT32 channels-first는 row별 bulk `get` | 전체 출력 복사와 클래스 최고 점수 scan |
| Letterbox | 모델 크기 Bitmap·Canvas·Paint·RectF 및 resize transform 캐시 | padding 채우기·리샘플링은 남음. 입력 크기가 같으면 원본 Bitmap 사용 |
| ROI | full-frame이면 crop Bitmap 생성 생략 | ROI가 실제 부분 영역일 때만 crop 복사·할당 |
| 프레임·UI | 단일 analyzer, KEEP_ONLY_LATEST, finally close, 최신 UI 작업 병합 | 완료 전 다음 앱 프레임 추론은 시작하지 않음 |
| ML Kit | 640×360 입력 Bitmap/Canvas 재사용, in-flight 1개, SDK 완료까지 lease 유지 | 사용 시 입력 리사이즈와 비동기 SDK 부하, 부분 ROI 임시 crop |
| 진단·저장 | 텐서 통계 최대 2초에 1회, PNG interval+동시 저장 1개, 디버그 큐 16개 | 켜졌을 때 scan·hash·문자열·Bitmap copy·PNG 압축 |
| 성능 로그 | 별도 writer, 큐 512개, 포화 시 tryAcquire 실패로 드롭. 시스템 조회 별도 executor | 매 행 flush, formatting, 통계 lock, 공유 CPU/I/O 부하 |

근거: [detector 버퍼 준비:139][detector-buffers], [RgbTensorWriter/YoloOutputReader:69][tensor-writers], [ROI:58][pipeline], [LatestUiUpdateDispatcher:7][ui-dispatch], [MlKitInputBuffer:25][ml-buffer], [DetectionDebugRecorder:19][debug-recorder], [PerformanceLogSession:76][log-session], [OrderedLogWriter:25][writer].

## 2. 확인된 성능 병목

**최대 병목으로 실측 확인된 항목은 사용자 제공 추론 시간이다.** 아래는 소스에서 확인한 비용과 개선 후보이며, 해당 3분 로그의 단계별 시간이 없으므로 순위별 절감 ms나 FPS 향상률을 확정하지 않는다.

### 2.1 추론 구간

`detect()`의 `inferenceTimeMs`는 전처리·출력 scan·NMS를 제외하고 Java Interpreter 호출을 감싼 monotonic 경과시간이다. 사용자 관측의 228→344ms 증가가 이 로그 필드라면 **추론 호출 구간 자체가 느려진 것**이다. Overlay만 제거해서 이 증가분이 직접 사라진다고 볼 수 없다. 다만 다른 작업의 CPU·메모리 경쟁과 발열을 줄이면 간접 개선 가능성은 있다.

기존 `nativeInvoke`와 Java 호출의 ns 경계를 함께 비교해야 한다. native 시간도 스케줄링·동기화·주파수 영향을 받을 수 있으므로 증가 자체를 Interpreter 내부 누적 결함으로 해석하지 않는다([추론 호출:271][detector-invoke]).

### 2.2 카메라 Bitmap 변환·회전의 반복 할당 — 확인

`ImageProxyExt.toBitmapSafe():27`은 매 분석 프레임에 새 전체 Bitmap을 만들고, 회전이 필요하면 `Bitmap.rotate():52`에서 Matrix와 두 번째 전체 Bitmap을 만든다. 정상 경로에서 이전 Bitmap은 recycle된다. **반복 할당은 확인되지만 누수는 확인되지 않았다.**

실제 분석 버퍼가 1280×720 ARGB_8888이라고 가정하면 픽셀 저장량은 Bitmap당 약 3.52MiB다. 회전 시 한 프레임에서 약 7.03MiB에 해당하는 두 Bitmap의 생성이 발생한다. 이는 stride·내부 버퍼를 제외한 계산이며 Java heap 증가량이나 상주 메모리 측정값이 아니다. 카메라 실제 크기는 fallback 가능하므로 `[CAMERA_SHAPE]`를 확인해야 한다.

우선 회전 출력 Bitmap/Canvas/Matrix만 analyzer 소유로 재사용하는 방안이 비교적 작다. 이 변경은 주로 할당을 줄이며 회전 픽셀 처리 자체를 없애지는 않는다. CameraX toBitmap의 새 Bitmap까지 제거하는 stride-safe RGBA 복사는 범위와 회귀 위험이 더 크므로 후속 단계다.

### 2.3 YUV→RGBA와 측정 범위 — 확인

카메라가 이미 RGBA라 YUV 변환 비용이 없다고 볼 수 없다. CameraX가 사용자 analyzer 이전에 변환한다. 현재 `cameraToBitmap`은 앱 콜백 안의 RGBA→Bitmap 복사 구간이다. `bitmap`, `totalProcessing`, `LATENCY ANALYZER`도 **그보다 앞선 CameraX 내부 변환을 직접 계측하지 않는다**.

`YUV_420_888` 출력으로 바꾸고 직접 `toBitmap()`을 사용하면 RGBA ImageReader 중간 경로를 줄일 여지가 있다. 그러나 색 변환 위치·경로가 바뀌고 CPU 경쟁과 픽셀 수치도 검증해야 한다. 단순 설정 한 줄로 확정 개선이라고 제안하지 않는다. RGBA 경로는 우선 유지하고, 이 후보는 동일 RGB 입력·BBox 결과와 전체 처리량을 비교하는 별도 실험으로 둔다. JPEG 경유 변환을 새로 도입할 이유는 없다.

### 2.4 ROI Crop·Letterbox·입력 전개 — 조건부 비용 확인

- `DetectionPipeline.process():58–64`: 부분 ROI/center crop일 때만 `Bitmap.createBitmap` 후 복사. 기본 `RecognitionRoi.enabled=false`, center crop=false이므로 기본 경로에는 ROI Bitmap 비용이 없다.
- `MlKitInputBuffer.acquire():40–56`: 부분 ROI이면 ML Kit 적격 프레임에서도 임시 crop이 생성된다. 이 프레임은 YOLO crop과 별개로 ROI 복사가 발생한다. ML Kit 최종 640×360 입력은 이미 재사용한다.
- `VisionStyleYoloDetector.detect():201` 부근의 Letterbox와 `fillInputBuffer():335`는 매프레임 모델 입력 픽셀을 만든다. 512×512 FLOAT32 입력이라면 direct tensor 약 3MiB, scratch 약 3MiB, pixel 배열 약 1MiB, 입력 Bitmap 약 1MiB가 지속 재사용된다. **매프레임 이 8MiB를 새로 할당하는 구조가 아니다.**
- ROI Bitmap 재사용은 정확히 잘라낸 픽셀을 보존하는 방식으로 검토한다. ROI와 Letterbox를 단일 drawBitmap으로 합치는 것은 경계 보간을 바꿀 수 있다. ML Kit 소스에도 이 때문에 실제 crop을 유지한다는 주석이 있으므로 픽셀 동등성 확인 없이 합치지 않는다.

### 2.5 출력 scan·NMS — scan은 고정 작업, NMS는 이미 제한

`parseOutput():351`은 재사용 출력 배열에 복사한 뒤 모든 raw 후보의 클래스 점수를 scan한다. 실제 shape는 모델 텐서에서 읽는다. 현재 기본 v2의 과거 검증 로그는 `[1,16,5376]` FLOAT32이며, 이 shape라면 매프레임 5,376×12 점수 비교와 5,376×4 좌표 통계 처리가 발생한다. 실제 세션 shape는 헤더로 재확인해야 한다([기존 모델 검증 로그:40][v2-log]).

확인된 절감 후보는 다음과 같다.

1. **`maxClassScore`를 모든 프레임에서 갱신하지만 사용은 `diagnoseTensorThisFrame` 안이다.** 진단 프레임에서만 누적하도록 옮기는 것은 매우 작은 변경이며 검출 계산에 영향을 주지 않는다.
2. `rawMin/rawMax`는 confidence 통과 후보마다 계산하지만 사용은 `enableDiagnostics` 로그 안이다. 같은 조건 안으로 이동할 수 있다.
3. 전체 후보의 `rawCoordinateMin/Max`를 진단 OFF에서도 매프레임 계산한다. 단, 캡처·녹화 JSON도 이 값을 사용한다. **진단 OFF라는 이유만으로 없애면 기존 기록 정보가 달라진다.** 최초 로그·상세 진단·self-test·캡처·녹화의 소비 여부를 먼저 전달하거나, 필요한 결과 필드의 의미를 유지하는 방식으로 최적화해야 한다.
4. 상세 진단 ON이면 `ArrayList<Float>(boxCount)`의 boxing과 전체 `sortedDescending().take(5)`가 매프레임 실행된다. 정확히 같은 top-5 값을 만드는 고정 크기 집계로 줄일 수 있다. 기본 OFF에서는 이 부하가 없다.

NMS 전에는 confidence 내림차순 정렬 후 `maxCandidates`만 남기며 기본값은 100이다. NMS 내부는 **다시 정렬하지 않는다**. 같은 label끼리 IoU 비교하며 mutable list의 `removeAt(0)`·iterator 제거로 최악 O(K²) 이동/비교 비용이 있다. 다만 기본 K≤100이고 실제 NMS 시간은 원본 로그로 미확인이다. 설정 검증은 최대 10,000까지 허용하므로 저장값이 높을 때는 별도 확인한다. cap이나 threshold를 낮추는 방식은 인식 결과를 바꾸므로 제안하지 않는다([parseOutput:391][parse], [후보 제한·NMS:527][nms]).

### 2.6 Overlay·경고 로그·성능 기록 — 서로 다른 경로

**경고 Logcat: 확인된 작은 수정 후보.** `MainActivity.processBitmap():815–869`, `logSelectedWarningCandidate():1400`, `logWarningOutput():1435`, `logCrowdDecision():1471`은 detector 진단 OFF에서도 매프레임 로그 문자열을 만든다. 경고 후보가 없어도 `none` 로그가 발생한다. `WarningOutputController.submit()`의 `logOutput()`도 같은 종류의 반복 출력이 있다. 기본 `ENABLE_VERBOSE_LOG=false`만으로 이 경로가 꺼지지 않는다. 상세 문자열은 기존 진단 gate에서 지연 생성하고, 오류·필요한 경고 이벤트 기록과 실제 출력은 유지하는 것이 우선이다. 절감량은 analyzer tail 측정으로 확인한다.

**Overlay ON: 확인된 추가 할당.** `drawLabelIfNeeded():292`는 객체마다 `TextPaint`, `StaticLayout`, 배치 후보 list와 RectF 등을 생성한다. FULL 모드에서는 매 업데이트 다중 문자열 formatting과 `metricsCollector.buildSummary()`의 synchronized 집계를 main에서 실행한다. CPU 경쟁과 metrics lock 대기 가능성은 있지만 현재 지연 기여는 미측정이다. 캐시는 크기·텍스트·폰트·화면 폭 변경 시 무효화하고 stale 결과 제거를 보존해야 한다. 기본 SIMPLE/Preview OFF 최적화의 우선 과제는 아니다([Overlay:104][overlay], [라벨:292][overlay-label]).

**성능 기록: 비동기이나 비용은 존재.** `OrderedLogWriter.append():32`는 매 행 flush한다. FRAME 외에 LATENCY 등 여러 행도 기록되므로 파일 행 수가 추론 수보다 많다. analyzer가 매 행 디스크 완료를 기다리는 구조는 아니며, 큐는 유계다. 그렇더라도 formatting·flush·락·스토리지와 CPU 공유 비용은 남는다. dropped=0은 기록 손실이 없다는 의미이며 기록 비용이 0이라는 의미가 아니다. 저FPS tail 보존을 위한 의도적 flush이므로 무조건 제거하지 않는다.

**상세 진단·PNG: 기본 OFF, 프리셋에서는 ON일 수 있음.** `SettingsPresets.fieldTest()`는 detector 진단·raw 기록·입력 PNG 저장을 모두 켠다. 이 프리셋 사용 여부를 먼저 확인한다. 텐서 통계는 input/output 전체 scan과 SHA 계산을 수행하고, PNG 저장은 analyzer에서 Bitmap copy를 한 뒤 별도 worker에서 압축한다. 이미 주기 제한과 큐 제한이 있으므로 “매프레임 PNG 저장”이라는 진단은 틀리다([프리셋:251][presets], [진단·PNG:55][debug-save], [TensorStatistics:153][tensor-stats]).

## 3. 장시간 성능 저하 원인 분석

### 3.1 제공 수치로 말할 수 있는 범위

| 사용자 관측 | 해석 | 현재 확인할 수 없는 것 |
| --- | --- | --- |
| 3분 평균 약 3.26 FPS | 성공 완료 처리량이 낮음 | 어떤 프레임/구간으로 집계했는지 원본 재확인 필요 |
| 초기 3.83 → 후반 2.47 FPS | 약 35.5% 감소 | 두 구간의 정확한 길이, 카메라·UI·로그별 기여 |
| 추론 228 → 344ms | 약 50.9%, 약 116ms 증가 | native 실행 시간 증가와 runnable 대기·GC·스레드 경쟁의 분리 |
| 추론 오류 0건 | 제공된 오류 집계상 실패 없음 | 스케줄링 지연·GC·열 제한이 없다는 의미는 아님 |

이 수치는 동작 복구와 처리량 저하를 함께 보여준다. 초기/후반 통계의 동일 시간창 여부를 모르므로 FPS 역수와 추론 평균의 차이를 정밀한 “기타 비용”으로 계산하지 않는다.

### 3.2 원인 가설과 코드상의 근거

| 가설 | 코드·관측 근거 | 판단과 검증 |
| --- | --- | --- |
| 발열/전력 정책에 따른 CPU 성능 제한 | detectInterval 기본 0, 단일 analyzer가 가능한 한 계속 CPU 추론. Preview·ML Kit·진단이 켜져 있으면 공유 부하 추가. 시간이 지나 추론 자체가 증가했다는 사용자 관측 | **우선 검증할 가설**. SYSTEM의 thermal/headroom/policy 상한과 nativeInvoke를 시간축으로 비교. 현재 원인 확정 아님 |
| CPU scheduling·경쟁 | YOLO worker 외 ML Kit, writer, PNG worker, main 및 경고 출력이 병행 가능 | **조건부 추정**. mlKitInFlight와 LATENCY, 초기/후반 Perfetto의 running/runnable 시간을 함께 확인. 별도 executor라는 사실만으로 경쟁이 없어지지 않음 |
| 반복 Bitmap 할당·GC/메모리 대역폭 | 전체 Bitmap 및 회전 Bitmap, 부분 ROI, 결과 객체 반복 생성 | **할당 구조 확인, GC 영향 미확인**. GC pause와 지연 프레임, GC 후 heap 기준선 확인 필요. recycle이 있어도 할당 비용이 0은 아님 |
| Interpreter가 누적 상태 때문에 느려짐 | 동일 Interpreter·고정 텐서 재사용, 매프레임 rebuild/resize 없음 | 현재 소스에서 누적 결함 근거 없음. 동일 조건의 고정 입력 독립 반복 실험으로만 추가 분리. 주기적 재생성은 제안하지 않음 |
| 프레임 큐·UI backlog 누적 | KEEP_ONLY_LATEST, 단일 analyzer, latest-only UI dispatcher | 앱이 옛 프레임을 무한히 쌓는 구조는 확인되지 않음. 이 부분의 재최적화 우선순위 낮음 |
| 검출 수 증가에 따른 후처리 증가 | raw scan 수는 shape로 고정. confidence 통과 수에 따라 결과 객체·정렬·정책 비용 변화 가능 | 동일 장면·후보 수와 parsing/NMS를 비교해야 함. FRAME의 detect 수는 raw 후보 수가 아님 |
| 로그·통계가 시간에 따라 계속 비대해짐 | 디버그 큐16·성능 큐512, 최근 통계100개·검출 기록500개 제한. 추적 history5개/기본 stale2초. 세션 표본 최대100,000개 | 무제한 매프레임 이력 정렬은 없음. interval bucket/histogram은 세션 동안 증가하지만 정규 성능 기록은 3/5/10분 종료. 장기 상시 상세 기록 설계는 별도 필요 |
| wall clock 변경 | 추론·주요 처리 시간은 elapsedRealtimeNanos. ML Kit eligibility와 움직임/cooldown 등 일부는 wall clock | 수백 ms 추론 증가의 직접 설명은 아님. 전용 단말의 시간 보정 시 안정성은 별도 검증 |

`SYSTEM processCpuUsage`는 모든 앱 스레드의 CPU 시간 합으로 100%=한 코어이며 100% 초과가 정상이다. `CpuFrequencyReader`는 기기 policy 수준 스냅샷을 읽으며 해당 순간의 YOLO 실제 실행 코어/유효 주파수가 아니다. `thermalStatus=NONE` 또는 N/A만으로 제한이 없다고 결론내리지 않는다. 실제 thermal·CPU 주파수 데이터가 없으면 “발열 의심”으로 남긴다([시스템 계측:35][system], [주파수 수집:48][cpu-frequency]).

**확인된 자원 수명 문제와 단순 추정을 구분한다.** 정상 경로의 프레임/ROI recycle, finally close, 종료 drain은 구현되어 있다. 따라서 메모리 누수나 Interpreter 누수를 현재 저하 원인으로 확정하지 않는다. 반면 화면 없는 단말에서 Activity lifecycle에 의존하는 점은 별도의 배포 구조 미완성 사항이다.

## 4. 최적화 우선순위 (높음/중간/낮음)

아래의 효과는 검증 전 기대 방향이다. 큰 FPS 향상률을 보장하지 않는다. 모델 재학습, 모델/카메라 해상도 축소, 추론 주기 제한, confidence/NMS/후보 cap/경고 정책 변경은 우선 대상에서 제외한다.

| 우선순위 | 작업 | 수정 범위·기대 효과 | 조건·보존 사항 |
| --- | --- | --- | --- |
| **높음 / 코드 0줄** | 세션 설정 확인: field-test 진단·PNG·FULL UI 여부와 실제 threads/model/해상도 | 진단이 켜져 있었다면 설정 비교만으로 큰 부하 차이 가능 | applied 설정을 임의로 덮어쓰지 않음. 진단 ON 기능은 그대로 유지 |
| **높음** | 무조건 실행되는 경고 상세 로그의 문자열 지연 생성·진단 gate | MainActivity와 출력 controller의 국소 수정. 프레임 후반 부하 절감 | 실제 경고, cooldown, 오류, 필수 이벤트 기록 보존. 기대 절감 ms는 미측정 |
| **높음 / 작은 절감** | 진단용 maxClassScore, rawMin/rawMax를 실제 소비 조건에서만 계산 | detector 한 파일의 매우 작은 변경. 검출 계산 그대로 | Tensor 진단 ON/self-test 결과 동등성 확인 |
| **높음 / 검증** | 기존 설정으로 2/4 threads의 3분·10분 지속 성능 비교 | 코드 수정 없음. 장기 native 구간 개선 가능성을 가장 직접 확인 | 기본값 자동 변경 금지. 단기 FPS만으로 선택하지 않음 |
| **중간** | rawCoordinate 통계의 소비 기반 계산, 진단 top-5 고정 크기 집계 | 고정 scan의 불필요 연산/boxing/sort 절감 | 캡처·녹화·최초 로그의 현재 통계 의미 보존이 선행. 기본 진단 OFF의 top-5 개선 효과는 없음 |
| **중간** | 회전 출력 Bitmap/Canvas/Matrix 재사용 | 전체 크기 Bitmap 1개/프레임의 할당 절감 가능 | rotation≠0일 때만 효과. 픽셀·density·alpha·해상도 변경·수명 회귀 검증 |
| **중간 / ROI 사용 시** | YOLO crop Bitmap 재사용 | 부분 ROI의 반복 할당 절감 | full-frame에서 효과 없음. 실제 crop과 경계 보간 일치 |
| **중간 / 조건부** | RGBA→Bitmap 재사용 복사 또는 YUV 직접 Bitmap 경로 비교 | 전체 프레임 복사/중간 이미지 비용 절감 가능 | stride·채널·색·orientation·정확도 검증 부담 큼. 변환 시간만이 아닌 총 FPS 비교 |
| **낮음 / 측정 후** | writer의 선택형 시간/크기 기준 flush | 별도 worker I/O·wake-up 감소 가능 | 큐 순서, 자동 종료 drain, 저FPS tail, 장애 시 손실 범위 보존·명시. 매행 flush 기본 의미를 몰래 변경하지 않음 |
| **낮음** | Overlay 라벨 캐시·FULL summary 갱신 절감 | Preview ON·객체 다수·FULL에서만 유효할 수 있음 | Preview OFF에는 프레임별 UI 제출이 이미 없어 효과 제한 |
| **낮음** | NMS list 이동 제거·정렬/후처리 추가 단순화 | K≤100 기본에서는 절감 한계. NMS 실측이 클 때 시행 | 동일 score tie 순서·클래스·IoU·threshold·cap 유지 |
| **낮음 / 시작 응답성** | detector 초기화를 백그라운드로 이동 | main 시작 지연 개선 | 3분 후 추론 저하 해결안이 아님. 초기화 실패 복구·시작 순서 보존 |

### Preview OFF와 GOTORO 화면 없는 단말

**이미 제거됨:** `CameraController.bindUseCases():192–202`는 OFF에서 Preview 객체/SurfaceProvider를 생성하지 않고 ImageAnalysis만 bind한다. `MainActivity:973`, `:1604`, 오류 UI `:1376`도 OFF에서 프레임별 YOLO·ML Kit UI 제출을 생략한다. `MainScreenView`는 Preview/Overlay를 GONE으로 둔다. Preview OFF 최적화를 다시 구현할 필요가 없다.

**전용 모드에서 추가 제거 가능:** 화면 View 트리·PreviewView 자체 생성, 1초 UI 상태/버튼 갱신, 화면 표시 전용 문자열, Overlay 라벨·좌표 표시 계산, MediaProjection 화면 녹화와 화면용 캡처/공유 UI. 단, 1초 루프의 FPS 집계·자동 측정 상태 처리 등 화면 외 역할은 적절한 실행 주체로 옮겨야 한다. 경고 후보 계산, 움직임 추적, GPS 기반 사용자 상태, TTS/진동/Beep은 화면이 없어도 기능 경로이므로 제거하지 않는다. `overlayDetections`는 움직임 추적과 경고 생성에도 사용되므로 변수명만 보고 전체 경로를 삭제하면 안 된다([pipeline:123][pipeline], [UI 주기 작업:365][main-ui]).

**아직 없는 실행 구조:** 현재 카메라 lifecycle owner는 MainActivity이며 Manifest에는 mediaProjection용 service만 있다. Preview OFF는 화면 꺼짐/무화면 상시 실행 보장이 아니다. 전용 단말 단계에서 UI와 독립된 lifecycle/service 소유권, 부팅·권한·재시작·종료를 설계해야 한다. 현재 targetSdk=36이며 일반 Android의 camera foreground service는 `camera` type·`FOREGROUND_SERVICE_CAMERA`·CAMERA 권한과 while-in-use 시작 제한을 고려해야 한다. 부팅 receiver에서 언제나 시작할 수 있다고 가정하지 말고, 실제 전용 OS/device-owner 권한에서 허용되는 시작 경로를 검증한다. 이는 이번 작은 성능 수정과 분리하는 안정성 과제다([Manifest:9][manifest], [Android 공식 camera foreground service 규칙](https://developer.android.com/develop/background-work/services/fgs/service-types#camera)).

## 5. 수정 대상 파일 및 메서드

위치는 분석 기준 소스의 1-based 줄 번호다. 아래 표는 향후 변경 후보이며 이번에는 수정하지 않았다.

| 파일·클래스 | 메서드·위치 | 향후 변경 내용 |
| --- | --- | --- |
| [MainActivity.kt:731][main-process] | `processBitmap`, L815–869, L1400/1435/1471의 로그 helper | 상세 Logcat gate와 지연 문자열. 경고 출력·정책은 유지 |
| [warning/output/WarningOutputController.kt:33][warning-output] | `submit`, `logOutput` L308 | none/cooldown 등 반복 상세 로그의 동일 gate 적용 |
| [detector/VisionStyleYoloDetector.kt:351][parse-method] | `parseOutput`, L391–439, L505–558 | max score/좌표 진단 연산의 조건화, 필요할 경우 top-5 집계 개선 |
| [camera/ImageProxyExt.kt:12][bitmap] | `toBitmapSafe`, `Bitmap.rotate` L52 | 재사용 회전 출력 경로. 안전한 기존 CameraX 변환은 우선 유지 |
| [camera/CameraController.kt:118][camera-frame] | analyzer, `stopAsync` L221 | 재사용 Bitmap 소유권과 반환 시 recycle 계약 조정, drain 후 해제 |
| [pipeline/DetectionPipeline.kt:35][pipeline] | `process`, crop L58–64 / cleanup L200–205 | ROI 사용 시 crop storage 재사용. 해상도/ROI 변경 대응 |
| [mlkit/MlKitInputBuffer.kt:25][ml-buffer] | `acquire`, `Lease.close` | 후속 ROI 공용화 검토 시에만 수정. 기존 입력 재사용·SDK lease는 보존 |
| [logging/OrderedLogWriter.kt:25][writer] | `append`, `close` | 측정으로 필요성이 확인될 때만 선택형 flush 정책 |
| [ui/BoundingBoxOverlay.kt:292][overlay-label] | `drawLabelIfNeeded`, `buildLabel` | Preview ON에서 실제 비용이 클 때 캐시 도입 |
| [MainActivity.kt:340][main-ui] / [ui/MainScreenView.kt:28][screen] | `setupUi`, 상태 갱신 / View 생성 | 별도 GOTORO 모드에서 UI 생성·주기 갱신 분리 |
| [AndroidManifest.xml:45][manifest-service] / [MainActivity.kt:675][main-camera] | lifecycle owner, service 선언 | 화면 없는 단말 상시 실행을 위한 별도 구조 설계 |
| [metrics/PerformanceLogSession.kt:76][log-session] / [metrics/PerformanceLogFormatter.kt:171][formatter] | `recordFrame`, `formatFrame` | 원칙적으로 유지. 세부 계측 추가 시 기존 열과 의미 보존 |

설정 기본값과 모델·임계값·클래스 옵션은 유지한다. XNNPACK 추가, direct buffer 도입, Letterbox Bitmap 재사용, KEEP_ONLY_LATEST 도입, UI latest-only 병합은 이미 완료되어 수정 계획에 다시 넣지 않는다.

## 6. 단계별 코드 개선 계획

### 단계 0 — 기존 계측으로 기준 확정, 기능 변경 없음

1. 현재 3분 원본 로그가 있으면 모델 SHA/input-output shape·실제 해상도·ROI·threads·XNNPACK·Preview·ML Kit·debug/PNG 설정과 `stopReason`, dropped/error 수를 확인한다.
2. 원본의 0–30초와 150–180초를 같은 기준으로 재집계한다. 3분 복구 결과를 기준선으로 두고 같은 설정의 10분 세션을 추가해 후반 안정성을 확인한다. 3/5/10분 자동 종료는 이미 지원한다.
3. 상세 진단이 켜졌다면 진단 OFF 비교를 별도 조건으로 시행한다. 기존 설정을 자동 변경하지 않는다. 충분히 냉각한 시작 온도·잔량·충전 상태·장면·화면 밝기·측정 전 실행 시간을 맞추고 A/B 순서를 교대한다.
4. 기존 trace `CameraX.toBitmap`, `CameraX.rotate`, `YOLO.resize`, `YOLO.getPixels`, `YOLO.rgbWrite`, `YOLO.Interpreter.run`부터 사용한다. native 증가 원인이 불명확하면 초기/후반에 짧은 Perfetto로 sched/frequency/GC를 비교한다. 앱에 세부 계측부터 대량 추가하지 않는다.

### 단계 1 — 최소 코드 변경: 상세 로그와 사용하지 않는 진단 연산

1. MainActivity·WarningOutputController에서 반복 상세 로그를 진단 gate 내부에서 생성한다. 오류와 필요한 경고 이벤트, 실제 경고 정책·우선순위·출력·cooldown은 유지한다. 진단 ON의 상세 관측 기능을 보존한다.
2. `maxClassScore`와 로그 전용 `rawMin/rawMax`만 먼저 조건화한다. `rawCoordinateMin/Max`는 캡처/녹화 소비 계약 때문에 이 작은 변경에 섞어 무조건 비활성화하지 않는다.
3. 각각을 독립 변경으로 비교한다. 로그 변경은 `LATENCY ANALYZER`의 pipeline 완료 이후 tail, 진단 연산 변경은 outputParsing 및 pipeline로 평가한다. **로그 감소만으로 inference 116ms 증가가 해결된다고 기대하지 않는다.**
4. 기존 `DetectorCpuOptimizationTest`, `DetectorPreprocessingRegressionTest`, golden 결과 검증을 활용한다. 입력 텐서와 raw output, class/confidence/BBox·NMS 순서·경고 결과가 같아야 한다. 진단 ON/OFF·캡처·녹화의 필드 동등성을 확인한다. 이번 분석에서는 이 테스트들을 새로 실행하지 않았다.

### 단계 2 — 필요할 때만 진단 scan 최적화와 스레드 설정 비교

`candidateScan`이 유의미하면 소비가 없는 프레임의 좌표 통계를 생략하도록 프레임별 수요를 명시한다. 캡처 요청은 추론 시작 전에 스냅샷하거나 다음 적격 프레임으로 원자적으로 연결하여 요청 경합 때문에 통계가 빠지지 않게 한다. `debugFrame != null`은 PNG-only에서도 참일 수 있으므로 raw 문자열/진단 결과 객체 생성의 실제 소비 gate를 구분한다. top-5는 정확한 상위값·동점 동작을 보존한다.

이와 별개로 기존 설정에서 2/4 threads를 비교한다. 1 thread는 필요 시 추가한다. XNNPACK ON, 모델·해상도·ROI·ML Kit·경고 설정은 고정한다. 스레드 변경은 커널 합산 순서에 따른 수치 차이도 회귀 검증하고, 후반 FPS·P95·열 상태가 반복 개선된 경우에만 전용 단말 설정 후보로 선택한다. 앱 기본값은 자동 변경하지 않는다.

### 단계 3 — 회전 Bitmap, 그다음 실제 사용 중인 ROI Bitmap 재사용

1. 우선 카메라 입력 `toBitmap()`은 유지하고 회전 대상 Bitmap/Canvas/Matrix를 analyzer 소유로 재사용한다. 프레임 해상도/회전 변경 시 안전하게 교체하고 analyzer drain 후 해제한다.
2. 기존 `onFrame` 반환 후 controller가 무조건 프레임 Bitmap을 recycle하는 계약도 함께 수정해야 한다. 재사용 Bitmap을 기존 finally에서 recycle하지 않도록 일회성/대여 프레임 소유권을 명확히 한다. 캡처용 별도 copy와 ML Kit SDK lease는 유지한다.
3. ROI가 켜져 있고 crop 시간이 유의미할 때 YOLO crop storage 재사용을 추가한다. 1:1 crop 픽셀·density·alpha, Letterbox padding=114, source 좌표 복원, ROI 경계 보간을 기준과 비교한다. 회전+crop+resize를 한 번에 합치는 변경은 첫 단계에서 제외한다.
4. 0/90/180/270도, full-frame/edge ROI/center-square, 해상도·설정 전환, Preview ON/OFF, ML Kit in-flight, 캡처, 오류·취소·종료에서 재사용/회수 경쟁을 검증한다. FPS 외에 할당량·GC 변화로 목적 달성 여부를 확인한다.

### 단계 4 — 측정된 잔여 병목만 개선하고 GOTORO 실행 구조 분리

writer 부하가 확인되면 opt-in batching을 검토하고 순서·드롭·자동 종료·tail 보존을 `OrderedLogWriterTest`와 `PerformanceLogSessionTest`로 확인한다. 출력 복사/scan 중 어느 쪽이 큰지 기존 로그가 분리하지 못하면 선택형 저빈도 요약 또는 trace만 추가한다. 기본 direct-buffer/bulk 경로를 근거 없이 다시 작성하지 않는다.

GOTORO 모드는 Activity/Overlay/화면 녹화를 분리하되, 경고·추적·필요 로그를 유지하는 독립 lifecycle을 별도 작업으로 설계한다. ML Kit는 현재 사용자 기능이므로 자동 제거하지 않는다. 전용 단말에서 사용하지 않기로 명시된 경우에만 기존 OFF 설정을 적용하고 불필요한 초기화까지 지연하는 방안을 검토한다. 열 보호 해제·CPU 주파수 고정·우선순위 강제 상승·주기적 Interpreter 재생성은 개선안에 포함하지 않는다.

## 7. 기존 성능 로그로 확인 가능한 검증 항목

### 7.1 기존 필드로 확인 가능

| 질문 | 사용할 로그·계산 | 해석상 주의 |
| --- | --- | --- |
| 동일 설정 비교인가? | 헤더 Settings Snapshot, model SHA, input/output shape/type, requested/actual camera resolution, ROI, Preview, ML Kit, threads, xnnpackRequested | 요청 해상도와 실제 협상 해상도 구분. actual=0x0은 미관측 |
| 후반 처리량이 개선되는가? | 최종 `INTERVAL_SUMMARY`의 30초별 successfulFrames/inferenceFps, `CUMULATIVE_SUMMARY`, `TEST_SESSION` | 구간 FPS=성공 완료 수/실제 구간 시간. 정수 FRAME fps 평균이나 1/평균 inference로 대체하지 않음 |
| native 구간도 느려지는가? | FRAME `inference`, `nativeInvoke`, `inferenceStartElapsedNs`, `inferenceEndElapsedNs`의 평균·P95 | 상세 native와 Java 측정 경계가 다름. 정수 ms 내림값끼리 빼 정확한 JNI 비용이라 하지 않음 |
| 전처리 중 어디가 큰가? | `bitmap`, `cameraToBitmap`, `rotationMs`, `crop`, `resize`, `inputBuffer`, `getPixels`, `rgbWrite`, `inputDiagnostics` | cameraToBitmap은 CameraX 내부 YUV 변환 시간이 아님. rotationDegrees는 각도 |
| 출력 파싱/NMS가 느린가? | FRAME `outputParsing`, `nms`, `post`; 진단 ON의 `outputCopyMs`, `candidateScanMs` | 기본 성능 FRAME은 outputCopy+candidateScan 합만 저장. NMS 0ms는 1ms 미만일 수 있음 |
| 추론 외 전체 처리도 증가하는가? | `pipeline`, `totalProcessing`, `pipelineCompletedElapsedNs` | totalProcessing은 analyzer 수신→pipeline 호출 반환 직후. pipeline finally의 debug 제출·ROI recycle은 포함하나 이후 경고·성능 기록 제출·카메라 프레임 recycle·close·UI draw는 제외 |
| pipeline 뒤의 숨은 tail이 큰가? | 같은 sessionId/frameId의 `LATENCY stage=ANALYZER` 결합. `(analyzerCallbackEndElapsedNs - analyzerElapsedNs)/1e6` 및 `(analyzerWorkEndElapsedNs - pipelineCompletedElapsedNs)/1e6` | 후자는 경고·기록·UI 제출·프레임 recycle 등이 포함된 wall span. 어느 한 작업 CPU 시간으로 해석하지 않음 |
| close 지연/실패가 있는가? | `imageProxyCloseEndElapsedNs - imageProxyCloseStartElapsedNs`, closeSucceeded/outcome | callback end는 close 후 계측 enqueue 직전 관측이며 실제 executor 반환 instruction은 아님 |
| ML Kit가 겹치는가? | `mlKitPrepare`, `mlKitInFlight`, LATENCY MLKIT request/completion, YOLO inference ns | inFlight는 한 시점의 힌트. SDK wall span 겹침도 CPU 실행 경쟁의 확정 증거는 아님 |
| CPU·열 정책 변화가 동반되는가? | SYSTEM processCpuUsage/window, thermalStatus/headroom, policy current/scalingMax/cpuinfoMax, BATTERY_SAMPLE 온도 | 주파수는 policy 스냅샷. N/A를 0/최대값으로 채우지 않음. 배터리 온도≠CPU 다이 온도 |
| 로그 최적화가 정확한 비교인가? | droppedLogLines, FRAME 수와 최종 successfulFrames, ERROR/inferenceErrors, stopReason | 최종 집계는 writer drop과 독립적으로 누적되므로 FRAME 파일 수와 다를 수 있음 |
| Preview OFF가 정상인가? | header preview=false, backend=CAMERAX, 성공 완료 지속, Overlay 이벤트 부재 | Overlay 지연 N/A는 정상. 카메라 수신 시각 신뢰 여부도 확인 |
| 경고 안정성을 유지하는가? | LATENCY WARNING/OUTPUT, accepted/skippedReason, 경고 기록 및 회귀 결과 | TTS onStart는 callback 수신 시점. 진동/Beep API 호출과 물리 출력 시작은 다름 |

근거: [PerformanceLogFormatter:171][formatter], [FrameLatency.finishAnalyzerFrame():65][latency], [PerformanceIntervalCollector:29][interval], [MainActivity 완료 시각:748][main-process], [SYSTEM 수집:68][system].

타이밍은 포함 관계가 있으므로 전부 더하지 않는다. `getPixels/rgbWrite/inputDiagnostics ⊂ inputBuffer ⊂ preprocess`, `nativeInvoke ⊂ inference`, `outputParsing/NMS ⊂ detector post`, detector post와 pipeline 정책 후처리가 FRAME post에 포함된다. `crop`은 detector preprocess 밖, `outputDiagnostics`는 detector/pipeline 안이지만 parse post 밖이다. `mlKitPrepare`는 pipeline 이전이며 totalProcessing 안이다. `totalProcessing`과 analyzer callback도 부분적으로 겹친다. detector의 입력 PNG copy는 parse에서 계산한 detectorTotal 이후에 실행되고, pipelineTime은 return 식에서 측정되므로 finally의 debug 제출·ROI 회수는 그 값에 포함되지 않는다. 이처럼 세부 합계와 바깥 wall span은 정확히 같아야 하는 관계가 아니다.

### 7.2 기존 로그만으로 확정할 수 없는 항목

- Java/native heap·PSS 추이, 프레임당 할당량, GC count/time/pause: 현재 SYSTEM 스키마에 없다. Perfetto/메모리 계측이 필요하다.
- YOLO 실제 실행 CPU·스레드별 running/runnable 대기, 주파수 residency: policy 스냅샷만으로 불가하다.
- 실제 XNNPACK 연산 partition 범위, Interpreter 내부 누적 문제: Java 실행 설정 로그만으로 불가하다.
- CameraX 내부 YUV→RGBA 단독 비용, writer flush 단독 비용, 라벨 draw CPU 비용: 기존 FRAME 합계에서 분리할 수 없다.
- 진단 OFF 상태의 confidencePassed/nmsInput 세부 후보 수와 copy/scan 개별 시간: 현재 기본 FRAME에 없다. `detect`/`visible`만으로 복원하지 않는다. 필요할 때 기존 상세 trace/저빈도 통계를 보완한다.
- 검출 정확도·경고 안전성의 유지: FPS와 오류 0만으로 증명할 수 없다. 기존 golden/전처리/정책/ROI·회전 및 실제 경고 검증이 필요하다.

### 7.3 재집계 방법과 통과 기준

기존 분석기를 사용할 수 있다. 아래 `C:\logs\...`는 실제 원본 파일 경로로 바꿔 실행하는 예시이며 이번 분석에서 실행한 실측 결과가 아니다.

```powershell
.\.venv\Scripts\python.exe tools\analyze_performance_logs.py "C:\logs\baseline_3min.txt" "C:\logs\candidate_3min.txt" --interval-seconds 30
```

[tools/analyze_performance_logs.py:252][analyzer]는 30초 구간, 기존 세부 필드, SYSTEM 상관 및 LATENCY를 분석한다. 0–30초와 150–180초를 비교하고, 동일한 10분 측정에서는 30초별 추세와 후반 안정 구간을 본다. 최종 확정 `INTERVAL_SUMMARY`를 사용하고 `INTERVAL_PREVIEW`의 경계 중간 상태와 섞지 않는다. P95는 프레임 분포에서 다시 구하며 구간 P95끼리 평균하지 않는다. 경계에서 들어오고 나간 비동기 이벤트는 N/A로 남기고 세션/프레임 ID로 결합한다.

개선 통과 기준은 동일 조건의 반복 A/B에서 **후반 성공 FPS·pipeline/analyzer P95가 반복 변동폭을 넘어 개선**되고, 인식·경고 결과 회귀, 오류 증가, 로그 손실 증가, 자원 종료 오류가 없는 것이다. 회전/ROI 재사용은 할당량 감소와 픽셀 동등성도 확인한다. 계측 수준·시작 온도·충전·장면을 바꾸어 얻은 차이를 코드 효과로 계산하지 않는다. 10분 통과 후 전용 단말의 실제 운용 시간 검증을 별도로 진행하되, 현재 상세 성능 파일의 3/5/10분 제한과 상시 운영 로그의 보관 정책을 구분한다.

이번 작업의 검증 범위는 호출 경로·할당·소유권·기본/저장 설정 경로·로그 생성 및 타이밍 경계의 정적 확인과 Markdown 참조 검사다. 앱 코드 변경이 없어 Android build/테스트와 새 성능 측정은 실행하지 않았다.

[camera-bind]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/camera/CameraController.kt:100>
[camera-frame]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/camera/CameraController.kt:118>
[bitmap]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/camera/ImageProxyExt.kt:12>
[main-process]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/MainActivity.kt:731>
[pipeline]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/pipeline/DetectionPipeline.kt:35>
[gradle]: <D:/develop/Projects/TestProjects/ObjectDetection/app/build.gradle.kts:55>
[main-init]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/MainActivity.kt:306>
[detector-init]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/detector/VisionStyleYoloDetector.kt:92>
[model-assets]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/detector/ModelAssets.kt:23>
[shutdown]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/camera/AnalyzerShutdown.kt:8>
[main-restart]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/MainActivity.kt:579>
[main-close]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/MainActivity.kt:1687>
[detector-close]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/detector/VisionStyleYoloDetector.kt:711>
[settings]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/settings/AppSettings.kt:26>
[settings-map]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/settings/AppSettings.kt:182>
[execution]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/detector/InferenceExecutionInfo.kt:3>
[detector-buffers]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/detector/VisionStyleYoloDetector.kt:139>
[tensor-writers]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/detector/YoloTensorContract.kt:69>
[ui-dispatch]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/ui/LatestUiUpdateDispatcher.kt:7>
[ml-buffer]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/mlkit/MlKitInputBuffer.kt:25>
[debug-recorder]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/detector/DetectionDebugRecorder.kt:19>
[log-session]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/metrics/PerformanceLogSession.kt:76>
[writer]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/logging/OrderedLogWriter.kt:25>
[detector-invoke]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/detector/VisionStyleYoloDetector.kt:271>
[v2-log]: <D:/develop/Projects/TestProjects/ObjectDetection/docs/validation/yolo-v2-preview-off-performance-20261103.txt:40>
[parse]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/detector/VisionStyleYoloDetector.kt:391>
[parse-method]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/detector/VisionStyleYoloDetector.kt:351>
[nms]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/detector/VisionStyleYoloDetector.kt:527>
[overlay]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/ui/BoundingBoxOverlay.kt:104>
[overlay-label]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/ui/BoundingBoxOverlay.kt:292>
[presets]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/settings/AppSettings.kt:251>
[debug-save]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/detector/DetectionDebugRecorder.kt:55>
[tensor-stats]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/detector/YoloTensorContract.kt:153>
[system]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/metrics/SystemPerformanceDiagnostics.kt:35>
[cpu-frequency]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/metrics/AndroidSystemMetricSource.kt:48>
[main-ui]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/MainActivity.kt:340>
[screen]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/ui/MainScreenView.kt:28>
[manifest]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/AndroidManifest.xml:9>
[manifest-service]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/AndroidManifest.xml:45>
[main-camera]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/MainActivity.kt:675>
[warning-output]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/warning/output/WarningOutputController.kt:33>
[formatter]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/metrics/PerformanceLogFormatter.kt:171>
[latency]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/metrics/FrameLatency.kt:65>
[interval]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/metrics/PerformanceIntervalCollector.kt:29>
[analyzer]: <D:/develop/Projects/TestProjects/ObjectDetection/tools/analyze_performance_logs.py:252>
