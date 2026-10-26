# YOLO 지면 기반 전방 거리 추정

## 구현 범위

- `distance/GroundPlaneDistanceEstimator.kt`: Android 의존성이 없는 지면 교차 수식, 설정 검증, 실제 분석 버퍼의 광학/회전/유효 영역 모델.
- `distance/Camera2DistanceGeometry.kt`, `camera/CameraController.kt`: 실제 ImageAnalysis 크기·cropRect·회전과 노출 타임스탬프가 일치하는 Camera2 메타데이터를 전달한다.
- `pipeline/DetectionPipeline.kt`, `MainActivity.kt`: ROI 복원 직후 analyzer에서 거리 계산. 실패해도 기존 탐지 및 필터/경고 흐름을 유지한다.
- `detector/DetectionResult.kt`: 기본값 `null`인 `estimatedDistanceMeters`, 계산 상태/광학 출처/유효 내부 파라미터를 보관하는 `distanceEstimation`을 마지막에 추가했다. 기존 생성 인자와 `copy()`를 유지한다.
- `ui/BoundingBoxOverlay.kt`: YOLO에만 `약 2.3m`를 표시한다. NONE에서도 거리 표시가 켜지고 값이 유효하면 라벨을 그린다. 기존 confidence/위험 정보는 유지한다. 라벨 폭 제한·줄바꿈·화면 경계 보정·이미 배치한 라벨과의 충돌 회피를 적용한다. 표시 공간이 없으면 해당 라벨을 생략한다.
- `settings/AppSettings.kt`, `AppSettingsStore.kt`, `SettingsActivity.kt`: 스키마 8, 거리 표시 및 장착/화각 설정, 범위 검증, 영구 저장과 사용자 프리셋을 지원한다. 구버전은 표시 OFF, 장착값 미설정으로 마이그레이션한다.
- `evaluation/EvaluationJson.kt`, `EvaluationDataRecorder.kt`: 기존 JSON 캡처와 detection JSONL에 거리/방법/상태/광학 출처/유효 내부 파라미터를 추가한다. 기존 키는 유지한다.
- 단위 테스트: `distance/GroundPlaneDistanceEstimatorTest.kt`, `settings/DistanceSettingsTest.kt`, `evaluation/DistanceJsonTest.kt`.
- 기기 테스트: `distance/DistancePipelineTest.kt`, 기존 `BoundingBoxOverlayTest`, `CameraPreviewRuntimeTest`, `SettingsActivityTest`, `MainScreenLoggingLayoutTest`를 확장했다.

YOLO 모델·학습 파일, confidence/NMS 기준, 위험도/ProximityLevel/WarningPolicy와 경고 출력 정책은 변경하지 않았다. ML Kit에는 거리 추정을 적용하지 않는다. 커밋/푸시는 하지 않았다.

## 수식과 좌표계

카메라의 지면 위 높이를 `h`(m), 수평에서 아래로 기울인 각도를 `θ`, 회전된 전체 분석 프레임의 유효 수직 초점거리를 `fy`, 주점을 `cy`라고 한다. 객체의 지면 접점은 복원된 BBox의 하단 중앙 `((left+right)/2, bottom)`이다.

```text
q       = (bottom - cy) / fy
rayDown = sin(θ) + q cos(θ)
rayZ    = cos(θ) - q sin(θ)
Z       = h × rayZ / rayDown
        = h / tan(θ + atan(q))
```

표시값은 지면을 따라 카메라의 전방 축으로 잰 `Z`이다. 렌즈에서 물체까지의 사선 거리나 좌우 위치를 포함한 지면 방사 거리는 아니다. 좌우 기울기(roll)는 0°, 지면은 평면으로 가정한다. 따라서 하단 중앙의 x는 유효 영역 확인에 사용되며, 같은 하단 y를 갖는 객체의 전방 Z는 동일하다.

좌표 흐름:

```text
실제 CameraX 전체 버퍼
  → ImageProxyExt가 전체 비트맵을 시계 방향 0/90/180/270° 회전
  → 앱 ROI/중앙 정방형 크롭
  → 모델 Letterbox 또는 Stretch
  → detector가 모델 좌표를 ROI 비트맵 좌표로 복원
  → pipeline이 ROI 오프셋을 한 번 복원
  → 전체 회전 프레임 좌표에서 거리 계산
  → 기존 FIT_CENTER로 화면 표시
```

`ImageProxyExt`는 `ImageProxy.cropRect`를 비트맵에서 잘라내지 않는다. 따라서 cropRect는 함께 회전해 접점 유효 영역으로만 사용하고, 주점/박스를 다시 이동시키지 않는다. ROI 크롭도 화각에 재적용하지 않는다. 다만 ROI의 하단에서 잘린 박스는 접점이 확인되지 않으므로 계산을 거부한다.

광학 파라미터는 Camera2의 해당 프레임 SCALER_CROP_REGION에 스트림 종횡비에 따른 중심 크롭을 적용하고, 실제 ImageAnalysis 버퍼 크기로 스케일한다. 90/270° 회전에서는 fx/fy를 교환하고 주점과 cropRect도 회전한다. Camera2의 픽셀 중심은 `n+0.5`인 좌표계를 따른다. [Camera2 내부 파라미터·센서 영역 문서](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics#LENS_INTRINSIC_CALIBRATION), [SCALER_CROP_REGION 문서](https://developer.android.com/reference/android/hardware/camera2/CaptureResult#SCALER_CROP_REGION).

## 자동 모드와 수동 모드

AUTO는 다음 정보를 검사한다.

- 노출의 SENSOR_TIMESTAMP와 ImageProxy 타임스탬프 일치. 다른 프레임의 정보를 재사용하지 않는다.
- 센서 active/pre-correction 영역, 실제 scaler crop, 줌, 회전/크롭 및 센서 pixel mode.
- 논리 멀티 카메라 여부와 손떨림 보정 상태.
- 프레임의 intrinsic calibration을 우선 사용한다. 정적 calibration만 있으면 단일 고정 초점거리인지도 확인한다.
- calibration이 없으면 실제 프레임의 렌즈 초점거리(mm), 센서 물리 크기, pixel array 크기로 구한 근사를 사용하고 `CAMERA2_PHYSICAL_APPROXIMATION`으로 기록한다. 임의의 기본 화각을 사용하지 않는다.

렌즈 전환 가능성이 있는 논리 카메라, 줌 비율 1 이외, 활성화되었거나 상태를 알 수 없는 손떨림 보정, 모델링하지 않은 왜곡/센서 좌표 변환, 필요한 메타데이터 부재에서는 자동 거리값을 생성하지 않는다. AUTO가 실패했다고 수동값으로 조용히 전환하지 않는다. 고정 장착 설정이 없으면 Camera2 capture callback 수집도 활성화하지 않는다.

MANUAL은 사용자가 보정한 **회전 전 전체 ImageAnalysis 버퍼의 수직 화각**을 사용한다.

```text
f = rawBufferHeight / (2 tan(manualVerticalFov / 2))
fx = fy = f
cx = rawBufferWidth / 2
cy = rawBufferHeight / 2
```

이후 실제 프레임 회전을 적용한다. 수동 모드는 정방 픽셀과 화면 중앙 주점을 가정하므로 별도 카메라 보정 행렬만큼 정확하지 않다. 모델의 640/512 입력 화각이나 앱 ROI의 화각을 입력하면 안 된다.

## 실제 기기 설정

`설정 → 디버그 및 고급 설정 → Overlay / Display`에서 다음을 설정한다.

1. `YOLO 거리 표시` ON. 기본값은 OFF이다.
2. `카메라 설치 높이 (cm)`: 렌즈 중심에서 지면까지 실측한 높이, 1~1000cm.
3. `카메라 하향 각도 (°)`: 광축이 수평에서 아래로 기울어진 각도, 0~89°. 좌우 기울기는 0°로 장착한다.
4. `거리 화각 설정`: AUTO를 먼저 사용할 수 있다. 자동 광학 정보를 사용할 수 없는 기기는 MANUAL을 선택하고 현재 스트림의 실측 수직 화각 5~150°를 입력한다.
5. `적용 및 저장`. 사용자 프리셋에도 보정값을 함께 저장할 수 있다.

높이/각도/수동 화각의 빈 입력은 미설정이며, 유효한 계산을 할 수 있을 때만 거리값이 생긴다. 설치 방향·분석 종횡비·해상도·줌·렌즈가 달라지면 수동 보정을 다시 확인한다. 높이나 각도를 예시값으로 추정해 입력하지 않는다. ROI/Letterbox 전환은 동일한 원본 접점에 대해 거리를 바꾸지 않는다.

## 실패와 수명 관리

- NaN/Infinity, 역전/빈/화면 밖 BBox, 잘린 하단 접점, 수평선 위/수평선의 광선, 전방 지면 교차가 없는 광선, 0 이하 또는 비유한 계산값은 모두 거리 `null`.
- 원본/ROI/proxy 유효 영역의 하단 1픽셀은 잘림을 확정할 수 없는 경계로 보고 보수적으로 계산하지 않는다.
- 거리 실패는 객체를 제거하지 않는다. 경고, 필터, 모션, confidence/NMS 정책에 거리값을 사용하지 않는다.
- 거리값은 같은 DetectionResult/OverlayFrameState 안에 보관하므로 기존 만료 시간, 프레임 역순 거부, 빈 결과, OFF/ON 재활성화 정책에 따라 박스와 함께 제거된다.
- Preview OFF는 기존 MainScreenView가 overlay를 GONE으로 유지하며, ImageAnalysis와 거리 계산은 계속된다. Overlay OFF도 분석은 계속된다.
- Canvas는 이미 계산된 값을 포맷하고 배치한다. 거리 추론이나 카메라 보정은 수행하지 않는다.

기존 JSON/JSONL의 각 탐지 객체에 추가되는 키:

```json
{
  "estimatedDistanceMeters": 2.3,
  "distanceEstimationMethod": "GROUND_PLANE",
  "distanceEstimationValid": true,
  "distanceEstimationStatus": "VALID",
  "distanceOpticsSource": "MANUAL_FOV",
  "distanceIntrinsics": {
    "fx": 800.0, "fy": 800.0, "cx": 640.0, "cy": 360.0,
    "coordinateSpace": "UPRIGHT_FULL_ANALYSIS_BUFFER"
  }
}
```

실패 시 거리값은 JSON `null`이며 상태에는 `MOUNT_UNSET`, `CONTACT_CLIPPED`, `NO_FORWARD_GROUND_INTERSECTION`, `LOGICAL_CAMERA_REQUIRES_MANUAL_FOV` 등의 원인을 기록한다. 상세 매 프레임 Logcat은 추가하지 않았다. 앱 설정 스냅샷에 장착/화각 값도 기존 codec을 통해 포함된다.

## 검증 및 정확도 한계

단위 테스트는 알려진 높이/각도/초점거리와 합성 지면 점, 수평선으로의 이동에 따른 거리 증가, 모든 회전, 스트림 크기/센서 크롭, ROI ON/OFF, 중앙 크롭, 640/512와 Letterbox/Stretch, 전체 9/11 클래스, 비정상 값과 화면 경계, 설정 마이그레이션과 JSON 호환성을 검사한다.

기기 테스트는 실제 Bitmap 회전 및 DetectionPipeline 복원, 거리값 유무에 따른 탐지/경고 동등성, NONE/confidence/거리 OFF와 ML Kit 제외, 라벨 경계/충돌, stale과 표시 토글, 실제 CameraX Preview OFF→ON→OFF 분석, 설정 검증/저장/재생성/사용자 프리셋을 검사한다. 기존 실제 YOLO 모델 선택·전처리·CPU 실행·성능 진단 테스트도 실행한다.

이 방식은 평평한 지면에 실제로 닿아 있는 객체에만 유효하다. 경사·계단·떠 있는 물체·가림·바닥 그림자·부정확한 BBox 하단, 카메라 흔들림과 장착 오차, 렌즈 왜곡과 주점 오차는 거리 오차를 만든다. 수평선 근처에서는 작은 픽셀/각도 오차도 큰 거리 오차로 확대된다. 수치 유효성은 실제 거리 정확도를 보증하지 않는다. 줄자로 잰 여러 거리의 현장 보정과 오차 측정은 별도로 필요하다.

### 2026-10-26 실행 결과

- `:app:testDebugUnitTest`: 38개 suite, 204개 테스트 통과, 실패/오류 0.
- `:app:assembleDebug`, `:app:assembleDebugAndroidTest`: 성공.
- `:app:lintDebug`: 성공, 오류 0, 경고 210. 범위 밖의 기존 경고 정리는 수행하지 않았다.
- SM-S921N / Android 16 선별 기기 회귀 검증: 서로 다른 테스트 총 32개 모두 통과. 기존 실행에서 통과한 28개와 잠금 해제 후 실행한 4개를 합산한 결과다. 합성 좌표/실제 Bitmap 파이프라인, 9/11 클래스, 640/512 모델, 기존 전처리/CPU 실행/성능 진단, 거리 라벨/경계/충돌/만료/ON/OFF, Preview 표시 게이트를 검증했다.
- 절전 후 보안 잠금 때문에 중단됐던 3개는 사용자가 잠금을 해제한 뒤 최종 코드로 모두 재실행해 통과했다. `BoundingBoxOverlayTest#attachedTimerExpiresWithoutFurtherResultsAndRecreationClearsOldView`, `SettingsActivityTest#distanceCalibrationValidatesPersistsAcrossRecreationAndRoundTripsCustomPreset`, `CameraPreviewRuntimeTest#analysisDeliversFramesWithAndWithoutPreviewAcrossRestart`가 포함된다.
- 추가로 `SettingsActivityTest#everyExistingControlIsReachableInItsCategory`가 통과해 설정 컨트롤 77개에 접근할 수 있음을 확인했다. 재실행은 총 4개, 실패/오류/건너뜀 0, Gradle BUILD SUCCESSFUL(49초)이다. 테스트 동안만 켠 USB 화면 유지 설정은 실행 전 값 0으로 복원했다.
- 초기의 긴 라벨 배치 실패는 작은 화면에서 글자 크기를 조정하도록 수정한 뒤 동일 테스트가 통과했다. 최종 코드에 남은 테스트 실패는 없다. 실측 거리의 현장 정확도 검증은 별도다.

전체 명령:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain

.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.samin.objectdetection.distance.DistancePipelineTest,com.samin.objectdetection.ui.BoundingBoxOverlayTest,com.samin.objectdetection.ui.MainScreenLoggingLayoutTest,com.samin.objectdetection.settings.SettingsActivityTest#distanceCalibrationValidatesPersistsAcrossRecreationAndRoundTripsCustomPreset,com.samin.objectdetection.camera.CameraPreviewRuntimeTest,com.samin.objectdetection.camera.RecognitionRoiInstrumentedTest,com.samin.objectdetection.detector.ModelSelectionDetectorTest,com.samin.objectdetection.detector.DetectorPreprocessingRegressionTest,com.samin.objectdetection.detector.DetectorCpuOptimizationTest,com.samin.objectdetection.metrics.SystemPerformanceDiagnosticsInstrumentedTest' --console=plain

.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.samin.objectdetection.ui.BoundingBoxOverlayTest#attachedTimerExpiresWithoutFurtherResultsAndRecreationClearsOldView,com.samin.objectdetection.settings.SettingsActivityTest#distanceCalibrationValidatesPersistsAcrossRecreationAndRoundTripsCustomPreset,com.samin.objectdetection.settings.SettingsActivityTest#everyExistingControlIsReachableInItsCategory,com.samin.objectdetection.camera.CameraPreviewRuntimeTest#analysisDeliversFramesWithAndWithoutPreviewAcrossRestart' --console=plain
```
