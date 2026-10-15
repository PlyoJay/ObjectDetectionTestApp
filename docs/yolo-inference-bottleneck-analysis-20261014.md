# YOLO 추론 병목 분석 및 안전한 전처리 개선

분석일: 2026-10-14 (Asia/Seoul). 기준 커밋: `1c81211` (`perf: optimize YOLO CPU inference and enhance performance diagnostics`). 시작 시 작업 트리는 깨끗했다. 원격 최신 여부를 별도로 조회하지 않았으며 현재 로컬 HEAD를 분석했다.

**결론:** 연결된 SM-S921N의 네이티브 로그에서 XNNPACK 실제 위임을 확인했다. 기존 `xnnpackRequested=true`만으로 내린 결론이 아니다. 기존 scalar lookup 전처리는 이 기기의 단독 시험에서 기존 division writer보다 느렸다. 동일 입력 바이트를 유지하는 재사용 FloatArray + bulk FloatBuffer 쓰기를 적용했다. CPU 기본값은 4 threads / XNNPACK ON으로 유지했고 GPU/NNAPI 전환은 적용하지 않았다. 카메라 전체 파이프라인의 변경 후 FPS 향상은 아직 측정하지 않았다.

## 1. 증거 수준과 기존 실측

아래 수치는 **사용자가 제공한 기기 테스트 결과**이며 이번 작업에서 재현한 카메라 벤치마크가 아니다.

| 항목 | 최적화 이전 | A CPU 4 | B CPU 2 | C CPU 4 |
|---|---:|---:|---:|---:|
| FPS | 약 2.05 | 2.041 | 1.881 | 2.009 |
| 평균 추론 ms | 약 373 | 375.6 | 399.9 | 375.7 |
| 추론 P95 ms | 미제공 | 400 | 516 | 391 |
| 평균 전처리 ms | 약 63.8 | 73.7 | 83.7 | 79.2 |
| 평균 pipeline ms | 약 449 | 456.2 | 491.1 | 461.9 |
| 평균 totalProcessing ms | 미제공 | 473.8 | 513.7 | 481.1 |

추론이 pipeline의 약 81~82%를 차지한다(제공된 평균값의 비율). CPU 4가 CPU 2보다 유리하지만 ON 설정만으로 기존 추론보다 빨라졌다는 증거는 없다. 원본 로그, 진단 ON/OFF, 실제 카메라 해상도, 발열 상태가 제공되지 않았으므로 과거 전처리 증가 전체를 한 가지 원인으로 확정할 수 없다.

이번 검증 기기는 Galaxy S24 `SM-S921N`, Android 16/API 36, board platform `erd9945`이다. debug APK와 AndroidJUnitRunner에서 합성 입력 및 저장소의 `self_test/known.png` 양성 이미지를 사용했다. 실행 중 일부 시점에서 Thermal Status 0을 확인했으나 모든 측정 동안의 연속 발열 추적은 하지 않았다. JIT, 스케줄링, 기기 사용 상태, 카메라/ML Kit 유무가 필드 테스트와 다르므로 서로 다른 시험의 추론 ms를 직접 개선율로 계산하면 안 된다.

## 2. 현재 추론 구조와 실제 모델

`dependencyInsight --dependency litert --configuration debugRuntimeClasspath --offline` 및 캐시 AAR 검사 결과:

- 직접 실행 의존성은 `com.google.ai.edge.litert:litert:2.1.1`이다. 패키지 이름이 `org.tensorflow.lite`라는 이유로 별도 옛 TensorFlow Lite runtime을 사용하는 것은 아니다.
- 앱 runtime은 bundled `Interpreter`, `FROM_APPLICATION_ONLY` 기본 경로이다. ML Kit 의존성은 `com.google.mlkit:object-detection:17.0.2`, CameraX는 1.3.4이다.
- 실제 기기의 `TensorFlowLite.runtimeVersion()`은 `2.21.0`이었다. Maven 배포 버전 2.1.1과 내부 TFLite runtime 버전 2.21.0은 서로 다른 버전 체계다.
- AAR에는 `libLiteRt.so`와 `libLiteRtOpenClAccelerator.so`가 arm64-v8a/x86_64용으로 들어 있다. `CompiledModel`, `Accelerator.GPU`, `TensorBuffer`도 존재한다.

현재 바이너리를 `.venv/Scripts/python.exe tools/inspect_tflite.py app/src/main/assets/best.tflite`로 다시 검사했다. 기존 `docs/tflite-model-inspection.json`에는 과거 1클래스 모델 정보가 남아 있어 현재 모델 판단에 사용하지 않았다.

| 항목 | 현재 바이너리 검사 결과 |
|---|---|
| 모델 | `best.tflite`, 37,962,632 bytes |
| SHA-256 | `32a44bc7432ef2d7ca5de802dd8e714a26568aea16566bad3fd7fcd2b85bcee2` |
| 입력 | `[1,3,640,640]` NCHW FLOAT32, scale=0, zeroPoint=0 |
| 출력 | `[1,13,8400]` FLOAT32, scale=0, zeroPoint=0 |
| 입력 버퍼 | 4,915,200 bytes |
| 출력 버퍼 | 436,800 bytes |
| 클래스 | bollard, person, bicycle, car, motorcycle, bus, truck, stop sign, bench |
| 그래프 | YOLO11s Detect, 353 operators, 첫 연산 NCHW→NHWC TRANSPOSE |
| 출력 의미 | normalized xywh 4개 + 클래스 점수 9개, 추가 objectness/sigmoid 없음 |

처리 흐름:

```text
CameraX RGBA_8888 (KEEP_ONLY_LATEST, 단일 analyzer executor)
  -> ImageProxy.toBitmap()                 [CameraX 내부 YUV->RGBA 비용은 analyzer 이전일 수 있음]
  -> 전체 Bitmap 회전
  -> ML Kit용 640x360 resize/비동기 dispatch (간격 및 in-flight gate)
  -> DetectionPipeline의 선택적 center crop
  -> YOLO Letterbox (RGB 114 padding, filtered Canvas draw)
  -> Bitmap.getPixels(reused IntArray)
  -> RGB /255.0f, NCHW 입력 작성
  -> Interpreter.run(input ByteBuffer, output ByteBuffer)
  -> 재사용 FloatArray rows로 출력 bulk copy
  -> 클래스/confidence/좌표 파싱 -> size filter -> 최대 100 candidates -> class-aware NMS
  -> 원본 좌표 복원 -> Geometry/size/class/temporal 정책 -> motion/warning
  -> Overlay 및 TTS 등 제출
```

## 3. XNNPACK: 확인된 사실과 확인 한계

### 옵션과 실제 위임

캐시 AAR `classes.jar`를 `javap -c -p`로 검사했다. `InterpreterApi.Options.getUseXNNPACK()`은 미설정/null이면 true이다. 현재 코드는 원래 기본 ON인 옵션에 `setUseXNNPACK(true)`를 명시한 것이므로 그 자체는 실행 경로를 새로 빠르게 만들지 않는다. 별도 Java XNNPACK Delegate를 생성할 필요는 없다.

`NativeInterpreterWrapper`는 `getNumThreads()`와 `getUseXNNPACK()`을 `createInterpreter(... int, boolean, ...)`에 넘긴다. 공식 v2.1.1 JNI에서는 `OpResolverLazyDelegateProxy(... useXnnpack)`와 `InterpreterBuilder.SetNumThreads(num_threads)`로 전달하고, tensor allocation 과정에서 lazy delegate를 적용한다. false이면 resolver의 default delegate creators를 비운다. [공식 JNI 소스](https://github.com/google-ai-edge/LiteRT/blob/v2.1.1/tflite/java/src/main/native/nativeinterpreterwrapper_jni.cc), [resolver 소스](https://github.com/google-ai-edge/LiteRT/blob/v2.1.1/tflite/java/src/main/native/op_resolver_lazy_delegate_proxy.cc).

**이번 작업에서 확보한 기기 로그:**

```text
I tflite: Created TensorFlow Lite XNNPACK delegate for CPU.
I tflite: Replacing 351 out of 353 node(s) with delegate (TfLiteXNNPackDelegate) node,
          yielding 5 partitions for subgraph 0.
```

이 모델을 생성한 테스트 프로세스와 시간대를 맞춰 확인했으며 ON/OFF/ON 재생성 시험에서도 ON의 초기화 구간에 같은 위임 로그가 나타났다. 따라서 **이 검증 기기의 이 APK/model에서는 실제 XNNPACK 적용이 확인됐다.** 351/353은 노드 개수 비율이며 실행 시간 비율 또는 모든 기기에서의 적용 보장이 아니다. 2개 잔여 노드와 5개 분할의 비용은 per-op 프로파일이 있어야 판단할 수 있다.

Java의 공개 Interpreter/Tensor API에는 활성 delegate 목록, 위임 노드 수, XNNPACK worker 수를 조회하는 API가 없다. `delegateVerification=UNAVAILABLE_JAVA_API`는 그대로 유지했다. `appliedBackend=CPU`는 기본 CPU kernel과 XNNPACK 모두를 포함하므로 XNNPACK 미적용을 뜻하지 않는다. `cpuFallbackCount=0`도 XNNPACK 내부 미지원 연산 개수를 뜻하지 않는다.

### CPU thread 설정의 중요한 한계

`cpuThreads`는 받아들인 옵션 값이다. native builder에 전달된다는 사실만으로 XNNPACK이 그 수만큼 병렬 실행한다고 확정할 수 없다.

공식 v2.1.1 resolver에는 두 구현이 있다. **non-opaque `createXNNPackDelegate(TfLiteContext*)`는 기본 options로 delegate를 생성하고 `options.num_threads`에 context의 스레드 수를 대입하지 않는다.** 반면 opaque creator는 `num_threads`를 XNNPackSettings에 넣는다. 공개 소스의 빌드 분기 및 배포 AAR의 native build는 구분해야 한다. 이번 ON 시험에서 2/4 옵션의 시간이 거의 같았다는 관측은 worker 설정이 반영되지 않는 경로와 부합하지만, AAR의 모든 native build flag나 실제 worker 수를 직접 증명한 것은 아니다. thread CPU/native stack profiling이 추가로 필요하다. 별도 JNI delegate 교체 또는 runtime 업그레이드는 이번 수정에 포함하지 않았다.

### ON/OFF 단독 시험

`YoloBottleneckBenchmarkTest`에서 동일한 114/255 FLOAT32 입력, 모델/입출력 direct buffer 고정, Interpreter를 매 변형마다 새로 생성했다. 변형별 warm-up 3회 제외, 10회 측정; 순서는 4 ON/OFF/ON, 2 ON/OFF/ON이다. 아래는 **이번 기기의 첫 단독 시험**이다.

| 옵션 | run 평균 ms | nativeInvoke 평균 ms | run P95 ms |
|---|---:|---:|---:|
| CPU 4, ON (첫 회) | 551.746 | 551.313 | 553.222 |
| CPU 4, OFF | 272.439 | 272.034 | 276.272 |
| CPU 4, ON (재확인) | 551.828 | 551.411 | 553.787 |
| CPU 2, ON (첫 회) | 551.948 | 551.520 | 555.003 |
| CPU 2, OFF | 389.077 | 388.657 | 389.952 |
| CPU 2, ON (재확인) | 552.351 | 551.898 | 553.618 |

ON/OFF의 raw output은 절대 1e-5 + 상대 1e-4 오차 내에서 비교했다. **OFF가 이 단독 조건에서 더 빨랐으므로 반드시 실제 카메라 A/B 시험 대상으로 포함해야 한다.** 제공된 약 375ms와 이번 약 552ms는 조건이 다른 결과이며 직접 회귀로 판정하지 않는다. 기본값을 OFF로 바꾸지는 않았다. 다른 kernel의 rounding 때문에 confidence/NMS 경계에서 검출이 달라질 수 있고, 단일 합성 입력만으로 운영 정확성을 보장할 수 없기 때문이다. 양성 이미지 ON/OFF 회귀 시험도 추가했다.

생성자에 `useXnnpack: Boolean = true`를 마지막 선택 인자로 추가해 별도 debug harness에서 같은 detector 경로를 비교할 수 있다. 앱 설정 UI·저장 schema·기본 동작은 바꾸지 않았다. raw 옵션만 바꾼 마이크로벤치마크와 앱 전체 A/B는 별개의 시험이다.

## 4. 전처리 증가 원인과 적용한 최적화

### 확인한 병목

기존 lookup은 `/255.0f` 결과를 256개 캐시하지만 NCHW 입력의 각 채널마다 `direct FloatBuffer.put(float)`를 호출한다. 한 프레임당 1,228,800번 scalar buffer put이 발생한다. NCHW는 R/G/B 세 pass로 같은 pixels를 읽는다. 나눗셈 제거만으로 반복적인 direct-buffer 접근 비용을 해결하지 못한다.

이번 기기의 640x640 합성 RGB 입력에 대해 동일 direct buffer를 재사용하고, writer 순서를 회전하며 각 writer warm-up 10회 제외/30회 측정했다. 세 writer의 입력 bytes는 완전히 일치했다.

| writer | 첫 시험 평균 ms | P95 ms |
|---|---:|---:|
| 기존 division + ByteBuffer.putFloat | 55.822 | 55.891 |
| 직전 최적화 scalar lookup + FloatBuffer.put | 63.129 | 63.262 |
| 재사용 FloatArray + bulk FloatBuffer.put | 2.055 | 2.165 |

따라서 **scalar lookup의 회귀 가능성은 코드 추정에 더해 이 기기 단독 시험으로 확인했다.** 이 결과가 과거 63.8→73~79ms 증가 전체를 설명한다고 단정하지 않는다. 모델 resize, JIT, 상세 진단, 카메라 입력 크기/Bitmap 형식도 기존 로그 없이 배제할 수 없다. 첫 bulk 시험은 테스트 내부 구현이었고 최종 벤치마크는 적용된 실제 `RgbTensorWriter.fillFloat32Bulk`를 호출한다.

### 변경 내용과 비용

- detector 초기화 때 FLOAT32 입력 크기의 scratch FloatArray를 한 번 할당한다. 매 프레임 scratch에 RGB lookup 값을 작성하고 `FloatBuffer.put(scratch)` 한 번으로 direct buffer에 옮긴다.
- 추가 메모리는 4,915,200 bytes, 약 4.69MiB/Interpreter이다. 입력 scratch→direct buffer 복사는 추가되지만 실제 단독 시험에서 scalar 쓰기 비용보다 작았다. 추가 direct buffer, 프레임별 FloatArray, 새 FloatBuffer view는 만들지 않는다.
- 기존 direct input/output ByteBuffer, FloatBuffer views, pixels IntArray, output rows, Letterbox Bitmap/Canvas 재사용을 유지한다. 출력 channels-first FLOAT32 bulk copy는 이미 적용돼 있어 변경하지 않았다.
- `/255.0f` lookup 값을 그대로 사용한다. `* (1/255)` 변경, RGB/BGR 변경, NCHW/NHWC 변경, sampling/Letterbox/padding 변경은 없다. quantized writer는 그대로다.
- Canvas bilinear resize, 전체 회전, CameraX plane 처리, ROI/해상도 변경, JNI/SIMD fused 전처리는 적용하지 않았다. 입증되지 않은 큰 변경으로 rounding·좌표·시야가 달라지는 것을 피했다.

### 그 외 확인한 비용과 한계

| 구간 | 코드로 확인한 사실 | 조치/추가 검증 |
|---|---|---|
| ImageProxy→Bitmap | CameraX.toBitmap에서 프레임 Bitmap 생성, 회전이 필요하면 추가 Bitmap/Matrix 생성 후 원본 recycle | 세부 타이밍/trace 추가. stride/채널 처리 안전성을 위해 직접 plane copy로 바꾸지 않음 |
| CameraX RGBA | ImageAnalysis 출력 RGBA 요청. 내부 색 변환은 앱의 bitmap 타이머 이전에 발생할 수 있음 | CameraX 및 camera→analyzer trace도 확인해야 함 |
| ROI crop | 기본 full frame이면 원본 사용, center crop이면 별도 Bitmap 생성·recycle | 기존 crop 측정/좌표 매핑 유지 |
| Letterbox | 고정 640 Bitmap/Canvas 재사용, padding drawColor 및 filtered drawBitmap | resize 타이밍 유지, sampling 동등성 회귀 시험 추가 |
| getPixels | 409,600 ARGB 픽셀을 재사용 IntArray로 복사 | getPixels 별도 ns 측정. bulk 개선 후 남는 비용 실측 필요 |
| 입력 진단 | enableDiagnostics/debugDetectionLogging일 때 2초마다 전체 통계·SHA-256, self-test는 PNG/bin export 포함 | inputDiagnostics 별도 측정. 기존 preprocess에서 빼지 않음 |
| 출력 진단 | output 전체 통계·hash는 parseOutput 타이머 시작 전에 수행 | outputDiagnostics 측정. detector/pipeline 안, 기존 post 밖 |
| ML Kit 입력 | 간격/in-flight 조건을 통과할 때 analyzer에서 640x360 Bitmap 생성 후 coroutine dispatch | mlKitPrepare 측정. InputImage.fromBitmap은 async 쪽 별도 trace/ns 로그 |
| ML Kit 자원 경쟁 | ML Kit 추론이 YOLO와 동시에 CPU를 사용할 수 있음 | mlKitInFlight 표시. CPU 시간/동시성의 완전한 측정값은 아님 |
| 동기화 | 단일 camera analyzer, synchronized detect/close, settings 재적용 시 detector 재생성 | 동일 detector 동시 호출 금지 유지. lifecycle/close 안전성 테스트 유지 |
| 후속 부수 작업 | warning/log formatting/debug bitmap snapshot/recording 등이 pipeline 완료 뒤에도 실행될 수 있음 | totalProcessing에 모든 UI/TTS/실제 overlay draw가 포함되는 것은 아님 |

ML Kit의 `InputImage.fromBitmap`은 YOLO 입력 작성에 직접 포함되지 않는다. analyzer의 ML resize/dispatch는 totalProcessing 안에 있고 pipeline/preprocess 밖이다. off-screen/in-flight 작업의 자원 경쟁은 타이머 합산으로 정량화할 수 없으며 Profiler로 확인해야 한다. 원본 Bitmap의 소유권과 기존 비동기 lifecycle은 그대로 유지했다.

## 5. 측정 범위와 신규 진단

기존 집계식과 측정 범위를 유지했다. **새 상세 시간은 부모 시간의 부분집합이므로 부모 집계에 더하지 않는다.** 기존 값은 정수 ms이고 신규 상세는 내부 ns를 저장해 로그에 소수점 3자리 ms로 출력한다. -1은 미확인, 0은 생략/실행하지 않은 진단이다.

| 필드 | 의미/부모 구간 |
|---|---|
| bitmap | 기존 toBitmapSafe 전체: CameraX Bitmap 변환·회전·관련 진단 포함 |
| cameraToBitmap, rotation | bitmap 안의 변환 및 회전 상세 |
| preprocess | 기존 `resizeTimeMs + inputBufferTimeMs` 그대로 |
| inputBuffer | 기존 입력 설명/getPixels/RGB write/입력 진단·self-test export 구간 |
| getPixels, rgbWrite, inputDiagnostics | inputBuffer의 부분집합. 반올림·타이머·설명 작성 때문에 합이 부모와 정확히 같지는 않음 |
| inference | 기존 Interpreter.run 호출 경계 그대로. Java tensor I/O 포함 |
| nativeInvoke | `getLastNativeInferenceDurationNanoseconds()`. JNI invoke 주변 측정; Java input set/output copy 제외. delegate 사용 증명이 아님 |
| outputParsing, nms | 기존 출력 copy/scan, NMS 구간 그대로 |
| outputDiagnostics | detector/pipeline 안, 기존 post 시작 이전의 출력 통계/export |
| mlKitPrepare | pipeline 전의 ML Kit eligibility/resize/dispatch 호출 전체 |
| mlKitInFlight | ML Kit 입력 dispatch 직후 실행 중 여부 snapshot; YOLO 전체 동안의 상태를 보장하지 않음 |
| pipeline | DetectionPipeline.process 기존 범위; camera bitmap/ML 입력 준비 제외 |
| totalProcessing | analyzer receipt→성공 pipeline completion 기존 범위; UI draw/warning output 제외 |

성능 저장 FRAME에 상세 필드를 추가했다. interval/cumulative 기존 집계는 그대로 유지했다. `tools/analyze_performance_logs.py`는 신규 소수 상세의 평균/P95와 inputDiagnostics ON/OFF별 preprocess를 분리 출력한다. 구형 로그에서 누락된 필드를 0으로 채우지 않는다. 세부 필드는 서로 겹치므로 합산 개선율을 만들지 않는다.

Android Studio System Trace/Perfetto에서 사용할 slice: `CameraX.toBitmap`, `CameraX.rotate`, `YOLO.resize`, `YOLO.getPixels`, `YOLO.rgbWrite`, `YOLO.Interpreter.run`, `MLKit.InputImage`. 기존 clock/실패 처리 경계를 유지하고 trace는 finally를 통해 닫는다.

## 6. 추론 병목

단독 시험에서 run과 nativeInvoke의 차이가 약 0.4ms였다. 이 조건에서는 4.69MiB 입력 및 0.42MiB 출력의 Java/native 전달보다 invoke 계산이 지배적이다. 이 관측은 모든 기기/카메라 프레임에서 복사 비용이 작다는 보장은 아니다.

출력은 이미 channels-first FLOAT32 `FloatBuffer.get(row)`로 재사용 rows에 bulk copy한다. 출력 parsing은 8,400 candidates × 9 class score 순회와 좌표 유효성 검사이며, confidence를 통과한 후보만 좌표 변환·필터를 진행한다. NMS는 class-aware, 상위 100 후보 제한이고 0.45를 유지한다. 모델 내부 계산량을 바꾸지 않고 output parsing/NMS를 줄여 약 375ms 전체를 크게 줄일 수 있다고 주장할 근거는 없다.

단순 direct ByteBuffer 사용도 완전한 zero-copy는 아니다. Java wrapper가 input tensor set과 output copy를 수행한다. 이번 변경은 run 자체의 입출력 방식·모델·parser/NMS·Geometry를 바꾸지 않는다. XNNPACK per-op profile과 worker CPU 배치를 먼저 확인해야 모델 계산 병목을 더 줄일 방향이 정해진다.

## 7. GPU/NNAPI 가능성과 별도 적용안

### 현재 2.1.1 의존성

공식 문서는 **Maven V2 Interpreter API의 GPU 실행은 지원 경로가 아니며 CompiledModel로 전환해야 한다**고 명시한다. 현재 AAR의 `Interpreter.Options`에는 GPU `addDelegate`/NNAPI `setUseNNAPI`가 없다. 따라서 기존 GPU 지원 로그는 현재 Interpreter 경로의 제약으로 해석하는 것이 맞다. 현재 패키지 전체가 GPU를 할 수 없다는 뜻은 아니다. [공식 migration 문서](https://developers.google.com/edge/litert/migration).

로컬 AAR의 `CompiledModel.create(AssetManager, String, Options)`, `Options(Accelerator...)`, `Accelerator.GPU`, input/output TensorBuffer 생성·run·writeFloat/readFloat를 확인했다. 2.1.1에는 OpenCL accelerator native library도 이미 포함돼 있다. 현재 최신 공식 예제는 2.3.0 및 별도 litert-gpu dependency를 사용하는데, 이를 2.1.1에 그대로 혼합하면 안 된다. 버전별 API/패키징 차이는 동일 버전 문서·AAR에 맞춰야 한다. [CompiledModel Kotlin 문서](https://developers.google.com/edge/litert/next/android_kotlin), [GPU 문서](https://developers.google.com/edge/litert/next/gpu), [v2.1.1 Model.kt](https://github.com/google-ai-edge/LiteRT/blob/v2.1.1/litert/kotlin/src/main/kotlin/com/google/ai/edge/litert/Model.kt).

이번 작업에서 GPU compile/run을 수행하지 않았다. 현재 모델은 정적 FLOAT32 builtin YOLO 그래프로 GPU 평가 후보지만 NCHW 경계 TRANSPOSE, 실제 operator/version 지원, 파티션·CPU 잔류 연산·driver 호환성은 실제 compile/run으로 검증해야 한다. `.tflite`가 CPU에서 동작한다는 사실만으로 전체 GPU 호환성을 확정할 수 없다.

### NNAPI

현재 bundled Java/Kotlin Interpreter Options에는 NNAPI 진입점이 없고 NNAPI delegate를 연결한 코드/의존성도 없다. Android의 NNAPI 자체는 Android 15에서 deprecated됐다. `CompiledModel`의 NPU accelerator는 vendor compilation/dispatch 경로이며 NNAPI 설정의 대체 Boolean이 아니다. NNAPI 실행을 위해 옛 1.x/Play Services/native runtime 경로를 선택하는 것은 별도의 runtime 구성 변경이다. 기존 V2와 옛 `org.tensorflow.lite` Java artifact를 섞는 방식은 duplicate class/native ABI 위험이 있어 권장하지 않는다. [Android NNAPI migration guide](https://developer.android.com/ndk/guides/neuralnetworks/migration-guide).

### 후속 구현 순서

1. `ObjectDetector` 뒤에 별도 CompiledModel 실행 adapter를 만들고 CPU adapter로 기존 input/output 및 검출을 우선 비교한다. 모델 SHA, RGB /255, NCHW, Letterbox, confidence/NMS·모든 필터를 고정한다.
2. input/output TensorBuffer는 초기화 때 한 번 생성하고 재사용한다. 현재 2.1.1 Kotlin `writeFloat(FloatArray)`/`readFloat()`의 추가 복사/배열 반환 비용을 실제 평가한다. zero-copy 광고를 현재 Bitmap 경로의 zero-copy 보장으로 해석하지 않는다.
3. GPU compile과 run을 동일 전용 worker에서 관리하고 lifecycle/close·app 설정 재적용·에러/fallback 로그를 명시한다. 실패 시 CPU Interpreter를 새로 준비하는 fallback은 별도로 검증한다. 단순 requested GPU 표기를 applied GPU 증거로 쓰지 않는다.
4. 먼저 FLOAT32 precision을 사용 가능한 옵션으로 고정해 CPU raw output/좌표를 비교하고, 이후 FP16 허용은 별도 정확도 변경으로 취급한다. confidence 0.2/NMS 0.45 인근 후보를 포함한 실제 이미지 corpus로 검증한다.
5. 동일 CameraX·ML Kit·Overlay/TTS 조건에서 warm-up/compile 시간, 평균/P95, FPS, 열·전력·메모리·드라이버별 실패를 측정한 후 설정 UI와 저장 schema에 연결한다.

GPU는 병렬 convolution과 CPU 자원 여유의 장점이 기대되지만 초기 compile, CPU↔GPU 복사, 미지원 op 분할, 발열, precision 변화, preview GPU 자원 경쟁 위험이 있다. NNAPI/NPU는 특정 기기의 전력/가속 이점이 가능하지만 vendor별 graph 지원·SDK·device matrix가 필요하다. 어느 경로도 현재 데이터만으로 개선 배수를 제시하지 않는다.

## 8. 변경 파일

| 파일 | 변경 |
|---|---|
| `detector/YoloTensorContract.kt` | 정확히 같은 lookup 값을 사용하는 bulk FLOAT32 writer |
| `detector/VisionStyleYoloDetector.kt` | 재사용 scratch, 세부 타이밍/nativeInvoke/trace, 기본 ON인 XNNPACK A/B 생성자 옵션 |
| `detector/DetectionDiagnostics.kt` | 부모 시간 안의 상세 ns 필드 |
| `camera/ImageProxyExt.kt`, `camera/CameraController.kt` | Bitmap 변환/회전 상세 전달·trace |
| `MainActivity.kt` | ML 입력 준비/in-flight·상세 성능 FRAME 전달 |
| `mlkit/MlKitObjectDetector.kt` | 비동기 InputImage 생성 trace/ns 로그 |
| `metrics/PerformanceLogFormatter.kt` | 상세 FRAME 저장 및 측정 범위 설명 |
| `test/.../YoloTensorContractTest.kt`, `PerformanceLogFormatterTest.kt` | 전체 입력 bytes·레이아웃·재사용·로그 검증 |
| `androidTest/.../DetectorPreprocessingRegressionTest.kt` | 독립 Letterbox reference, 크기 변경 재사용, 양성 이미지 raw/detection 반복, XNNPACK ON/OFF 정확도 |
| `androidTest/.../YoloBottleneckBenchmarkTest.kt` | 동일 입력 writer 비교, ON/OFF/ON run/nativeInvoke 측정 |
| `tools/analyze_performance_logs.py`, `tools/test_analyze_performance_logs.py` | 소수 상세/진단별 분석, 부모 집계 보존 검증 |

production Java/Kotlin 파일의 기준 경로는 `app/src/main/java/com/samin/objectdetection/`이다. 모델 파일, 클래스 매핑, confidence 0.2, NMS 0.45, Geometry/ML Kit/Overlay/TTS, 앱 설정 UI와 기존 성능 저장 기능은 유지했다. 기본 CPU bulk 변경은 입력 bit-identical을 목표로 했고, XNNPACK OFF/GPU/precision/해상도/ROI 변경은 결과가 달라질 수 있는 별도 실험이다.

## 9. 빌드 및 검증 결과

### 최종 통과 기록

| 검증 | 결과 |
|---|---|
| Android `assembleDebug`, `assembleDebugAndroidTest` | 최종 빌드 성공 |
| `testDebugUnitTest` | 25 suites / 102 tests, failures=0, errors=0, skipped=0 |
| Python 로그 분석 단위 테스트 | 4 tests 통과 |
| 최종 Android 비 UI 계측 테스트 | 15 tests 통과, 135.172초 |
| 설정 화면 계측 테스트 | 잠금 해제 후 8 tests 통과, 32.230초 |
| 모델 SHA | 수정 전후 동일 |
| 독립 전처리 reference | 720×1280 → 1280×720 → 640×640 → 511×733 → 720×1280, 입력 bytes 동일 |
| 반복 detector 메모리 | input/output buffers, views, pixels, scratch, output rows, Bitmap/Canvas identity 동일 |
| 동일 양성 이미지 반복 | 검출·raw 출력 동일, frameTimestamp는 결과 내용 비교에서 제외 |
| XNNPACK ON/OFF 양성 이미지 | 입력 bytes 동일, raw/score/좌표 허용 오차 내 통과 |

수정 전 기존 YOLO 6 tests 및 별도 양성 이미지 CPU 2/4 tests 2개도 통과했다. 중간 전체 계측 실행에서는 새 반복 테스트가 검출에 포함된 프레임 타임스탬프까지 equality 비교해 1개 실패했고, 기기 잠금으로 설정 UI 8개가 실패했다. 반복 테스트는 timestamp를 비교에서 제외하도록 수정했고, 사용자가 잠금을 해제한 뒤 최종 버전으로 위 23개 계측 테스트를 모두 다시 통과했다. production 결과를 맞추기 위한 threshold/파서 수정은 하지 않았다.

### 수정 전후 동일 입력의 직접 비교

변경 전 APK의 self-test 산출물을 보관하고 최종 APK 산출물과 직접 비교했다. model SHA와 입력/출력 shape를 먼저 확인했다. 아래 4 cases는 허용 오차 통과에 더해 **input.bin 및 output_0.bin이 바이트 단위로 완전히 같았고, 저장된 label/confidence/bbox도 정확히 같았다.**

| 입력/조건 | 입력 동일 | raw 최대 절대 오차 | 검출 동일 | 검출 수 |
|---|---|---:|---|---:|
| 640×640 solid red | byte-identical | 0 | 정확히 동일 | 0 |
| 1080×1440 red Letterbox | byte-identical | 0 | 정확히 동일 | 0 |
| known.png, CPU 2 / XNNPACK ON | byte-identical | 0 | 정확히 동일 | 1 bollard |
| known.png, CPU 4 / XNNPACK ON | byte-identical | 0 | 정확히 동일 | 1 bollard |

최종 XNNPACK ON/OFF 양성 이미지 시험은 raw 최대 절대 오차 `3.0510127544403076e-6`, confidence 0.92723477 vs 0.9272346으로 허용 오차를 통과했다. 동일 bollard 1개, 좌표 차이는 0.1px보다 작았다. 이 결과는 backend 변경의 전역 동등성을 보장하지 않는다. 현재 양성 reference는 bollard 1장이고 나머지 클래스·작은 객체·가림·confidence/NMS 경계의 실제 이미지 corpus 검증은 추가로 필요하다.

### 최종 적용 코드의 단독 성능 재확인

최종 실제 `fillFloat32Bulk` 함수의 30 samples 평균/P95는 **2.013/2.155ms**, 직전 scalar lookup은 **63.896/64.231ms**, division writer는 **56.114/56.298ms**였다. 성능을 자동 assertion하지 않고 입력 동등성만 assertion했다. 재사용 메모리 추가 비용과 복사는 위에 명시했다.

최종 known.png(640×640), 상세 진단 OFF, warm-up 제외 10 samples의 detector-only 상세:

| 지표 | 평균 ms |
|---|---:|
| 기존 정의 preprocess | 3.000 |
| getPixels | 1.428 |
| RGB bulk write | 1.946 |
| Interpreter.run (정수 ms 평균) | 545.700 |
| nativeInvoke (ns 기반 평균) | 545.854 |
| candidate scan (정수 ms 평균) | 5.000 |

이 입력은 이미 640×640이므로 resize/Letterbox 비용이 없고, CameraX/회전/ROI/ML Kit/전체 pipeline을 포함하지 않는다. output copy/NMS 정수 ms 평균은 0으로 기록됐지만 실행 비용이 0이라는 뜻은 아니다. inference 정수 ms 내림 때문에 ns 기반 nativeInvoke 평균이 약간 더 크게 표시될 수 있다. **이 3ms를 필드 전처리 73~79ms와 직접 비교하거나 전체 FPS 개선 수치로 사용하지 않는다.**

최종 단독 run ON/OFF/ON도 CPU 4에서 546.099 / 266.029 / 546.039ms, CPU 2에서 546.042 / 381.899 / 545.603ms였다. 초기 관측 방향이 재확인됐지만 camera field test 및 연속 발열 시험을 대체하지 않는다.

로컬 검증 증거는 Git ignored `build/` 아래 보관했다:

- `yolo-baseline-build.log`, `yolo-baseline-instrumentation.log`, `yolo-baseline-positive.log`
- `yolo-benchmark-instrumentation.log`, `yolo-benchmark-native.log`
- `yolo-final-build.log`, `yolo-settings-instrumentation.log`, `yolo-final-detector-instrumentation.log`
- `yolo-final-validation-native.log` (native 위임, writer 및 detector 단계 측정)
- `yolo-baseline-golden/`, `yolo-baseline-positive/`, `yolo-final-golden/`
- `yolo-before-after-comparison.json`, `compare_yolo_validation.py` (4 cases 직접 비교)
- `litert-inspection/` (현재 model 검사, AAR javap 및 공식 버전 소스 읽기 증거)

재현 명령:

```powershell
.\gradlew.bat :app:dependencyInsight --dependency litert --configuration debugRuntimeClasspath --offline
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest --offline
.venv/Scripts/python.exe -m unittest discover -s tools -p 'test_*.py'
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb push app/src/debug/assets/self_test/known.png /data/local/tmp/yolo-known.png
adb shell am instrument -w -e selfTestImage /data/local/tmp/yolo-known.png -e class com.samin.objectdetection.detector.DetectorCpuOptimizationTest,com.samin.objectdetection.detector.DetectorGoldenTest,com.samin.objectdetection.detector.DetectorPreprocessingRegressionTest,com.samin.objectdetection.detector.YoloBottleneckBenchmarkTest com.samin.objectdetection.test/androidx.test.runner.AndroidJUnitRunner
```

## 10. 권장 실기기 재시험과 프로파일링

- 운영 기능 검증: CPU 4 / XNNPACK ON, `best.tflite` SHA 고정, 640 input, Letterbox/full frame, confidence 0.2/NMS 0.45/100 candidates, 기존 Geometry·ML Kit·TTS·Overlay 설정 그대로. 카메라 요청 해상도뿐 아니라 actual resolution도 고정·기록한다.
- 비교 기준선: detector/debug tensor/PNG 진단 OFF, 성능 파일 저장 ON, 화면 녹화/캡처 OFF. ML Kit은 기능 비교에서는 동일하게 유지한다. 원인 분리를 위한 ML Kit OFF 시험은 별도 조건으로 명시한다.
- 동일 입력 writer 시험과 실제 카메라 시험을 구분한다. 카메라 변경 전/후/전 또는 후/전/후 순서로 반복하고 워밍업 30초 제외, 최소 2분 수집, 장시간 열 시험도 수행한다. 충전·절전·화면 밝기·background load를 기록한다.
- XNNPACK ON/OFF/ON을 **동일 CPU 4, 모델, 전처리, 입력**으로 비교한다. 각 옵션 전환 시 Interpreter를 재생성한다. CPU 2도 비교하되 cpuThreads 옵션 값과 실제 worker 수를 혼동하지 않는다. 모델 rounding/threshold 경계 검출 차이를 함께 검사한다.
- FRAME의 `getPixels`, `rgbWrite`, `resize`, `inputDiagnostics`를 각각 분석한다. 상세 진단이 켜진 프레임을 섞은 평균만 비교하지 않는다. `tools/analyze_performance_logs.py`의 진단별 preprocess 및 원래 평균/P95를 함께 본다.
- Logcat은 Interpreter를 생성한 PID·시간·model SHA와 매칭한다. `Created...`는 생성 근거이고 `Replacing ... TfLiteXNNPackDelegate ... partitions`가 실제 graph 위임 근거다. 앱/ML Kit/다른 테스트의 runtime 로그와 혼동하지 않는다. Java unavailable 표기를 임의로 VERIFIED로 바꾸지 않는다.
- Android Studio CPU Profiler의 native call stack과 System Trace/Perfetto에서 `YOLO.Interpreter.run` 중 활성 worker, core 이동, runnable/blocked 상태, XNNPACK/기본 CPU kernel을 확인한다. allocation profiling으로 CameraX Bitmap/rotation/ML input allocation과 GC를 분리한다. 이번 identity 검증은 재사용 객체의 재할당 없음만 증명하며 전체 앱의 allocation-free를 뜻하지 않는다.
- per-op profiling이 필요하면 동일 runtime 소스/빌드와 모델로 native benchmark를 준비하고 `--use_xnnpack=true/false --num_threads=4 --enable_op_profiling=true`를 비교한다. 다른 버전 benchmark binary의 수치를 이 APK의 개선 근거로 대신 쓰지 않는다.
- 최종 판정은 실제 완료 수/단조 경과시간 FPS, 평균/P95 inference/preprocess/pipeline/totalProcessing, memory/열 상태, 동일 이미지 결과 동등성을 함께 사용한다. 모델/정확도를 바꾸는 최적화는 별도 변경으로 관리한다.
