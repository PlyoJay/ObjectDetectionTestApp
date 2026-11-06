# ObjectDetectionTestApp — ARCore 제거 및 CameraX 복구

작업일: 2026-11-06 (Asia/Seoul)

## 변경 범위와 소스 분석

| 구분 | 분석 결과 및 처리 |
| --- | --- |
| AR 전용 카메라 | Session, GL 배경, CPU 이미지 복사, 4Hz worker, 세션 세대 검사와 백엔드 전환·fallback 제거 |
| AR 전용 거리 | FULL/RAW Depth 취득, confidence 필터, 좌표 변환, 표면 샘플링, 평활화, 만료 및 실측 오차 분석 제거 |
| AR 화면과 로그 | Depth 검증·YOLO 실험·Depth 로그 목록 화면, 전용 CSV/JSON/내보내기, 고급 AR 버튼과 상태 UI 제거 |
| 공용 인식 | CameraX, ROI/Letterbox, YOLO 전처리·추론·NMS, 클래스 정책, 추적, Overlay 유지 |
| 공용 부가 기능 | ML Kit, 설정·프리셋, 성능·탐지 로그, 캡처, MediaProjection 녹화, Preview On/Off 유지 |
| 기존 지면 추정 | 기존 비 AR 수학 유틸리티·보정 데이터·단위 테스트·JSON 직렬화는 보존. 카메라와 탐지 파이프라인에서는 호출하지 않으며 설정 UI와 거리 표시는 제거 |
| 실험 기록 | 기존 `docs/arcore-*.md`, YOLO 및 거리 실험 보고서 보존. 과거 로그를 읽는 PC 분석기의 호환성 테스트도 보존 |

`MainActivity`는 구체적인 `CameraController` 하나만 생성한다. 거리 표시 설정에 따라 카메라 소유권을 바꾸는 경로, AR Activity 전환, AR 재시도 및 fallback이 없다.

## 제거 파일 및 설정

`app/src/main/java/com/samin/objectdetection/` 기준:

- `arcore/`: `ArCameraBackground.kt`, `ArCoreDepthActivity.kt`, `ArCoreDepthSource.kt`, `ArDepthRenderer.kt`, `DepthCsvRecorder.kt`, `DepthLogExporter.kt`, `DepthLogFiles.kt`, `DepthLogListActivity.kt`, `DepthLogSharing.kt`, `DepthMeasurement.kt`, `MediaStoreDepthDownloads.kt`
- `arcore/yolo/`: `ArCoreFrameDepthContext.kt`, `ArCoreMainCameraController.kt`, `ArCorePipelineWorker.kt`, `ArCoreYoloExperimentActivity.kt`, `ArCoreYoloOverlay.kt`, `ArObjectSurfaceDepthSampler.kt`, `ArYoloGeometry.kt`, `ArYoloJsonLog.kt`, `ArYoloRenderer.kt`, `ArYoloWorker.kt`, `ArYuvSnapshot.kt`, `DepthCoordinateSnapshot.kt`, `DepthPixelCoordinates.kt`
- `camera/CameraBackend.kt`: 교체 가능한 백엔드 인터페이스와 선택 함수 제거
- `distance/ObjectDepthEstimate.kt`, `distance/DepthAnalysis.kt`: Depth 결과, 프레임 컨텍스트, AR viewport projection 및 분석 전용 모델 제거

AR 전용 단위 테스트 패키지, `ArCoreMainPipelineTest`, `DepthAnalysisSettingsTest`, `MainDistanceModeTest`는 삭제하고 현재 동작을 검증하는 설정 마이그레이션·거리 미지원 테스트로 교체했다.

- Gradle의 `com.google.ar:core:1.57.0` 의존성 제거
- Manifest의 AR feature, 전용 GLES 요구, AR Optional metadata 및 세 Activity 등록 제거
- FileProvider의 AR 전용 두 경로 제거. 기존 문서와 저장된 측정 파일을 삭제하는 코드는 추가하지 않음
- 설정 schema 12: 거리 표시·방식·Depth 입력·샘플 표시·실측 Z 필드 제거. 이전 저장값은 무시하며 모델 선택, 임계값, 스레드, Preview 등의 다른 설정을 유지
- `DetectionResult`의 AR Depth payload, Overlay의 AR projection/샘플 지점/Depth 만료 타이머, 성능 로그의 `depthObjects` 직렬화 제거

## 남아 있는 처리 구조

```text
CameraX ProcessCameraProvider
  → ImageAnalysis (RGBA_8888, KEEP_ONLY_LATEST, 단일 analyzer)
  → Bitmap 변환 및 화면 방향 회전
  → ROI 또는 중앙 크롭
  → 기존 YOLO Letterbox/Resize, 텐서 전처리
  → 기존 YOLO11s v2 추론 및 Confidence/NMS
  → 원본 좌표 복원, 기존 클래스·크기·Geometry·Temporal 정책과 추적
  → BoundingBoxOverlay (CameraX FIT_CENTER)
  → 기존 캡처·녹화 JSON, 탐지 및 성능 로그

같은 CameraX 프레임 → 같은 ROI → 기존 ML Kit detector → 독립적인 Overlay 상태
```

기존 `VisionStyleYoloDetector`, YOLO 모델 설정과 클래스 매핑, 추론 알고리즘, 학습 데이터 및 TFLite 파일은 변경하지 않았다. 기본 모델은 `best_512.tflite` (YOLO11s v2, 512 입력, 12클래스)이며, 기존 모델 선택도 그대로 보존한다.

클래스 순서: `bollard`, `person`, `bicycle`, `car`, `motorcycle`, `bus`, `truck`, `stop sign`, `bench`, `cat`, `dog`, `cardboard_box`. 신규 클래스 ID는 11이다. 클래스별 enabled/confidence, Threads 및 XNNPACK 설정을 유지한다.

추적된 TFLite 4개 파일은 현재 Git blob과 HEAD blob이 모두 일치한다. v2 모델 SHA256은 `591509278b3a1fe114a0084d4841b948a93b637c22005b7becf06309cb906667`이다.

## CameraX 해상도와 로깅

`CameraController.kt`는 기본 1280×720 요청에 대해 16:9 AspectRatioStrategy를 명시한다. 기존 ResolutionStrategy만 사용하면 기본 4:3 선호가 앞서 적용될 수 있으므로 이를 바로잡았다. 4:3 요청에는 4:3 선호를 유지한다. 지원하지 않는 해상도에는 기존 closest-higher-then-lower fallback을 사용한다.

- 바인딩 시 `[CAMERA_USE_CASES]`: 요청값, 실제 `ImageAnalysis.resolutionInfo`, crop, Preview 해상도 기록
- 첫 처리 프레임의 `[CAMERA_SHAPE]`: 실제 ImageProxy 버퍼 크기, format, 회전, crop 기록
- `onResolutionSelected`와 `CameraFrameTiming.analysisWidth/analysisHeight`: 회전 전 실제 버퍼 크기를 메인 화면에 전달
- 성능 파일의 `Requested Camera Resolution`과 `Camera Resolution`을 구분. 실제값을 아직 관측하지 못하면 `0x0`으로 남기며 요청값을 실제값처럼 대신 기록하지 않음
- 프레임별 크기와 ROI는 회전 후 좌표이다. 세로 모드에서 원본 1280×720이 Bitmap 720×1280으로 바뀌는 것을 구분할 수 있음

현재 연결된 기기가 없으므로 **실제 1280×720 선택 여부는 미검증**이다. 이 변경이 모든 단말에서 720p를 강제하는 것은 아니다. `CameraPreviewRuntimeTest`는 Preview OFF→ON→OFF에서 프레임, 회전 후 크기, 실제 버퍼 크기, 백엔드를 확인하고 720p 일치 여부를 출력하도록 갱신했다.

## 거리 미지원 처리와 경고

파이프라인에서 Depth와 지면 추정 호출을 모두 제거했다. 정상 YOLO 결과의 `estimatedDistanceMeters`와 `distanceEstimation`은 null이다. 거리값 없이 좌표 복원, BBox, Confidence, 클래스 필터와 추적이 계속 실행된다.

Overlay는 거리 표시 기능 자체를 제거하여 유효하지 않은 값이나 과거 payload가 들어와도 `0m` 또는 임의의 거리를 표시하지 않는다. 기존 Confidence 표시 설정과 Overlay NONE/SIMPLE/FULL 동작은 유지한다.

캡처·탐지 JSON은 일반 거리 키를 유지한다. 거리 미지원 시 `estimatedDistanceMeters=null`, `distanceEstimationValid=false`, `distanceEstimationMethod=null`, `distanceEstimationStatus=NOT_SUPPORTED`를 기록한다. AR 전용 샘플/실측/오차 JSON은 제거했다.

`WarningPolicy`, 경고 후보, 혼잡도, TTS/진동/Beep은 원래부터 BBox 크기·화면 위치·시각적 움직임을 사용하며 미터 거리값을 읽지 않는다. 이를 유지하여 null 거리 때문에 경고 계산이 멈추거나 0m로 해석되는 경로가 없다. 이 시각적 근접도는 센서 실측 거리가 아니며, 실제 안내 품질은 실기기에서 별도로 검증해야 한다.

## TinyF 두 채널 준비

새 파일: `app/src/main/java/com/samin/objectdetection/tof/TofMeasurement.kt`.

| 항목 | 계약 |
| --- | --- |
| `sensorId` | 물리 센서 식별자. 설치 방향·좌/우 역할과 무관 |
| `measuredAt` | 수신 시점의 monotonic ns. Android에서는 elapsedRealtimeNanos 기준 |
| `distanceMm` | 원시 mm 거리. 값이 없으면 null |
| `confidence` | 원시 confidence, 없으면 null. 범위·허용 임계값은 향후 실제 프로토콜 확인 필요 |
| `valid` | 유효 여부. 오래된 값·연결 해제 시 과거 정상값을 계속 사용하면 안 됨 |
| `status` | OK / NOT_CONNECTED / INVALID_READING / OUT_OF_RANGE / STALE / ERROR |

`TofMeasurementSource`는 `sensorId`와 `latest()`만 제공한다. 서로 다른 source 두 인스턴스에서 각 센서 값을 독립적으로 받을 수 있고 첫 측정 전에는 null을 반환한다. 현재 구현체, UART 연결, 타이머, 변환·융합·BBox 매칭은 없다.

제공 사양인 단일 지점 D-ToF, 20~4000mm, UART 115200bps, 기본 30Hz를 향후 통합의 전제로 기록한다. 두 측정값은 Depth Map이 아니므로 YOLO의 모든 BBox에 자동 배정하지 않는다. 센서 설치 위치와 방향, 시야, timestamp 동기화, confidence 정책이 확정된 후 별도 설계가 필요하다.

## 성능 재측정

공용 프레임 지연, bitmap 변환, ROI/crop, resize, inputBuffer/getPixels/rgbWrite, native inference, parsing/NMS, 후처리, totalProcessing, analyzer/inference FPS, 구간·누적 통계와 설정 스냅샷을 유지했다.

카메라 형상·plane/Bitmap 진단은 컨트롤러의 첫 처리 프레임에만 출력한다. AR 전용 Depth JSON 생성과 좌표 변환은 없어졌다. 기존 텐서 통계는 상세 진단을 활성화했을 때만 최대 2초에 한 번 수집하고, PNG 및 상세 로그는 기본 OFF이다. 파일 쓰기는 기존 유계 비동기 writer를 유지한다. YOLO 내부 알고리즘은 수정하지 않았다.

동일 기기에서 v2 모델·SHA256, Threads, XNNPACK, Confidence/NMS, 클래스 설정, ROI, Preview, ML Kit, 테스트 시간, 배터리/발열 상태를 기록한 뒤 성능 로그를 시작한다. 첫 프레임에서 해상도를 확인하고 3/5/10분 중 같은 길이로 측정한다. FPS는 추론 완료 처리량을 사용하고 analyzer FPS와 구분한다. 요청 해상도와 입력 경로가 바뀌므로 과거 AR 결과와 완전히 같은 조건은 아님을 표시한다.

사용자 제공 이전 기준: ARCORE 480×640, 추론 약 1163ms, 전처리 약 433ms, 후처리 약 334ms, 약 0.46 FPS. 새 실측 수치는 아직 없으며 성능 향상률을 추정하지 않는다.

## 빌드 및 테스트

검증 환경: Windows, Android Studio JBR 21, 저장소 Gradle Wrapper 9.4.1, 기존 SDK와 오프라인 의존성 캐시.

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:lintDebug --offline --console=plain --max-workers=2 '-Pkotlin.compiler.execution.strategy=in-process'
.\gradlew.bat :app:dependencyInsight --dependency com.google.ar --configuration debugRuntimeClasspath --offline --console=plain --max-workers=2
```

| 검증 | 결과 |
| --- | --- |
| Debug APK | 성공, `app/build/outputs/apk/debug/app-debug.apk` |
| JVM 단위 테스트 | 210개, 실패 0 / 오류 0 / skipped 0 |
| 계측 테스트 APK | 컴파일·패키징 성공, `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` |
| Android Lint | `lintDebug` 성공, 오류 0 / 경고 195. 전체 경고 해소는 이번 범위에 포함하지 않음 |
| 런타임 의존성 | debugRuntimeClasspath에 `com.google.ar` 일치 의존성 없음 |
| 실행 소스·Gradle·병합 Manifest | ARCore, FrameDepthContext, ObjectDepthEstimate, ViewportProjection 검색 결과 없음 |
| 모델·자산 | 추적된 TFLite 4개 모두 HEAD와 동일. 기존 assets 변경 없음 |
| 공백 검사 | `git diff --check` 통과 |
| 기기 테스트와 FPS | `adb devices -l`에 연결 기기 없음. 실행·실측 미수행 |

추가/갱신 검증 범위: 기존 AR 설정 마이그레이션 및 다른 설정 보존, 미지원·비정상 거리의 JSON null, Overlay의 거리 무시·Confidence 유지, ROI·회전·모델별 BBox 복원과 null 거리에서 경고 유지, CameraX Preview 전환 시 실제 해상도 로깅. 기존 모델 매핑·텐서 계약·클래스 설정·Warning·ML Kit·성능 로그 단위 테스트도 함께 통과했다. Android Bitmap/Canvas/카메라가 필요한 테스트는 컴파일만 검증했으며 실행 결과로 간주하지 않는다.

초기 일반 병렬 빌드에서 Kotlin daemon 연결 종료와 dex 병합 오류가 발생했다. `--max-workers=2` 및 in-process Kotlin 컴파일을 실행 옵션으로 적용한 최종 검증은 **BUILD SUCCESSFUL (5분 9초)**이었다. 저장소의 Gradle 메모리·컴파일러 설정 파일은 변경하지 않았다. 기존 trace API deprecated 경고는 남아 있다.

검증 기록:

- [최종 Gradle 빌드·테스트·Lint 로그](validation/camerax-gradle-20261106.txt)
- [ARCore 의존성 조회](validation/camerax-dependency-20261106.txt)
- [테스트 집계·자산 해시·정적 검사](validation/camerax-static-20261106.txt)

## 실제 기기 검증 목록

1. 카메라 권한 승인/거부·재실행, background/foreground와 설정 적용 후 CameraX 재바인딩 확인
2. `[CAMERA_USE_CASES]`와 `[CAMERA_SHAPE]`, 성능 파일에서 요청 1280×720 및 실제 버퍼 크기 확인. 지원되지 않으면 실제 fallback을 기록
3. Preview ON/OFF/ON 및 OFF→ON→OFF에서 프레임·추론 지속, 자원 해제 확인
4. `ModelSelectionDetectorTest` 또는 실제 모델 로그로 v2 asset, SHA256, 입력/출력, 12클래스 로딩과 실제 탐지 확인
5. 실제 대상의 BBox·Confidence·ROI 경계·회전 정합 및 cardboard_box 탐지·클래스별 On/Off 확인
6. 미지원 거리에서 BBox 유지 및 거리 숫자 부재, 시각적 경고·음성·진동·Beep 확인
7. ML Kit STREAM/SINGLE_IMAGE, 동시 프레임 처리, 표시·간격·로그·공유 기능 확인
8. 원본/Overlay 이미지 캡처, JSON null 거리, 화면 녹화 및 JSONL, Gallery와 공유 확인
9. Threads/XNNPACK 설정 적용과 로그 스냅샷 확인. 실제 native delegate 사용은 기존 Logcat/profiling으로 별도 확인
10. 상세 진단/PNG OFF 상태에서 동일 v2 모델 성능을 재측정하고 위 기준과 비교. 이후 진단 ON 비용을 따로 측정

`connectedDebugAndroidTest`는 기기 연결 후 실행한다. 계측 테스트 APK의 컴파일 성공과 기기에서의 테스트 실행 성공은 구분해서 기록한다.
