# ObjectDetectionTestApp 통합 YOLO 실시간 성능 분석

분석일: 2026-10-13, Asia/Seoul. 분석 대상은 로컬 `main`의 `5ba8951c353f167e28e5fc7a35412fcf15ce5dd6`이며, `git ls-remote origin refs/heads/main`으로 원격 최신 커밋과 일치함을 확인했다. 분석 시작 시 작업 트리는 깨끗했다. 이 보고서만 추가했으며 앱 코드, 설정 기본값, 모델 및 테스트는 변경하지 않았다.

결론은 **진단 OFF 조건에서 기준선을 다시 측정하고, CPU 스레드 비교와 단계별 프로파일링을 진행한 다음, GPU 및 모델 변환을 평가하는 것**이다. 제공된 수치에서는 추론 실행이 가장 큰 비중을 차지하지만, 상세 진단으로 부풀려진 전처리와 프레임 간 부수 작업도 구분해야 한다. 실기기 재측정이나 새로운 정확도 평가는 수행하지 않았으며, 아래의 기대 효과는 검증된 개선 수치가 아니다.

## 1. 현재 성능 병목과 코드 근거

### 실제 모델과 실행 환경

저장소의 `tools/inspect_tflite.py`를 기존 Python 환경에서 읽기 전용으로 실행해 현재 바이너리를 검사했다.

| 항목 | 확인 결과 |
|---|---|
| 사용 asset | `best.tflite`, 37,962,632 bytes |
| SHA-256 | `32a44bc7432ef2d7ca5de802dd8e714a26568aea16566bad3fd7fcd2b85bcee2` |
| 모델 metadata | YOLO11s Detect, Ultralytics 8.4.173, `end2end=false` |
| 입력 | `[1,3,640,640]`, NCHW, FLOAT32, 비양자화 |
| 출력 | `[1,13,8400]`, FLOAT32, 비양자화 |
| 클래스 순서 | bollard, person, bicycle, car, motorcycle, bus, truck, stop sign, bench |
| 그래프 경계 | 첫 연산은 NCHW→NHWC TRANSPOSE `[0,2,3,1]`. box 마지막 경로는 `1/640`을 곱하고 class score에는 LOGISTIC 적용 |
| Android runtime | `com.google.ai.edge.litert:litert:2.1.1`, `org.tensorflow.lite.Interpreter` |
| CameraX | 1.3.4, RGBA_8888 분석 출력, 단일 analyzer executor |

`README.md`와 `docs/yolo11s-nine-class-migration.md`의 “아직 1클래스 binary” 설명은 현재 asset과 맞지 않는다. 바이너리와 최신 `docs/field-test-fixes.md`를 우선했다. 별도 `best_float32.tflite`도 검사했는데 `[1,640,640,3]`→`[1,5,8400]`인 이전 bollard 1클래스 모델이다. **이 파일을 현재 9클래스 모델의 NHWC 또는 FLOAT32 비교군으로 사용하면 안 된다.**

### 필드 수치의 해석

| 제공된 지표 | 해석 |
|---|---|
| 추론 평균 약 562ms | `Interpreter.run` 호출 경계가 가장 큰 병목. 모델 계산뿐 아니라 런타임 입출력 처리 비용도 포함될 수 있음 |
| 전처리 평균 약 168ms | 현재 코드의 `preprocess`는 Letterbox resize + 입력 버퍼 작성 구간. 활성화된 입력 텐서 통계·SHA-256도 이 구간에 포함 |
| 전체 처리 평균 약 764ms | 원본 로그가 없으므로 정확한 필드명 확인 필요. 최신 코드의 `pipeline`이라면 CameraX Bitmap 변환·회전과 이후 MainActivity 작업은 제외 |
| FPS 약 1.25 | 실제 성공 완료 수/경과시간 기준으로 비교해야 함. 최신 코드의 기존 `Average FPS` 필드는 정수이므로 소수 `inferenceFps` 사용 |
| 화면 표시 지연 약 875ms | 유효한 camera timestamp를 기준으로 측정한 실제 overlay draw 지연인지 확인 필요 |

같은 표본과 같은 측정 범위라고 가정하면 562/764 ≈ 74%, 168/764 ≈ 22%다. 이 비율은 우선순위 판단용이다. 남는 약 34ms를 NMS 비용으로 확정할 수 없고, 875−764 ≈ 111ms를 UI 비용으로 확정할 수도 없다. 카메라 변환, 진단, MainActivity 작업, 표본 집합 차이가 섞여 있다. 1.25 FPS는 완료 간격 약 800ms를 뜻하지만 평균 pipeline 시간의 역수와 반드시 같지는 않다.

측정 경계는 다음과 같다.

| 구간 | 현재 포함/제외 범위와 근거 |
|---|---|
| Bitmap 변환 | `ImageProxy.toBitmap()` + 전체 Bitmap 회전. detector/pipeline 앞에서 별도 측정. [CameraController.kt:132](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/camera/CameraController.kt:132), [ImageProxyExt.kt:23](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/camera/ImageProxyExt.kt:23) |
| 입력 작성 | 모델 입력 설명 작성, `getPixels`, RGB 정규화, NCHW writer, 진단 프레임의 통계·checksum. [VisionStyleYoloDetector.kt:194](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/detector/VisionStyleYoloDetector.kt:194) |
| 추론 | `run` 직전부터 직후까지. 입력/출력 텐서 통계 자체는 제외. [VisionStyleYoloDetector.kt:238](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/detector/VisionStyleYoloDetector.kt:238) |
| 출력 진단 | `run` 이후, `parseOutput` 내부 postprocess 타이머 시작 이전. 출력 통계·checksum은 `postprocess` 세부 수치에 들어가지 않지만 detector/pipeline에는 포함. [VisionStyleYoloDetector.kt:247](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/detector/VisionStyleYoloDetector.kt:247) |
| PNG snapshot | `parseOutput`에서 `detectorTotalTimeMs`를 채운 뒤 `saveInput` 호출. Bitmap 복사는 detector의 세부 total에는 빠지지만 바깥 pipeline에는 포함. PNG 압축·파일 쓰기는 별도 worker. [VisionStyleYoloDetector.kt:263](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/detector/VisionStyleYoloDetector.kt:263) |
| pipeline | crop→detector→좌표/정책/모션 후처리. 반환 객체의 타이머 이후 finally의 debug finish 비용은 제외. [DetectionPipeline.kt:187](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/pipeline/DetectionPipeline.kt:187) |
| 완료 이후 | MainActivity의 경고 선택·출력·Logcat·녹화 snapshot/로그 제출·성능 기록·UI 제출. pipeline 평균에는 제외되어도 다음 analyzer 처리 및 실제 draw 지연에 영향. [MainActivity.kt:489](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/MainActivity.kt:489) |
| 화면 지연 | 실제 overlay draw 콜백에서 측정. camera clock이 REALTIME임을 확인할 수 없으면 -1로 제외. [MainActivity.kt:297](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/MainActivity.kt:297) |

### 추론 실행과 CPU 병목

Interpreter는 Activity 생성 시 한 번 만들고 analyzer에서 직렬 실행한다. 모델 mapping, 직접 ByteBuffer, pixels 배열, outputData 배열, 모델 입력 Bitmap/Canvas를 재사용한다. 프레임마다 모델을 로드하거나 Interpreter를 생성하는 구조는 아니다. [초기화 및 버퍼: VisionStyleYoloDetector.kt:77](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/detector/VisionStyleYoloDetector.kt:77), [모델 mapping: ModelAssets.kt:13](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/detector/ModelAssets.kt:13).

`Interpreter.Options`에서 명시한 것은 `setNumThreads`이며 GPU/NNAPI delegate 추가는 없다. 4 threads는 모델의 CPU 연산 병렬도에 관한 값으로, 전처리 Kotlin 루프나 카메라 executor를 4개로 늘리는 설정이 아니다. 단일 Interpreter에 여러 CameraX 프레임을 동시에 실행하는 방식은 제안하지 않는다.

**XNNPACK 기본 옵션은 이미 ON이다.** 로컬 Gradle 캐시의 실제 `litert-2.1.1.aar`를 검사했다. `InterpreterApi.Options.getUseXNNPACK()`은 미설정 상태에 true를 반환하며, `NativeInterpreterWrapper`는 이 값과 thread count를 native 생성 함수에 전달한다. 공식 API 문서도 기본 활성화라고 설명한다. 다만 필드 단말에서 실제 위임된 연산 수·partition 및 fallback은 별도 확인이 필요하다. PC 검사 때 출력된 XNNPACK 로그를 Android 적용 증거로 대체하지 않았다. [공식 Options 문서](https://developers.google.com/edge/api/litert/kotlin/org/tensorflow/lite/InterpreterApi.Options).

현재 모델 그래프는 총 353개 연산이며 CONV_2D 81개, DEPTHWISE_CONV_2D 7개, BATCH_MATMUL 2개, TRANSPOSE 14개, RESHAPE 16개 등이 있다. 첫 입력 transpose 외에도 attention/head 내부 변환이 있다. 이 연산 개수는 비용 비율이나 미위임 여부를 증명하지 않는다. 특히 BATCH_MATMUL, TRANSPOSE, SOFTMAX 경로는 실기기 op profiling으로 확인할 대상이다. 9개 클래스 순회 자체가 562ms의 원인이라고 볼 근거는 없다.

### 전처리와 복사 병목

현재 경로는 CameraX RGBA 출력 → `toBitmap()` → 전체 프레임 회전 Bitmap → 선택적 ROI → 재사용 Letterbox Canvas → `getPixels(IntArray)` → RGB /255 → NCHW ByteBuffer다.

- CameraX RGBA 출력의 내부 색 변환과 앱의 Bitmap 생성·회전 비용을 구분해야 한다. CameraX 내부 처리 일부는 analyzer 진입 전이므로 앱 `bitmapConversionMs`만으로 전체 비용을 설명하지 못한다.
- 회전이 필요하면 원본 크기의 Bitmap을 추가 생성한다. 파일 문서에 남은 과거 1440×1080 실측 해상도는 이번 필드 세션의 실제 해상도로 확정하지 않았다. 현재 `[CAMERA_SHAPE]`를 확인해야 한다.
- Full Frame일 때 ROI 단계는 원본 Bitmap을 그대로 사용하므로 불필요한 crop 복사는 이미 피한다. [DetectionPipeline.kt:57](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/pipeline/DetectionPipeline.kt:57).
- Letterbox는 입력 Bitmap/Canvas, Paint, transform을 재사용하며 별도 resized Bitmap을 매번 만들지 않는다. padding RGB=114와 정수 반올림 기반 transform이 적용된다. [VisionStyleYoloDetector.kt:166](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/detector/VisionStyleYoloDetector.kt:166).
- `getPixels`의 409,600개 ARGB int 복사 후 NCHW writer가 pixels를 R/G/B 각각 순회한다. 입력은 1,228,800 FLOAT32 값, 4,915,200 bytes다. pixels/입력 Bitmap은 각각 약 1.56MiB, 입력 tensor는 약 4.69MiB다. 재할당은 피했어도 메모리 읽기·쓰기와 채널별 계산 비용은 남는다. [writer: YoloTensorContract.kt:68](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/detector/YoloTensorContract.kt:68).
- NCHW writer 다음에 그래프 첫 연산이 NHWC로 다시 변환한다. 앱 writer만 NHWC로 바꾸면 모델 입력 계약을 위반한다. NHWC 최적화는 동일 가중치 모델을 다시 export하고 검증해야 한다.
- 출력 13×8400 FLOAT32, 436,800 bytes를 ByteBuffer에서 outputData로 매 프레임 복사한 뒤 다시 후보 순회한다. 배열은 재사용하지만 복사는 남는다. [VisionStyleYoloDetector.kt:295](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/detector/VisionStyleYoloDetector.kt:295).
- direct ByteBuffer 사용만으로 앱↔native tensor의 완전한 zero-copy를 보장할 수 없다. 런타임 내부 복사와 delegate transfer는 프로파일링으로 구분해야 한다.

### 진단 기능의 오버헤드

| 기능 | 동기 경로 비용 | 비동기/시스템 비용 | 판정 |
|---|---|---|---|
| `enableDetectorDiagnostics` | 카메라·bbox·정책 Logcat, 전체 후보 confidence 수집·정렬 등 | 로그 시스템 부하 | OFF/ON 실측 필요 |
| `debugDetectionLogging` | 후보별 raw/filtered 문자열, summary, 입력 설명, 텐서 진단 | 상세 파일 append | OFF/ON 실측 필요 |
| 입력/출력 텐서 통계 | 첫 프레임 및 약 2초마다 전체 값 순회, RGB mean, SHA-256 | 일반 경로에서는 통계 자체가 analyzer에서 실행 | 전처리 평균·P95를 왜곡할 수 있음 |
| 입력 PNG | 주기 도래 시 640×640 Bitmap snapshot 복사 | PNG 압축·쓰기, CPU/메모리/스토리지 경쟁 | 비동기라도 오버헤드 존재 |
| 화면 녹화 | 녹화용 객체 snapshot/제출 | MediaProjection, H264 30fps, 최대 긴 변 1920, 8Mbps, JSONL/timeline writer | ON 여부 미제공; 별도 측정 필요 |
| 성능 로그 | FRAME 문자열 formatting은 caller에서 실행 | 최대 512개의 순차 writer 큐, 행별 flush | 비교 시 상태 고정, 독립 대조군 필요 |
| 일반 경고 Logcat | 일부 진동·beep·TTS·feedback 로그는 진단 OFF에도 실행 | 로그 시스템 부하 | 설정 OFF가 모든 logging 제거를 뜻하지 않음 |

텐서 진단은 `enableDiagnostics || debugDetectionLogging`으로 활성화되며 첫 프레임과 약 2초 간격으로 실행된다. 입력 122만여 float를 순회하고 checksum을 계산하므로 전처리 168ms 전체를 resize 비용으로 해석하면 안 된다. [VisionStyleYoloDetector.kt:154](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/detector/VisionStyleYoloDetector.kt:154), [TensorStatistics: YoloTensorContract.kt:92](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/detector/YoloTensorContract.kt:92).

PNG worker는 큐 16개, pending 이미지 1개로 제한하고 포화 시 생략한다. 기존 프레임을 보존하기 위한 snapshot 복사는 analyzer에서 수행하며, 주기 저장과 검출 저장이 동시에 조건을 만족하면 **한 snapshot을 두 파일로 각각 PNG 압축**한다. PNG 저장만 켜도 debugFrame이 만들어져 일부 진단용 객체·문자열 계산 경로가 활성화된다. [DetectionDebugRecorder.kt:31](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/detector/DetectionDebugRecorder.kt:31), [saveInput: DetectionDebugRecorder.kt:55](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/detector/DetectionDebugRecorder.kt:55).

일반 녹화는 매 프레임 전체 Bitmap을 복사하지 않는다. `copyBitmap=needsCapture`이며 JSONL 생성은 writer에서 수행한다. 사진 캡처를 요청한 프레임에만 Bitmap 복사가 추가된다. 따라서 “녹화가 프레임마다 Bitmap 복사 때문에 느리다”는 진단은 현재 코드와 맞지 않는다. [MainActivity.kt:619](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/MainActivity.kt:619), [EvaluationDataRecorder.kt:127](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/evaluation/EvaluationDataRecorder.kt:127).

## 2. 코드 수정 없이 개선 가능한 항목

1. **성능용 설정을 저장·적용한다.** LETTERBOX, Full Frame, 현재 confidence/NMS/필터/클래스 정책을 유지하고 `enableDetectorDiagnostics=false`, `debugDetectionLogging=false`, `debugSaveInferenceInput=false`, `legacyDebugImageEnabled=false`, `debugSaveOnDetection=false`로 설정한다. 기존 FIELD TEST 프리셋은 상세 진단과 PNG를 켜므로 선택 후 반드시 해당 항목을 바꾼다. 성능 로그는 ON으로 고정하고 화면 녹화·사진 캡처는 OFF로 둔다. UI의 SIMPLE 표시는 유지해도 된다. [AppSettings.kt:81](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/settings/AppSettings.kt:81).
2. **CPU threads 1/2/4/6/8을 비교한다.** 설정 UI가 1..16을 지원한다. 처음에는 1/2/4로 범위를 좁히고 필요하면 6/8을 추가한다. 각 적용 후 재초기화와 워밍업을 거쳐 평균·P95·장시간 발열을 비교한다. 8이 4보다 빠르다고 가정하지 않는다. [SettingsActivity.kt:161](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/settings/SettingsActivity.kt:161).
3. **ML Kit 상태를 확인한다.** FIELD TEST 프리셋에서는 OFF지만 일반 기본값은 ON이다. 통합 YOLO 성능을 평가할 때는 OFF로 고정한다. 사용 중이라면 640×360 추가 resize와 별도 추론의 자원 경쟁이 존재한다. ML Kit을 끄면 보조 검출 기능이 달라지므로 최종 운영 비교에서는 필요한 기능 상태를 동일하게 맞춘다. [MainActivity.kt:1199](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/MainActivity.kt:1199).
4. **요청 카메라 해상도를 비교한다.** 설정 UI의 단말 지원 목록에서 같은 화면비의 한 단계 낮은 해상도를 선택한다. 모델 입력은 계속 640이다. CameraX가 선택한 실제 해상도와 Preview/Analysis 시야를 반드시 확인한다. 현재 selector가 Preview와 Analysis에 함께 적용되므로 이 조정은 Preview 화질과 원거리 소형 객체 정보에도 영향을 줄 수 있다. [CameraController.kt:88](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/camera/CameraController.kt:88), [지원 목록: SettingsActivity.kt:457](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/settings/SettingsActivity.kt:457).
5. **Detect Interval은 최대 처리량 시험에서 0으로 유지한다.** 간격을 늘리는 것은 발열·전력 제어 수단이지 FPS 향상 수단이 아니다. 지금처럼 처리 시간이 긴 경우 짧은 interval 변경은 효과가 없을 수도 있다.
6. 동일 기능·설정·모델의 release 빌드를 별도 비교할 수 있다. 빌드 모드 자체의 효과는 실측해야 하며, release에서도 현재 진단 설정과 일부 Logcat은 남는다. 기능 OFF 설정을 대신하지 않는다.

confidence를 올리거나 Max Candidates를 줄이거나 Center ROI를 켜는 방법은 권장 초기 성능 대책에 포함하지 않는다. 추론 그래프의 562ms를 줄이지 못하면서 검출 누락·시야 축소를 유발할 수 있다.

## 3. 코드 수정이 필요한 항목

### 측정과 진단 경로 정리

- Bitmap 생성, 전체 회전, Letterbox, `getPixels`, RGB 정규화/NCHW 작성, 입력·출력 통계, PNG snapshot, pipeline finally, MainActivity 부수 작업, UI 제출→draw를 각각 측정한다. `bitmapConversionMs`, `resizeMs`, `inputBufferMs`, `outputCopyMs`, `candidateScanMs`, `nmsMs`는 이미 있으므로 부족한 구간만 추가한다.
- 가벼운 성능 지표와 텐서 correctness 진단을 분리한다. 통계·checksum은 기본 OFF, 필요 시 제한 프레임에만 수행한다. 진단 OFF에서는 로그 함수에 전달할 문자열도 만들지 않도록 caller에서 조건을 검사한다.
- production에 필요 없는 경고 Logcat을 설정 또는 빌드 조건으로 제한한다. 상세 raw confidence 전체 정렬과 매 프레임 진단 전용 threshold/좌표 범위 계산도 필요한 경우에만 한다. 성능 집계·실패 기록은 유지한다.
- PNG의 두 저장 조건이 겹칠 때 중복 압축을 제거하는 방안을 검토한다. 비동기 worker가 재사용 입력 Bitmap을 직접 읽게 바꾸면 안 된다. 기존 bounded queue, skip 정책, 소유권과 snapshot 안전성을 유지한다.
- 성능 파일 formatting을 writer로 옮길 수 있다. 행별 flush는 최근 수정에서 데이터 보존을 위해 도입된 것이므로 비용이 확인된 경우에만 시간/행 단위 batching을 검토한다. 순서, 정상 종료 drain, 오류 통지, dropped count를 보존하고 flush 사이 강제 종료 손실 범위를 명시한다. [PerformanceLogRecorder.kt:61](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/metrics/PerformanceLogRecorder.kt:61), [OrderedLogWriter.kt:25](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/logging/OrderedLogWriter.kt:25).

### 전처리 최적화

우선 Kotlin FLOAT32 전용 writer를 dtype별로 분리해 내부 반복마다 codec 분기를 하지 않게 하고, 동일 `/255.0f` 결과를 담은 256-entry lookup 또는 채널별 연속 쓰기를 비교한다. `FloatBuffer` bulk put 방식은 중간 FloatArray 및 추가 복사를 만들 수 있으므로 무조건 우수하다고 보지 않는다. 입력의 R 전체→G 전체→B 전체 순서, native byte order, padding 114/255를 유지한다.

다음 단계에서는 native/JNI·SIMD 경로로 RGBA 읽기, 정규화 및 NCHW 작성의 중간 복사를 줄일 수 있다. 완전한 fused resize/rotation 경로는 기존 bilinear sampling과 rounding을 달리할 수 있으므로 위험도가 더 높다. 먼저 Bitmap→tensor만 최적화한 뒤, 전체 프레임 회전과 Letterbox를 하나의 변환으로 결합하는 단계로 확장하는 편이 검증하기 쉽다.

직접 ImageProxy plane을 읽거나 YUV 출력으로 전환할 경우 rowStride/pixelStride, cropRect 처리 범위, 회전, 실제 채널 순서를 그대로 검증해야 한다. 이전의 잘못된 raw plane 복사 방식으로 돌아가지 않는다. 임의 RGB/BGR 뒤집기, proxy crop의 추가 적용, ROI 축소는 제외한다. 네이티브 경로가 `ImageProxy.close()` 이후 plane을 읽지 않도록 프레임 소유권을 유지한다.

출력은 raw ByteBuffer에서 축에 맞게 직접 score/좌표를 읽어 `outputData` 전체 복사를 생략할 수 있다. FLOAT32 fast path와 INT8/UINT8 dequantization을 구분하고 기존 best-class 선택, finite 검증, confidence 정렬, Max Candidates 및 **같은 클래스끼리 NMS**의 순서와 의미를 유지한다. 이 최적화는 `outputCopyMs`가 실제로 의미 있는 비중일 때 진행한다.

### GPU와 NNAPI

**현재 LiteRT 2.1.1의 Interpreter 경로에 예전 `GpuDelegate` 코드 몇 줄을 추가하는 계획은 충분하지 않다.** 실제 AAR에는 CompiledModel 및 OpenCL accelerator가 있지만 `org.tensorflow.lite.gpu.GpuDelegate`/NNAPI delegate 클래스는 없고, 공식 v2.1.0 릴리스는 v2.1.0+ Maven의 Interpreter API를 CPU only로 명시한다. GPU는 현재 런타임의 `CompiledModel`/`TensorBuffer` 경로를 우선 검증하거나, Interpreter delegate를 지원하는 별도 호환 런타임·패키지 조합을 명시적으로 선정해야 한다. 단순 버전 혼합은 피한다. [공식 릴리스](https://github.com/google-ai-edge/LiteRT/releases/tag/v2.1.0), [공식 마이그레이션 안내](https://developers.google.com/edge/litert/migration).

현재 Interpreter는 MainActivity `onCreate`에서 만들고 CameraX의 단일 executor에서 실행하며, 종료는 analyzer 종료를 기다린 뒤 Dispatchers.IO에서 close한다. legacy GPU delegate 경로를 택한다면 생성·실행·해제를 동일한 소유 스레드/EGL 컨텍스트에서 수행하도록 lifecycle을 조정해야 한다. CompiledModel 경로는 선택한 API의 스레드·buffer 규약에 맞춘다. 기존 CameraX `KEEP_ONLY_LATEST`, try/finally의 proxy close, Bitmap recycle, 안전한 analyzer shutdown 순서를 보존한다. [MainActivity.kt:209](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/MainActivity.kt:209), [종료: MainActivity.kt:1300](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/MainActivity.kt:1300), [GPU delegate 스레드 제약](https://developers.google.com/edge/litert/android/gpu).

GPU 비교에서는 353개 연산의 실제 지원 여부와 partition/CPU fallback을 기록한다. TRANSPOSE, RESHAPE, BATCH_MATMUL, SLICE 및 연산 version/shape를 점검한다. 연산 이름만으로 지원·미지원을 확정하지 않는다. 지원되지 않는 연산이 CPU/GPU 분할을 만들면 동기화·복사 비용으로 CPU보다 느려질 수 있다. 모델 단독 시간 외에 업로드/다운로드, 전처리, 실제 overlay age와 녹화 경쟁까지 평가한다. [공식 GPU 성능·분할 실행 설명](https://developers.google.com/edge/litert/performance/gpu).

NNAPI는 단말·driver가 지원하는 경우 비교 실험 후보가 될 수 있지만 현재 코드와 런타임에 연결되어 있지 않다. Android 15부터 deprecated이며 향후 CPU backend가 사용되는 기기가 많아질 수 있어 장기 개선의 우선 경로로 권장하지 않는다. NNAPI ON이 실제 NPU 실행을 의미하지 않으므로 실제 accelerator, CPU fallback, partition을 확인해야 한다. NPU가 필요하면 SoC별 LiteRT accelerator 지원을 별도로 평가한다. [Android 공식 NNAPI 안내](https://developer.android.com/ndk/guides/neuralnetworks).

### 10클래스에서도 재사용할 구조

전처리·buffer 재사용·성능 계측·backend 비교는 클래스 수와 독립적으로 재사용한다. 현재 output parser는 `4 + labels.size`로 채널 축을 판별하고 buffer 크기는 tensor에서 읽으므로 10클래스에서는 14채널로 확장할 수 있다. 다만 `ModelConfig.CLASS_LABELS` 일치 검사, labels asset, overlay/tuning/warning 등록은 별도 갱신이 필요하다. 기존 9개 ID와 매핑을 유지하고 새 클래스 ID를 모델 metadata와 대조한다. [ModelConfig.kt:4](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/detector/ModelConfig.kt:4), [출력 계약: YoloTensorContract.kt:26](/D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/detector/YoloTensorContract.kt:26).

모델 descriptor에 hash, 입력 크기/layout/dtype/quantization, 라벨 순서, 출력 축 및 normalized xywh 계약을 묶고 backend 실행만 교체할 수 있게 하면 재사용이 쉽다. 클래스 수가 9→10으로 늘었다고 전체 지연이 10/9배가 된다고 추정하지 않는다. backbone과 학습/export 변경을 별도 측정한다.

## 4. 모델 변환이 필요한 항목

| 변환 후보 | 현재 앱 호환성/필요 작업 | 검증 대상 |
|---|---|---|
| 같은 9클래스 가중치의 FP16 weights, 640 | FLOAT32 I/O이면 현 codec 유지 가능. 실제 FLOAT16 I/O이면 codec 확장 필요 | CPU/GPU 실행 precision, dequantize/변환 비용, 출력 차이 |
| 같은 가중치의 INT8, 640 | per-tensor INT8/UINT8 I/O codec은 이미 있음. 정규화·scale·zeroPoint 계약 확인 필요 | 실제 INT8 연산 coverage, float fallback, calibration 및 소형 객체 recall |
| 같은 가중치의 FLOAT32, 512 | 현재 640 고정 require에 걸림. `MODEL_INPUT_SIZE`, 입력 destination 및 테스트를 tensor 크기 기준으로 일반화해야 함 | box normalization=1/512, 출력 candidate 수, 원거리/소형 객체 정확도 |
| 같은 가중치의 NHWC export | writer는 NHWC 지원. 정확히 같은 9클래스 가중치로 새 export 필요 | 불필요한 경계 transpose 감소, RGB 계약·raw output 동등성 |
| 더 작은 backbone/architecture | 단순 정밀도 변환을 넘는 재학습·모델 교체 | 전체 정확도, 특히 희소 클래스·위험 객체 recall |

FP16은 weight 저장량을 줄이고 GPU FP16 실행에 적합할 수 있다. **CPU에서는 기본적으로 FP32로 되돌려 실행할 수 있어 지연 감소를 보장하지 않는다.** FP16 weights와 FLOAT16 입력 tensor는 다른 선택이다. [공식 양자화 문서](https://developers.google.com/edge/litert/conversion/tensorflow/quantization/post_training_quantization).

INT8은 실제 정수 연산 경로에서 CPU/NPU 계산량·대역폭 절감 후보이다. calibration에는 앱과 같은 RGB/정규화/Letterbox 및 입력 layout을 적용한 대표 데이터를 사용한다. 9개 클래스, 야외·그늘·역광·야간, 작은 볼라드·표지판·원거리 사람/차량, 빈 장면과 어려운 오탐 장면을 포함한다. calibration과 최종 holdout 평가는 분리한다. PTQ 정확도 저하가 허용 범위를 넘으면 QAT를 검토한다. 코드가 I/O INT8을 지원한다는 사실만으로 그래프 전체 INT8 가속을 증명하지 못한다.

legacy GPU delegate의 INT8 모델 지원은 GPU가 INT8로 계산한다는 뜻이 아니다. 해당 경로는 float view로 실행하고 정수 I/O를 CPU에서 변환할 수 있어 추가 비용이 생긴다. GPU 후보는 FLOAT32/FP16과 float I/O를 먼저 비교한다. [공식 GPU 양자화 실행 설명](https://developers.google.com/edge/litert/performance/gpu).

512는 640 대비 입력 픽셀 수가 `(512/640)^2=0.64`, 즉 36% 적다. 현재와 같은 stride 8/16/32 head라면 candidate 수는 8400→5376이 예상되지만 실제 export tensor로 확인한다. 이 감소 비율을 그대로 inference ms나 FPS에 적용할 수 없다. 현재 모델에는 attention/BATCH_MATMUL과 고정 overhead도 있다. 작은 객체의 모델 입력상 선형 크기는 80%로 줄어드므로 놓칠 위험이 특히 크다. 모델 input만 `resizeInput`으로 바꾸기보다 원본 학습 가중치에서 512 고정 export를 만들어 graph 상수·decode·좌표 단위를 함께 확인한다.

9클래스와 차기 10클래스 각각에서 같은 원본 가중치를 기준으로 FLOAT32/FP16/INT8 및 640/512를 만든다. precision, 해상도, 클래스 수, backbone을 한 번에 바꾸면 성능·정확도 변화 원인을 분리하기 어렵다. 원본 학습 가중치와 대표 데이터의 제공 여부는 이번 저장소 검사만으로 확정하지 않았다.

## 5. 예상 개선 효과와 정확도 영향

| 항목 | 기대하는 효과 | 정확도·동작 영향 | 상태 |
|---|---|---|---|
| 진단/PNG/녹화 OFF | 동기 계산·복사와 비동기 자원 경쟁 감소 | 동일 입력의 모델 판정은 유지. 처리 간격 변화로 실시간 temporal/motion/경고 타이밍은 달라질 수 있음 | 개선 폭 실측 필요 |
| thread 최적값 선택 | CPU run 평균/P95 및 장시간 처리량 최적화 | 병렬 부동소수점 순서 차이는 비교. 검출 정책 유지 | 최적 thread 실측 필요 |
| `setUseXNNPACK(true)` 명시 | 설정 의도 고정·ON/OFF 비교 가능 | 기본값과 같다면 실행 변화 없음 | 이미 기본 ON; 추가 개선 근거 없음 |
| 카메라 해상도 낮춤 | 원본 변환·회전·메모리 부담 감소 후보 | Preview 및 미세 객체 정보 손실 가능 | 실측·정확도 평가 필요 |
| writer/출력 복사 최적화 | 해당 CPU 구간과 대역폭 감소 후보 | 입력 byte와 기존 후처리 결과 동등성 목표 | 단계 비용 확인 후 적용 |
| fused 전처리 | 전체 회전 Bitmap/중간 복사 감소 후보 | 보간·좌표·채널 오류 위험 | correctness 검증 필수 |
| GPU | 큰 추론 구간의 가속 후보 | precision·driver·op 지원에 따른 차이 | 호환성·실측·장시간 테스트 필요 |
| FP16 | weight 저장량은 이론상 FP32의 절반, GPU 실행 후보 | score/box 미세 차이, threshold 경계 결과 변화 가능 | 전체 모델 크기·속도·정확도 실측 필요 |
| INT8 | weight 정수화 부분은 이론상 FP32의 1/4 저장량, 정수 계산 가속 후보 | calibration/quantization 오차, bbox/score 및 소형·희소 클래스 영향 | coverage·정확도·속도 실측 필요 |
| 512 | 입력 픽셀 36% 감소 | 원거리/소형 객체 recall 저하 가능 | ms/FPS 감소율 확정 불가 |
| 작은 모델 | 계산량 감소 후보 | 표현력·모델 정확도 저하 가능 | 후순위 재학습·평가 |

추론 562ms가 그대로 남는다면 전처리만 개선해서 높은 실시간 FPS를 보장할 수 없다. 반대로 GPU로 추론을 줄여도 전처리·카메라 변환·MainActivity 부수 작업이 다음 병목이 될 수 있다. 여러 항목의 개선 효과는 자원 경쟁과 발열 때문에 단순 합산할 수 없다. 이 보고서는 특정 FPS 달성이나 “몇 배 개선”을 예측하지 않는다.

## 6. 우선 적용할 개선 작업

| 우선순위 | 작업 | 완료 판단 |
|---|---|---|
| P0 | 진단·PNG·녹화 OFF 기준선과 기존 필드 조건 재현 | 설정·모델 hash·실제 해상도·빌드·단말이 기록된 반복 측정 |
| P1 | CPU threads 1/2/4, 필요 시 6/8 비교 | 워밍업 및 장시간 평균/P95/FPS/발열을 기준으로 선택 |
| P1 | 측정 누락 구간과 op profiling 확보 | 실제 run 병목, 입력 writer/통계 비용, 프레임 간 부수 작업 비중 확인 |
| P2 | 진단 caller 조건·FLOAT32 writer·필요한 출력 복사 최적화 | golden 입력/출력·좌표·NMS·클래스 회귀 통과 및 측정 개선 |
| P2 | 현재 LiteRT의 GPU 실행 PoC | runtime/API 호환, op 지원·fallback, CPU 대비 전체 지연·정확도 확인 |
| P3 | 같은 가중치의 FP16·INT8·512·NHWC 비교 | 각 요인의 독립적인 속도·정확도 보고서 |
| P4 | 대규모 fused 전처리 또는 작은 architecture | 앞선 결과로 추가 필요성이 확인된 경우 |

562ms run이 주병목이므로 기준선이 확보된 뒤 GPU PoC를 작은 전처리 최적화와 함께 우선 검증할 가치가 있다. 전처리의 모든 소규모 개선이 끝날 때까지 GPU 평가를 미룰 필요는 없다. 단, 실험 순서와 비교군을 분리한다.

현재 코드의 정상 관리 방식은 유지한다: CameraX `KEEP_ONLY_LATEST`, 단일 analyzer의 동기 처리, 모든 경로의 `ImageProxy.close`, Bitmap 소유권·recycle, analyzer 종료 후 detector 해제, 최신 UI 갱신 병합, 기존 Letterbox scale/padding 역변환, normalized xywh, 같은 클래스 NMS, 클래스 ID→label 매핑. 프레임 큐 확대나 여러 프레임 동시 Interpreter 실행으로 숫자를 높이는 접근은 제외한다.

## 7. 개선 전후 비교 테스트 계획

### 비교 조건과 순서

모든 실행에서 단말/SoC/Android, APK commit·빌드 모드, 모델 SHA/metadata, 적용 설정, 실제 CameraX 해상도·회전, 배터리·전원 연결·화면 밝기·온도 조건, 실행 시간·성공/실패 수를 기록한다. confidence/NMS/Max Candidates와 필터·경고 정책은 고정한다. 첫 실행은 설정 적용 후 충분히 워밍업하고, 최초 초기화·첫 run과 정상 상태를 분리한다. 비교 순서를 교차하고 각 조건을 최소 3회 반복한다.

| 단계 | 대조군/실험군 | 목적 |
|---|---|---|
| A | 기존 필드 설정 vs 모든 무거운 진단 OFF | 진단이 섞인 현재 측정을 정상 기준선과 분리 |
| B | A의 OFF 기준선 + Logcat diagnostics만 ON / 상세 파일 debug만 ON / PNG만 ON / 녹화만 ON | 각 기능의 단독 비용. 이후 실제 조합도 비교해 상호작용 확인 |
| C | 진단 OFF에서 CPU thread 변경만 | thread 최적값 선택 |
| D | thread 고정, 요청 카메라 해상도만 변경 | Bitmap/회전 비용과 시야·정확도 변화 |
| E | 동일 모델에서 코드 최적화 하나씩, CPU vs GPU | 구현·backend 효과 분리 |
| F | 동일 가중치에서 precision 또는 입력 크기 하나씩 변경 | 모델 변환의 속도·정확도 tradeoff |
| G | 선택 후보로 30분 이상 반복 | 열 throttling, 메모리/GC, writer 누락, 안정성 |

기본 비교에서는 Performance Log ON을 모든 조건에 동일하게 유지한다. 별도 Performance Log OFF 대조를 하되, 현재 앱에서 파일 로그가 꺼진 경우의 정밀 지속 FPS는 외부 계측 또는 추후 가벼운 계측으로 확보한다. 영상이 필요한 경우 성능 기록용 실행과 짧은 시각 검증용 실행을 나눈다. 촬영 경로가 달라지는 live 시험만으로 모델 정확도를 확정하지 않는다.

### 반드시 수집할 지표

- 각 단계 평균·P50·P95: camera→analyzer(신뢰 가능 시), Bitmap 생성, 회전, crop, Letterbox, pixels 복사, tensor 작성, 진단 통계, run, output parsing, NMS, 정책/모션, snapshot, analyzer 전체 점유, UI 대기·draw.
- 실제 성공 추론/pipeline FPS=`successes/측정 초`, analyzer FPS, 실패 수·실패 단계. 최신 `inferenceFps` 소수 필드를 사용하고 정수 `Average FPS`만 비교하지 않는다. 정상 0건은 성공에 포함한다.
- 실제 표시된 성공 프레임의 capture→overlay draw 평균/P95와 sample 수. `captureTimestampReliable=false`의 -1 값은 평균에 넣지 않고 별도로 센다.
- frameId/sessionId로 완료·draw를 연결해 UI 대기와 표시되지 않은 프레임을 분리한다. pipeline 표본과 overlay 표본의 개수를 함께 보고한다.
- 메모리 peak/PSS, allocation/GC pause, CPU 이용률·가능한 주파수/thermal 상태, 녹화 중 GPU 경쟁, 파일 writer dropped 수/오류, 배터리·전력 지표(측정 가능한 범위).
- 초기 1~5분과 마지막 5분을 나눠 장시간 성능 저하를 확인한다. 앱 explicit skip과 CameraX 내부 폐기 프레임을 혼동하지 않는다.

모델 단독 benchmark는 같은 Android runtime/모델/threads로 실행하고 warmup·steady-state·메모리 및 op profiling을 수집한다. `benchmark_model`의 XNNPACK/GPU/profiling flag는 선택한 binary의 도움말로 확인한다. 별도 benchmark는 앱 preprocessing/CameraX/UI를 제외하므로 그 숫자를 앱 FPS로 보고하지 않는다. profiler를 켠 실행과 최종 성능 실행도 분리한다. [공식 benchmark 안내](https://developers.google.com/edge/litert/models/measurement).

### 정확성과 회귀 검증

1. **동일 모델의 코드 최적화**: 기존 `YoloTensorContractTest`, `YoloResizeTransformTest`, `DetectionDebugFrameTest`, `DetectorGoldenTest`, analyzer shutdown 및 로그 회귀를 실행한다. 같은 최종 Bitmap→tensor 경로는 가능하면 input byte/SHA가 같아야 한다. raw output은 기존 FLOAT32 golden 허용오차를 출발점으로 비교하고 최종 label/box/confidence/NMS를 확인한다.
2. **새 resize/native 경로**: 세로·가로, 회전 0/90/180/270, 홀수 크기, row padding/stride, 전체 프레임 및 기존 ROI 옵션을 검사한다. 실제 letterbox의 정수 scaled size와 L/T/R/B padding, RGB114, ROI→회전 프레임→FIT_CENTER overlay 좌표를 검증한다. 보간 구현으로 byte가 다르면 차이를 기록하고 정확도 평가로 승인 여부를 판단한다.
3. **backend/precision/해상도 변경**: FLOAT32 baseline과 동일 원본 이미지에서 비교한다. 512는 이미 640로 줄인 PNG를 재축소해 평가하지 않고 원본에서 직접 Letterbox한다. FP16/INT8은 input/output checksum 동등성을 요구하지 않는다. quantization/layout을 해석해 box·score·클래스와 최종 결과를 비교한다.
4. **정확도 데이터셋**: annotation이 있는 별도 holdout에서 클래스별 precision/recall/AP, bbox IoU, 거리·객체 크기별 recall, 오탐/미탐 수를 측정한다. 특히 bollard, 작은 stop sign, 원거리 사람·차량과 드문 클래스의 감소가 전체 평균에 가려지지 않게 한다. 동일 threshold/NMS를 사용하고 최적화 손실을 threshold 변경으로 숨기지 않는다.
5. **실시간 동작**: 속도 변화가 motion sample 간격, temporal confirmation, stale clear, cooldown 및 TTS/beep/vibration에 미치는 영향을 확인한다. latency 감소로 경고 시점이 좋아지는지, 박스가 실제 피사체에 맞는지 확인한다. 화면 draw 이벤트는 물리 디스플레이 scanout 또는 카메라→광자 지연과 같지 않으므로 875ms 지표의 범위를 명시한다.
6. **차기 10클래스**: output 14채널 및 metadata·라벨 순서, 기존 9개 클래스 ID 보존, 새 클래스 overlay/warning 등록, 모델 변경 후 cache/buffer 재초기화, 같은 backend 및 전처리 회귀를 다시 실행한다.

채택 기준은 같은 조건의 반복 측정에서 평균/P95 및 실제 overlay 지연이 개선되고 장시간 안정성이 유지되며, 클래스별 정확도와 위험 객체 recall이 사전에 정한 허용 범위 안에 있는 것이다. 현재 요청에는 정확도 허용 손실이나 목표 지연이 지정되어 있지 않아 임의 기준을 만들지 않았다. **개선 후 FPS, 각 진단 기능의 ms 비용, GPU 위임률, 최적 thread, 변환 모델의 정확도 손실은 모두 실제 측정 후 확정할 항목이다.**
