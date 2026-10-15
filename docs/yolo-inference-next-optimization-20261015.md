# YOLO 추론 병목 정밀 분석 및 다음 최적화 검증

분석일: 2026-10-15 (Asia/Seoul). 기준 HEAD: `f62b8d1`. 시작 시 작업 트리는 깨끗했다.

**이번 기기 단독 추론에서 nativeInvoke가 run 시간의 99.85~99.94%였다. Java 호출용 컨테이너 재사용을 적용했고 실제 입력·출력 bytes와 탐지 결과의 동일성을 확인했지만, 속도 향상이나 카메라 3 FPS 달성은 확인하지 않았다.** XNNPACK은 네이티브 partition 로그로 실제 적용을 확인했다. 기본값은 CPU 4 threads / XNNPACK ON으로 유지한다.

이 문서는 각 절에서 다음 증거 수준을 구분한다.

- **[코드 사실]** 현재 저장소, 모델 FlatBuffer, 실제 의존 AAR/API를 검사한 결과. 외부 API 제약은 공식 출처를 함께 표시한다.
- **[벤치마크 검증]** 이번 SM-S921N에서 실행한 Debug instrumentation 또는 로컬 빌드·회귀 검증 결과. 사용자 제공 카메라 결과는 별도로 표시한다.
- **[미검증 예상]** 모델 후보의 기대 효과, 원인 가설, 후속 검증이 필요한 항목. 실제 측정값으로 취급하지 않는다.

주요 증거:

- [현재 모델 검사 JSON](yolo-model-inspection-20261015.json)
- [측정 및 golden 비교 JSON](validation/yolo-next-optimization-results-20261015.json)
- [같은 PID의 원본 benchmark/native 로그](validation/yolo-next-optimization-benchmark-20261015.txt)
- [변경 전 기기 테스트](validation/yolo-next-optimization-baseline-tests-20261015.txt), [변경 후 모델 테스트](validation/yolo-next-optimization-detector-tests-20261015.txt)
- [설정·카메라 lifecycle·로그 및 최종 재사용 회귀](validation/yolo-next-optimization-settings-tests-20261015.txt)

## 1. 현재 추론 구조

### [코드 사실]

실행 경로는 다음과 같다. 소스 경로는 `app/src/main/java/com/samin/objectdetection/` 기준이다.

```text
settings/AppSettings / AppSettingsCodec / AppSettingsStore
  → AppSettings.toDetectionConfig() → camera/DetectionConfig
  → MainActivity.onCreate()의 detector 초기화
  → detector/VisionStyleYoloDetector 생성
      ModelAssets.load(): asset mmap
      Interpreter.Options.setNumThreads(n), setUseXNNPACK(on/off)
      Interpreter(modelBuffer, options), tensor 계약 검증, 재사용 저장소 할당

CameraController: 단일 analyzer executor / KEEP_ONLY_LATEST
  → ImageProxy.toBitmapSafe(): CameraX.toBitmap + 필요 시 회전
  → MainActivity: ML Kit 입력 준비/비동기 dispatch
  → DetectionPipeline.process(): 선택적 ROI crop
  → detector.detect() [synchronized]
      Letterbox → getPixels → FLOAT32 bulk write
      → runForMultipleInputsOutputs(재사용 입력 배열, 출력 맵)
      → 출력 bulk copy → 후보 scan → class별 NMS
  → 좌표 매핑 / Geometry 등 기존 정책 → Overlay / 경고·TTS
```

설정 적용 시 기존 camera analyzer를 drain하고 Interpreter를 닫은 뒤 detector를 재생성한다. 프레임별 Interpreter 생성은 없다. `detect`, `runSelfTest`, `close`는 직렬화되며 close는 멱등이다.

의존성은 `com.google.ai.edge.litert:litert:2.1.1`이다. `org.tensorflow.lite` 패키지를 사용하지만 Maven 버전과 내부 runtime 버전은 별개이며 이번 Android 로그의 runtime은 `2.21.0`이다. 호스트 모델 검사에는 기존 `.venv`의 `ai-edge-litert 2.2.0`, `tflite 2.18.0`, `numpy 2.4.6`을 사용했다. 호스트에서 나온 XNNPACK 생성 메시지는 Android 위임 증거에 포함하지 않는다.

현재 모델 계약:

| 항목 | 실제 값 |
|---|---|
| asset / 크기 | `best.tflite` / 37,962,632 bytes |
| SHA-256 | `32a44bc7432ef2d7ca5de802dd8e714a26568aea16566bad3fd7fcd2b85bcee2` |
| 모델 metadata | Ultralytics YOLO11s Detect, 8.4.173, batch 1, end2end=false |
| 입력 | FLOAT32, `[1,3,640,640]`, NCHW, 4,915,200 bytes |
| 출력 | FLOAT32, `[1,13,8400]`, channels-first, 436,800 bytes |
| shape signature | 입출력 모두 shape와 동일한 고정 크기; 전체 tensor에 음수 signature 없음 |
| quantization | scale=0, zeroPoint=0, quantized tensor 0개 |
| tensor 타입 | FLOAT32 535개, INT32 37개. INT32 shape/index 상수를 INT8 모델로 해석하면 안 됨 |
| 전처리·parser | RGB /255.0f; normalized xywh + 9 class scores, 추가 objectness/sigmoid 없음 |
| 클래스 순서 | bollard, person, bicycle, car, motorcycle, bus, truck, stop sign, bench |

첫 그래프 경계는 NCHW→NHWC TRANSPOSE이며 뒤에 PAD/CONV가 있고, 끝부분에서 좌표를 정규화한다. RGB 정규화 방식은 기존 계약 및 실제 입력 비교와 함께 확인했다. 과거 `docs/tflite-model-inspection.json`은 1클래스 모델 검사이므로 현재 모델 근거로 사용하지 않았다.

| 연산 | 개수 | 연산 | 개수 |
|---|---:|---|---:|
| CONV_2D | 81 | DEPTHWISE_CONV_2D | 7 |
| ADD | 16 | MUL | 80 |
| LOGISTIC | 77 | CONCATENATION | 21 |
| TRANSPOSE | 14 | RESHAPE | 16 |
| SLICE | 23 | PAD | 7 |
| MAX_POOL_2D | 3 | RESIZE_NEAREST_NEIGHBOR | 2 |
| BATCH_MATMUL | 2 | SOFTMAX | 2 |
| SUB | 2 | 합계 | 353 |

subgraph 1개, custom op 없음. Conv2D만 정적 shape로 합산한 비용은 10,633,600,000 MAC이다. 이를 전체 FLOPs나 실측 실행 시간으로 표현하지 않는다. BatchMatMul에는 `[4,400,32]×[4,32,400]` 및 `[4,64,400]×[4,400,400]` 경로가 있다.

### [벤치마크 검증]

Android 실제 tensor 계약, RGB/Letterbox, 9클래스 출력, 반복 추론·해제 테스트가 통과했다. 변경 전후 APK가 같은 SHA의 모델과 같은 입력 bytes를 사용했다.

### [미검증 예상]

각 연산의 시간·메모리 대역폭 비중은 per-op profile이 없어 미확인이다. Conv 개수가 많다는 이유만으로 특정 Conv 또는 BatchMatMul을 지배적인 개별 병목으로 확정하지 않는다.

## 2. 실제 병목 분석

### [코드 사실]

LiteRT 2.1.1 캐시 AAR의 `classes.jar`를 `javap -p -c`로 검사했다.

1. 기존 `InterpreterImpl.run(input, output)`은 `Object[1]`과 `HashMap`을 생성하고 `runForMultipleInputsOutputs()`로 전달한다.
2. 그 메서드는 같은 `NativeInterpreterWrapper.run(inputs, outputs)`로 진입한다.
3. wrapper는 input shape 확인·필요 시 allocation → `TensorImpl.setTo` → JNI native run → `TensorImpl.copyTo`를 실행한다.
4. `lastNativeInferenceDurationNanoseconds`는 **JNI native run 호출을 둘러싼 System.nanoTime 차이**이다. Java input set/output copy와 앱의 output reader는 제외한다. native 호출 안의 스케줄링 대기는 포함할 수 있다.
5. FLOAT32 direct 입력은 `writeDirectBuffer`로 전달된다. direct buffer라는 이유만으로 모델 tensor와 완전히 zero-copy라고 단정할 수 없다. Java 출력은 native tensor buffer에서 앱 output ByteBuffer로 `put` 복사하며, 앱은 다시 13개 재사용 FloatArray로 bulk get한다.

재사용 저장소는 input/output direct ByteBuffer, input/output FloatBuffer view, RGB FloatArray scratch, pixels IntArray, outputData의 13개 행, Letterbox Bitmap/Canvas/Paint/RectF, 같은 소스 크기의 transform이다. 이번에는 호출용 배열·맵까지 재사용한다. 출력 direct buffer와 행 배열의 합계는 873,600 bytes이며 매 프레임 새로 할당하지 않는다. 입력 scratch 약 4.69MiB의 재사용은 기존 bulk write의 일부로 유지한다.

아직 프레임별 할당 가능한 항목:

- CameraX Bitmap, 회전 시 Matrix/추가 Bitmap, 선택적 ROI Bitmap.
- 주기적인 ML Kit 640×360 scaled Bitmap과 비동기 결과. YOLO와 CPU/메모리 자원을 공유할 수 있다.
- 후보 DetectionResult, 후보/정렬/NMS 리스트, 결과·정책 snapshot과 metrics/logging 객체.
- 진단 활성 시 confidence boxing·정렬, 통계/hash/문자열, 선택적 PNG 및 JSON export.
- 라이브러리 내부 output ByteBuffer view, iterator 등. 호출용 컨테이너 재사용을 allocation-free라고 표현하지 않는다.

GC 가능성은 존재하지만 이번에는 allocation recording이나 GC pause 귀속 분석을 수행하지 않았다. Bitmap 재활용 ownership은 ML Kit 비동기 수명과 연관되므로 변경하지 않았다.

### [벤치마크 검증]

사용자 제공 최신 **카메라** 결과이며 이번에 원본 FRAME 로그를 재집계한 값은 아니다:

| 지표 | ON(A) | OFF(B) | ON(C) |
|---|---:|---:|---:|
| FPS | 2.266 | 1.996 | 2.099 |
| inference 평균 ms | 373.644 | 406.658 | 402.661 |
| inference P95 ms | 421 | 491 | 423 |
| preprocess 평균 ms | 3.345 | 5.270 | 3.998 |
| pipeline 평균 ms | 415.131 | 458.484 | 449.805 |

제공된 평균의 비율로 inference/pipeline은 약 88.7~90.0%(ON은 89.5~90.0%)다. 이 표에는 nativeInvoke/CPU sample이 없어 카메라 환경의 native 비중과 CPU 차이를 직접 계산할 수 없다.

이번 **카메라 없는 Debug instrumentation**: SM-S921N, Android 16/API 36, board `erd9945`, 같은 모델·고정 RGB114 입력, variant마다 Interpreter 재생성, warmup 3회 제외 후 10회 측정, 순서 4→2→1 threads 각각 ON(A)→OFF(B)→ON(C). 다음 절의 표가 이번 단독 실측이다.

| threads | XNNPACK/phase | run 평균 ms | nativeInvoke 평균 ms | run−native 평균 ms | run P95 ms | process CPU % |
|---:|---|---:|---:|---:|---:|---:|
| 4 | ON A | 545.066 | 544.658 | 0.408 | 545.767 | 98.94 |
| 4 | OFF B | 267.436 | 267.032 | 0.404 | 272.980 | 332.55 |
| 4 | ON C | 544.846 | 544.433 | 0.413 | 545.195 | 99.13 |
| 2 | ON A | 544.931 | 544.506 | 0.425 | 547.015 | 99.09 |
| 2 | OFF B | 381.836 | 381.441 | 0.395 | 383.254 | 183.56 |
| 2 | ON C | 544.692 | 544.280 | 0.413 | 545.050 | 98.99 |
| 1 | ON A | 545.425 | 545.014 | 0.411 | 547.130 | 98.93 |
| 1 | OFF B | 637.349 | 636.953 | 0.396 | 640.394 | 98.78 |
| 1 | ON C | 544.886 | 544.453 | 0.433 | 547.112 | 98.92 |

`run−native`는 각 동일 호출의 ns 차이를 집계했다. P95끼리 빼서 만든 값이 아니다. nativeInvoke 비중은 99.85~99.94%이며 이 시험에서는 네이티브 실행 구간이 압도적이다. 남은 약 0.4ms는 순수 Java 계산만의 시간이 아니라 JNI 경계, input set/output copy 등을 포함한 잔여다.

양성 known.png의 detector 반복 10회에서는 preprocess 평균 3ms, getPixels 1.420ms, bulk RGB write 2.034ms, candidate scan 5ms, output copy/NMS 0ms였다. 정수 ms 집계에서 0은 1ms 미만일 수 있으며 무료 실행을 뜻하지 않는다. inference 544.5ms / nativeInvoke 544.546ms처럼 보이는 차이도 기존 inference 필드의 내림 때문이다. native 비중 판정에는 위 ns benchmark를 사용했다. 같은 입력 반복이므로 resize 0ms이고 실제 카메라 Letterbox 전체 비용을 대표하지 않는다.

### [미검증 예상]

3 FPS는 완료 간격 약 333.3ms를 요구한다. 사용자 ON 평균 inference만도 373.6~402.7ms여서 현재 추론 시간을 유지한 채 나머지 작업만 없애서는 목표에 도달할 수 없다. pipeline의 나머지 비용 약 41.5~47.1ms를 유지한다는 단순 예산에서는 inference를 약 286~292ms 이하로 내려야 한다. 실제 FPS에는 pipeline 밖 카메라 변환·스케줄링 등이 포함되므로 이는 목표 달성 예측이 아니다.

단독 시험은 카메라 실측과 XNNPACK 우열이 반대다. foreground 상태, CPU 정책/클럭, JIT, 열, 동시 ML Kit, 장면 차이와 thread 전달 경로를 통제한 재현이 필요하다. 원인을 어느 하나로 확정하지 않았다. 짧은 단독 테스트의 `1000/run`을 카메라 FPS로 보고하지 않는다.

## 3. XNNPACK 적용 여부 검증 결과

### [코드 사실]

`YoloSettings.useXnnpack=true` 기본값 → codec/store → DetectionConfig → MainActivity → detector 생성자 → `Options.setUseXNNPACK(useXnnpack)` → Interpreter 생성의 경로를 유지했다. Options의 `getUseXNNPACK()`은 이 AAR에서 미설정 시에도 true다. Java wrapper는 `getNumThreads()`와 `getUseXNNPACK()`을 native `createInterpreter(... int, boolean, ...)`에 전달한다.

현재 런타임에 XNNPACK 구현이 포함되어 있어야 하고, 요청 후 모델의 타입·shape·상수/연산 제약이 delegate에서 수락되어야 실제 partition이 생성된다. [공식 XNNPACK 문서](https://raw.githubusercontent.com/google-ai-edge/LiteRT/main/tflite/delegates/xnnpack/README.md)는 미지원 연산을 기본 구현으로 실행하며 Conv 등의 dtype·상수 제약을 설명한다. 문서의 일반적인 default 설명보다 **검사한 2.1.1 AAR 옵션 동작**을 현재 앱 판단에 우선했다. 연산 이름만으로 353개 노드의 지원 여부를 확정하지 않았다.

`appliedBackend=CPU`에는 기본 CPU kernel과 XNNPACK이 모두 포함된다. `xnnpackRequested=true`는 요청 증거다. 공개 Java API로 활성 delegate, partition coverage, 실제 worker 수를 조회할 수 없어 `delegateVerification=UNAVAILABLE_JAVA_API`를 유지한다. `cpuFallbackCount=0`은 잔여 CPU 연산 0개라는 뜻이 아니다.

### [벤치마크 검증]

같은 PID의 variant 시작 로그를 **Interpreter 생성 전**에 기록하여 네이티브 로그와 매칭했다.

```text
10:28:02.499 [XNNPACK_VARIANT_BEGIN] phase=0 threads=4 requested=true pid=12470 runtime=2.21.0
10:28:02.501 Replacing 351 out of 353 node(s) with delegate (TfLiteXNNPackDelegate) node,
                 yielding 5 partitions for subgraph 0.
10:28:09.942 [XNNPACK_VARIANT_BEGIN] phase=1 threads=4 requested=false ...
10:28:13.723 [XNNPACK_VARIANT_BEGIN] phase=2 threads=4 requested=true ...
10:28:13.725 Replacing 351 out of 353 node(s) with delegate (TfLiteXNNPackDelegate) node,
                 yielding 5 partitions for subgraph 0.
```

2/1 threads의 ON 구간에서도 같은 위임 메시지가 있고 OFF 구간에는 없다. 따라서 **이번 기기·APK·모델에서 XNNPACK 실제 그래프 위임은 확인됨**이다. 351/353은 노드 수 비율이며 시간·FLOPs 비율이 아니다. 미위임 2개의 노드 이름과 비용은 이 로그로 알 수 없다.

프로세스 CPU는 `Process.getElapsedCpuTime()` 차이 / 단조 wall 시간으로, warmup 후 측정 창 전체를 집계했다. 100%는 한 코어, 332.55%는 평균 약 3.33코어에 해당하는 프로세스 전체 CPU 시간이다. 기기 전체 사용률이나 XNNPACK worker 개수가 아니다. 이 시험은 카메라·ML Kit를 시작하지 않는다.

ON에서 1/2/4 요청 모두 약 545ms·99%로 thread 증가 효과를 관측하지 못했다. OFF는 1→2→4에서 637→382→267ms, CPU 약 99→184→333%였다. **옵션 전달은 확인되었지만 ON의 실제 병렬 사용은 입증되지 않았으며**, 설정값 4를 활성 worker 4개라고 설명하면 안 된다.

### [미검증 예상] 추가 확인 방법

- Android Studio CPU Profiler/Perfetto에서 `YOLO.Interpreter.run` 구간과 native stack, tid별 CPU 실행 시간, sched/core 이동, 열/주파수를 함께 확인한다. worker 수와 잔여 연산명을 확정하려면 이 과정이 필요하다.
- [공식 benchmark 지침](https://developers.google.com/edge/litert/models/measurement)의 같은 버전 소스 기반 benchmark binary로 `--use_xnnpack=true/false --num_threads=1/2/4 --enable_op_profiling=true`를 비교할 수 있다. 별도 binary는 앱과 delegate/thread 생성 경로가 다를 수 있으므로 APK 실측을 대체하지 않는다. 이번에는 binary를 빌드·실행하지 않았다.
- `debug.tflite.trace`와 내부 op tracing의 지원은 실제 빌드별로 확인해야 한다. 현재 앱의 자체 trace 구간은 존재하지만 2.1.1의 모든 내부 op 이벤트가 노출된다고 보장하지 않는다.
- [업스트림 issue #1328](https://github.com/google-ai-edge/LiteRT/issues/1328)에는 1.2.0의 thread 전달 관련 유사 보고가 있다. **다른 버전의 보고**이므로 이 앱의 원인·2.1.1 수정 여부·업그레이드 효과를 확정하는 증거로 사용하지 않는다. 현재 AAR의 delegate 생성 시 threadpool 연결과 후속 버전의 변경을 비교하는 것이 우선이다.

## 4. 안전한 최적화 적용 내역

### [코드 사실]

프로덕션 변경은 `VisionStyleYoloDetector.kt` 한 파일이다. 초기화 시 input 배열과 output 맵을 한 번 만들고, 기존 `run(inputBuffer, outputBuffer)` 대신 `runForMultipleInputsOutputs(interpreterInputs, interpreterOutputs)`로 전달한다. 실제 tensor buffer, 인덱스, wrapper/native 실행 경로는 동일하다. 프레임마다 만들던 Object 배열·HashMap의 명시적 생성 경로를 제거했다. ART의 최적화까지 고려한 실제 할당 bytes/GC 횟수 감소량은 측정하지 않았다.

기존 `YOLO.Interpreter.run` trace 이름, inference 시간 경계, nativeInvoke 조회, 오류 집계·로그는 유지했다. 모델·클래스·confidence 0.2/NMS 0.45/maxCandidates·Letterbox·bulk write·XNNPACK 스위치/기본 ON·Geometry·ML Kit·Overlay·TTS·설정 저장 형식은 변경하지 않았다.

출력 tensor 복사를 생략하거나 tensor native 메모리를 직접 보유하는 변경은 적용하지 않았다. 현재 잔여 비용보다 수명·allocation 변경 위험이 크며, 이 수준의 복사 제거만으로 3 FPS 예산을 해결할 근거가 없다. 후보 scan/NMS 구조의 대규모 변경도 하지 않았다.

### [벤치마크 검증]

변경 전 APK와 변경 후 APK 각각의 `DetectorGoldenTest.knownPositiveImageRunsWithoutCameraX`가 export한 같은 `self_test/known.png`를 비교했다.

| 비교 | 결과 |
|---|---|
| 모델 SHA | 동일 |
| 입력 4,915,200 bytes | byte-identical, SHA `f36db8c06df5f2128ce41d8bd18eba206c4a64448e3fc19588b2c507a2018c87` |
| 출력 436,800 bytes | byte-identical, SHA `5e5567c4f76aea0d59e68f54ed275072f79d66a89d0b930f1005ffdd58f92fb2` |
| 최종 결과 | bollard 1개, confidence `0.92723477`, 좌표 `[292.61978,250.63263,383.16992,599.968]` 정확히 동일 |

추가 검증:

- 2/4 threads × ON/OFF에서 원래 division writer 및 고정된 원래 run/parser/NMS reference와 비교했다. reference의 XNNPACK 옵션을 비교 대상과 맞춰 API 변경과 backend 변경의 오차를 분리했다. raw 허용 오차 `1e-5 + abs(expected)*1e-4`, confidence `1e-4`, 좌표 `0.1px`를 통과했다.
- 같은 Interpreter에서 원래 `run()`과 재사용 컨테이너 API를 번갈아 호출했다. 실제 양성 이미지/색상 control을 바꿔 14라운드 × ON/OFF, 총 56회 추론의 두 경로 raw output bytes가 정확히 동일했다.
- 서로 다른 소스 크기 5개에서 독립 Letterbox 입력 비교 및 버퍼/행/호출 컨테이너 객체 재사용을 검사했다.
- 기존 반복 생성·추론·중복 close·close 후 거부, 오류 후 회복, ON/OFF 양성 이미지 허용 오차 테스트를 유지했다.

호출 경로의 wall 측정은 warmup 4라운드 제외, 각 경로 10개 sample이며 순서를 회전했다:

| 옵션 | 원래 run 평균 / P95 ms | 컨테이너 재사용 평균 / P95 ms |
|---|---:|---:|
| XNNPACK ON | 559.322 / 568.582 | 558.385 / 582.799 |
| XNNPACK OFF | 272.249 / 285.611 | 273.214 / 287.031 |

평균 차이 방향이 옵션에 따라 바뀌며 P95 개선도 없다. **이 변경의 속도 향상은 입증되지 않았다.** 동등성과 명시적 allocation 경로 제거가 확인된 범위다.

기존 bulk write도 재검증했다. 같은 NCHW input bytes에 대해 division / scalar lookup / bulk lookup 평균은 56.246 / 64.135 / 2.033ms였다(각 30개, 순서 회전). 이번 신규 변경의 개선 수치로 사용하지 않는다.

### [미검증 예상]

양성 reference는 bollard 이미지 1장이다. 9클래스 전체·경계 confidence/NMS·야간/가림 corpus의 동등성이나 정확도를 모두 입증한 것은 아니다. 런타임 변경, 모델 경량화/양자화에는 별도 정확도 평가가 필요하다.

## 5. FLOAT32 / INT8 / FP16 비교

### [코드 사실]

현재 그래프의 모든 실수 tensor는 FLOAT32이고 FLOAT16/INT8 tensor는 없다. `TensorValueCodec`은 FLOAT32/INT8/UINT8 I/O 및 scale/zeroPoint 처리를 지원한다. quantized I/O는 기존 codec 경로를 사용하고 FLOAT32 bulk 경로를 타지 않는다. 진짜 FLOAT16 입출력은 현재 codec이 거부한다. FP16 **가중치** 모델과 FP16 **입출력** 모델은 구분해야 한다.

| 방식 | 적용 전제·CPU 호환성 | 예상 이점 | 제약·검증 |
|---|---|---|---|
| FLOAT32 유지 | 현 모델 그대로 실행 가능 | 결과 변경 위험 최소; runtime/thread 문제를 먼저 분리 가능 | native 계산량은 유지. Java 소규모 변경만으로 큰 FPS 개선 근거 없음 |
| full INT8 | 대표 calibration 데이터 및 원 학습 체크포인트/SavedModel 등으로 별도 export; 실제 전체 op·quantization 확인 | 가중치 저장량 감소, 지원 CPU kernel에서 계산·대역폭 절감 가능 | Conv 외 BatchMatMul/Softmax 등까지 양자화되는지 확인. float fallback/quantize 경계가 남을 수 있음. 정확도와 성능 모두 미측정 |
| FP16 가중치 + FLOAT32 I/O | 별도 export 필요. float I/O이면 현재 codec 계약과 연결 가능 | 주로 가중치 파일 크기 감소; GPU 후보에도 유리할 수 있음 | CPU에서 float32로 확장 실행될 수 있어 2배 속도를 기대하면 안 됨. native FP16 실행은 CPU ISA·delegate/build 지원 확인 필요 |
| FP16 I/O | 현재 앱에서 적용 불가 | 이번 범위에서 평가하지 않음 | codec/writer/reader 및 결과 계약 변경 필요 |

[공식 INT8 가이드](https://developers.google.com/edge/litert/conversion/tensorflow/quantization/post_training_integer_quant)는 대표 데이터 calibration 및 full integer 변환·I/O 타입 검증 절차를 설명한다. [공식 FP16 가이드](https://developers.google.com/edge/litert/conversion/tensorflow/quantization/post_training_float16_quant)는 가중치 저장 크기 감소, 기본 float I/O, CPU 확장 실행을 설명한다. 일반적인 가중치 크기 비율 INT8 약 1/4·FP16 약 1/2을 현재 전체 파일 크기나 실제 속도 비율로 보장하지 않는다.

### [벤치마크 검증]

현 FLOAT32의 실제 실행·동등성만 검증했다. 별도 INT8/FP16 모델을 만들거나 벤치마크하지 않았다. 양자화 codec 단위 테스트 통과는 현재 모델의 INT8 연산 지원·속도 검증이 아니다.

### [미검증 예상]

9클래스·주간/야간·작은/먼 객체·가림·배경 음성을 포함한 분리된 validation set으로 클래스별 mAP50-95/AP50, precision/recall, small-object recall, bollard 놓침, 오경고를 비교한다. calibration set과 validation set을 분리한다. 같은 Letterbox/색상/정규화·class 순서·0.2/0.45 정책을 유지하고, raw score/좌표 오차 및 confidence/NMS 경계의 최종 검출 변화를 함께 평가한다. 양자화 전후에는 bit-identical을 합격 기준으로 삼지 않는다.

## 6. 640 / 512 / 416 입력 크기 비교

### [코드 사실]

현재 asset의 고정 shape/signature, 8400 후보, graph 상수와 좌표 정규화, 앱의 `MODEL_INPUT_SIZE=640` 검증 때문에 **설정만 512/416으로 바꾸는 방식은 적용할 수 없다.** ByteBuffer 크기 변경이나 `resizeInput()`만으로 anchor/grid/reshape 상수를 자동 변환한다고 보장할 수 없다.

원래 9클래스 YOLO11s checkpoint를 각 `imgsz`로 별도 export하고, 모델 metadata·normalized xywh·class 순서·새 출력 shape를 재검사해야 한다. 새 크기 export 자체가 반드시 재학습을 요구하지는 않는다. 정확도 저하가 크면 해당 크기에 맞춘 fine-tuning/재학습을 검토한다. checkpoint/학습 데이터는 이번 작업에서 확인·변경하지 않았다. 앱의 크기 검증과 STRETCH용 고정 INPUT_RECT도 별도 변경이 필요하며 Letterbox/역좌표 단위 테스트를 새 크기에 맞춰야 한다.

### [벤치마크 검증]

현재 640 모델만 실측했다. 다음 표는 **shape에서 계산한 기하학적 비교**이며 후보 모델 측정값이 아니다.

| 입력 | 픽셀 수 | 면적/공간 Conv 비용의 근사 비율 | 감소율 | 같은 3-scale Detect head의 예상 후보 수 |
|---|---:|---:|---:|---:|
| 640² | 409,600 | 1.0000 | 0% | 8400 (현재 확인) |
| 512² | 262,144 | 0.6400 | 36.00% | 5376 |
| 416² | 173,056 | 0.4225 | 57.75% | 3549 |

### [미검증 예상]

공간 Conv 계열은 대략 면적에 비례하지만 attention의 토큰×토큰 BatchMatMul, padding/분할·메모리·threadpool·카메라 고정비 때문에 전체 latency가 정확히 0.64/0.4225배가 되지는 않는다. 512는 선형 크기 80%, 416은 65%이므로 작은·먼 객체의 특징이 줄어 recall 저하 가능성이 있다. 감소하는 mAP 수치는 아직 알 수 없다. 먼저 512 FLOAT32를 export하여 현 640과 같은 validation set 및 실기기 장면으로 비교할 것을 권한다.

[Ultralytics export 문서](https://docs.ultralytics.com/modes/export)는 `imgsz`, TFLite, FP16/INT8 및 calibration data 옵션을 제공한다. 현재 모델의 좌표 정규화 wrapper 계약까지 자동으로 동일하게 export된다는 보장은 없으므로 재검사가 필요하다.

## 7. YOLO11s / YOLO11n 비교

### [코드 사실]

현재 asset metadata는 YOLO11s 9클래스이다. YOLO11n asset이나 해당 9클래스 학습 checkpoint는 이 작업에서 검증하지 않았다. 범용 COCO YOLO11n을 파일명만 바꿔 배포하면 현 class 계약을 만족하지 않는다.

[Ultralytics 공식 YOLO11 표](https://docs.ultralytics.com/models/yolo11)는 640 기준 n 약 2.6M params/6.5B FLOPs/COCO mAP50-95 39.5, s 약 9.4M/21.6B/47.0을 제시한다. 이는 표준 모델·COCO의 참고 자료이며 **현재 사용자 학습 9클래스 모델의 정확도·Android LiteRT 속도 실측이 아니다.** 공식 CPU ONNX 시간도 Android CPU 성능으로 대입하지 않는다.

### [벤치마크 검증]

YOLO11s만 측정했다. n/s 속도·전력·정확도 비교 실측은 없다.

### [미검증 예상]

9클래스 같은 순서로 학습/fine-tune한 YOLO11n을 동일 export 계약으로 만든 뒤 비교할 수 있다. n의 이론 FLOPs는 표준 s의 약 30%이지만 앱 latency가 30%로 줄어든다는 뜻은 아니다. 640 해상도를 유지하면서 모델 용량을 줄일 수 있어, 512 s와 함께 작은 객체 recall/latency를 비교할 가치가 있다. 기준 데이터·학습 split·클래스별 recall·오경고 및 실기기 지속 성능을 맞춰 평가한다.

## 8. GPU/NNAPI 적용 가능성

### [코드 사실]

[공식 2.1.0 릴리스](https://github.com/google-ai-edge/LiteRT/releases/tag/v2.1.0)와 [현재 Android 지원 표](https://developers.google.com/edge/litert/android)는 Maven 2.1.x의 Interpreter를 CPU only로 명시한다. 실제 2.1.1 AAR의 Interpreter Options에는 `addDelegate`, `setUseNNAPI`가 없고 GPU/NNAPI Delegate 클래스도 없다. 이 버전에 과거 GPU 예제의 코드를 그대로 붙일 수 없다.

같은 AAR에는 `CompiledModel`, `CompiledModel.Options(Accelerator...)`, `CpuOptions/GpuOptions`, 재사용 가능한 `TensorBuffer`, `libLiteRtOpenClAccelerator.so`가 arm64-v8a/x86_64로 포함되어 있다. 이는 CompiledModel GPU 시도를 위한 API·native artifact가 있다는 근거이며 **이 모델·기기에서 GPU 초기화 성공/속도가 입증됐다는 뜻은 아니다.**

| 경로 | 현재 적용 가능성 | 예상 변경 범위·복잡도 |
|---|---|---|
| 현 2.1.1 Interpreter + GPU/NNAPI 옵션 | 제공 API로 적용 불가 | 지원되는 다른 runtime 구성/별도 native build 검토 필요. 단순 설정 추가 범위가 아님 |
| 지원되는 Interpreter GPU runtime/Play services로 이동 | 별도 경로로 검토 가능 | 의존성·초기화·compatibility 확인·전용 실행 스레드·CPU fallback·수명 관리, 모델 회귀 검증. 중~높음 |
| 현 2.1.1 CompiledModel GPU | API·OpenCL artifact 존재; 모델 호환성은 미검증 | 별도 detector adapter, tensor type/name 검사, input/output TensorBuffer 생성/쓰기/읽기·재사용, 실행·close와 실패 복구·성능 로그 연결. 높음 |
| NNAPI | 현 Interpreter에 API 없음. CompiledModel의 NPU 옵션도 NNAPI와 동일하지 않음 | 별도 NNAPI 지원 runtime 또는 native integration 및 드라이버 검증 필요. 신규 도입 우선순위 낮음 |

[공식 GPU Interpreter 가이드](https://developers.google.com/edge/litert/android/gpu)는 별도 지원 runtime/패키지와 생성·실행의 동일 스레드를 요구한다. 현재 앱은 main thread에서 detector를 생성하고 analyzer에서 실행하므로 그 경로로 GPU를 도입한다면 create/run/close의 스레드 수명부터 재설계해야 한다. CompiledModel 역시 선택한 버전/backend의 스레드·buffer 수명 규칙을 확인해야 한다.

[Android NNAPI 문서](https://developer.android.com/ndk/guides/neuralnetworks)는 Android 15에서의 deprecation과 대체 경로 검토를 명시한다. minSdk 33을 만족한다는 사실만으로 NNAPI 가속 드라이버 지원을 보장하지 않는다. 현재 AAR enum은 NONE/CPU/GPU/NPU이며 NNAPI 선택 항목이 없다.

기존 `.tflite`는 CompiledModel 입력 형식이므로 API를 바꾸기 위해 반드시 재학습·INT8 변환부터 할 필요는 없다. 다만 GPU가 현 TRANSPOSE/BATCH_MATMUL/SLICE/resize 및 전체 graph를 수락하는지는 실제 compilation/partition profile로 검증해야 한다. 필요하면 같은 학습 모델을 accelerator 친화적 layout/op로 다시 export해야 한다. FP16 파일로 바꾸는 것만으로 GPU 지원이 생기지는 않는다.

### [벤치마크 검증]

이번에는 GPU/NNAPI를 생성·실행·적용하지 않았다. CompiledModel도 production에 도입하지 않았다. 기기 GPU 드라이버 호환성, partition 범위, fallback, CPU 대비 개선율은 미측정이다.

### [미검증 예상]

GPU는 병렬 Conv에서 이점이 있을 수 있으나 partition 분할·입출력 upload/download·CPU 동기화 비용과 지속 발열 때문에 반대로 느려질 수도 있다. 현재 float 출력 parser와 정책을 유지하는 별도 adapter proof-of-concept로 동일 이미지 결과 및 sustained camera FPS를 확인한 뒤 판단한다. 네이티브 unsupported op를 미리 단정하거나 임의의 배속을 제시하지 않는다.

## 9. 우선순위별 개선 권장안

### [코드 사실]

이번 production 변경은 결과/설정/로그 계약을 유지한다. 모델·입력 해상도·양자화·GPU/NNAPI·thread 기본값 변경은 하지 않았다.

### [벤치마크 검증]

nativeInvoke가 대부분이며 단독 XNNPACK ON에서 thread scaling이 관측되지 않았다. Java 컨테이너 재사용의 latency 개선은 미입증이다. 이 결과를 우선순위에 반영한다.

| 우선순위 | 권장 작업 | 판단 기준 / 아직 검증되지 않은 효과 |
|---|---|---|
| P0 | 같은 foreground/입력으로 CPU 1/2/4 × ON/OFF/ON 재현, worker·native per-op profile; 2.1.1 threadpool 경로와 후보 runtime 비교 | 현재 모델을 유지한 가장 직접적인 native 병목 조사. 실제 worker 사용/클럭/열을 확인하고 카메라 실측과 다른 이유를 좁힘. runtime 변경으로 빨라진다는 보장은 없음 |
| P1 | 원 체크포인트의 512 FLOAT32 export | 모델 구조·9클래스 유지하면서 계산량 감소를 평가. 작은 bollard/원거리 recall 기준을 통과해야 채택 |
| P1 | 같은 9클래스 YOLO11n 640 학습/export와 512 s 비교 | 해상도와 모델 용량의 trade-off를 실제 dataset으로 비교 |
| P2 | full INT8 export/calibration 및 실제 op profile | dtype·fallback·정확도·native 속도 검증 후 채택. float I/O 여부와 codec 비용도 포함 |
| P2 | CompiledModel GPU 별도 adapter 실험 | 현 모델 compilation과 동일 입력 결과·버퍼 전송·지속 발열·실패 후 CPU 복귀까지 검증 |
| P3 | FP16 가중치 export | 저장량 감소 목표에는 유효할 수 있음. CPU latency 목표에서는 native FP16 지원 확인 전 우선순위 낮음 |
| P3 | CameraX/ML Kit 메모리·출력 scan/copy 추가 개선 | allocation/GC profile로 실제 비중이 확인된 부분만 변경. 기존 ownership·탐지 정책 유지 |
| 낮음 | NNAPI 신규 도입 | 현 API 부재·deprecated 상태·기기 드라이버 편차 때문에 GPU/runtime 조사보다 후순위 |

### [미검증 예상]

모든 후보의 안정적 3 FPS 달성과 정확도 허용 범위는 아직 미검증이다. 모델 후보 정확도 하한을 먼저 정하고 성능과 함께 합격 여부를 결정해야 한다.

## 10. 다음 실기기 테스트 계획

### [코드 사실] 재현 명령과 비교 조건

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
./gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:lintDebug

# 고정 모델 검사. Python 결과는 Android benchmark가 아님.
./.venv/Scripts/python.exe tools/inspect_tflite.py app/src/main/assets/best.tflite --report docs/yolo-model-inspection-20261015.json

# 원 writer/run/parser와 새 경로, Letterbox·버퍼 재사용·ON/OFF 회귀
./gradlew.bat :app:connectedDebugAndroidTest `
  -Pandroid.testInstrumentationRunnerArguments.class=com.samin.objectdetection.detector.DetectorCpuOptimizationTest,com.samin.objectdetection.detector.DetectorGoldenTest,com.samin.objectdetection.detector.DetectorPreprocessingRegressionTest

# 카메라 없는 측정. native begin/end와 PID로 로그를 매칭
./gradlew.bat :app:connectedDebugAndroidTest `
  -Pandroid.testInstrumentationRunnerArguments.class=com.samin.objectdetection.detector.YoloBottleneckBenchmarkTest

adb logcat -d -v threadtime -s tflite:I YoloBottleneckBenchmark:I VisionStyleYoloDetector:I

# 최종 카메라 로그의 실제 FRAME으로 재집계. 이번에는 tail을 임의로 제외하지 않음.
./.venv/Scripts/python.exe tools/analyze_performance_logs.py on_a.txt off_b.txt on_c.txt
```

이번 직접 기기 실행은 빌드한 APK를 `adb install -r`로 설치하고 동일 class 목록을 `adb shell am instrument -w -r -e class ... com.samin.objectdetection.test/androidx.test.runner.AndroidJUnitRunner`에 전달했다. golden 원본은 로컬 `build/next-optimization-baseline/positive`, `build/next-optimization-after/positive`에 보존했고 비교 hash·결과를 문서의 JSON에 저장했다.

다음 카메라 비교에서는 모델 SHA·9클래스·0.2/0.45·640 Letterbox·CPU4·ML Kit ON·Geometry ON·기존 Overlay/TTS·ROI/카메라 actual resolution·maxCandidates·진단 설정을 고정한다. ON(A)→OFF(B)→ON(C) 및 역순을 반복하고 각 상태의 안정화 후 최소 수분 측정, 10~15분 지속 시험을 추가한다. 초기 열·충전/절전·foreground·주변 조건을 맞춘다. 테스트 시간과 반복 수는 후속 계획이며 이번 실행 사실이 아니다.

기존 FPS는 성공 completion 수 / 실제 단조 경과시간으로 비교한다. inference/nativeInvoke/getPixels/rgbWrite/outputCopy/candidateScan/NMS, pipeline, totalProcessing, 평균/P95, GC pause, PSS/native heap, process CPU·thermal·cpufreq, ERROR를 함께 기록한다. nativeInvoke는 inference의 부분집합이므로 더하지 않는다. process CPU는 앱 전체 스레드이며 카메라/ML Kit/TTS가 포함된다. ML Kit OFF는 경쟁 원인을 분리하는 추가 진단에만 사용하고 기본값은 유지한다.

### [벤치마크 검증] 이번 실행 결과와 변경 파일

| 검증 | 결과 |
|---|---|
| `:app:assembleDebug` | 성공 |
| `:app:testDebugUnitTest` | 104개, failures/errors/skipped 0 |
| `:app:assembleDebugAndroidTest` | 성공 |
| `:app:lintDebug` | 성공, 0 errors / 174 warnings |
| Python 로그 분석 회귀 | 4개 통과 |
| 변경 전 기기 detector 회귀 | 9개 통과 |
| 변경 후 기기 detector·microbenchmark | 12개 통과 |
| 변경 전후 known.png | input/raw output bytes 및 최종 탐지 정확히 동일 |
| 설정·camera restart·기존 로그·최종 객체 재사용 회귀 | 12개 통과, skipped 0 |
| `git diff --check` | 통과 |

마지막 12개는 SettingsActivity 10개, XnnpackRuntimeSettings 1개, 새 컨테이너 identity를 포함한 Letterbox/재사용 1개다. runtime 테스트는 camera 실행 중 `(4,ON)→(4,OFF)→(2,OFF)→(2,ON)→(4,ON)` 설정 적용, 기존 analyzer drain/Interpreter close, 새 세션별 실제 FRAME·summary 저장, 요청 설정·기존 시간 필드·오류 없음까지 확인했다. 사용자 기존 preferences를 테스트 전 값으로 복원한다. 물리 TTS/beep/vibration 출력은 이 자동 테스트 동안만 끄므로 실제 소리 출력의 청감 검증은 포함하지 않는다. Overlay/TTS/ML Kit production 코드는 변경하지 않았다.

| 파일 | 변경 목적 |
|---|---|
| `app/src/main/java/com/samin/objectdetection/detector/VisionStyleYoloDetector.kt` | 호출용 input 배열/output 맵 재사용; native 경로·성능 로그 경계 보존 |
| `app/src/androidTest/java/com/samin/objectdetection/detector/DetectorCpuOptimizationTest.kt` | 기본 입력을 실제 양성 fixture로 변경; 원 run API reference와 같은 XNNPACK 설정 비교 |
| `app/src/androidTest/java/com/samin/objectdetection/detector/DetectorPreprocessingRegressionTest.kt` | 기존 크기 변경 회귀에서 호출 컨테이너 identity도 검사 |
| `app/src/androidTest/java/com/samin/objectdetection/detector/YoloBottleneckBenchmarkTest.kt` | 1/2/4 thread·ON/OFF/ON·native 잔여·프로세스 CPU 및 재사용 API byte 동등성 측정 |
| `tools/inspect_tflite.py` | 현재 shape signature/bytes·전체 op/type inventory·Conv MAC·BatchMatMul shape·검사 runtime 출력 |
| 이 문서, `docs/yolo-model-inspection-20261015.json`, `docs/validation/yolo-next-optimization-*` | 분석 및 실제 실행 증거 |

### [미검증 예상] 남은 합격 판정

이번 결과는 기존 탐지 동등성과 설정·실행 경로를 검증하는 범위다. 실제 동선에서의 장시간 3 FPS, 모든 클래스의 accuracy, GC pause의 기여, delegate 내부 worker 수·잔여 노드 per-op 비용, 신규 해상도/모델/양자화/가속기의 성능은 추가 검증 대상이다. 다음 변경은 원본 모델과 분리하여 실측 후 채택한다.
