# YOLO 추론 최적화 — 2026-10-14

CPU 경로의 tensor I/O와 임시 할당을 최적화했다. 모델·클래스·탐지 기준과 기존 설정 저장 형식은 유지했다.
GPU는 현재 의존성의 Interpreter API에서 지원되지 않아 추가하지 않았다.
실제 Android 기기가 연결되어 있지 않으므로 추론 속도 개선 수치와 실제 탐지 결과 일치를 아직 보고할 수 없다.

## 1. 기존 구조와 소스 근거

| 항목 | 확인 결과 | 근거 |
|---|---|---|
| 초기화 | Activity의 main thread에서 모델 mmap → Interpreter 생성 → tensor 계약 검증 → 버퍼 할당. 프레임마다 재생성하지 않음 | `MainActivity.kt:209`, `detector/ModelAssets.kt:13`, `detector/VisionStyleYoloDetector.kt:81` |
| XNNPACK | 기존 앱은 옵션을 명시하지 않았지만 **현재 2.1.1 AAR의 `InterpreterApi.Options.getUseXNNPACK()`은 미설정 시 true**. `NativeInterpreterWrapper.init()`은 이 값과 `getNumThreads()`를 native `createInterpreter()`에 전달. AAR `classes.jar`를 javap로 직접 확인 | `app/build.gradle.kts`, `VisionStyleYoloDetector.kt:88` |
| 실제 XNNPACK 적용 | 요청값과 Interpreter 생성 성공은 확인 가능. 지원 연산의 delegate partition·활성 worker 개수는 public Java API에서 확인 불가. 실제 기기 native 로그/프로파일링 필요. 요청만으로 모든 연산이 delegate 처리됐다고 표현하지 않음 | 새 `InferenceExecutionInfo.kt`의 `delegateVerification=UNAVAILABLE_JAVA_API` |
| CPU 스레드 | `AppSettings.yolo.interpreterThreadCount` → `DetectionConfig` → detector constructor → `setNumThreads()`로 전달. 기존 1~16 설정에서 2/4 비교 가능 | `settings/AppSettings.kt:171`, `MainActivity.kt:217`, `VisionStyleYoloDetector.kt:90` |
| GPU | 기존 GPU 의존성·Delegate 없음. LiteRT 2.1.1 AAR의 Java 클래스에 `GpuDelegate`/`CompatibilityList` 없음. AAR 내부 OpenCL accelerator는 CompiledModel 경로용이며 현재 Interpreter에서 사용할 수 있다는 근거가 아님 | 아래 공식 릴리스 문서 및 실제 AAR 검사 |
| NNAPI | 앱에 NNAPI 설정·Delegate 코드 없음. 이번 변경에서 추가하지 않음 | 소스 전체 delegate/NNAPI 검색 |
| 입력/출력 재사용 | direct ByteBuffer 2개, pixels IntArray, 출력 FloatArray 행, Letterbox Bitmap/Canvas/Paint/RectF, resize transform 재사용이 이미 구현됨 | `VisionStyleYoloDetector.kt` 초기화 및 `detect()` |
| 프레임별 할당 | CameraX `toBitmap()`과 회전 Bitmap/Matrix, ML Kit용 scaled Bitmap, 선택적 ROI Bitmap, 탐지·정렬/NMS 결과. 이전 parser는 confidence 통과 후보마다 normalized RectF도 생성 | `camera/ImageProxyExt.kt`, `pipeline/DetectionPipeline.kt:67`, `MainActivity.kt:1218`, `VisionStyleYoloDetector.kt` |
| CameraX/TFLite 실행 | 단일 analyzer executor에서 변환·동기 pipeline·YOLO `Interpreter.run()` 실행. KEEP_ONLY_LATEST로 backlog 제한. 별도 YOLO coroutine pool 없음 | `camera/CameraController.kt:63`, `:103`, `MainActivity.kt:452`, `:490` |
| ML Kit 영향 | 기본 1500ms마다 640×360 Bitmap 생성은 analyzer에서 수행. ML Kit 비동기 작업은 captureWork/Dispatchers.IO와 SDK worker에서 YOLO와 겹칠 수 있어 CPU/메모리 대역폭 경쟁 가능. 영향 크기는 기기 측정 필요 | `MainActivity.kt:1205`, `mlkit/MlKitObjectDetector.kt:35` |
| 리소스 해제 | CameraX unbind 후 analyzer drain을 기다리고 IO에서 detector.close(). ML Kit 작업도 drain 후 close. GPU가 없으므로 GPU thread affinity 문제는 현 경로에 해당하지 않음 | `MainActivity.kt:1306`, `camera/AnalyzerShutdown.kt`, `VisionStyleYoloDetector.kt:679` |

GPU 판단 근거: [LiteRT 2.1.0 공식 릴리스](https://github.com/google-ai-edge/LiteRT/releases/tag/v2.1.0)는 Maven v2.1.0+의 Interpreter API가 CPU only임을 명시한다.
[GPU Interpreter 가이드](https://developers.google.com/edge/litert/android/gpu)는 별도의 GPU/GPU API 패키지와 동일 스레드 생성·실행을 요구한다.
Google Maven의 [GPU 패키지 메타데이터](https://dl.google.com/dl/android/maven2/com/google/ai/edge/litert/litert-gpu/maven-metadata.xml),
[GPU API 메타데이터](https://dl.google.com/dl/android/maven2/com/google/ai/edge/litert/litert-gpu-api/maven-metadata.xml)도 조회했으며 2.1.1 버전은 없다.
따라서 서로 다른 버전의 native 패키지를 섞거나 런타임을 변경해 GPU를 강제로 연결하지 않았다.
이는 **현재 소프트웨어 경로의 지원 제한**이며 사용자 휴대폰의 GPU 하드웨어가 미지원이라고 판단한 것은 아니다.

## 2. 병목과 적용한 최적화

사용자 제공 일반 구간 수치는 inference 약 373ms / 전체 약 449ms다. 추론이 주요 비용이며,
입출력·Bitmap·ML Kit 비용은 별도 관찰 대상이다. 원본 필드 로그는 저장소에 없어 P95나 절전 진입 시점을 재계산하지 않았다.
해당 필드테스트 마지막 약 23초는 정상 구간 비교에서 제외한다.

- XNNPACK을 `setUseXNNPACK(true)`로 명시했다. 기존 기본값도 true이므로 이 변경 자체를 속도 개선으로 주장하지 않는다.
- FLOAT32 입력은 **재사용 FloatBuffer view + 256개 RGB 정규화 lookup**으로 쓴다. 기존 `channel / 255.0f`의 결과를 그대로 저장하며, 반올림이 달라질 수 있는 역수 곱셈을 사용하지 않는다. 추가 전체 tensor 배열은 만들지 않는다.
- FLOAT32 channels-first 출력은 기존 reusable FloatArray 행으로 bulk get한다. 후보 우선 출력 및 INT8/UINT8은 기존 codec 경로를 유지한다.
- 후보의 normalized RectF를 같은 연산 순서의 scalar 좌표로 바꿨다. clipping, 좌표 역변환, area, Confidence/NMS/maxCandidates는 유지했다.
- tensor 진단용 threshold 배열은 상수로 공유하고, 진단을 수행하지 않는 프레임에서는 threshold count 계산·배열 생성을 생략한다. 원시 좌표 범위·탐지 진단 정보는 유지했다.
- Interpreter 생성 후 계약·Bitmap·metadata 초기화 실패 시 리소스를 정리한다. `detect/runSelfTest/close`를 직렬화하고 close를 멱등 처리해 실행 중 해제 및 중복 해제를 방지한다.

통합 `best.tflite`, 9개 클래스 매핑, Letterbox RGB114 padding, Confidence 0.2/NMS 0.45 기본값,
Geometry Filter, ML Kit, overlay, warning/TTS, 녹화·시스템 진단 및 저장 설정은 변경하지 않았다.
모델 SHA256: `32a44bc7432ef2d7ca5de802dd8e714a26568aea16566bad3fd7fcd2b85bcee2`.

## 3. 로그와 설정

기존 분리된 설정 화면의 YOLO 카테고리와 CPU 스레드 필드를 재사용한다. 지원하지 않는 GPU 선택 항목은 만들지 않았다.
CPU/XNNPACK 요청과 GPU 미지원 이유를 해당 화면에 설명한다. 저장 키·스키마·프리셋 형식은 유지한다.

Header, FRAME, ERROR, 구간·누적 summary에 요청 backend, 실제 선택 backend, CPU thread 옵션,
XNNPACK 요청값, 초기화 결과, delegate 검증 가능 여부, 마지막 추론 결과, 오류·CPU fallback 횟수와 runtimeVersion을 기록한다.
오류/fallback 카운터는 detector 생성 이후의 누적값이다. CPU-only 경로의 fallback=0을 GPU 성공으로 해석하면 안 된다.
성능 세션 시작 후 발생한 마지막 추론 오류도 최종 summary의 execution 상태에 반영한다.

기존 시간·FPS 의미는 유지하고 새 값을 추가했다.

| 로그 값 | 범위 |
|---|---|
| `inference` / inferenceAvgMs / inferenceP95Ms | `Interpreter.run()` |
| `pipeline` / pipelineAvgMs / pipelineP95Ms | 기존 `DetectionPipeline.process()`; 카메라 Bitmap 변환·ML Kit input 생성 제외 |
| `totalProcessing` / totalProcessingAvgMs / totalProcessingP95Ms | analyzer 수신부터 pipeline 성공 완료; Bitmap 변환·ML Kit input 생성 포함, warning 출력·UI draw 제외 |
| `inferenceFps` | 성공한 pipeline completion / 실제 구간 시간 |

P95는 실제 유효 frame sample의 nearest rank다. 모르는 시간은 -1/N/A로 두고 평균에서 제외한다.
native partition 적용을 확인하려면 기기에서 Logcat의 XNNPACK delegate 생성/연산 위임 메시지와 프로파일링을 함께 확인한다.

`tools/analyze_performance_logs.py`는 최종 성능 로그의 실제 FRAME에서 정상 구간을 다시 집계한다.
30초 평균을 다시 평균 내거나 평균에서 P95를 추정하지 않는다. 완료 monotonic timestamp로 프레임을 선택하며,
로그의 종료 시간은 CUMULATIVE_SUMMARY의 endSeconds를 사용한다. 로그가 누락됐으면 `frameLogComplete=false`로 결과의 제한을 표시한다.

```powershell
# 해당 2026-10-14 기존 필드 로그에만 마지막 23초 제외
python tools/analyze_performance_logs.py baseline.txt --exclude-tail-seconds 23

# 새로운 정상 CPU 2/4 비교 로그 (절전 저하가 없으면 tail을 제외하지 않음)
python tools/analyze_performance_logs.py cpu2.txt cpu4.txt
```

## 4. 변경한 파일

| 파일 | 변경 |
|---|---|
| `detector/VisionStyleYoloDetector.kt`, `detector/YoloTensorContract.kt` | CPU tensor I/O, 임시 객체 제거, 명시 XNNPACK, 수명 관리 |
| `detector/InferenceExecutionInfo.kt`, `detector/DetectionResult.kt` | 불변 실행 상태 snapshot / ObjectDetector 조회 API |
| `MainActivity.kt` | 성능 기록에 실행 상태와 analyzer 전체 처리 시간 연결 |
| `metrics/PerformanceLogFormatter.kt`, `PerformanceIntervalCollector.kt`, `PerformanceLogSession.kt`, `PerformanceLogRecorder.kt` | 기존 형식을 확장하여 실행 상태·전체 처리 평균/P95·오류 기록 |
| `settings/SettingsActivity.kt` | 기존 YOLO 화면에 CPU 비교/GPU 지원 제한 안내 한 항목 |
| `src/test/.../YoloTensorContractTest.kt`, `PerformanceIntervalCollectorTest.kt`, `PerformanceLogFormatterTest.kt` | 바이트 동일성·출력 축/codec/bit 보존·통계·상태 로그 검증 |
| `src/androidTest/.../DetectorCpuOptimizationTest.kt` | 실제 모델 원본 writer/parser와 CPU 2/4 결과 비교 및 반복 생성/추론/해제 |
| `tools/analyze_performance_logs.py`, `tools/test_analyze_performance_logs.py` | 절전 tail 제외 및 비교 집계, 자체 테스트 |
| 이 문서 | 분석 근거·검증 범위·기기 테스트 절차 |

시작 시 이미 수정돼 있던 SettingsActivity의 분리 화면 작업, `docs/in_app_detection_settings.md`,
settings instrumentation tests를 보존했다. SettingsActivity에는 위 안내만 추가했다.

## 5. 검증 결과와 제한

- `:app:assembleDebug`: 성공.
- `:app:testDebugUnitTest`: **101개 통과, failure/error 0**. 모든 채널 값, NCHW/NHWC, byte order, 프레임 버퍼 재사용에서 기존/최적화 입력 bytes 동일. 출력 축·quantized fallback·NaN/Inf/signed-zero bit, 전체 처리 P95 및 오류 상태 기록을 확인했다.
- `:app:assembleDebugAndroidTest`: 성공. 새 실제 모델 비교 및 반복 수명 테스트가 테스트 APK에 포함된다.
- `:app:lintDebug`: 성공, 0 errors / 162 warnings. 프로젝트의 lint warning은 남아 있다.
- Python 로그 집계 테스트: **3개 통과** (tail 경계, 누락 로그, 미확인 latency/invalid window). 테스트 fixture 수치는 실제 성능 측정 결과가 아니다.
- `git diff --check`: 통과. model/labels/config 기준·저장 파일 변경 없음.
- ADB server를 실행해 확인한 `adb devices` 목록이 비어 있었다. CPU native 초기화·추론, 기존/변경 탐지 비교, 성능 로그의 실제 MediaStore 저장, 반복 설정 변경의 메모리 관찰은 **기기에서 아직 실행하지 못했다**.
- GPU 적용·GPU 실패의 CPU fallback 테스트는 현재 지원 경로가 없어 적용 대상이 아니다. 새 코드에 GPU 경로는 없다.

## 6. 기기 비교 설정과 실행

1. 기존 필드테스트와 같은 설정을 복원하고, `best.tflite`, 640×640, Letterbox, Confidence 0.2, NMS 0.45,
   ROI·카메라 해상도·maxCandidates·Geometry·ML Kit·경고·overlay 설정을 고정한다. **스레드만 4 → 2 → 4**로 바꿔 비교한다.
2. 각 설정에서 동일 장면/동선으로 충분한 길이의 성능 로그를 저장한다. 배터리 상태·절전 모드·초기 온도·주변 조건을 맞춘다.
   상세 후보 로그/PNG 저장은 비교 동안 동일하게 끄고 System Performance Diagnostics는 켜서 열/CPU 상태를 함께 기록한다.
3. inference 및 기존 pipeline, 새 totalProcessing의 평균/P95, inferenceFps, ERROR와 execution 상태를 비교한다.
   절전 tail이 발생한 기존 필드 로그는 위 스크립트로 마지막 23초를 제외한다. 새 로그의 저하 시점이 다르면 실제 시점을 기준으로 제외 시간을 지정한다.
4. ML Kit CPU 경쟁은 **추가 진단용** ON/OFF 세션으로 측정한다. 기본/배포 설정은 ON을 유지한다.
5. 실제 볼라드·다른 클래스가 포함된 동일 이미지로 instrumentation 비교 테스트를 실행한다.

```powershell
./gradlew.bat :app:connectedDebugAndroidTest `
  -Pandroid.testInstrumentationRunnerArguments.class=com.samin.objectdetection.detector.DetectorCpuOptimizationTest `
  -Pandroid.testInstrumentationRunnerArguments.selfTestImage=/sdcard/Android/data/com.samin.objectdetection/files/known.png
```

이미지 인자를 생략하면 생성한 portrait color control만 비교하므로 양성 객체의 정확도 검증은 대신하지 못한다.
테스트는 원본 writer의 input bytes, 원본 CPU 옵션의 전체 raw output(`atol=1e-5, rtol=1e-4`),
원본 box/NMS parser의 label·검출 개수·confidence(`1e-4`)·좌표(`0.1px`)를 비교한다.
또한 2/4 스레드를 번갈아 6회 생성하고 각각 두 프레임 처리·중복 close·close 이후 추론 거부를 확인한다.

6. 실제 설정 화면에서도 2/4 변경 → 적용 → camera 실행을 반복한다. Android Studio Memory Profiler 또는
   `adb shell dumpsys meminfo com.samin.objectdetection`로 안정화 후 native heap/PSS를 비교한다.
   일시적인 구/신 Activity 겹침과 GC 지연을 지속 누수로 판단하지 않는다. 새 instrumentation의 반복 close 검증만으로 누수 부재를 확정할 수 없다.

## 7. 다음 개선 후보

기기에서 먼저 CPU 2/4와 ML Kit 경쟁을 측정한다. CameraX 변환·회전 Bitmap의 별도 재사용은
stride·회전·RGB 정확도 및 비동기 ownership을 검증한 후 진행할 수 있다.
GPU는 별도 변경에서 현재 모델과 tensor 계약을 유지하는 CompiledModel 이식 또는 지원되는 Interpreter runtime 구성을 검토한다.
생성·추론·해제를 하나의 전용 스레드에 묶고, 실패 시 리소스를 정리한 새 CPU runtime으로 전환하며,
원본 raw output/9클래스 탐지 일치와 delegate partition을 실제 기기에서 검증한 뒤 선택 옵션을 도입해야 한다.
모델 양자화·입력 축소·threshold 조정은 정확도에 영향을 주므로 이번 범위에는 포함하지 않았다.
