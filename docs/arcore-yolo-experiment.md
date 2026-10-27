# YOLO + ARCore Depth 통합 실험 모드

> 후속 변경: 메인 화면 통합, schema 9 설정, 객체 내부 다중 영역/RAW Confidence 샘플링은 [메인 통합 문서](arcore-main-integration.md)를 기준으로 한다. 아래는 최초 별도 실험 화면의 구현·검증 기록이다. 현재 별도 YOLO 검증 화면도 개선된 내부 영역 sampler를 사용한다.

2026-10-27 구현. 메인 화면의 **YOLO + ARCore 거리 표시** 버튼으로 별도 `ArCoreYoloExperimentActivity`에 진입한다. 기존 **ARCore 거리 측정** 화면과 CameraX 메인 모드는 유지한다. 이 화면의 숫자는 ARCore의 **카메라 광축 방향 깊이 Z(m)**이며 사선 직선거리나 지면 기반 거리 추정값과 다르다. 정확도 보정값이나 실측 결과를 생성하지 않는다.

## 1. 변경 파일

경로의 Kotlin 소스 기준 디렉터리는 `app/src/main/java/com/samin/objectdetection/`이다.

| 파일 | 변경 내용 |
| --- | --- |
| `MainActivity.kt` | 기존 CameraX 종료 대기·복귀 경로를 새 Activity에도 연결하고 현재 설정을 전달 |
| `ui/MainScreenView.kt` | 신규 진입 버튼, 기존 측정 중 버튼 잠금 적용 |
| `arcore/yolo/ArCoreYoloExperimentActivity.kt` | AR Optional 확인·설치·권한·세션 lifecycle, 실험 UI, 선택적 로그 제어 |
| `arcore/yolo/ArYoloRenderer.kt` | ARCore 카메라 배경, CPU/Depth 이미지 복사와 닫기, 현재 프레임의 좌표 변환 저장 |
| `arcore/yolo/ArYuvSnapshot.kt` | stride를 고려한 YUV→RGB 변환, 최대 4Hz·단일 작업 제한 |
| `arcore/yolo/ArYoloWorker.kt` | 전용 백그라운드 YOLO, 회전 복원, 객체별 Depth 샘플링, 결과 만료·종료 drain |
| `arcore/yolo/ArYoloGeometry.kt` | CPU↔표시 좌표, Depth 매핑, 지역 샘플링, 최근 5개 중앙값, 좌표 세대·만료 판정 |
| `arcore/yolo/ArCoreYoloOverlay.kt` | 정규화된 화면 BBox와 미리 계산한 거리 라벨 표시 |
| `arcore/yolo/ArYoloJsonLog.kt` | 별도 JSONL 기록·flush/drain·원본/필터 값 보존 |
| `app/src/main/AndroidManifest.xml` | 비공개 실험 Activity 등록, 회전 시 세션 유지 |
| `app/src/main/res/xml/file_paths.xml` | 실험 JSONL 폴더의 FileProvider 공유 경로 |
| `app/src/test/java/com/samin/objectdetection/arcore/yolo/ArYoloGeometryTest.kt` | 회전·viewport crop·모델 resize·샘플 위치·누락·클래스 검증 |
| `app/src/test/java/com/samin/objectdetection/arcore/yolo/ArYuvSnapshotTest.kt` | YUV 색/stride/홀수 크기, 프레임 적체 방지 검증 |
| `app/src/test/java/com/samin/objectdetection/arcore/yolo/ArObjectDepthSmootherTest.kt` | 동일 클래스 객체 매칭, 중복 깊이·누락·만료·필터 검증 |
| `app/src/test/java/com/samin/objectdetection/arcore/yolo/ArYoloFrameTest.kt` | BBox/Depth 개별 만료, 회전·pause 세대 변경, 기존 stale 설정 검증 |
| `app/src/test/java/com/samin/objectdetection/arcore/yolo/ArYoloJsonLogTest.kt` | 거리 출처·null·정상 종료·세션별 파일 보존 검증 |
| `docs/arcore-yolo-experiment.md` | 구현 및 검증 기록 |

기존 `VisionStyleYoloDetector`, CameraX analyzer, `DetectionPipeline`, 지면 거리 추정, ML Kit, 기존 ARCore 단독 측정/CSV/로그 목록, 경고·위험도·성능 측정 코드는 수정하지 않았다. YOLO 모델·라벨·학습 파일 및 기존 설정/프리셋 형식도 수정하지 않았다.

## 2. ARCore와 YOLO 결합 방식

ARCore가 새 화면의 카메라를 소유한다. 기존 SDK `com.google.ar:core:1.57.0`과 `ArCameraBackground`를 재사용하며 의존성이나 권한을 추가하지 않는다. 카메라 권한 확인 후 `checkAvailabilityAsync()` → `requestInstall()` → `Session` → `isDepthModeSupported(AUTOMATIC)` → AUTOMATIC 설정 순서로 준비한다. 미지원·권한 거부·설치/초기화 실패는 상태에 표시하고 이전 화면으로 돌아갈 수 있다.

Session의 `resume/pause/update`, display geometry, texture 연결, 이미지 취득 및 변환은 renderer 잠금으로 직렬화한다. YOLO worker에는 Session, Frame, Image를 전달하지 않는다. GPU 카메라 배경은 기존 OES 렌더러를 사용한다.

YOLO는 별도의 `VisionStyleYoloDetector` 인스턴스를 전용 단일 executor에서 생성·실행·닫는다. 메인에서 선택한 640/9클래스 또는 512/11클래스 모델, confidence/NMS, size filter, resize, CPU thread, XNNPACK, max candidates 설정을 그대로 전달한다. 탐지된 모든 클래스에 같은 Depth 처리를 적용한다. 기존 경고 정책이나 objectMotionTracker에는 전달하지 않는다.

공식 문서 확인:

- [Frame: CPU/Depth 이미지 취득과 transformCoordinates2d](https://developers.google.com/ar/reference/java/com/google/ar/core/Frame)
- [Coordinates2d: CPU IMAGE_PIXELS·VIEW_NORMALIZED·TEXTURE_NORMALIZED 정의](https://developers.google.com/ar/reference/java/com/google/ar/core/Coordinates2d)
- [Depth 개발 가이드: AUTOMATIC·16비트 mm·카메라 전방 Z](https://developers.google.com/ar/develop/java/depth/developer-guide)

## 3. 프레임 처리와 카메라 충돌 방지

1. 메인에서 기존 측정/녹화 종료를 확인하고 `CameraController.stopAsync()`를 기다린다. analyzer 종료 후 진행 중인 ML Kit bitmap 사용도 drain한다. 그 뒤 새 Activity를 실행한다.
2. GL 스레드에서 `setDisplayGeometry`·`Session.update()`·카메라 배경 렌더링을 수행한다.
3. 새 프레임이며 YOLO worker가 비어 있고 마지막 입력 이후 250ms 이상 지났을 때만 `acquireCameraImage()`를 호출한다. **실행 중 1개, 대기 큐 0개**로 적체를 막는다. 바쁜 동안 입력을 버리고 다음 처리 기회에 현재 ARCore 프레임을 취득한다.
4. Y/U/V plane을 rowStride/pixelStride와 함께 CPU 배열로 복사하고 Image를 즉시 `close()`한다. worker에서 YUV→ARGB Bitmap 변환 후 현재 화면 방향으로 0/90/180/270도 회전한다.
5. 기존 detector가 모델 입력 resize/Letterbox/NMS와 입력 Bitmap 좌표 복원을 수행한다. 이 결과를 회전 전 원본 CPU 이미지 픽셀로 역변환한다. 앱의 CameraX ROI는 이 실험에 적용하지 않으며 전체 CPU 프레임을 입력한다.
6. Depth도 같은 ARCore Frame에서 취득·복사·닫는다. YOLO와 해당 프레임의 Depth/변환 복사본이 묶여 이동한다. 미준비 시 YOLO는 계속 실행한다.
7. 종료는 GL 루프 정지 → Session.pause/detach → worker의 실행 작업 및 detector.close 완료 → 로그 drain → Session.close → Activity.finish 순서다. 메인은 기존 runtime 종료·재생성 경로를 거쳐 CameraX를 다시 바인딩한다.

`onPause`는 GL 정지 후 Session을 pause하고 결과를 비운다. 정상 복귀에서는 같은 Session을 resume한다. 화면 종료·회전·tracking 변화마다 좌표 세대(epoch)를 갱신해 이전 추론 결과가 뒤늦게 들어와도 표시하지 않는다. 이미지 사용은 `use` 블록으로 묶고 원본/회전 Bitmap은 `finally`에서 recycle한다.

## 4. 좌표와 Depth 샘플링

현재 GL 프레임에서 원본 CPU 이미지의 네 모서리를 `Frame.transformCoordinates2d()`로 각각 `IMAGE_PIXELS → VIEW_NORMALIZED`와 `IMAGE_PIXELS → TEXTURE_NORMALIZED`로 변환해 보관한다. worker는 이 사각형의 보간으로 BBox/샘플 지점을 변환한다. 표시용 변환과 Depth용 변환을 분리해 viewport crop을 Depth에 중복 적용하지 않는다. 보간은 Double 중간값을 사용해 픽셀 경계에서 Float 누적 오차로 한 픽셀 밀리는 것을 줄인다.

- 화면: 원본 CPU BBox 네 꼭짓점 → VIEW_NORMALIZED → 실제 overlay 크기로 확대·클리핑.
- 샘플: **화면에서의 BBox 하단 중앙** → 원본 CPU 픽셀 → TEXTURE_NORMALIZED → Depth 이미지 픽셀. CPU 이미지가 90/180/270도 돌아가 있으면 화면의 아래쪽이 원본의 오른쪽/위쪽/왼쪽에 대응한다.
- 우선 하단 중앙의 5×5 영역을 사용하고, 표본 부족·범위 오류이면 BBox 중앙의 5×5를 시도한다. 이미지 경계에서 유효한 영역만 읽으며 Depth의 실제 cropRect를 넘는 영역은 거부한다.
- 기존 `DepthPlaneSampler`가 little-endian unsigned 16비트 mm를 rowStride/pixelStride로 읽는다. 0을 제외하고 중앙값/MAD로 이상치를 줄이며 필터 후 최소 9개 표본이 있어야 한다. `Z(m) = mm / 1000.0`이다.
- invalid BBox/변환, 범위 밖 좌표, Tracking 중단, 미준비/오래된/미래 timestamp, 표본 부족이면 거리 null이다. 유효한 YOLO 탐지는 유지한다.
- 같은 label과 IoU≥0.25인 객체를 가볍게 매칭해 최근 최대 5개 유효 깊이의 중앙값을 표시한다. 동일 Depth timestamp를 반복 가중하지 않는다. 누락 시 이전 Z를 가져오지 않고 다음 유효 표본부터 시작한다.
- Depth는 카메라 Frame timestamp와 비교해 1초 이상 오래되면 거부한다. 처리 지연까지 포함한 만료 시각을 계산하므로 YOLO가 느려도 거리 수명이 연장되지 않는다. BBox도 입력 후 최대 1초(기존 overlay stale 설정이 더 짧으면 그 값)까지만 표시한다.

## 5. 화면과 선택적 로그

상단에 `Experimental: YOLO + ARCore Depth`, 지원 여부, Tracking·Depth·이미지 크기·CPU 카메라·YOLO 상태를 표시한다. 가운데는 ARCore 카메라와 BBox이며 라벨은 `bollard 약 2.3m` 형태다. 기존 confidence 표시 설정을 적용한다. 거리 없음은 클래스 라벨만 표시한다. 긴 라벨은 줄바꿈/말줄임하고 화면 경계와 다른 라벨 위치를 고려한다. Canvas는 전달받은 좌표와 거리만 그린다.

하단에는 다시 확인·실험 설명·로그 시작/종료·공유·이전 화면이 있다. 세로에서는 하단을 제한된 높이의 스크롤로, 가로에서는 카메라와 조작부를 좌우로 배치한다. 메인의 Preview/Overlay OFF 설정은 별도 실험 화면을 끄지 않는다. 메인으로 돌아가면 원래 설정을 복원한다.

JSONL은 **기본 OFF**이며 이 화면에서 시작한 경우에만 `files/arcore_yolo/arcore_yolo_<time>_<id>.jsonl`에 기록한다. 기존 ARCore CSV와 파일 형식·폴더를 공유하지 않는다. 기록은 수락된 YOLO 결과당 한 줄, 최대 4Hz다. 파일은 정상 종료·flush/drain 후 기존 FileProvider 방식으로 공유한다. 공용 Downloads 내보내기나 기존 CSV 다중 목록 기능의 대상을 확장하지는 않는다.

주요 필드: `mode=EXPERIMENTAL_YOLO_ARCORE`, `distanceSource=ARCORE_DEPTH`, `distanceDefinition=CAMERA_FORWARD_Z`, frame/camera/depth timestamp, `coordinateEpoch`, tracking, 이미지 크기, inferenceMs 및 객체별 label/confidence, `bboxSpace=CPU_IMAGE_PIXELS`의 bbox, viewportBox, **rawDepthMeters**, 표시용 **estimatedDistanceMeters**, depthSampleCount, distanceStatus, Depth 픽셀 samplePoint. 거리 없음은 JSON null로 기록한다. 이전 로그를 덮어쓰거나 삭제하지 않는다.

## 6. 자동 검증 결과와 성능 한계

검증 명령:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
```

2026-10-27 최종 실행: **BUILD SUCCESSFUL (2분 15초)**.

- 단위 테스트 **258개 / 48개 suite 통과**, 실패·오류 0. 기존 235개와 신규 23개를 포함한다. YOLO 모델 계약/좌표 복원, 경고, 지면 거리, ML Kit 진단, 설정, 성능 측정, 기존 ARCore CSV/로그 관리 회귀 테스트를 포함한다.
- `:app:assembleDebug`와 `:app:assembleDebugAndroidTest` 성공. APK: `app/build/outputs/apk/debug/app-debug.apk`, 계측 테스트 APK: `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`.
- `:app:lintDebug` 성공: **오류 0, 경고 252**. 기존 239개에 프로그램 방식 UI 문자열·Canvas 할당 등 13개 경고가 추가됐다. 보고서: `app/build/reports/lint-results-debug.html`.
- `git diff --check` 통과. 모델·라벨·학습 파일 변경 없음, 커밋/푸시 없음.

Android test APK 빌드는 실제 단말 계측 테스트 실행과 다르다. 이번 기능의 기기 설치/실행 및 실측은 수행하지 않았다.

신규 테스트는 네 방향 BBox 역회전/화면 하단 샘플, viewport/Depth FoV 차이, Letterbox/Stretch·640/512 조합, 9/11개 클래스, 0/누락·crop·timestamp 거부, 객체별 중앙값, YUV stride/홀수 크기/색, 단일 작업 제한, 좌표 세대와 만료, JSONL 종료 drain을 검증한다. 기존 단위 테스트도 함께 실행한다.

입력과 Depth 취득은 최대 4Hz이고 실제 처리율은 YUV 변환·추론 시간에 따라 더 낮다. GL에서 plane 복사 시 짧은 렌더 지연이 생길 수 있다. CPU 배열 및 Bitmap 할당, 별도 YOLO interpreter와 ARCore의 동시 메모리 사용량, 발열/전력 영향은 아직 기기에서 측정하지 않았다. 메인 interpreter는 기존 전환 구조대로 정지 상태로 남고 실험용 interpreter는 종료 시 닫힌다.

카메라 배경은 현재 시점이고 BBox는 추론 입력 시점이어서 움직임이 빠르면 표시 지연과 위치 차이가 있다. 하단/중앙 표본이 바닥·배경·가려진 물체를 읽을 수 있고, 작은/투명/반사/무질감 객체나 먼 거리에서는 Depth 품질이 낮을 수 있다. 간단한 IoU 매칭은 교차하는 동종 객체의 거리를 섞을 수 있다. 이 실험은 정밀 거리 검증이나 안전 판단에 연결하지 않는다.

## 7. 실제 단말에서 확인할 항목

이번 작업에서는 사용자 요청에 따라 기기 설치·실행 검증을 생략한다. 다음은 미검증 항목이며 자동 테스트 통과만으로 실제 동작이나 정확도를 확인했다고 보지 않는다.

1. Galaxy S24에서 AR Optional 설치/권한·Depth AUTOMATIC 활성화, 미지원/거부 시 복귀.
2. 기존 메인 → 기존 ARCore 단독 측정 → 메인, 기존 메인 → 새 통합 화면 → 메인을 각각 반복해 카메라 충돌·검은 화면·재시작 누락 확인.
3. 640/9클래스 및 512/11클래스 모델에서 카메라 영상, 실제 YOLO 추론, BBox, confidence·일부 객체의 거리 표시 확인.
4. 세로/가로/반대 방향 회전과 viewport 종횡비 변화에서 BBox 및 화면 하단 샘플의 정합 확인.
5. 기기를 천천히 움직여 Depth 준비 후 표시되는지, Tracking PAUSED/카메라 가림·업데이트 중단·앱 전환 시 이전 Z가 사라지는지 확인.
6. Depth가 없을 때 탐지 라벨은 유지되는지, 빠른 객체/카메라 이동에서 지연이 어느 정도인지 확인.
7. pause/resume·반복 진입/종료 후 메모리/카메라 핸들·Image 고갈·발열·실제 FPS 확인.
8. 선택적 JSONL 시작/종료·파일 내용·원본/필터 값·다른 앱에서 URI 읽기 확인. 기존 CSV 목록/다중 공유/공용 내보내기도 그대로 동작하는지 확인.

실제 측정 정확도 수치는 생성하지 않았다. 추후 1/2/3/5m 시험은 **렌즈 중심의 광축 방향 Z**를 같은 정의로 실측하고 배경/재질/시점/샘플 위치를 함께 기록해야 한다.

## 8. 이후 개선 우선순위

1. 실제 단말 영상에서 회전·crop·시점 정합과 카메라 복귀를 우선 확인한다.
2. JSONL과 샘플 위치를 통해 BBox 대표점이 객체 표면을 읽는지 확인한 뒤 내부 복수 영역/Depth confidence/객체 경계 선택을 개선한다.
3. YUV 변환/Bitmap 할당/이미지 복사 비용과 실제 FPS·전력·메모리를 측정하고 필요하면 버퍼 재사용·입력 해상도 조절을 적용한다.
4. 동일 클래스 교차/가림 사례에서 필터 매칭을 개선하고, 측정 목적에 필요한 로그 메타데이터와 내보내기를 확장한다.
5. 안정화 후에만 YOLO 입력 시점과 Depth 시점의 연결 계약을 메인 모드/향후 ToF 어댑터로 확장한다. CameraX와 ARCore의 동시 카메라 점유나 별도 시점의 Depth 단순 재사용은 추가 설계가 필요하다.

커밋과 푸시는 수행하지 않는다.
