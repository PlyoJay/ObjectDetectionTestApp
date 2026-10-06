# 객체 인식 실패 원인 분리

2026-10-06: 새 `best.tflite`의 NCHW 입력 불일치를 재현하고 수정했다.
최신 모델 구조, Self Test, PC/Android 수치 검증과 절차는
[TFLite inference correctness](tflite-inference-correctness.md)를 참조한다.
아래의 과거 필드 테스트 기록보다 이 문서의 최신 검증 결과를 우선한다.

## A. 현재 코드의 파이프라인

```text
CameraX (1280×720 요청, RGBA_8888, KEEP_ONLY_LATEST)
 → row padding 제거 후 Bitmap 생성
 → ImageProxy.rotationDegrees만큼 Matrix.postRotate
 → 전체 프레임 또는 선택적 중앙 정사각형 ROI
 → 모델 tensor에서 읽은 입력 크기로 bilinear stretch 또는 중앙 letterbox (현재 640×640, 기본 LETTERBOX)
 → RGB 순서, 각 채널 /255.0f, 실제 shape에 따른 NCHW/NHWC 및 dtype/quantization buffer
 → TFLite Interpreter.run
 → 실제 클래스 채널 축에 따라 복사, normalized xywh + 클래스 점수 중 최고 점수 선택
 → 실제 confidence threshold (기본 0.20)
 → 좌표 정규화/0~1 clamp, 유효 bbox 검사
 → detector 면적 필터 (기본 DISABLED)
 → confidence 정렬, 상위 maxCandidates=100
 → 클래스별 NMS (기본 0.45)
 → ROI offset을 더해 회전된 원본 프레임 좌표로 복원
 → WarningPolicy 평가 (경고용 속성 부여)
 → BollardGeometryValidator (기본 ON)
 → SmallBoxFilterPolicy (기본 DISABLED)
 → OverlayObjectFilter 클래스 허용 목록
 → AdaptiveTemporalValidator (기본 OFF)
 → ObjectMotionTracker / 경고 속성 갱신
 → 최신 UI 업데이트 전달 → FIT_CENTER overlay
 → 업데이트 후 1500ms가 지나면 기존 stale clear 로직 수행
```

ML Kit 검출은 별도 경로이며 이 문서의 YOLO 단계별 개수에 합산하지 않는다.
경고 대상 개수와 화면 표시 대상 개수는 다르다. 경고 정책을 통과하지 못해도 표시될 수 있다.
ROI 외부는 추론 전 잘리므로 개별 객체에 `reason=ROI`를 부여하지 않는다.

## B. 확인된 구현과 원인 후보

| 구분 | 코드에서 확인한 사실과 검증 방법 |
|---|---|
| 모델 confidence | 현장 로그만으로는 모델 반응을 확정할 수 없다. RAW_YOLO_SUMMARY의 전체 tensor 후보 최대값을 확인한다. |
| Android 전처리 | 기본 LETTERBOX는 비율을 유지하고 RGB 114 padding을 넣는다. STRETCH는 전체 화면의 종횡비를 바꾼다. 저장 PNG에서 형태·회전·색상·padding을 확인하고 같은 조건에서 A/B 비교한다. |
| output parsing | 현재 asset은 `4 + classes`, normalized xywh, 별도 objectness·sigmoid 없음으로 확인했다. 채널 축은 labels 수와 대조하며 값 크기에 따른 좌표 단위 추정은 제거했다. |
| tensor layout/type | 새 best.tflite는 NCHW인데 기존 writer가 NHWC여서 detection=0을 재현했다. 현재 writer는 실제 shape/dtype에 맞게 쓴다. 자세한 수치 근거는 최신 검증 문서를 참조한다. |
| confidence filtering | 기본 0.20을 유지한다. 입력 layout 정확성을 검증한 후 모델 점수를 평가한다. |
| Geometry/Size | geometry는 bbox 범위·유효성·면적·가로세로 비율을 검사한다. size DISABLED여도 geometry는 독립적으로 동작한다. 단계별 제거 수/이유를 확인한다. |
| ROI | 기본은 전체 프레임. center-square를 켜면 화면 가장자리 객체가 추론 입력에서 빠질 수 있다. FRAME_INPUT의 ROI로 확인한다. |
| Preview 좌표 | Preview와 overlay 모두 FIT_CENTER를 사용하지만 CameraX의 별도 Preview/Analysis 출력과 장치 crop 차이는 실기기에서 확인해야 한다. raw tensor, ROI pixel, rotated-frame pixel 좌표를 혼용하지 않는다. |
| 지연 | 제공된 약 621ms 추론/1070ms overlay frame age는 화면과 bbox의 시간 차이를 만들 수 있다. 낮은 confidence의 직접 원인으로 확정할 수는 없다. 기존 perf 로그와 OVERLAY_DRAW를 함께 본다. |

## C. 변경 파일

이번 작업 전부터 존재하던 미커밋 변경은 보존했다. 이번 작업의 수정 범위:

- `camera/DetectionConfig.kt`: 진단 로그, raw 최소 점수/개수, 입력 저장/주기/검출 저장 옵션. 기존 confidence·geometry 설정 유지.
- `detector/DetectionDebugRecorder.kt` (신규): 별도 로그, bounded 비동기 I/O, 프레임별 trace, 무손실 PNG 저장.
- `detector/VisionStyleYoloDetector.kt`: confidence 적용 전 후보 기록, 최대 confidence, 실제 tensor/전처리 설명, size/invalid/candidate cap/NMS 사유, 입력 Bitmap 저장 연결. 기존 동기식 debug 이미지 저장 코드는 주기 제한 비동기 저장으로 교체.
- `detector/DetectionResult.kt`: ObjectDetector에 기본 no-op 프레임 진단 hook 추가.
- `pipeline/DetectionPipeline.kt`: 동일 frame ID로 ROI·geometry·size·class·temporal·최종 표시 후보 추적.
- `pipeline/DetectionPipelineResult.kt`: UI까지 debugFrameId 전달.
- `MainActivity.kt`: recorder 생성·연결·종료, overlay 로그 연결.
- `ui/BoundingBoxOverlay.kt`: 실제 draw, 표시 OFF, draw 전 교체, stale clear 이벤트 기록.
- `detector/DetectionDebugFrameTest.kt` (단위 테스트 신규): production threshold 미만 기록, cap과 독립적인 최대 confidence, 실제 제거 항목만 기록, OFF 동작 검증.

탐지 threshold, bbox, NMS, geometry, temporal, 경고 정책의 판정 로직은 변경하지 않았다.
이전 프레임의 성공 통계가 추론 실패 시 재사용되지 않도록 detector 통계를 프레임 시작 시 초기화했다.

## D. 설정 및 실기기 테스트

설정 UI에서 `FIELD TEST - LETTERBOX`를 선택해 적용 및 저장한다. A/B 비교는 `FIELD TEST - STRETCH`를 사용한다. 코드의 동일한 프리셋은 아래와 같다.

```kotlin
import com.samin.objectdetection.camera.YoloResizeMode

private val detectionConfig = DetectionConfig.fieldTest(YoloResizeMode.STRETCH)
// 두 번째 실행: DetectionConfig.fieldTest(YoloResizeMode.LETTERBOX)
```

이 preset은 `detectIntervalMs=0`, 전체 프레임, confidence 0.20, NMS 0.45, size/geometry/temporal 필터 OFF, ML Kit OFF, diagnostics/RAW 로그/입력 PNG 저장 ON이다. `DetectionConfig()` 기본 resize는 LETTERBOX이며 다른 production 기준은 유지한다. 저장 주기는 기본 2초다. 평가 JSON에는 전체 설정, 모델 identity 및 단계별 count가 추가되었다. 자세한 형식과 실기기 절차는 [model-evaluation-reliability.md](model-evaluation-reliability.md)를 참조한다.

```kotlin
private val detectionConfig = DetectionConfig(
    bollardGeometryFilterEnabled = false,
    confidenceThreshold = 0.20f,
    debugDetectionLogging = true,
    diagnosticRawConfidenceThreshold = 0.01f,
    diagnosticMaxRawCandidates = 200,
    debugSaveInferenceInput = true,
    debugSaveIntervalMs = 2000L,
    debugSaveOnDetection = true
)
```

기본값은 `debugDetectionLogging=false`, `debugSaveInferenceInput=false`, `debugSaveOnDetection=false`다.
기존 `enableDetectorDiagnostics`는 Logcat 상세 진단 옵션으로 별개다. 새 파일 로그만 사용할 때 켤 필요가 없다.
기존 `enableDetectorDebugImage=true`도 새 주기 제한 PNG 저장을 활성화하는 호환 옵션이다.

1. `fieldTest(STRETCH)`로 빌드하고 같은 피사체의 거리·각도·조명·이동 경로를 정해 촬영한다. Performance Log 버튼을 켜고 충분한 프레임을 기록한다.
2. `fieldTest(LETTERBOX)`로만 바꿔 재빌드하고 같은 장면과 경로를 반복한다. 두 실행에서 confidence, 모델, 카메라, 후처리 설정을 유지한다.
3. 성능 로그 헤더의 `resizeMode`와 MODEL_INPUT의 scaled 크기·scale·padding을 확인한다. RAW_YOLO_SUMMARY의 `maxConfidence`, `maxClass`, `bollardMaxConfidence`, `bollardCandidateCount`를 비교한다. 최대 confidence는 상세 로그 개수 제한과 관계없이 전체 후보를 대상으로 계산한다. Bollard 후보 수는 production threshold 이전의 진단 threshold를 사용한다.
4. DETECTION_FLOW에서 confidence → invalid/size → 후보 제한 → NMS → geometry → size → class → temporal → visible을 비교한다. Geometry ON으로 재시험해 제거 차이를 확인한다.
5. 저장된 640×640 PNG를 열어 실제 bollard 포함 여부, 회전, 색상, 찌그러짐과 LETTERBOX의 회색 padding을 확인한다. 두 실행의 preprocess/inference/postprocess/pipeline 평균·P95와 FPS를 비교한다.
6. PC의 같은 학습 가중치 `best.pt`에 **이 PNG 자체**를 넣는다. 입력 640×640, 클래스 목록, confidence와 NMS IoU를 맞추고 추가 crop/회전/색상 반전을 적용하지 않는다. PC 도구에 원본 카메라 사진을 대신 넣으면 동일 입력 비교가 아니다.
7. Android의 RAW_YOLO 및 YOLO_RESULT와 PyTorch 결과의 클래스·점수·좌표를 비교한다. PC에서 후처리된 결과는 Android의 NMS 이후 결과와 비교한다. pre-NMS raw 후보와 PC 최종 bbox 개수를 직접 동일시하지 않는다. 가능하면 같은 PNG를 PC TFLite에서도 돌려 export 차이와 Android 경로 차이를 추가 분리한다.

좌표 비교: YOLO_RESULT는 **ROI pixel** 좌표다. STRETCH의 PNG 좌표는 x에 `640/roi.width`, y에 `640/roi.height`를 곱한다. LETTERBOX에서는 x에 `scaleX`를 곱하고 `padLeft`를, y에 `scaleY`를 곱하고 `padTop`을 더한다. FILTERED의 geometry 이후 좌표는 회전된 전체 프레임 pixel이므로 ROI offset을 먼저 뺀다. RAW_YOLO는 clamp/스케일 판단 전 tensor xyxy이며 `rawCxCyWh`도 남긴다. tensor 단위는 MODEL_INPUT에 기록된 기존 휴리스틱과 export 사양을 대조한다.

### 저장 위치와 로그 해석

```text
/sdcard/Android/data/com.samin.objectdetection/files/debug_detection/<session>/
  detection_debug_YYYYMMDD_HHmmss_SSS.txt
  input/frame_000123.png
  input/detection_frame_000123_conf_0.310.png
```

외부 앱 디렉터리를 사용할 수 없으면 내부 filesDir로 대체된다. Android Studio Device Explorer 또는 접근 가능한 ADB 환경에서 다음 디렉터리를 가져온다.

```text
adb pull /sdcard/Android/data/com.samin.objectdetection/files/debug_detection ./debug_detection
```

기존 `perf_*.txt`는 기존 Documents 저장 경로와 UI를 유지한다. 진단 파일은 perf 녹화 시작/중지와 독립적으로 설정이 활성화된 앱 실행 세션에 기록된다. frame ID는 세션별 증가 번호이며 FRAME의 captureTimestampMs로 perf 시각과 연결한다.

- RAW_YOLO_SUMMARY `tensorCandidateCount` 및 DETECTION_FLOW `rawCandidates`: 모든 tensor 후보 수.
- RAW_YOLO_SUMMARY `candidateCount`: 진단 최소 confidence 이상인 후보 수. `logged/omitted`는 상세 기록 제한을 설명한다. 기본은 tensor 순서의 처음 200개이며 최고 점수 후보가 상세 줄에 없을 수 있어 summary를 함께 본다.
- `afterConfidence`: 실제 threshold 통과 수. detector size 검사와 candidate cap은 **NMS 이전**에 수행된다.
- `visible`: UI에 제출할 YOLO 후보 수. 실제 화면 여부는 같은 frame의 OVERLAY_DRAW `visible`과 `drawingEnabled`로 구분한다. UI 최신 프레임 병합으로 일부 프레임은 draw 로그가 없을 수 있다. OVERLAY_SKIPPED는 overlay에 전달된 뒤 draw 전에 교체된 경우다.
- 상세 FILTERED에는 실제 존재하는 CONFIDENCE, INVALID_BOX, SIZE_DETECTOR, MAX_CANDIDATES, NMS, GEOMETRY_FILTER, SIZE_PIPELINE, OVERLAY_CLASS, TEMPORAL 사유만 사용한다. 후처리 상세 사유는 프레임당 300개까지 기록하며 DETAIL_LIMIT에 초과 수를 남긴다.
- DETECTOR_ERROR는 추론/파싱 실패이며 정상적인 0건 검출과 구분한다. 이 경우 detector 단계 통계는 -1이다.

PNG는 `fillInputBuffer`가 읽은 동일한 최종 RGB Bitmap에서 복사한다. 복사는 해당 추론이 끝난 후, Bitmap 재사용/반환 전에 수행하며 tensor를 역변환하거나 Preview를 캡처하지 않는다. 재인코딩 손실을 피하려고 JPEG 대신 무손실 PNG를 선택했다.

주기 저장과 검출 저장은 각각 같은 interval로 제한한다. 검출 저장 기준은 geometry 이전 **NMS 통과 검출**이므로 후처리에서 없어진 객체도 확인할 수 있다. 두 조건이 동시에 충족되면 같은 입력을 두 이름으로 저장한다.

파일 압축/쓰기와 상세 로그 쓰기는 worker에서 실행한다. 대기 작업은 최대 16개, 이미지 snapshot은 최대 1개만 유지하며 포화 시 추론을 기다리게 하지 않고 진단 작업을 생략한다. DEBUG_QUEUE, INPUT_SAVE_SKIPPED 및 Logcat `DetectionDebug`의 I/O 오류를 확인한다. INPUT_SAVE_QUEUED는 저장 요청이며 실제 성공은 파일 존재와 INPUT_SAVED Logcat으로 확인한다. OFF 상태에는 프레임 trace/이미지 복사/파일 쓰기가 없다. ON 상태의 문자열 작성과 Bitmap 복사 비용은 존재하므로 OFF/ON perf 비교도 필요하다.

## 검증 결과

`gradlew.bat :app:testDebugUnitTest :app:assembleDebug` 성공. 전처리/좌표 테스트 4개를 포함한 전체 39개 단위 테스트 통과.
실기기 촬영, PNG의 실물 색상 확인, 디스크 포화/종료 시 저장 검증, best.pt 비교 및 FPS 영향 측정은 아직 수행하지 않았다.
