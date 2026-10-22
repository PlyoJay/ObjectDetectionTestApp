# YOLO 추론·장시간 안정성 최적화 분석 — 2026-10-20 테스트 기준

작성일: **2026-10-21, Asia/Seoul**. 분석 대상은 `D:/develop/Projects/TestProjects/ObjectDetection`의 ObjectDetectionTestApp이다. 로컬 HEAD와 `origin/main` 추적 ref는 `53560e44e2b01c6d3bee88b44147e90a7dc2261d`이며, GitHub 커넥터의 최신 커밋 검색 결과도 일치했다. 작업 시작 시 작업 트리는 깨끗했다. 원격 ref 직접 조회는 네트워크 오류로 실패했으므로 원격 ref를 새로 fetch한 결과로 표현하지 않는다.

**이번 변경은 이 분석 문서뿐이다. 앱 소스·모델·클래스 매핑·기본 설정·기존 문서와 테스트 결과를 수정하지 않았고 커밋·푸시하지 않았다.** 로그와 기존 문서는 관측 자료로 읽었으며, 그 안의 과거 작업 지시나 최적화 제안을 이번 변경의 지시로 실행하지 않았다. Android 앱 실행·새로운 실기기 벤치마크·정량 정확도 평가는 수행하지 않았다.

## 1. 현재 실행 구조 및 병목 요약

### 1.1 결론과 판단 범위

1. **가장 큰 시간 병목은 TFLite 네이티브 추론 구간이다.** 512/2T 재검증에서 inference 평균 **339.128ms**, totalProcessing **373.333ms**로 약 **90.84%**다. nativeInvoke 평균도 **339.307ms**로 추론 구간과 거의 같다. 이는 순수 계산만의 시간이 아니라 네이티브 호출 중 스케줄링 대기를 포함할 수 있다. 특정 Conv, XNNPACK worker 또는 메모리 대역폭을 개별 원인으로 확정할 per-op/tid 자료는 없다.
2. **지속 성능의 가장 강한 설명 후보는 열·전력 관리에 동반한 CPU 정책 제한이다.** 2T의 첫 1분/마지막 1분은 **3.90→2.25 FPS**, inference **224.115→392.585ms**, 배터리 온도 **30.8→41.7°C**다. 정책 상한 하락, Headroom 상승, LIGHT/MODERATE 전환이 함께 관측된다. 제한을 설정한 제조사 thermal/power 정책과 앱의 실제 실행 코어는 미확인이다.
3. **코드에서 직접 줄일 수 있는 다음 비용은 큰 카메라 Bitmap의 변환·회전·ROI 복사와 ML Kit 입력 준비/동시 실행이다.** 2T의 bitmap 평균 **17.770ms**, 그 안의 회전 **15.179ms**, 별도 YOLO crop **3.712ms**, ML Kit 준비 **1.589ms**다. ML Kit 네이티브 처리의 YOLO 간섭량은 미측정이다. 이것을 세 번째 관측 병목 영역으로 보되, GC나 ML Kit를 확정된 성능 저하 원인으로 부르지 않는다.

입력/출력 ByteBuffer와 Letterbox Bitmap은 **이미 재사용한다**. 이를 새 최적화로 다시 제안할 이유가 없다. 2T 평균의 비추론 잔여는 **34.205ms, 약 9.16%**다. 그 전부를 없애도 같은 추론 시간에서는 처리 시간 기준 이상적 상한이 약 **2.95회/초**, 개선 배율 **1.101배**다. 실제 프레임 전달·경고·기록과 열 상태가 변하므로 달성 FPS 예측이 아니다. 3 FPS의 333.3ms 예산보다 현재 평균 추론 자체가 길다. 카메라 복사를 줄이는 목적은 작은 평균 지연 절감과 할당·열 부담 완화이며, 초기 3.90 FPS를 지속시키는 효과는 아직 증명되지 않았다.

### 1.2 실제 호출 순서와 소유권

아래 코드 경로의 기준 접두사는 `app/src/main/java/com/samin/objectdetection/`이다. §8에 전체 상대 경로와 핵심 함수를 정리했다.

```text
AppSettings.toDetectionConfig()
  → MainActivity.createYoloDetectorWithRecovery()
    → ModelAssets.load(): asset mmap
    → VisionStyleYoloDetector.init: Interpreter 1회 생성, tensor 검증, 재사용 저장소 준비

CameraController.bindUseCases()
  → CameraX RGBA_8888 / KEEP_ONLY_LATEST / 단일 analyzer executor
  → analyzer 수신 시각·camera timestamp·frameId 생성, 간격 검사
  → ImageProxy.toBitmapSafe(): CameraX.toBitmap() → Bitmap.rotate()
  → Listener.onFrame() → MainActivity.processBitmap() [동기 호출]
    → maybeRunMlKitDetection()
      ROI crop → 640×360 Bitmap → captureWork(Dispatchers.IO) 비동기 process().await()
    → DetectionPipeline.process()
      회전 완료된 full frame에서 ROI crop
      → VisionStyleYoloDetector.detect() [synchronized]
        Letterbox → getPixels → RGB /255, NCHW FloatArray → direct input buffer
        → Interpreter.runForMultipleInputsOutputs()
        → 출력 buffer → 재사용 FloatArray 행 → 후보 scan → confidence/크기 검사
        → confidence 내림차순 전역 maxCandidates 제한 → 같은 클래스 간 NMS
      → Letterbox 역변환 결과에 ROI offset 적용
      → WarningPolicy.evaluate → Geometry → SmallBox → Overlay class
      → 선택적 Temporal → ObjectMotionTracker → 시나리오/voice tuning → warning 대상
    → pipeline 완료 시각 기록
    → 경고 후보/cooldown → WarningOutputController.submit()
    → metrics·선택적 평가/성능 기록
    → Preview ON일 때만 latest UI 전달 → Overlay draw/만료
  → callback 복귀 후 회전 Bitmap recycle
  → finally ImageProxy.close()
```

ML Kit는 YOLO 결과나 경고에 합쳐지는 구조가 아니다. 현재 실행 경로에서는 별도 객체 추적 결과/통계/화면 표시를 담당하고, 경고 후보는 YOLO pipeline 결과에서 만든다. ML Kit를 제품에서 제거한다는 결론은 아니다.

CameraX의 RGBA 모드는 내부 YUV→RGBA 변환을 포함한다. 앱의 `cameraToBitmap` 계측은 analyzer에 전달된 뒤의 `toBitmap()`만 재므로 그 이전 CameraX/ISP 작업을 전부 나타내지 않는다. 요청 해상도 **1280×720**와 실제 회전 후 영상 **1080×1440**도 다르다. 해상도 selector의 fallback과 실제 기기 선택을 구분해야 한다. [CameraX 공식 설명](https://developer.android.com/media/camera/camerax/analyze).

실제 full frame은 1,555,200 pixels로 요청한 921,600 pixels의 **1.6875배**다. `bindUseCases()`는 ResolutionStrategy를 지정하지만 AspectRatioStrategy를 명시하지 않는다. 이것만으로 이번 기기의 선택 원인을 확정할 수는 없으며 지원 해상도/종횡비·실제 stream 구성을 함께 확인해야 한다. 실제 카메라 크기를 줄이는 것은 큰 사본 비용을 줄일 후보지만 보간/노출·작은 객체 정보도 바뀌므로 단순 버퍼 재사용과 다른 정확도 위험 항목이다.

### 1.3 중첩된 시간의 의미

| 필드 | 실제 범위 | 해석 주의 |
|---|---|---|
| `bitmap` | `toBitmapSafe()` 호출 주변, 변환·회전 | `cameraToBitmap`, 회전 시간을 포함 |
| `preprocess` | detector의 resize + inputBuffer | ROI·CameraX 변환을 포함하지 않음 |
| `inputBuffer` | RGB 채우기와 해당 진단 등 | `getPixels`, `rgbWrite`, `inputDiagnostics`는 내부 부분값 |
| `inference` | `runForMultipleInputsOutputs()` 주변 | `nativeInvoke`와 더하지 않음 |
| `post` | detector parse/NMS 구간 + pipeline 정책 후처리 | `outputParsing`, NMS와 더하지 않음. 출력 tensor 진단은 detector post 바깥 |
| `pipeline` | `DetectionPipeline.process()`의 측정된 반환 시점까지 | ROI·detector·정책 포함. `finally`의 debug finish/crop recycle은 반환 객체의 timing 산출 뒤 |
| `totalProcessing` | analyzer 수신부터 `process()`가 복귀한 직후 완료 시각까지 | bitmap·ML Kit 준비·pipeline 포함. 이후 경고 선택/출력·metrics·writer enqueue·UI draw·callback 복귀·ImageProxy close 제외 |
| FRAME `completed*` | **pipeline 완료 시각** | 네이티브 inference 종료나 실제 경고/표시 종료 시각이 아님 |
| ML Kit 평균/`mlKitInFlight` | 비동기 처리 경과 시간/dispatch 직후 힌트 | YOLO CPU 비용 또는 정확한 실행 겹침 시간을 뜻하지 않음 |

`inference/preprocess/pipeline` 등 여러 필드는 정수 ms 내림이고 native·회전 세부값은 소수 ms다. 부분값 합과 부모값이 1ms 안팎 다르거나 native 평균이 inference 평균보다 조금 커질 수 있다. 이를 음수 Java 비용이나 계측 범위 초과로 단정하지 않는다. P95끼리 빼거나 구간별 P95를 평균내어 전체 P95를 만들지 않는다.

## 2. 로그로 확인된 사실과 코드로 확인된 사실

### 2.1 사용 자료와 존재 여부

| 자료 | 확인 결과와 사용 범위 |
|---|---|
| `C:/Users/SAMIN/Downloads/perf_20261020_171606.txt` | 실제 존재, 원문 재집계. 512/4T, session `be5efc24-e28b-4051-b674-82ae6631bebb` |
| `C:/Users/SAMIN/Downloads/perf_20261020_174051.txt` | 실제 존재, 원문 재집계. 512/2T, session `718d8293-7766-440b-974e-b1bf1aa3af4b` |
| `docs/yolo-long-run-performance-analysis-20261015.md`, `docs/validation/yolo-long-run-analysis-20261015.json` | 존재. 이전 640/4T의 정책 상한·지연 동행과 전류 신뢰성 문제 참고. 원본을 새로 재검증한 결과로 취급하지 않음 |
| `docs/yolo-inference-next-optimization-20261015.md`, `docs/validation/yolo-next-optimization-benchmark-20261015.txt`, `docs/yolo-model-inspection-20261015.json` | 존재. 과거 640 모델 native 위임·스레드 실험, 이미 적용된 재사용 최적화 참고 |
| `docs/yolo-inference-bottleneck-analysis-20261014.md`, `docs/yolo-inference-optimization-20261014.md`, `docs/yolo-realtime-performance-analysis-20261013.md`, `docs/xnnpack-settings-implementation-20261014.md` | 존재. 과거 분석/구현 이력. 과거 성능 수치를 이번 512 조건과 직접 합치지 않음 |
| `docs/yolo-model-selection-20261020.md`, `docs/validation/model-selection-models-20261020.json` | 존재. 현재 asset SHA·tensor·class 계약과 대조 |
| `docs/validation/model-selection-640-perf-20261020.txt`, `model-selection-512-perf-20261020.txt` | 존재. 짧은 모델 선택 검증 로그이며 시스템 진단 1000ms 등 조건이 다름. 사용자가 보고한 10분 결과의 원본으로 대체하지 않음 |
| `docs/overlay-frame-freshness-20261019.md`, `docs/camera-preview-toggle-20261019.md`, `docs/battery-power-accuracy-20261019.md`, `docs/battery-thermal-diagnostics-20261015.md` | 존재. 현재 코드의 만료·Preview OFF·진단 의미와 대조 |
| `docs/validation/camera-preview-on-log-20261019.txt`, `camera-preview-off-log-20261019.txt`, `battery-thermal-device-perf-on1-20261015.txt`, `battery-thermal-device-perf-on2-20261015.txt`, `battery-thermal-device-perf-off-20261015.txt` | 존재. 이전 기능/진단 시험 자료이며 최신 장시간 비교와 조건이 같지 않음 |
| 과거 원본 `perf_20261013_171159.txt`, `perf_20261015_112344.txt`, `perf_20261019_164442.txt`, `perf_20261019_164821.txt` | 저장소/작업 공간의 `perf*.txt` 검색, Downloads, 기존 문서에 명시된 `C:/Users/SAMIN/OneDrive/Documents/카카오톡 받은 파일`에서 **찾지 못함**. 과거 분석 JSON/문서의 존재와 원본 부재를 구분 |
| 사용자 보고 640/2T=1.377 FPS, 640/4T=1.688 FPS, 512/2T 1차=2.370 FPS·370ms | 이 세 10분 세션에 대응하는 원본을 식별하지 못함. **사용자 보고값**으로만 기록. 이전 validation 로그를 해당 원본으로 간주하지 않음 |

검색 범위 밖 디스크 전체에 파일이 없다는 뜻은 아니다. 최신 두 원본 SHA-256:

```text
perf_20261020_171606.txt  82d9dc83f1b05461ffb3314986b7cf79ce7493eba5d59fe4ffb662dc433bbc9e
perf_20261020_174051.txt  2566b0b54df66d93a86eb2c5466f0656af5bf051255eae028fcfc36caadcaaef
```

### 2.2 최신 두 세션의 재집계

두 세션 모두 같은 512 모델 SHA, NCHW FLOAT32 `[1,3,512,512]` 입력, `[1,13,5376]` 출력, Letterbox, confidence 0.2/NMS 0.45/maxCandidates 100, ROI 상단 .2·좌우 .1, Preview OFF, ML Kit ON/1500ms, Geometry ON, Temporal OFF, size filter DISABLED, 탐지 간격 0ms다. 시스템·배터리 진단은 ON/5000ms, 상세 tensor/PNG 진단은 OFF다. 실제 beep/TTS/진동 설정은 모두 ON이며 실제 출력 횟수는 이 성능 로그의 warning 수만으로 알 수 없다.

성공 완료 시각은 `completedElapsedNs - Start Elapsed Ns`로 계산했다. `[0,600초)`의 완료 프레임을 집계하고 확정 `CUMULATIVE_SUMMARY`와 대조했다. 실제 종료 600.042/600.034초의 짧은 마지막 빈 구간을 포함한 전체 FPS는 각각 `1232/600.042`, `1560/600.034`다. 두 로그 모두 FRAME 누락 0, 기록 오류 0, 확정 성공 프레임 수와 원문 개수 일치. 초기 몇 프레임은 세션 시작 전에 촬영/처리가 시작됐을 수 있으므로 첫 구간을 순수 cold start 벤치마크로 부르지 않는다.

| 지표 | 512/4T | 512/2T 재검증 |
|---|---:|---:|
| 성공 프레임 / 전체 FPS | 1,232 / 2.05319 | 1,560 / 2.59985 |
| inference 평균 / P95 ms | 420.612 / 655 | 339.128 / 390 |
| nativeInvoke 평균 ms | 420.382 | 339.307 |
| pipeline 평균 / P95 ms | 437.723 / 677 | 353.063 / 406 |
| totalProcessing 평균 / P95 ms | 469.220 / 719 | 373.333 / 431 |
| bitmap 평균 / P95 ms | 27.513 / 39 | 17.770 / 22 |
| cameraToBitmap 평균 ms | 5.243 | 3.047 |
| **회전 시간 평균 / P95 ms** | **22.720 / 33.119** | **15.179 / 17.965** |
| YOLO ROI crop 평균 ms | 5.416 | 3.712 |
| preprocess 평균 ms | 4.191 | 3.247 |
| resize / inputBuffer 평균 ms | 1.235 / 2.955 | 0.804 / 2.444 |
| getPixels / rgbWrite 평균 ms | 0.929 / 2.442 | 0.605 / 2.231 |
| outputParsing / post 평균 ms | 4.954 / 5.014 | 4.846 / 4.862 |
| NMS 평균 / P95 ms | 0 / 0 | 0 / 0 |
| ML Kit 준비 평균 / P95 ms | 2.734 / 12.265 | 1.589 / 7.520 |
| input/output tensor 진단 시간 | 전 프레임 0ms | 전 프레임 0ms |
| 프로세스 CPU 표본 평균 | 126.058% | 121.704% |
| 배터리 잔량 / 온도 | 50→44%, 32.7→38.9°C | 45→37%, 30.8→41.7°C |
| Thermal 상태 전환 시각 | 410.294초 LIGHT | 216.881초 LIGHT, 457.046초 MODERATE |
| Headroom 첫/마지막 표본 | 0.677→0.840 | 0.583→0.923 |

표의 `0ms` NMS는 정수 계측 해상도에서 1ms 미만일 수 있으며 비용 없음의 증거가 아니다. FRAME의 count는 객체 수/대상 수로 정확도나 실제 음성 출력 횟수가 아니다.

**분석 도구에서 확인한 별도 결함:** `PerformanceLogFormatter.formatFrame()`은 `rotation=15.179ms` 같은 시간을 출력한 뒤, `MainActivity.processBitmap()`의 `roiDescription`에서 `rotation=90`이라는 각도를 같은 행에 추가한다. `tools/analyze_performance_logs.py::fields()`는 정규식 결과를 dict로 만들면서 뒤의 키로 덮어써 `details.rotation.averageMs=90`을 잘못 산출한다. 실제 원문에서 `\brotation=([\d.]+)ms`를 별도 추출한 값이 위 표다. 평균 추론·pipeline·totalProcessing에는 이 중복 키 문제가 영향을 주지 않는다. 기존 로그/분석기/테스트는 수정하지 않았다. 후속 수정에서는 시간 `rotationMs`와 각도 `rotationDegrees`를 분리하고 과거 형식도 지원해야 한다.

또한 FINAL SUMMARY의 정수 `Average FPS: 3`을 2T의 정확한 FPS로 사용하지 않았다. `DetectionMetricsCollector`의 legacy 누적/반올림 값과 recorder의 정확한 세션 통계를 구분해야 한다.

### 2.3 이미 구현된 최적화와 남은 할당/복사

| 코드 확인 | 현재 동작 / 남은 일 |
|---|---|
| `VisionStyleYoloDetector.init`, `ModelAssets.load` | Interpreter·mmap을 생성 때 준비. 매 프레임 모델 로드/해시/Interpreter 재생성 없음 |
| `VisionStyleYoloDetector.detect`, `fillInputBuffer` | input/output direct ByteBuffer, FloatBuffer view, pixels IntArray, RGB scratch, 13개 output 행, 호출 배열/맵, Letterbox Bitmap/Canvas/Paint/RectF, 크기별 transform 재사용 |
| `RgbTensorWriter.fillFloat32Bulk` | 256개 `/255.0f` lookup과 NCHW 3 plane scratch 채우기 후 bulk put. scalar direct-buffer 루프로 되돌리는 것은 개선안이 아님 |
| `YoloOutputReader.copy` | 출력 direct buffer에서 재사용 행으로 bulk get. 라이브러리 tensor→앱 출력과 앱 출력→행 복사는 남지만 크기/시간이 작음. zero-copy로 단정 불가 |
| `ImageProxy.toBitmapSafe`, `Bitmap.rotate` | 프레임별 full-size Bitmap과 90도 Matrix/회전 Bitmap. 원본은 회전 뒤 recycle하나 할당/대역폭 비용은 남음 |
| `DetectionPipeline.process` | ROI `[108,288,972,1440]`, **864×1152** crop Bitmap을 매 프레임 준비하고 finally recycle |
| `MainActivity.maybeRunMlKitDetection` | ML Kit 실행 주기에 같은 ROI를 별도로 crop한 뒤 640×360으로 scale. YOLO crop과 공간 결정은 같지만 Bitmap 준비는 중복 |
| `VisionStyleYoloDetector.parseOutput`, `nms` | 5,376개 후보를 scan, threshold 통과 결과·정렬/NMS 리스트 생성. 좌표 min/max·max score 집계는 일반 프레임에도 수행. NMS는 전체 confidence 정렬 후 최대 100개에서 클래스별 억제 |
| `DetectionPipeline.process`, `ObjectMotionTracker.update` | map/filter/copy·결과 snapshot/후보 리스트 반복 생성. tracker는 현재 탐지에 운동 상태를 붙이며 과거 박스를 새 결과로 재출력하지 않음 |
| `DetectionMetricsCollector.recordPerformance`, `PerformanceIntervalCollector.record` | 여러 짧은 synchronized 구간. 최근 표본 100개, detection record 500개, legacy 세션 latency 표본 최대 100,000개. recorder는 구간 histogram. 일부 세션 자료는 측정 중 증가하지만 무제한 프레임 FIFO의 증거는 아님 |

ARGB_8888 기준 full frame 한 장 약 **5.93MiB**, ROI 한 장 약 **3.80MiB**, 512 Letterbox 한 장 **1MiB**, ML Kit 640×360 한 장 약 **0.879MiB**다. full frame+회전+YOLO crop을 매번 별도 할당한다고 단순 계산하면 약 **15.66MiB/처리 프레임**의 Bitmap payload가 오간다. CameraX/native 내부 추가 복사, stride, allocator 재사용은 제외한 **산술 추정**이며 실측 allocation rate·동시 상주 메모리·GC 양이 아니다. input direct/scratch 각 3MiB, pixels 1MiB, output direct/행 각각 279,552 bytes는 재사용 저장소다.

## 3. 장시간 성능 저하 원인 후보와 확인 방법

### 3.1 시간대 대조

아래는 실제 완료 ns로 나눈 60초 구간이다. CPU/Headroom/주파수는 해당 구간 SYSTEM 표본의 산술 평균이다. Headroom에는 10초 주기로 수집해 재사용한 값이 포함된다. 로그의 `INTERVAL_SUMMARY` 시스템 필드는 구간의 마지막 표본이므로 이 표와 평균 방식이 다르다.

| 구간 초 | 2T FPS | 2T inference 평균/P95 ms | 2T CPU % | 2T Headroom | 2T policy9 상한 평균 GHz | 4T FPS | 4T inference 평균/P95 ms | 4T policy9 상한 평균 GHz |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 0–60 | 3.900 | 224.115 / 239 | 121.91 | .684 | 2.560 | 3.300 | 252.646 / 304 | 2.376 |
| 60–120 | 2.850 | 306.596 / 347 | 121.62 | .785 | 1.872 | 2.117 | 408.528 / 649 | 1.760 |
| 120–180 | 2.567 | 344.149 / 347 | 122.23 | .808 | 1.632 | 1.883 | 454.088 / 651 | 1.696 |
| 180–240 | 2.517 | 351.762 / 367 | 122.02 | .829 | 1.600 | 1.917 | 456.748 / 651 | 1.664 |
| 240–300 | 2.417 | 365.179 / 368 | 122.50 | .848 | 1.536 | 1.933 | 444.767 / 651 | 1.664 |
| 300–360 | 2.417 | 365.145 / 368 | 121.73 | .871 | 1.536 | 1.917 | 451.591 / 653 | 1.680 |
| 360–420 | 2.417 | 365.517 / 368 | 121.61 | .883 | 1.536 | 1.900 | 458.658 / 652 | 1.712 |
| 420–480 | 2.400 | 370.056 / 388 | 121.42 | .898 | 1.520 | 1.867 | 468.366 / 689 | 1.632 |
| 480–540 | 2.267 | 388.743 / 392 | 121.36 | .909 | 1.440 | 1.833 | 471.982 / 689 | 1.632 |
| 540–600 | 2.250 | 392.585 / 405 | 120.65 | .919 | 1.440 | 1.867 | 466.616 / 690 | 1.616 |

2T의 FPS는 초기 대비 **42.3% 감소**, 평균 추론은 **75.2% 증가**했다. native 평균도 **224.350→392.809ms**이므로 단순 Kotlin 전처리/Overlay 지연 증가만으로 설명되지 않는다. 4T 역시 초기 대비 FPS 약 **43.4% 감소**했다.

2T policy9는 첫 SYSTEM에서 상한 3.072GHz, 100.338초에 1.632GHz, 220.783초에 1.536GHz, 471.670초에 1.440GHz가 관측된다. 중간 초기 구간에는 상한 반등도 있다. policy0/4/7도 마지막에 1.440GHz 상한이며 governor 이름은 `energy_aware`로 유지된다. 4T policy9는 첫 상한 2.880GHz에서 주로 1.632/1.728GHz로 내려간다. 따라서 “한 번에 MODERATE가 되어 모든 저하가 발생했다”는 설명은 맞지 않는다. **NONE 상태인 첫 1–2분에도 이미 지연이 커진다.**

`CpuFrequencyReader.read()`의 sysfs 값은 **기기 전체 정책**이다. `scaling_cur_freq`는 드라이버 보고 정책값이며 앱 전용 실제 클럭이 아니다. policy9가 앱 실행 코어라는 증거도 없다. 속성은 순차 조회하므로 current가 같은 행의 상한보다 크게 보일 때조차 순간 변화를 고려해야 한다. 프로세스 CPU는 앱의 모든 스레드 합계이며 **100%=한 코어**다. 121.7%를 기기 CPU 121.7% 점유 또는 YOLO worker 1.217개로 번역하면 안 된다. [Linux CPUFreq 설명](https://www.kernel.org/doc/html/latest/admin-guide/pm/cpufreq.html).

### 3.2 원인 후보의 증거 수준

| 후보 | 근거 / 반증 또는 한계 | 확인 방법 |
|---|---|---|
| 열 제한·전력 정책에 따른 native 실행 여력 감소 — **우선 검증** | native 지연 증가·정책 상한 하락·온도/Headroom 상승 동행, 후반 LIGHT/MODERATE. SoC 온도/실행 코어/제한 주체 미기록 | 같은 기기·시작 상태·장면에서 냉각 복귀 후 반복. Perfetto sched/cpu_frequency/cpu_idle, thermal 정보와 native 구간 대조. 최종 단말의 케이스·착용 조건에서도 확인 |
| 4T의 스케줄링/CPU·메모리 경합 — **미확정** | 4T는 초기부터 더 느리고 P95가 큼. 하지만 옵션 4개 worker의 실제 병렬 동작은 증명되지 않음 | 512 동일 SHA로 1/2/4T, XNNPACK 고정, tid별 CPU/runqueue/core 이동/partition 기록. 무프로파일 반복과 병행 |
| ML Kit 동시 처리 — **구조상 가능, 크기 미확정** | 1500ms 시작 간격, 별도 Task가 YOLO와 겹침. dispatch 힌트는 전체 overlap을 설명하지 못함 | Task 시작/완료·CPU trace와 YOLO 구간을 같은 clock으로 기록. 같은 온도/주파수 구간에서 비교. ON/OFF/ON은 원인 분리 실험으로만 수행 |
| Bitmap/객체 할당→GC 또는 native allocator/메모리 대역폭 — **가능성** | 큰 프레임 반복 할당 확인. GC/PSS/RSS/native heap/allocator 지연은 최신 로그에 없음 | 초기/후반 allocation·GC pause·heapprofd 또는 메모리 표본 수집. 지연 spike와 실제 교집합 확인. 누수 판정은 장시간 retained heap 추이로 수행 |
| 진단·writer·로그 경합 — **작은 비용 관측, 인과 미확정** | 별도 worker, bounded writer, 누락 0. 조회 wall 합은 작음. 짧은 session/metrics lock 및 OS I/O 경합은 가능 | enqueue/lock 대기/queue depth/writer 지연과 프로파일. 각 진단 기능만 바꾸는 통제 실험 |
| 프레임 FIFO 또는 Overlay backlog — **현재 구조에서 주원인 근거 없음** | 동기 단일 analyzer, KEEP_ONLY_LATEST, UI coalescing·순서/TTL 검사 | 부하를 걸어 capture→analyzer 나이가 누적 증가하는지, pending 수/실제 draw frameId 확인 |
| Interpreter 재생성/추론 오류 — **최신 두 세션에서 근거 없음** | 생성은 설정/Activity 초기화 때, 세션 옵션 고정, 오류 0 | lifecycle/초기화 횟수·오류 이벤트 계속 감시 |

Thermal Headroom은 잔여 성능 비율이나 °C가 아니다. 현재 코드는 `getThermalHeadroom(0)`을 최소 10초 간격으로 호출하고 표본 시각/age를 보존한다. 1.0은 SEVERE 기준이며 단말별 상태 매핑이 다르다. 공개 상태 NONE만으로 제한 부재를 증명할 수 없다. [Android Thermal API](https://developer.android.com/games/optimize/adpf/thermal).

### 3.3 2 Threads가 더 좋은 이유: 확인과 미확인

이번 **세션 간 관측값**으로 2T는 4T보다 평균 FPS 약 **26.6% 높고**, 평균 추론 약 **19.4% 짧고**, P95 약 **40.5% 짧다**. 운영 후보의 기준을 512/2T로 삼을 근거는 있다. 그러나 시작 온도 **30.8 vs 32.7°C**, 시작 잔량 **45 vs 50%**, 초기 정책 상한, 장면/주변 온도/다른 프로세스 등이 같다고 입증되지 않았다. 스레드 수만 바꾼 인과적 효과의 크기로 보고하지 않는다. 사용자 보고 640에서는 4T가 2T보다 빨랐으므로 해상도·열 상태·런타임 경로에 독립적인 보편 법칙도 아니다.

일반적으로 스레드를 늘리면 병렬 계산이 빨라질 수 있지만, worker 동기화·cache/메모리 대역폭·다른 작업 경합과 발열이 늘 수도 있다. 이 앱의 `setNumThreads()`는 CameraX executor·RGB 루프·ML Kit worker 수를 조정하지 않는다. 단일 Interpreter를 여러 프레임에서 병렬 호출하거나 여러 Interpreter로 처리량을 늘리는 제안은 최신성과 열 예산에 불리할 수 있다.

실제 존재하는 과거 **640/카메라 없는** instrumentation 기록에서는 XNNPACK ON이 1/2/4T 모두 약 545ms·프로세스 약 99%였고, OFF는 4T 약 267ms·333%였다. ON에서 `Replacing 351 out of 353 ... TfLiteXNNPackDelegate ... 5 partitions` 로그를 확인할 수 있다. 이는 과거 APK/640 모델에서 실제 위임한 증거이며 **현재 512 세션의 worker·partition·ON/OFF 우열 증거로 대체할 수 없다**. 짧은 OFF 성능만 보고 XNNPACK을 끄면 지속 CPU/열/전력 특성이 악화될 수도 있다. 기존 ON 요청을 유지하며 재현 실험을 먼저 한다.

최신 2T에서 dispatch 직후 `mlKitInFlight=true`인 363프레임의 inference 평균은 348.185ms, false인 1,197프레임은 336.381ms다. 차이 11.804ms는 인과적 비용이 아니다. 4T에서는 반대로 true 355프레임이 374.093ms, false 877프레임이 439.442ms다. 주기·시간대·주파수·장면이 섞이고 힌트가 false인 추론 도중 Task가 시작될 수도 있어 이 그룹만으로 ML Kit의 득실을 판정할 수 없다.

특히 2T는 이번 로그에서 **더 높은 최종 온도와 더 큰 잔량 감소**를 보였다. “2T가 저발열·저전력”이라는 결론은 현재 자료로 지지되지 않는다. 더 많은 프레임을 처리하는 효과도 있으므로 시간당 에너지와 유효 최신 결과당 에너지를 함께 평가해야 한다.

### 3.4 배터리와 부가 기능 비용

두 최신 로그의 전류 신뢰성은 `SUSPECT_CURRENT_COUNTER_MISMATCH`, counter는 `UNVERIFIED_FUEL_GAUGE_ESTIMATE`다. 2T의 counter 감소 294.880mAh와 API 전류 적분 약 .243mAh의 비율은 **.000823**이다. 4T는 228.920mAh와 .184mAh, 비율 **.000802**다. API current/power의 단순 산술값이나 counter 환산 약 6.363W/5.042W를 **앱 소비 전력·절감량으로 쓰지 않는다**. 단위 임의 ×1000 보정도 금지한다. counter조차 교정되지 않은 단말 전체 추정이며 화면·ISP·카메라·위치·TTS·다른 프로세스를 포함한다.

`SystemPerformanceDiagnostics`는 별도 단일 scheduled executor에서 조회한다. 4T는 SYSTEM 120회 평균 15.561ms와 배터리 121회 평균 8.525ms, 조회 elapsed 합 **2.899초/600초(약 .483%)**다. 2T는 각각 11.453/4.994ms, 합 **1.979초(약 .330%)**다. **CPU 사용률이나 FPS 손실률이 아니라 조회 wall 합**이며 writer/lock 비용은 제외했다. 이 정도 자료로 진단을 장시간 저하의 주원인이라고 볼 수 없다.

`OrderedLogWriter.append()`는 tryAcquire를 쓰는 최대 512개 bounded 큐이며 포맷/쓰기/flush를 별도 단일 worker에서 수행한다. append마다 flush하므로 I/O/wakeup은 남지만 analyzer가 파일 flush 완료를 기다리는 구조는 아니다. 누락 0은 writer 지연이 0이라는 뜻도 아니다. flush 변경은 마지막 로그 유실/종료 drain 계약을 함께 검증해야 한다.

`MainActivity`의 verbose/timing 문자열은 이미 lambda로 지연 생성한다. 반면 `WarningOutputController.logOutput()`과 `MainActivity.logSelectedWarningCandidate/logWarningOutput/logCrowdDecision`에는 일반 경고 진단 문자열/Logcat 작업이 남아 있다. 기능과 필수 경고 이벤트를 보존하면서 변경 없음/반복 skip 이벤트의 상세 포맷만 샘플링할 여지는 있다. 속도·에너지 효과는 미측정이다.

TTS·beep·진동은 lifecycleScope의 출력 작업으로 전달된다. `runOutput()`은 coroutine delay를 써 analyzer를 220ms 동안 sleep시키지 않는다. 하지만 출력 준비·Binder·오디오/TTS 서비스·진동의 기기 자원과 전력 비용은 남는다. `UserLocationTracker.start()`도 권한/제공자 조건에 따라 GPS/Network를 1500ms·거리 0m 기준으로 요청한다. 실제 등록 상태/위치 수신량은 성능 로그만으로 확인할 수 없다. 이 기능들은 임의 비활성화하지 않고 같은 보행 시나리오에서 개별 비용을 측정한다.

## 4. 프레임 최신성과 화면 없는 실행 경로

### 4.1 KEEP_ONLY_LATEST와 결과 순서

`CameraController.bindUseCases()`의 단일 executor에서 `listener.onFrame()`이 동기로 끝날 때까지 analyzer가 유지되고, 모든 정상/오류/skip 경로는 finally `ImageProxy.close()`를 호출한다. YOLO를 별도 unbounded coroutine 큐에 넣는 경로는 없다. 따라서 코드상 **CameraX에 다음 최신 프레임 한 개를 유지하게 하는 정책이 정상 적용될 구조**다. 공식 정책도 분석 중 새 프레임으로 보류 버퍼를 덮어쓴다. [CameraX backpressure](https://developer.android.com/media/camera/camerax/analyze).

최신 두 로그의 capture timestamp는 전 프레임 reliable=true다. capture→analyzer 최대가 4T **135.940ms**, 2T **116.161ms**로 10분 동안 수초·수분 FIFO가 누적된 증거는 없다. 로그의 app `droppedFrames=0`은 CameraX가 내부적으로 버린 센서 프레임이 0이라는 뜻이 아니다. 로그에서 보이는 frameId는 analyzer 전달 순서이지 모든 촬영 프레임 번호도 아니다.

UI는 YOLO/ML Kit 각각 `LatestUiUpdateDispatcher.submit()`의 AtomicReference로 pending 최신 콜백 한 개를 유지한다. 예약 시 과거 콜백을 다시 덮어쓰지 않는다. `OverlayFrameState.update()`는 소스별 frameId 이하를 거부하며, 빈 결과·거부 결과도 마지막 ID를 진전시킨다. 두 detector 상태/좌표 크기/TTL은 분리되어 있어 늦은 ML Kit 결과가 YOLO 결과를 대체하지 않는다.

`OverlayFrameState`는 reliable capture elapsed 기준으로 **1500ms** TTL을 적용하고, capture 비신뢰일 때는 analyzer elapsed를 **화면 만료용**으로만 쓴다. UI 도착 때 이미 만료된 결과, OFF/ON reset 전 촬영 결과를 거부하고, `BoundingBoxOverlay.onDraw()`에서도 재검사한다. 현재 근거로 과거 결과가 새 결과를 덮는 확정 결함을 보고할 수는 없다. 기존 테스트는 존재하지만 이번 작업에서 실기기 재실행한 것은 아니다.

### 4.2 실제 나이와 계측 공백

| 지연 ms, 평균 / P95 | 4T | 2T |
|---|---:|---:|
| capture→analyzer | 100.798 / 119.465 | 82.895 / 99.231 |
| analyzer→Interpreter 호출 시작 | 41.830 / 64.495 | 28.093 / 37.248 |
| capture→inference 시작, 로그의 정수값 | 142.123 / 173 | 110.501 / 130 |
| capture→pipeline 완료, ns 원문 계산 | **570.527 / 835.539** | **456.726 / 520.782** |
| capture→pipeline 완료 P99 / 최대 | 868.917 / 890.929 | 533.177 / 889.748 |
| 한 완료→다음 analyzer 수신 평균 | 17.329 | 10.600 |

pipeline 밖 잔여 작업·ImageProxy close·다음 전달 대기가 마지막 행에 섞여 있으므로 경고/로그 비용이라고 전부 귀속하지 않는다. `1000/totalProcessing`이 실측 FPS와 같지 않은 이유이기도 하다.

보행 중 최신 Preview 위에 과거 촬영 시점 BBox를 그리는 시간 차이는 큐가 없어도 남는다. TTL은 잔상을 제한할 뿐 카메라/신체 이동을 보정하지 않는다. 현재 `ObjectMotionTracker`는 위치 변화 기반 상태 추정이며 촬영 시점 박스를 현재 Preview 위치로 재투영하는 기능이 아니다. 좌표 오류와 시간차 오류는 정지 패턴/회전 패턴/실제 보행을 나누어 확인해야 한다.

`inferenceStartElapsedNs`는 Java Interpreter 호출 직전이다. 라이브러리 내부 input set/JNI 진입이 뒤따르므로 정확한 native 시작 시각과 같다고 단정하지 않는다. `nativeInvoke`는 내부 호출의 소요시간이며 절대 시작/종료 시각을 제공하지 않는다.

**부족한 시각:** Interpreter 호출 종료 ns, 라이브러리 내부 native 시작/종료, warning 후보 확정/submit/실제 진동 시작/beep 시작/TTS utterance 시작, analyzer callback 복귀·ImageProxy close, ML Kit 준비 완료/Task 실제 실행 구간이다. 현재 `completed*`를 inference end나 warning end로 대체하지 않는다. UI 전달·draw는 Preview ON의 별도 OVERLAY/진단 이벤트로 연결할 수 있으나 OFF 세션에는 없다. 후속 코드에서는 우선 공개 호출 종료를 기록하고 내부 native 시각은 별도 profiling이 확인하는 범위로 구분한다.

`WarningOutputController.submit()`은 null 후보/cooldown/출력 진행·CRITICAL interrupt 정책을 적용하지만 capture age 제한을 받지 않는다. `runOutput()`의 TTS 전 220ms 및 선택적 beep delay 동안 더 오래된 후보가 남을 수 있다. `TtsWarningPlayer.speak()`의 `QUEUE_FLUSH`는 음성 큐 정책이며 영상 최신성 보장은 아니다. 이것은 **경고 최신성 검증 공백**으로 분류한다. 새 TTL이나 자동 취소는 장애물 경고 누락을 만들 수 있으므로 계측 후 별도 동작 명세·회귀 검증을 거쳐야 한다.

### 4.3 Preview OFF와 실제 headless의 차이

OFF에서 `CameraController`는 Preview use case/surface를 만들지 않고 ImageAnalysis만 bind한다. `processBitmap()`과 ML Kit 완료 경로는 화면 전달을 생략하고, `MainScreenView`는 Preview/Overlay를 GONE 처리한다. 따라서 **Overlay Draw=N/A/-1, OVERLAY 이벤트 0은 정상 미표시**다. 화면이 없는 운영에서도 경고용으로 사용하는 policy/필터를 이름에 Overlay가 들어간다는 이유로 제거하면 안 된다.

현재 구조는 여전히 `MainActivity`를 LifecycleOwner로 삼고, UI 객체 생성과 1초 상태/타이머 루프, metrics를 실행한다. manifest의 유일한 foreground service는 **mediaProjection용 ScreenRecordService**이며 camera foreground service는 없다. **Preview OFF가 화면 꺼짐·Activity 중지·재부팅 뒤 headless 지속 실행을 보증하지 않는다.** 실제 단말에서 lifecycle/권한·카메라 중단/복구를 먼저 검증해야 한다.

후속 제품화에서는 카메라/추론/경고 수명을 Activity UI에서 분리하는 설계가 필요하다. 일반 앱의 camera foreground service에는 유형·권한 및 while-in-use 시작 제한이 있으므로, 최종 단말의 OS/관리형 배포 방식에 맞춰 확인한다. 무조건 BOOT_COMPLETED에서 시작하거나 wake lock을 추가하는 식으로 해결했다고 간주하지 않는다. [Android camera foreground service 제약](https://developer.android.com/develop/background-work/services/fgs/service-types#camera).

## 5. LiteRT/XNNPACK 및 별도 백엔드·모델 대안

### 5.1 현재 CPU 실행에서 확인한 것

`app/build.gradle.kts`는 **`com.google.ai.edge.litert:litert:2.1.1`**, CameraX **1.3.4**, ML Kit object-detection **17.0.2**다. `VisionStyleYoloDetector.init`는 `Interpreter.Options.setNumThreads(n)`와 `setUseXNNPACK(useXnnpack)` 후 Interpreter를 한 번 만든다. `AppSettings.toDetectionConfig → MainActivity.createYoloDetectorWithRecovery → detector`로 옵션을 전달한다. 생성/설정 검사·재시작과 live detect를 분리하고 analyzer drain 후 close한다.

로그의 내부 runtime **2.21.0**과 Maven **2.1.1**은 별도 버전 표기다. `InferenceExecutionInfo`의 `appliedBackend=CPU`, `initialization=INTERPRETER_CREATED`, `xnnpackRequested=true`는 앱 옵션/생성 상태다. `delegateVerification=UNAVAILABLE_JAVA_API`를 유지한다. `cpuFallbackCount=0`은 이 코드의 기록값이며 미위임 op 수 0, GPU에서 CPU로 fallback하지 않았다는 독립 증거, XNNPACK 전체 coverage를 의미하지 않는다.

확인 수준:

- **현재 소스/로그로 확인:** 요청 ON, 스레드 옵션, 생성 성공, 추론 오류 0.
- **현재 자료로 불가:** 512 그래프의 실제 위임 노드/partition·worker 수, 세부 op 시간, 모델 전체 native CPU active 시간.
- **추가 확인:** Interpreter 생성 **전부터** 같은 PID/session/model SHA/variant의 Logcat을 수집해 XNNPACK 생성과 그래프 replacement를 구분. 생성 메시지 한 줄만으로 coverage를 계산하지 않음. 앱 trace와 Perfetto의 tid/sched를 결합하고 같은 버전 benchmark의 per-op profiling으로 보완. 업스트림 README의 일반 옵션/지원 조건을 현재 AAR 내부 동작의 증거로 대체하지 않음. [XNNPACK 공식 지원/프로파일 문서](https://raw.githubusercontent.com/google-ai-edge/LiteRT/main/tflite/delegates/xnnpack/README.md), [공식 benchmark 안내](https://developers.google.com/edge/litert/models/measurement).

CPU 개선은 단일 소유 Interpreter/재사용 버퍼를 유지하면서 worker 전달/활성 실행을 먼저 검증하고, 1/2/4T·런타임 후보를 별도 실험으로 비교한다. 단순 스레드 증가, 다중 프레임 병렬화, affinity/governor 고정이나 고성능 모드 강제는 현재 권고하지 않는다. 새로운 런타임은 고정 golden input으로 수치/탐지 차이와 지속 자원 사용을 확인한 뒤 채택한다.

### 5.2 모델과 런타임 지원을 실제 확인한 별도 대안

기존 `.venv`와 `tools/inspect_tflite.py::inspect()`로 **현재 512 asset을 읽기 전용 재검사**했다. SHA는 최신 로그 및 10/20 검사 자료와 일치한다. 모델은 **37,926,343 bytes**, 입력 3,145,728 bytes, 출력 279,552 bytes, 정적 단일 subgraph/353 ops, FLOAT32 535 tensors·INT32 shape/index tensors 37개, quantized tensor 0개다. 주요 op는 CONV_2D 81, DEPTHWISE 7, TRANSPOSE 14, SLICE 23, BATCH_MATMUL 2, SOFTMAX 2다. Conv2D만 정적 합계 **6.805504G MAC**이며 전체 FLOPs나 개별 병목 비중이 아니다. host Interpreter의 XNNPACK 생성 메시지는 Android 위임 증거가 아니다.

현재 로컬 Gradle 캐시 `litert-2.1.1.aar`도 읽었다. SHA-256은 `31837e6893a106ae75cdb8a379b694c1ba231578bcfe1cfdb439822fdba44531`이다. `classes.jar`에는 Interpreter, CompiledModel, TensorBuffer, Environment가 있고 GpuDelegate/NnApiDelegate 클래스는 없었다. arm64 native에는 `libLiteRt.so`, `libLiteRtOpenClAccelerator.so`가 있다. 앱은 CompiledModel을 사용하지 않는다. 공식 2.1.1 표와 2.1.0+ 릴리스도 이 Maven Interpreter를 **CPU only**로 명시한다. [공식 Android 버전/API 표](https://developers.google.com/edge/litert/android), [v2.1.0 릴리스](https://github.com/google-ai-edge/LiteRT/releases/tag/v2.1.0).

| 별도 후보 | 현재 확인된 지원 범위 | 다음 단계와 위험 |
|---|---|---|
| 같은 512 FP32 + GPU CompiledModel | 런타임에 API/OpenCL 구현 존재. **이 모델·최종 기기에서 compile/run/전체 위임 성공은 미검증** | input/output TensorBuffer 계약, NCHW↔NHWC TRANSPOSE·BATCH_MATMUL·SLICE 등의 실제 shape/op version 지원·분할·복사/동기화 비용을 실기기에서 확인. 전체 처리·경고 지연·온도/에너지·정확도 비교 후 판단 |
| NNAPI/NPU | 현재 앱/API 경로에 연결 안 됨. 최종 SoC/driver·모델 실제 지원 미검증 | NNAPI는 Android 15부터 deprecated. NPU 필요 시 최종 SoC의 LiteRT accelerator 지원과 실제 offload를 별도 검증. ON이라는 이름으로 NPU 실행을 주장하지 않음. [Android NNAPI](https://developer.android.com/ndk/guides/neuralnetworks) |
| FP16 weights export | 현재 asset은 FP32. 파일 축소 가능성만으로 CPU 지연 감소 보장 불가 | CPU에서 FP32로 실행될 수 있음. FLOAT16 입력과 FP16 weight는 구분. GPU 후보와 수치/정확도 검증을 묶어 별도 모델 실험 |
| INT8/PTQ·필요 시 QAT | 현재 모델에는 양자화 tensor 없음. 입출력 codec 지원과 전체 graph 가속은 별개 | 실제 보행 대표 calibration·holdout 분리, 9클래스/작은 장애물/역광·야간 recall 검증. 모델/매핑/정규화 계약 변경 위험 높음 |
| 512보다 작은 입력·작은 모델·재학습 | 기존 코드 최적화와 별도. 512 육안 확인은 정량 정확도 검증을 대체하지 않음 | 시간/열 효과 미측정. 먼/작은 bollard·표지판·차량 탐지와 경고 recall/시간을 먼저 평가 |

512의 클래스 순서는 `bollard, person, bicycle, car, motorcycle, bus, truck, stop sign, bench`다. 그래프 normalized xywh + 9 class scores, RGB /255, 추가 objectness/sigmoid 없음이라는 계약을 보존한다. 모델 대안의 속도 배율/전력 절감을 현재 수치로 제시할 근거는 없다.

## 6. 개선 항목별 효과·수정 범위·위험도

아래는 **후속 제안**이며 이번에 적용한 앱 수정이 아니다. 효과의 숫자는 기존 비용 예산 또는 산술 상한이며, 변경 후 실측 향상이 아니다.

| 우선순위 / 항목 | 예상 효과와 근거 | 수정 범위·핵심 함수 | 위험도·보존 조건 |
|---|---|---|---|
| P0 계측 키 충돌 수정·시간 경계 추가 | 분석 오류 제거. FPS 효과 없음. Interpreter 호출 종료/경고 실제 시작/callback 종료/ML Kit span을 구분 | `metrics/PerformanceLogFormatter.formatFrame`, `MainActivity.processBitmap`, `FrameTrace`, `detector/VisionStyleYoloDetector.detect`, `tools/analyze_performance_logs.py::fields/detail_stats`, 관련 테스트 | 낮음~중간. 기존 로그 파싱 호환·미측정 -1 유지. trace 과다 수집 부하 통제 |
| P0 512/2T 기준의 온도 통제 반복·worker/per-op 확인 | 가장 큰 native 339ms와 후반 증가의 원인 분리. 속도 효과 **미확정** | `detector/YoloBottleneckBenchmarkTest` 등 기존 계측 테스트, 측정 운영. 기본값 변경 없음 | 낮음. cold/steady·camera/no-camera·현재 512/과거 640 결과 분리 |
| P1 ML Kit ROI→640×360 직접 draw·전용 Bitmap 재사용 | 주기적 큰 ROI 임시 Bitmap 제거. 평균 준비 1.589ms가 측정 범위. allocation·열 효과 **추정** | `MainActivity.maybeRunMlKitDetection`, 필요 시 `MlKitObjectDetector.detect` | 중간. single-in-flight/Task 완료 전 buffer 재사용 금지. crop 뒤 scale과 같은 샘플링·좌표 보장, 예외/종료 반환 |
| P1 YOLO용 ROI view/원본 rect에서 Letterbox draw | YOLO crop 평균 3.712ms 및 약 3.80MiB 반복 payload 제거 후보. 절감 시간 **미검증** | `DetectionPipeline.process`, `VisionStyleYoloDetector.detect`, `YoloResizeTransform.calculate` | 중간~높음. 바깥 픽셀 bilinear 혼입·rounding·pad114·ROI origin/scale 변경 위험. 입력 bytes 동등성 통과 전 적용 금지 |
| P1 warning/metrics의 불필요한 문자열·복사 절감 | pipeline 완료 뒤 gap 평균 10.600ms 중 일부 후보. 전부가 로그 비용은 아님. FPS/전력 효과 **미측정** | `MainActivity` 경고 로그 함수, `WarningOutputController.logOutput`, metrics 호출부 | 낮음~중간. 경고 출력/필수 이벤트·cooldown/counters·기록 재현성 보존. 새 로그 생략 정책은 동작과 별도 |
| P1 ML Kit 스케줄링을 실제 단조 시각/실행량으로 관리 | wall clock 변경 민감성 제거, 불필요 동시 부하 완화 가능. YOLO 이득 **미확정** | `MainActivity.maybeRunMlKitDetection`, 설정 연결·계측 | 중간~높음. 기능·주기·유효 결과 나이 보존. 별도 executor만으로 native CPU 격리 안 됨. 무조건 직렬화는 지연 합산 위험 |
| P2 카메라 원본/회전 저장소 재사용 또는 회전·ROI·resize 통합 | bitmap 17.770ms 중 큰 회전 15.179ms가 대상. full-size 사본/Matrix 할당 절감 기대 **추정** | `camera/ImageProxyExt.toBitmapSafe/Bitmap.rotate`, `CameraController.bindUseCases`, detector 입력 준비 | 높음. RGBA stride/channel order, YUV 변환, 0/90/180/270, cropRect와 ROI의 좌표계, ML Kit 수명/호환성 검증 필요 |
| P2 실제 카메라 해상도/센서 FPS 협상 확인 | 요청 대비 1.6875배 pixels를 처리 중. 실제 크기·생산량 절감의 효과는 **미측정** | `CameraController.bindUseCases`, 카메라 지원 stream/FPS·노출 계측 | 높음. 모델 입력 512 유지와 별개로 영상 정보·motion blur·작은 객체 recall이 달라짐. 현재 카메라 FPS는 로그만으로 확정 불가 |
| P2 headless 실행 수명·UI 분리 | UI 생성/1초 상태 작업 감소는 작을 것으로 **추정**. 화면 꺼짐/재시작 안정성은 별도 핵심 요구 | `MainActivity`, `CameraController`, `MainScreenView`, `AndroidManifest.xml`, 신규 camera 실행 owner/service 설계 | 높음. camera 권한·foreground lifecycle·중복 실행·시작/중지/drain·경고 유지 검증. 즉시 변경에 포함하지 않음 |
| P2 적응형 시작 간격/부하 예산 | 지속 발열 완화 가능 **추정**, 갱신 간격 증가. ML Kit/진단을 임의 OFF하지 않음 | `CameraController` interval 검사, 선택적 scheduler/설정/metrics | 높음. 보행 최소 갱신률·경고 지연·사라짐/재진입 recall 명세가 먼저 |
| P2 파서 진단 집계·후처리 할당 최소화 | 후보 scan/outputParsing 평균 4.846ms 중 일부. NMS 정수 0ms여서 우선도 낮음 | `VisionStyleYoloDetector.parseOutput/nms`, `DetectionPipeline.process`, metrics snapshot | 중간. 안정 정렬/tie·전역 max100·클래스별 NMS·비유한 값 처리와 진단 필드 유지. 결과 객체를 비동기 소비 중 재사용 금지 |
| P2 writer batching/metrics histogram 정리 | I/O wakeup·lock/기록 메모리 개선 가능 **미측정** | `logging/OrderedLogWriter.append/close`, `PerformanceLogSession`, `DetectionMetricsCollector` | 중간. bounded 큐·즉시성/마지막 로그 보존·오류 전달·정확한 session P95/종료 drain 유지 |
| P3 런타임/CompiledModel CPU/GPU/NPU·모델 변경 | native 비중이 큰 만큼 큰 개선 가능성은 있으나 **지원/효과 미검증** | detector 실행 추상화, `app/build.gradle.kts`, 별도 검증 실험 | 높음. §5의 실제 지원·장시간 수치·정확도 관문 후 판단 |

입력 버퍼 재사용·bulk RGB·호출 배열/맵 재사용·lazy timing 로그는 **완료된 기존 최적화**다. 출력 tensor native 메모리 직접 참조나 재사용 버퍼를 여러 스레드에 공유하는 변경은 작은 복사 절감에 비해 수명/동기화 위험이 커서 후순위다. synchronized 제거 역시 현재 단일 analyzer에서 실측 contention이 확인되기 전에는 제안하지 않는다.

탐지 interval은 **시작 시각 사이의 최소 간격**이다. 현재 처리보다 짧은 100/200ms는 지속 400ms 안팎의 loop에 실제 휴식을 거의 만들지 않는다. 반대로 600ms 고정 간격은 완료 뒤 600ms 휴식이 아니며 최신 결과 갱신을 늦춘다. 실험 시 카메라가 고속으로 계속 생산/변환하는 비용도 따로 확인해야 한다.

## 7. 우선순위별 실행 계획과 통과 기준

### 7.1 권장 작업 순서 / 후속 코드 수정안

1. **관측 정확성 수정:** rotation 시간/각도 이름 충돌 및 과거 로그 파서 회귀부터 처리한다. 다음 작은 변경으로 Interpreter 호출 종료, callback/close, ML Kit 실행 span, warning 실제 시작을 session/frameId+단조 clock으로 연결한다. 탐지/경고 정책은 유지한다.
2. **기준선 재현:** 512/2T/XNNPACK 요청 ON·현재 ROI/Letterbox·ML Kit/경고/진단 ON·Preview OFF를 그대로 10분 반복한다. 워밍업·시작 온도/잔량·환경을 기록하고 30–60분 soak로 확장한다. 1/2/4T를 순서 교차·냉각 복귀해 비교한다. 과거 단독 benchmark의 worker 이상 관측을 512에서 재검증한다.
3. **할당 제거를 작은 단위로 적용:** ML Kit ROI 중간 복사 제거/전용 입력 수명 → YOLO ROI 중간 복사 제거를 서로 다른 변경으로 검증한다. full-size 회전 통합은 golden 검증·실측 결과를 본 다음 진행한다. 동일성에 실패하면 입력 변환 변경은 보류한다.
4. **동시 작업과 지속 부하 조정:** trace 근거로 ML Kit dispatch/경고 로그·metrics 비용을 줄인다. 기능을 유지한 스케줄링으로 평가하고 변경 주기/경고 age 정책은 별도 명세로 분리한다. 시스템·배터리 진단 ON/OFF 시험은 진단용이며 최종 기능 제거로 연결하지 않는다.
5. **실제 화면 없는 단말 운영 검증:** UI와 카메라 실행 owner 분리·foreground lifecycle/복구 설계를 독립 작업으로 수행한다. Preview OFF 단말 측정과 화면 OFF 측정을 구분한다.
6. **모델/백엔드 별도 실험:** CPU 지속 성능 한계가 남으면 같은 512의 CompiledModel/GPU 지원부터 PoC. 양자화/더 작은 모델·입력·재학습은 정확도 평가 데이터가 갖춰진 뒤 별도 작업으로 진행한다.

후속 작업은 이 순서의 **여러 작은 변경**으로 나누고, 각 변경에 새 실측과 수치/경고 회귀 결과를 붙인다. 기본 640/4T나 사용자 저장 설정을 이번 분석만으로 512/2T로 바꾸지 않는다.

### 7.2 수정 후 검증할 테스트와 통과 기준

다음 숫자형 허용 범위는 **검증 계획의 제안값**이며 보행 안전성에 대해 합의된 제품 요구나 이번에 통과한 결과가 아니다. 평균 FPS만으로 통과시키지 않는다.

| 검증 | 통과 기준 |
|---|---|
| 변경 범위·모델 계약 | 기존 두 모델/labels SHA 불변, 9클래스 순서·normalized xywh·RGB /255·입출력 shape/크기·confidence/NMS/후처리 기본값 불변. 기존 저장 설정/프리셋/rollback 정상 |
| 전처리 동등성 | 같은 source의 0/90/180/270°, portrait/landscape, 홀수 크기/stride·padding, 경계/비대칭 ROI, Letterbox/STRETCH에서 기존 기준 입력과 비교. **계산 동일 경로 목표 변경은 input bytes 일치**. 직접 crop/scale의 pixel 차이는 자동 허용하지 않고 원인/정확도 검증으로 분리 |
| 출력/정책 회귀 | 같은 backend/input에서 기존 golden 결과 동일. runtime/thread 변경 시 우선 raw 오차 `1e-5 + abs(expected)*1e-4`, confidence `1e-4`, 좌표 `0.1px`를 과거 회귀 기준으로 사용하되 임계 근처 검출의 추가/삭제·클래스/NMS 결과·warning 후보/쿨다운은 별도 전수 비교 |
| 메모리 소유권 | ML Kit await 완료 전에 재사용/recycle 없음. detector/Canvas buffer는 단일 소유. 오류/중지/설정 재시작/중복 close 시 use-after-recycle·누수·deadlock·실행 중 Interpreter close 없음 |
| 최신성/순서 | 인위적 느린 추론·UI 정체·ML Kit 지연에도 pending 프레임/작업 수가 유계, frameId 역전/빈 결과 후 부활 0. 1500ms 만료 프레임은 실제 draw에서 0. capture 비신뢰일 때 지연은 -1, 화면 만료 fallback만 사용 |
| latency 회귀 | 동일 통제 환경의 변경 전후 각각 3회 이상. 초기/후반 capture→analyzer/inference start/pipeline complete/실제 경고 start, P50/P95/P99/최대/갱신 공백을 비교. 제안: 지속 구간 P95 악화 **5% 이내**, 긴 꼬리/spike 증가 없음. 2T 현 관측 completion P95 520.8ms/P99 533.2ms/최대889.7ms는 참고선이며 출력까지의 목표 아님 |
| FPS/지속 성능 | 성공 완료 수÷정확한 elapsed로 계산. 제안: 변경 후 마지막 5분 FPS가 대응 기준보다 5% 넘게 나빠지지 않고, 개선 주장에는 반복에서 ≥5% 이득 또는 유의한 allocation/자원 절감 근거 필요. 첫 1분 3.90 FPS 회복만으로 성공 판정 금지 |
| 30–60분 soak | 오류/재초기화 루프/OOM/ANR 0, pending 작업 지속 증가 없음. 제안: 15–20분과 25–30분 FPS 차이 10% 이내를 우선 안정 구간 관문으로 삼고 장면/열 상태가 다른 경우 분리 보고. 30분 안정 자체는 현재 미확인 |
| CPU/열/에너지 | 같은 기능/장면에서 프로세스 CPU·GC·heap/PSS 추이 및 최대/정상상태 온도 비교. 제안: CPU 평균 +5 percentage points 초과 또는 배터리 온도 +1°C 초과 시 재검토, SEVERE 이상 지속 전환 없이 유지. 실제 허용 온도는 최종 하드웨어 규격으로 정함. current 경고 시 mW 절감 주장 금지 |
| ML Kit 기능 보존 | ON 기본/1500ms 동작·single-in-flight·같은 ROI 좌표·결과 최신성·추적 유지. 시작 간격과 실제 Task 빈도·나이를 별도 확인. OFF 실험 결과는 기능 보존 통과로 간주하지 않음 |
| 경고 실효성 | 정지/보행/급회전, 사람/볼라드/접근 차량/군중/빈 프레임·위험 사라짐·CRITICAL interrupt에서 후보·메시지·우선순위·cooldown·실제 TTS/beep/진동 동등. output submit 성공과 실제 재생 시작을 구분. 새 age 제한 도입은 경고 recall/최대 반응시간 요구가 먼저 |
| Preview/headless | ON/OFF/ON에서 동일 detector·좌표, OFF의 OVERLAY 없음은 정상, UI 업데이트 억제 확인. 화면 꺼짐·Activity 중지/복귀·프로세스 재생성·권한 거부/회수·카메라 사용 불가/복구를 최종 단말에서 검증. foreground/시작 제한을 우회한 테스트로 통과 처리하지 않음 |
| 기록/진단 | dropped log 0, FRAME 성공 수와 확정 누적·구간 수 일치, deadline 경계·최종 battery endpoint/drain 보존. 중복 rotation 키·과거 형식·정수 FPS·N/A/미표시·current 경고 회귀 통과 |
| 정량 정확도 | 우선 같은 512의 코드 변경은 동등 입력/출력·영상 검출/경고 동일성. **512의 제품 채택/모델 변경**에는 별도 holdout의 mAP50:95, 클래스별 precision/recall, 작은/원거리·야간/역광 장애물 recall, 경고 오탐·누락·최초 경고 시간 분포 필요. 데이터/허용 recall 하락량은 아직 합의되지 않아 정확도 통과를 선언할 수 없음 |

측정은 같은 단말/OS/build variant/앱/모델 SHA, 카메라 실제 해상도·프레임률·회전/ROI, 장면/노출/조도, 화면 상태, 위치/네트워크, 경고 발생 시나리오, 케이스/통풍, 충전 해제·절전 설정을 기록한다. 시작 배터리 온도 ±1°C·잔량 ±5 percentage points 이내를 반복 시작 조건의 제안으로 삼고, 표면/SoC 열이 안정될 때까지 냉각한다. 순서 교차 A/B/A 및 후보별 3회 이상으로 환경 차이를 확인한다. Perfetto/메모리 profiler는 초기/후반 짧은 구간만 수집하고 무프로파일 세션을 별도로 둔다.

현재 `metrics/PerformanceTestWindow.kt::ALLOWED_MINUTES`는 **3/5/10분**만 지원하며 로그 자동 종료가 카메라 실행 종료는 아니다. 30–60분 soak는 앱을 연속 실행하면서 구분된 10분 로그 창/외부 단조 시각 계측을 사용하거나, 별도 테스트 harness를 먼저 준비한다. 여러 창의 P95를 평균내지 않고 원문 표본을 합쳐 계산하며 창 사이 미기록 구간도 명시한다.

전력은 교정된 외부 계측 또는 제조사 확인값으로 표준 부하의 전체 단말 에너지를 측정하고, 동일 기능의 baseline을 둔다. 단순 camera-only baseline 차분도 DVFS/시스템 상호작용 때문에 앱 단독 전력의 완전한 분리가 아님을 표시한다. 잔량/counter는 보조 지표다.

활용할 기존 테스트 경로:

- `app/src/test/java/com/samin/objectdetection/`: `detector/YoloTensorContractTest.kt`, `YoloResizeTransformTest.kt`, `camera/RecognitionRoiTest.kt`, `AnalyzerShutdownTest.kt`, `ui/OverlayFrameStateTest.kt`, `LatestUiUpdateDispatcherTest.kt`, `metrics/PerformanceLogFormatterTest.kt`, `PerformanceLogSessionTest.kt`, `PerformanceIntervalCollectorTest.kt`, `logging/OrderedLogWriterTest.kt`, 기존 policy/warning 테스트.
- `app/src/androidTest/java/com/samin/objectdetection/`: `detector/DetectorGoldenTest.kt`, `DetectorCpuOptimizationTest.kt`, `DetectorPreprocessingRegressionTest.kt`, `ModelSelectionDetectorTest.kt`, `YoloBottleneckBenchmarkTest.kt`, `camera/RecognitionRoiInstrumentedTest.kt`, `CameraPreviewRuntimeTest.kt`, `ui/BoundingBoxOverlayTest.kt`, `settings/XnnpackRuntimeSettingsTest.kt`.
- `tools/test_analyze_performance_logs.py`: 실제 중복 키·ns 경계 구간·N/A·배터리 신뢰성 회귀 확장. 현재 640 고정 전처리 회귀가 있는 테스트에는 **512/실제 ROI** 케이스를 명시적으로 추가한다.

### 7.3 수정 예상 파일 목록

필수 1차(계측/분석 정확성):

- `app/src/main/java/com/samin/objectdetection/MainActivity.kt`
- `app/src/main/java/com/samin/objectdetection/metrics/PerformanceLogFormatter.kt`
- `app/src/main/java/com/samin/objectdetection/metrics/FrameTrace.kt`
- `app/src/main/java/com/samin/objectdetection/detector/VisionStyleYoloDetector.kt` — Interpreter 호출 종료 시각 추가 시.
- `tools/analyze_performance_logs.py`, `tools/test_analyze_performance_logs.py`
- 대응 `PerformanceLogFormatterTest.kt`, `PerformanceLogSessionTest.kt` 및 trace/경고 계측 테스트.

할당·스케줄링 개선 후보(프로파일과 동등성 결과에 따라 선택):

- `camera/ImageProxyExt.kt`, `camera/CameraController.kt`, `pipeline/DetectionPipeline.kt`, `detector/VisionStyleYoloDetector.kt`, `detector/YoloResizeTransform.kt`, `mlkit/MlKitObjectDetector.kt` — 접두사는 모두 `app/src/main/java/com/samin/objectdetection/`.
- 같은 접두사의 `warning/output/WarningOutputController.kt`, `TtsWarningPlayer.kt`, `BeepWarningPlayer.kt`, `VibrationWarningPlayer.kt` — 실제 출력 시각/수명 연결 시.
- 같은 접두사의 `metrics/DetectionMetricsCollector.kt`, `PerformanceLogSession.kt`, `logging/OrderedLogWriter.kt` — 측정에서 의미 있는 비용이 확인된 경우에만.
- 대응 detector/ROI/Overlay/CameraPreview/경고 테스트 및 `YoloBottleneckBenchmarkTest.kt`.

독립 후속 작업: headless owner/service와 `app/src/main/AndroidManifest.xml`, `ui/MainScreenView.kt`; 백엔드 실험 시 `app/build.gradle.kts`와 별도 실행 추상화. `ModelConfig.kt`, `labels.txt`, 기존 `.tflite`, 설정 기본값은 일반 코드 최적화의 수정 대상에 포함하지 않는다.

## 8. 근거 파일·함수 색인과 재현 방법

저장소 root를 기준으로 한 경로다. 아래 파일들은 모두 이번 분석 때 실제 존재를 확인했다.

| 근거 경로 | 확인한 핵심 함수/구조 |
|---|---|
| `app/build.gradle.kts` | LiteRT/CameraX/ML Kit dependency, tflite noCompress |
| `app/src/main/AndroidManifest.xml` | Activity/lifecycle 전제, mediaProjection service, camera service 부재 |
| `app/src/main/java/com/samin/objectdetection/MainActivity.kt` | `createYoloDetectorWithRecovery`, `startCamera`, `processBitmap`, `maybeRunMlKitDetection`, `setupUi`, `postLatestDetectionUiUpdate`, 경고 로그 함수, `onDestroy` |
| `app/src/main/java/com/samin/objectdetection/settings/AppSettings.kt` | `toDetectionConfig`, YOLO/ML Kit/Preview/진단 설정 |
| `app/src/main/java/com/samin/objectdetection/camera/CameraController.kt` | `bindUseCases`, analyzer lambda, `stopAsync`, `CameraFrameTiming` |
| `app/src/main/java/com/samin/objectdetection/camera/ImageProxyExt.kt` | `ImageProxy.toBitmapSafe`, `Bitmap.rotate` |
| `app/src/main/java/com/samin/objectdetection/camera/RecognitionRoi.kt` | `FrameRoi.calculate`, `sourceX`, `sourceY` |
| `app/src/main/java/com/samin/objectdetection/camera/DetectionConfig.kt` | 입력·필터·thread·ML Kit·interval 옵션 |
| `app/src/main/java/com/samin/objectdetection/camera/AnalyzerShutdown.kt` | `shutdownAnalyzer` |
| `app/src/main/java/com/samin/objectdetection/pipeline/DetectionPipeline.kt` | `process`, `createInferenceRect`, `mapToOriginalFrame` |
| `app/src/main/java/com/samin/objectdetection/detector/VisionStyleYoloDetector.kt` | `init`, `detect`, `fillInputBuffer`, `parseOutput`, `nms`, `runSelfTest`, `close` |
| `app/src/main/java/com/samin/objectdetection/detector/YoloTensorContract.kt` | `RgbTensorWriter.fillFloat32Bulk`, `YoloOutputReader.copy`, `TensorStatistics.measure` |
| `app/src/main/java/com/samin/objectdetection/detector/YoloResizeTransform.kt` | `calculate`, `sourceX`, `sourceY` |
| `app/src/main/java/com/samin/objectdetection/detector/ModelAssets.kt` | `load`, `validate`, `identity`, `inspect` |
| `app/src/main/java/com/samin/objectdetection/detector/InferenceExecutionInfo.kt` | `logFields`, 요청/기록 정보와 실제 위임의 구분 |
| `app/src/main/java/com/samin/objectdetection/detector/DetectionDebugRecorder.kt` | `begin`, `modelInput`, `saveInput`, `finish` |
| `app/src/main/java/com/samin/objectdetection/mlkit/MlKitObjectDetector.kt` | `detect`의 InputImage/process/await, STREAM_MODE |
| `app/src/main/java/com/samin/objectdetection/ui/LatestUiUpdateDispatcher.kt` | `submit`, `schedule`, `close` |
| `app/src/main/java/com/samin/objectdetection/ui/OverlayFrameState.kt` | `update`, `expire`, `configure`, `clear` |
| `app/src/main/java/com/samin/objectdetection/ui/BoundingBoxOverlay.kt` | `updateDetections`, `updateMlKitDetections`, `onDraw`, `expireAndSchedule` |
| `app/src/main/java/com/samin/objectdetection/ui/MainScreenView.kt` | Preview/Overlay GONE, `updatePreviewStatus` |
| `app/src/main/java/com/samin/objectdetection/metrics/FrameTrace.kt` | `captureAgeMsAt`, `frameAgeAtInferenceMs`, `analyzerToInferenceMs`, `FrameRateMeter` |
| `app/src/main/java/com/samin/objectdetection/metrics/AndroidSystemMetricSource.kt` | `thermalHeadroom`, `processCpuTimeMs`, `CpuFrequencyReader.read` |
| `app/src/main/java/com/samin/objectdetection/metrics/SystemPerformanceDiagnostics.kt` | `SystemMetricsReader.sample`, `ProcessCpuUsageMeter.measure`, `start`, `stop` |
| `app/src/main/java/com/samin/objectdetection/metrics/BatteryDiagnostics.kt` | `BatteryMetricsReader.sample`, `BatterySummary.record/format`, API 단위/표본 범위 |
| `app/src/main/java/com/samin/objectdetection/metrics/BatteryDischargeTracker.kt` | `record`, current/counter 일관성 판정 |
| `app/src/main/java/com/samin/objectdetection/metrics/PerformanceLogFormatter.kt` | `formatHeader`, `formatFrame`, `formatTrace`, `formatFinalSummary`, `formatInterval` |
| `app/src/main/java/com/samin/objectdetection/metrics/PerformanceLogSession.kt` | `recordFrame`, `close`, diagnostics callbacks |
| `app/src/main/java/com/samin/objectdetection/metrics/PerformanceLogRecorder.kt` | `start`, `recordFrame`, `recordOverlay`, `stopSession`, timer/MediaStore와 logging session 연결 |
| `app/src/main/java/com/samin/objectdetection/metrics/PerformanceTestWindow.kt` | `accepts`, `deadlineNs`, `ALLOWED_MINUTES` |
| `app/src/main/java/com/samin/objectdetection/metrics/PerformanceIntervalCollector.kt` | `record`, `interval`, `finalSummaries`, histogram nearest-rank P95 |
| `app/src/main/java/com/samin/objectdetection/metrics/DetectionMetricsCollector.kt` | `recordPerformance`, `recordYoloDetections`, `stopPerformanceSessionSnapshot`, legacy 평균 |
| `app/src/main/java/com/samin/objectdetection/logging/OrderedLogWriter.kt` | `append`, `close` |
| `app/src/main/java/com/samin/objectdetection/motion/ObjectMotionTracker.kt` | `update`, history/track 제거 |
| `app/src/main/java/com/samin/objectdetection/location/UserLocationTracker.kt` | `start`, `handleLocation`, `stop` |
| `app/src/main/java/com/samin/objectdetection/warning/output/WarningOutputController.kt` | `submit`, `runOutput`, `canInterrupt`, `logOutput` |
| `app/src/main/java/com/samin/objectdetection/warning/output/TtsWarningPlayer.kt` | `speak`, `stop`, `release` |
| `tools/analyze_performance_logs.py`, `tools/inspect_tflite.py` | `fields`, `analyze`, `long_run_analysis`, `detail_stats`; `inspect` |

현재 두 로그의 재집계는 기존 분석기의 stdlib 함수를 사용했고, rotation만 원문 단위 명시 패턴으로 보완했다. 간단 재현 예시는 저장소 root에서 아래 Python을 실행하는 것이다. 파일을 쓰거나 기존 자료를 고치지 않는다.

```python
import sys, re, math
from pathlib import Path
sys.path.insert(0, "tools")
from analyze_performance_logs import analyze, fields

for name in ("perf_20261020_171606.txt", "perf_20261020_174051.txt"):
    text = (Path("C:/Users/SAMIN/Downloads") / name).read_text(encoding="utf-8-sig")
    report = analyze(text, interval_seconds=60)
    print(name, report["fpsFromRecordedFrames"], report["inference"], report["totalProcessing"])
    raw = [line for line in text.splitlines() if line.startswith("FRAME ")]
    rotation = sorted(float(re.search(r"\brotation=([\d.]+)ms", line)[1]) for line in raw)
    print("rotationMs", sum(rotation)/len(rotation), rotation[math.ceil(.95*len(rotation))-1])
    frames = [fields(line) for line in raw]
    age = sorted((int(f["completedElapsedNs"]) - int(f["cameraTimestampNs"])) / 1e6
                 for f in frames if f["captureTimestampReliable"] == "true")
    print("captureToPipelineCompleteMs", sum(age)/len(age), age[math.ceil(.95*len(age))-1])
    assert report["frameLogComplete"] and report["droppedLogLines"] == 0
```

§3의 구간도 `analyze(..., interval_seconds=60)`처럼 **완료 ns 기준**으로 나눴다. 소수 세 자리 `elapsedSeconds`만으로 경계 분류하면 실제 600초 직전 프레임이 600.000으로 반올림되어 마지막 1분의 수가 달라질 수 있다. 새 분석 결과의 `details.rotation`은 위 충돌을 수정하기 전까지 그대로 인용하면 안 된다.

이번 분석 검증은 두 원본의 FRAME 수·평균/P95·확정 누적 통계 일치, rotation 원문 단위 추출, capture timestamp 신뢰 표본·완료 지연 계산, 모델/labels/제공 로그 SHA 보존, 문서의 명시적 근거 경로 존재 및 공백/문법 구조 확인이다. 앱 소스 변경이 없어 앱 빌드/JVM·instrumentation 회귀는 실행하지 않았다. 기존 테스트 통과 기록을 이번 실행 결과로 재사용하지 않았다. 최종 작업 트리의 추가 파일은 이 문서 하나다.
