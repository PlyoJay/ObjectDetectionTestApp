# 메인 화면 YOLO + ARCore Depth 통합

2026-10-27. 설정 → **디버그 및 고급 설정 → Overlay / Display → 객체 거리 표시**에서 ON/OFF하고 거리 추정 방식을 고른다. 신규 설정의 기본값은 **OFF**, 방식은 **ARCORE_DEPTH**, Depth 입력은 **FULL**이다. 적용하면 기존 안전한 runtime 종료·재생성 경로로 같은 MainActivity의 카메라를 다시 준비한다. 별도 Activity 진입은 필요 없다.

기존 장착 높이·각도가 필요한 **GROUND_PLANE** 방식, ARCore 중앙 거리 측정 화면/CSV 목록, 별도 YOLO+ARCore 검증 화면은 보존한다. 두 검증 화면의 진입 버튼은 메인 **고급 ARCore 검증 도구** 안으로 이동했다. 모델/학습 파일, confidence/NMS 기준, 위험도·ProximityLevel·경고 출력 기준은 수정하지 않았다.

## 1. 변경 파일

Kotlin 주 소스 기준 디렉터리: `app/src/main/java/com/samin/objectdetection/`.

| 파일 | 역할 |
| --- | --- |
| `MainActivity.kt` | 설정에 따른 백엔드 선택, 같은 분석/경고/기록 callback 공유, 실패 시 CameraX 복구, 프레임별 projection 전달 |
| `ui/MainScreenView.kt` | 카메라 영역만 GLSurfaceView로 교체, 백엔드 상태/실패 사유 표시, 고급 검증 도구 접기 |
| `camera/CameraBackend.kt` (신규) | 카메라 start/drain 계약과 설정 기반 모드 선택 |
| `camera/CameraController.kt` | 기존 CameraX 동작 유지, 공통 계약·연속 frame ID·선택적 Depth context 추가 |
| `arcore/yolo/ArCoreMainCameraController.kt` (신규) | 메인 AR Optional 지원/설치·세션·GL·lifecycle·오류 관리 |
| `arcore/yolo/ArCorePipelineWorker.kt` (신규) | 최대 4Hz, 단일 입력, YUV/Bitmap 변환 후 기존 메인 analyzer callback 실행 |
| `arcore/yolo/ArCoreFrameDepthContext.kt` (신규) | 현재 프레임의 좌표/Depth 복사본으로 ROI 복원 후 탐지 결과에 Z 연결 |
| `arcore/yolo/ArObjectSurfaceDepthSampler.kt` (신규) | 여러 내부 영역, confidence, 중복 픽셀, 공간 지지·군집 일관성 검사 |
| `arcore/yolo/ArYoloRenderer.kt` | 공통 frame consumer, 선택적 RAW/Confidence 취득, Preview OFF 검은 렌더링, CPU 해상도 상태 |
| `arcore/yolo/ArYoloGeometry.kt` | 회전/viewport projection, Depth 출처/시각, 객체별 필터의 모호한 매칭/급변 처리 |
| `arcore/yolo/ArYuvSnapshot.kt` | pause/resume 뒤 같은 카메라 timestamp가 새 입력으로 재사용되지 않도록 순서 검사 |
| `arcore/yolo/ArYoloWorker.kt` | 별도 검증 화면도 개선된 내부 영역 sampler 재사용; 메인에는 이 전용 detector worker를 붙이지 않음 |
| `arcore/yolo/ArYoloJsonLog.kt` | 검증 JSONL에 Depth 방법·표본 시각·지지 영역 수 추가 |
| `distance/ObjectDepthEstimate.kt` (신규) | ARCore Z 메타데이터, 방식 enum, 최소 프레임 거리 context/projection 계약 |
| `detector/DetectionResult.kt` | nullable `depthEstimation` 필드 추가; 기존 생성/copy 기본값 호환 |
| `pipeline/DetectionPipeline.kt` | 원본 회전 프레임으로 ROI 복원 후 선택적 Depth context 실행, 기존 필터/경고 경로 유지 |
| `motion/ObjectMotionTracker.kt` | 백엔드 종료 후 좌표 이력을 초기화하는 reset만 추가 |
| `ui/BoundingBoxOverlay.kt` | ARCore viewport projection을 YOLO/ML Kit/ROI에 적용, Depth 만료 라벨 갱신 |
| `settings/AppSettings.kt`, `AppSettingsStore.kt`, `SettingsActivity.kt` | schema 9, 거리 방식/RAW 비교 선택, 장착 입력 활성 상태, 기존 설정/프리셋 마이그레이션 |
| `evaluation/EvaluationJson.kt`, `FrameTraceJson.kt` | 기존 capture JSON/detection JSONL에 Depth 메타데이터·백엔드 추가 |
| `metrics/FrameTrace.kt`, `PerformanceLogFormatter.kt` | 백엔드 식별, 선택적 객체별 Depth 상세, ARCore 계측 정의 안내 |
| `app/src/test/java/com/samin/objectdetection/arcore/yolo/ArObjectSurfaceDepthSamplerTest.kt` (신규) | 내부 표면/바닥·배경 혼입/RAW confidence/stride/무효/군집 테스트 |
| `app/src/test/java/com/samin/objectdetection/arcore/yolo/ArCoreFrameDepthContextTest.kt` (신규) | ROI/resize/모델/회전 조합과 Depth 연결·만료 테스트 |
| `app/src/test/java/com/samin/objectdetection/settings/MainDistanceModeTest.kt` (신규) | 모드 선택·OFF·schema 8 마이그레이션·사용자 프리셋 codec 테스트 |
| `app/src/test/java/com/samin/objectdetection/arcore/yolo/ArObjectDepthSmootherTest.kt`, `ArYuvSnapshotTest.kt` | 필터 급변/출처 변경/교차 객체, 카메라 timestamp 재사용 거부 검증 |
| `app/src/test/java/com/samin/objectdetection/evaluation/DistanceJsonTest.kt` | FULL/RAW 로그 필드와 null 호환 검증 |
| `app/src/test/java/com/samin/objectdetection/metrics/PerformanceLogFormatterTest.kt`, `tools/test_latency_analysis.py` | 기존 성능 로그의 고유 키·파서/지연 지표 호환성 검증 |
| `app/src/androidTest/java/com/samin/objectdetection/distance/ArCoreMainPipelineTest.kt` (신규) | Android Bitmap으로 실제 ROI 복원·필터/경고 동등성 검증용 계측 테스트 |
| `app/src/androidTest/java/com/samin/objectdetection/settings/SettingsActivityTest.kt`, `ui/BoundingBoxOverlayTest.kt` | 설정 UI/재생성·장착값 불필요, projection·Depth 만료 렌더링 검증용 계측 테스트 |
| `docs/arcore-main-integration.md` | 통합 구조·차이·검증 기록 |
| `docs/arcore-depth-test.md`, `docs/arcore-yolo-experiment.md` | 후속 메인 통합 문서 연결 |

추가 SDK·저장소 권한·Manifest 필수 feature 변경은 없다. 기존 ARCore Optional 구성과 SDK 1.57.0을 재사용한다.

## 2. 메인 화면 통합 구조

```text
거리 OFF 또는 GROUND_PLANE           거리 ON + ARCORE_DEPTH
CameraX CameraController            ARCore Session / GLSurfaceView
  ImageAnalysis                       acquireCameraImage + Depth 복사
  회전 Bitmap                         YUV→RGB→회전 Bitmap (단일 worker)
          └─────────────────┬───────────────────┘
                  MainActivity.processBitmap
                     ├─ 기존 ML Kit (같은 ROI, 거리 제외)
                     └─ 기존 DetectionPipeline
                         ROI→기존 YOLO→원본 회전 프레임 복원
                           → 선택적 객체 Depth 연결
                           → 기존 geometry/size/class/temporal 필터
                           → 기존 motion/위험도/경고 후보
                         MainActivity의 기존 경고·평가·녹화·성능 기록
                           → 기존 BoundingBoxOverlay
```

메인은 실험용 `ArYoloWorker`의 별도 탐지·표시 경로를 재사용하지 않는다. 이미 생성한 메인 detector와 `DetectionPipeline`을 같은 callback으로 호출하므로 confidence/NMS, 640/9클래스·512/11클래스, Letterbox/Stretch, CPU threads, XNNPACK, 모든 기존 탐지 필터를 공유한다. Depth를 얻지 못해도 탐지 목록은 유지한다.

거리 메타데이터는 `estimatedDistanceMeters`와 별도 `depthEstimation`에 들어간다. 지면 방식의 `distanceEstimation`과 구분하며 경고 계산에 거리값을 읽는 코드를 추가하지 않는다. 거리 시각과 confidence 등은 동일한 탐지 객체의 copy를 통해 후속 필터/기록까지 유지된다.

## 3. 카메라 모드 전환과 복귀

- OFF에서는 ARCore controller/Session/GL worker를 만들지 않는다. 기존 CameraX 경로와 탐지 주기를 유지한다.
- ON+ARCore는 메인 카메라 view 영역에만 GL surface를 넣는다. 기존 버튼, warning UI, BoundingBoxOverlay를 유지한다. 카메라는 ARCore 하나만 소유한다.
- 설정 적용은 기존 녹화/성능/ML Kit 로그 측정 중 변경 제한을 유지한다. 기존 analyzer와 ML Kit 입력 사용 종료, detector/세션 종료 완료 후 Activity를 재생성해 다른 카메라를 시작한다.
- ARCore 지원/설치·Depth 미지원, 초기화·카메라 사용 실패는 동일 화면에 사유를 표시한다. ARCore GL 정지·pause, worker drain, Session.close와 기존 ML Kit 사용 종료를 기다린 다음 CameraX를 시작한다. 지면 방식으로 자동 대체하지 않는다. 재시도는 설정 재적용 시 수행한다.
- fallback 전후 frame ID는 메인 공통 sequence를 사용한다. old ARCore callback은 좌표 epoch가 바뀌면 무시한다. backend 변경 후 기존 temporal/motion 좌표 이력을 비운다.
- pause는 GL을 멈춘 후 Session을 pause하고 overlay를 비운다. resume은 같은 Session을 사용하지만 epoch를 바꾸며, 중복/역행 카메라 timestamp는 다시 수락하지 않는다. 새 네이티브 Session을 만들 때만 timestamp cursor를 초기화한다.
- 모든 Session 호출과 현재 Frame 변환/이미지 취득은 renderer lock으로 직렬화한다. CPU/Depth/Confidence Image는 `use`로 즉시 닫고, worker에는 바이트 배열과 변환 복사본만 전달한다. Bitmap은 `finally`에서 recycle한다. stop Future는 worker/네이티브 세션 종료를 기다린다.

스키마 8 이하에서 `showDistance=true`였던 설정/사용자 프리셋은 **GROUND_PLANE**으로 마이그레이션해 기존 장착값과 의미를 유지한다. OFF였던 설정은 OFF를 유지하고 다음 선택의 기본 방식을 ARCore로 둔다. 지면 높이/각도/화각은 그대로 저장하며 ARCore를 선택하면 해당 입력을 비활성화한다.

## 4. 개선한 Depth 샘플링과 표시

현재 Frame의 CPU 모서리를 `IMAGE_PIXELS → VIEW_NORMALIZED` 및 `IMAGE_PIXELS → TEXTURE_NORMALIZED`로 변환해 보관한다. YOLO가 사용하는 회전 Bitmap에서 ROI/모델 resize를 복원한 BBox를 원본 CPU 좌표로 역회전한 후 Depth 픽셀에 매핑한다. overlay는 같은 Frame의 viewport projection을 사용한다. CameraX FIT_CENTER를 ARCore에 중복 적용하지 않는다. ML Kit와 ROI 가이드도 자신이 받은 원본 Frame의 projection을 사용한다.

샘플링 기본값은 `ObjectDepthSamplingConfig`로 분리했다. 초기 품질 기준이며 거리 보정계수가 아니다.

1. 화면 방향 BBox 내부 x=30/50/70%, y=25/50/75%의 9개 지점을 취한다. 외곽 12%와 바닥 경계는 샘플 윈도우에서도 제외한다. 화면 밖 지점과 Depth cropRect 밖 픽셀도 제외한다.
2. 각 지점 주변 최대 5×5에서 unsigned little-endian D_16 mm를 실제 row/pixel stride로 읽는다. 0 제외, RAW는 confidence≥128/255 조건을 더 적용한다.
3. 각 패치에서 median/MAD 이상치를 제외한다. 겹친 패치의 픽셀을 중복 집계하지 않고 같은 Depth 중심 픽셀을 반복 영역으로 세지 않는다.
4. 정렬한 값의 차이가 `max(150mm, 인접 값 평균의 12%)`보다 크면 군집을 나눈다. 최소 12개 고유 픽셀과 3개 서로 다른 영역의 지지가 필요하다.
5. 가장 큰 군집이 전체의 65% 이상이어야 한다. 다른 군집이 최소 4개/전체 15% 이상의 픽셀과 2개 영역 지지를 갖는다면 배경/객체 중 어느 쪽인지 단정하지 않고 null로 처리한다. 단일 최근접 픽셀을 선택하지 않는다.
6. 대표 군집의 10~90 백분위 폭이 중앙값의 30%를 넘으면 일관성이 부족한 것으로 거부한다. 통과하면 `Z(m)=median(mm)/1000`이다.

같은 클래스+IoU를 이용한 최근 최대 5개 중앙값을 유지한다. 동종 객체가 겹쳐 대응이 모호하면 새 이력을 시작한다. FULL/RAW 출처 변경, 거리 급변(0.5m와 이전 값의 25% 중 큰 값 초과), 1초 이상 이력 단절, 누락에는 과거 값을 이어 붙이지 않는다. 중복 Depth timestamp는 반복 가중하지 않는다.

유효 Depth는 Frame timestamp와의 차이 및 추론 소요 시간을 포함해 최대 1초까지만 인정한다. Tracking 실패/회전/viewport 변경은 epoch를 바꿔 이전 결과를 즉시 버린다. 메인 ARCore BBox stale도 최대 1초(사용자 설정이 더 짧으면 그 값)이며, Depth가 먼저 만료되면 숫자만 먼저 지운다. BBox가 지워진 뒤 별도의 거리값이 남는 구조는 없다. 거리 없음은 기존 라벨 동작을 유지한다. Overlay/YOLO BBox OFF와 Preview OFF는 기존 표시 차단 조건을 유지한다.

## 5. RAW Depth와 Confidence 적용

기본 **FULL**은 기존 `acquireDepthImage16Bits()`만 사용한다. 비교용 **RAW_WITH_FULL_FALLBACK**을 고르면 같은 ARCore Frame에서 `acquireRawDepthImage16Bits()`와 `acquireRawDepthConfidenceImage()`를 함께 취득한다. 두 이미지의 timestamp/해상도/stride/crop 유효성을 검사하고 confidence가 낮은 픽셀을 제외한다. FULL과 RAW 픽셀을 한 분포로 섞지 않는다.

RAW 미제공·표본 부족·무효 confidence·stale이면 같은 Frame에서 확보한 FULL을 독립적으로 검사할 수 있다. RAW 자체의 군집이 모호하거나 표면 일관성이 깨졌으면 FULL로 억지 보완하지 않는다. 실제 선택된 FULL/RAW, 사용 Depth timestamp와 fallback 이유를 객체 메타데이터와 로그에 보존한다. RAW가 더 정확하다고 실측 결론을 내리거나 모든 물체에서 RAW를 쓸 수 있다고 가정하지 않는다.

공식 자료를 확인한 API/단위/출처 정의:

- [Raw Depth 개발 가이드](https://developers.google.com/ar/develop/java/depth/raw-depth)
- [Frame: 이미지 취득 및 confidence/좌표 API](https://developers.google.com/ar/reference/java/com/google/ar/core/Frame)
- [Coordinates2d](https://developers.google.com/ar/reference/java/com/google/ar/core/Coordinates2d)

## 6. 기존 기능과의 차이 및 기록

| 기능 | CameraX/OFF | 메인 ARCore |
| --- | --- | --- |
| 모델·후처리·필터·경고 정책 | 기존 경로 | 동일 detector/pipeline/출력 경로. 거리와 위험도를 연결하지 않음 |
| 입력 주기 | 기존 설정 | 최대 4Hz, detectInterval이 250ms보다 길면 그 주기. 대기 큐 없음 |
| 카메라 해상도 | 기존 요청/실제 CameraX 결과 | ARCore SDK 선택 CPU 해상도. 설정 요청 해상도는 미적용, 상태 UI/성능 헤더에 안내 |
| Preview/overlay 좌표 | 기존 FIT_CENTER | ARCore 변환에 따른 회전/crop. 추론·ROI·경고 기하학의 기준은 전체 회전 CPU 프레임 |
| ROI·Letterbox/Stretch | 기존 방식 | 같은 ROI를 YOLO/ML Kit에 적용하고 원본 프레임으로 한 번 복원 |
| ML Kit | 기존 동작 | 같은 CPU Bitmap/ROI로 기존 비동기 경로 실행. 거리 표시는 YOLO만 적용 |
| Preview OFF | CameraX Preview 미생성 | GL은 Session.update에 필요해 유지하되 배경을 검게 그린다. 영상·BBox·라벨을 숨기고 분석은 유지 |
| 캡처·녹화 | 기존 버튼과 저장 | 같은 평가 캡처·JSON/JSONL, MediaProjection 녹화 경로. 평가 이미지는 회전 CPU 전체 프레임, 영상은 화면 viewport |
| 성능 로그 | 기존 통계 | 기존 통계에 실제 cameraBackend와 선택적 객체 Depth 정보 추가. accepted CPU 입력 기준의 analyzer FPS이며 GL/센서 FPS와 다름 |
| 촬영 지연 계측 | 검증된 Camera2 realtime만 사용 | ARCore timestamp와 elapsedRealtime의 동일 clock을 단정하지 않아 captureTimestampReliable=false. 수신 이후 지연을 기록하고 촬영 지연은 미상(-1) |

평가 JSON/JSONL의 기존 필드와 파일 형식은 유지한다. 추가 필드: `distanceSource=ARCORE_DEPTH`, `distanceDefinition=CAMERA_FORWARD_Z`, `depthMethod=FULL/RAW`, rawDepthMeters, estimatedDistanceMeters, depthSampleCount, distanceEstimationStatus, depthFrameTimestampNs, depthTimestampNs, depthTrackingState, cameraBackend. null은 0m로 변환하지 않는다. 기존 지면 광학 파라미터는 ARCore 결과에서 null이다.

성능 로그 기록 중에만 객체별 클래스·confidence·BBox·동일 거리 메타데이터를 `depthObjects` JSON으로 추가한다. 기본 사용에서 상세 JSON을 매 프레임 생성하지 않는다. 기존 성능 로그/평가 저장·공유 기능을 사용하며 별도의 상시 로그 writer를 열지 않는다.

남는 한계: segmentation 없이 BBox 내부가 실제 객체 표면인지 증명할 수 없다. 배경만 일관되게 보이는 객체, 가림·반사·투명/무질감 표면, 작은/먼 객체는 오차 또는 null이 생길 수 있다. 여러 군집은 보수적으로 거부하므로 표시 빈도가 줄 수 있다. 카메라 영상보다 추론 BBox가 늦고, 단순 IoU 대응은 복잡한 교차에서 완전한 재식별을 보장하지 않는다. 경고 정책은 같지만 최대 4Hz 입력 때문에 경고 후보 갱신과 ML Kit 요청 시작 간격도 기존 고속 CameraX 입력보다 길어질 수 있다. CPU YUV 변환/Bitmap/샘플 분포 처리·ARCore의 전력/발열 비용도 있다. 정확도 개선 수치·FPS·메모리 수치는 실측하지 않았다.

## 7. 빌드 및 테스트

검증 명령:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
```

자동 검증 결과:

2026-10-27 최종 전체 명령: **BUILD SUCCESSFUL (1분 28초)**.

- 단위 테스트 **279개 / 51개 suite 통과**, 실패/오류 0. 기존 258개와 신규 21개를 포함한다. 설정·mode 선택, 640/512·Letterbox/Stretch·ROI·4방향 좌표, 내부 표면/배경·군집·confidence·stride·누락·만료·출처 전환·JSON을 확인한다. 기존 YOLO 계약/필터/경고·ML Kit 진단·지면 거리·CSV 관리·성능 통계 테스트도 함께 실행했다.
- 성능 로그 분석 Python 테스트 **27개 통과**. 신규 backend/Depth JSON 정보가 기존 지연 지표를 바꾸지 않는지와 기존 파서 회귀를 확인했다. `tools/`에서 번들 Python으로 `-m unittest test_latency_analysis test_analyze_performance_logs` 실행.
- **Debug APK 및 계측 테스트 APK 빌드 성공**. 결과: `app/build/outputs/apk/debug/app-debug.apk`, `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`.
- **Lint 오류 0, 경고 255**. 기존 프로그램 방식 UI 문자열·Canvas 할당 등의 경고를 포함한다. 최종 보고서에서 `LintError` 분석 실패 항목은 0개다. 보고서: `app/build/reports/lint-results-debug.html`.
- `git diff --check` 통과. 모델/학습 파일 변경 없음. 커밋/푸시 없음.

성능 로그의 중복 backend 키는 기존 고유 키 회귀 테스트에서 발견해 한 번만 기록하도록 수정했다. 촬영 시간 미검증은 -1/false로 유지하고 원본 Depth는 표시값이 처리 중 만료되어도 로그에 보존한다.

Android 계측 테스트는 실제 pipeline ROI 복원과 경고 동등성, overlay projection/만료, 설정 UI/재생성/저장을 검증하도록 추가·수정했다. APK 컴파일과 실제 계측 테스트 실행을 구분한다.

## 8. 실제 기기 확인 항목

이번 변경의 실제 단말 설치/실행과 실측은 수행하지 않는다. 이전 작업의 단말 결과를 이 기능의 검증 결과로 재사용하지 않는다.

1. 거리 OFF에서 ARCore를 시작하지 않고 기존 카메라·YOLO·ML Kit·경고·모델 선택이 동작하는지.
2. 거리 ON에서 같은 메인 화면에 카메라·BBox·일부 객체의 전방 Z가 표시되고 FULL/RAW 출처가 로그와 맞는지.
3. 640/9클래스·512/11클래스, ROI ON/OFF·중앙 정방형·Letterbox/Stretch, Preview/Overlay/YOLO BBox ON/OFF 조합.
4. 여러 전경/배경, 객체 하단에 바닥이 보이는 장면, 작은/가린/투명 객체에서 null과 군집 거부가 합리적인지. 샘플 중앙값이 객체 표면인지 영상과 비교.
5. Google Play Services for AR 설치 취소·업데이트·Depth 미지원·카메라 점유 시 오류 사유를 보이며 CameraX 인식으로 복구되는지. 지면 거리로 대체되지 않는지.
6. ON/OFF 반복 적용·지면 방식 전환·앱 pause/resume·Tracking 중단·업데이트 정지·기기 잠금 후 이전 박스/거리가 사라지고 카메라가 복구되는지.
7. 녹화 MP4에 GL 영상/overlay가 포함되는지, 평가 PNG/JSON/JSONL·성능 로그·공유가 완료되는지. 측정 중 설정 제한도 유지되는지.
8. 고급 영역의 기존 ARCore 단독/YOLO 검증 화면을 반복 진입/복귀해 동시 점유·검은 화면·Image 고갈이 없는지.
9. 장시간 메모리/CPU/발열·배터리·실제 입력/추론율. 실제 1/2/3/5m 시험 시 광축 Z 정의에 맞는 실측값을 별도로 기록.

## 9. 향후 ToF 센서 교체

`FrameDepthContext`는 탐지 결과 목록에 거리와 projection을 연결하고 `ObjectDepthEstimate`는 Z/출처/시각/유효성 결과를 전달한다. ToF 어댑터로 확장하려면 센서의 Z/사선 거리 정의, 해상도·단위·confidence, 카메라와 센서의 내/외부 파라미터, 회전/크롭 매핑, 두 센서의 timestamp 동기화/지연/만료를 먼저 정의해야 한다. ARCore 좌표를 ToF에 그대로 재사용할 수 있다고 가정하지 않는다.

현재 카메라 start/stop 계약은 ARCore와 CameraX를 분리하며, ToF 카메라 소유 방식에 맞게 backend를 추가할 수 있다. 기존 경고 정책과의 결합은 별도 요구사항과 검증이 필요한 후속 작업이다. 커밋과 푸시는 수행하지 않는다.
