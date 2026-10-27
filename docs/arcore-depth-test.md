# ARCore Depth 거리 측정 테스트

## 범위와 변경 파일

기존 객체 인식과 독립적인 AR Optional 실험 화면이다. 메인 화면의 **ARCore 거리 측정** 버튼으로 진입한다. CameraX 파이프라인, YOLO 모델·후처리, ML Kit, 지면 거리 추정, 경고 및 성능 계산 코드는 변경하지 않았다. 메인 화면의 진입/복귀 수명 관리와 버튼만 연결했다. 서버, Cloud Anchors, Geospatial, 유료 클라우드 API는 사용하지 않는다.

| 파일 | 역할 |
| --- | --- |
| `app/build.gradle.kts` | 공식 ARCore Android SDK `com.google.ar:core:1.57.0` 의존성 |
| `app/src/main/AndroidManifest.xml` | AR Optional metadata, 선택적 AR/OpenGL feature, 별도 Activity 등록 |
| `app/src/main/res/xml/file_paths.xml` | ARCore CSV 폴더만 FileProvider 공유 범위에 추가 |
| `app/src/main/java/com/samin/objectdetection/MainActivity.kt` | CameraX analyzer 종료 대기, ARCore 진입, 기존 종료·재생성 경로로 복귀 |
| `app/src/main/java/com/samin/objectdetection/ui/MainScreenView.kt` | ARCore 버튼과 기존 측정 잠금 연결 |
| `app/src/main/java/com/samin/objectdetection/arcore/ArCoreDepthActivity.kt` | 지원/설치/권한 확인, 세션 lifecycle, 화면과 로그 제어 |
| `app/src/main/java/com/samin/objectdetection/arcore/ArDepthRenderer.kt` | Session 직렬화, GL 렌더 루프, 10Hz 깊이 표본 취득 |
| `app/src/main/java/com/samin/objectdetection/arcore/ArCameraBackground.kt` | OES 카메라 텍스처, 화면 좌표에 맞춘 UV 및 카메라 배경 렌더링 |
| `app/src/main/java/com/samin/objectdetection/arcore/ArCoreDepthSource.kt` | 현재 ARCore 프레임의 Depth Image 획득·닫기, 중앙 좌표 변환 |
| `app/src/main/java/com/samin/objectdetection/arcore/DepthMeasurement.kt` | Z 표본 계약, stride 처리, 지역 중앙값/MAD, 시간 필터와 만료 |
| `app/src/main/java/com/samin/objectdetection/arcore/DepthCsvRecorder.kt` | 독립적인 CSV 직렬화와 순서 보장 비동기 파일 기록 |
| `app/src/test/java/com/samin/objectdetection/arcore/DepthPlaneSamplerTest.kt` | 합성 16비트 버퍼·좌표·이상치·경계 검증 |
| `app/src/test/java/com/samin/objectdetection/arcore/DepthMeasurementFilterTest.kt` | 원본/표시값, 중복·추적 실패·만료·오차 정의 검증 |
| `app/src/test/java/com/samin/objectdetection/arcore/DepthCsvRecorderTest.kt` | CSV 필드·locale·저장·반복 세션·종료 drain 검증 |
| `docs/arcore-depth-test.md` | 구현, 실행 방법, 검증 범위 및 향후 결합 작업 |

## SDK와 공식 문서

2026-10-26 확인 시 공식 최신 릴리스는 **1.57.0**이다. 검색 결과에 남아 있던 1.56.0 대신 공식 latest redirect의 1.57.0을 확인했고, Google Maven에서 해당 의존성이 해석되는 것도 확인했다. 앱의 기존 AGP/Gradle 버전은 변경하지 않았다.

- [공식 1.57.0 릴리스](https://github.com/google-ar/arcore-android-sdk/releases/tag/1.57.0)
- [AR Optional·지원 확인·설치·권한](https://developers.google.com/ar/develop/java/enable-arcore)
- [Depth 활성화·취득·Z 정의](https://developers.google.com/ar/develop/java/depth/developer-guide)
- [Frame API: 16비트 Depth와 좌표 변환](https://developers.google.com/ar/reference/java/com/google/ar/core/Frame)
- [공식 BackgroundRenderer 샘플](https://github.com/google-ar/arcore-android-sdk/blob/master/samples/hello_ar_java/app/src/main/java/com/google/ar/core/examples/java/common/samplerender/arcore/BackgroundRenderer.java)

OES 텍스처와 변환된 UV로 배경을 표시하는 샘플의 접근을 참고했다. 샘플 전체 렌더링 프레임워크를 복사하거나 가상 물체·occlusion 기능을 추가하지 않았다.

## AR Optional 초기화

`com.google.ar.core=optional`, `android.hardware.camera.ar required=false`, OpenGL ES 2 feature `required=false`로 구성한다. Depth 전용 필수 feature로 배포 대상을 제한하지 않는다. 병합 Manifest에서도 optional과 feature false를 확인했다.

별도 Activity에서 카메라 권한을 확인한 뒤 `ArCoreApk.checkAvailabilityAsync()`로 지원 여부를 확인한다. 지원 기기는 `requestInstall()`로 Google Play Services for AR 및 기기 프로파일 설치/업데이트를 처리하고 `Session`을 생성한다. 취소/권한 거부/미지원/초기화 실패는 화면에 표시하며 뒤로 돌아갈 수 있다. 사용자가 명시적으로 다시 확인을 누르면 설치 요청도 다시 시도할 수 있다.

`Session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)`이 true일 때만 Depth AUTOMATIC을 활성화한다. 평면 탐색과 조명 추정은 이 실험에서 사용하지 않아 비활성화한다. `LATEST_CAMERA_IMAGE` 갱신 모드를 사용하며 같은 프레임/깊이 타임스탬프는 새 측정으로 집계하지 않는다.

SDK 호출과 거리 계산은 기기에서 수행된다. Google Play Services for AR의 설치·지원 확인·기기 프로파일 준비에는 Google 서비스의 네트워크 접근이 필요할 수 있다. 앱이 측정값을 서버에 전송하는 기능은 없다.

## 화면과 측정 조작

- 상단: ARCore 지원 상태, Depth 지원/AUTOMATIC 활성화 상태, Tracking, 깊이 해상도, 갱신 상태, 다시 확인 버튼.
- 중앙: GLSurfaceView 카메라 영상, 중앙 십자선, `전방 깊이: 약 2.35m`. 숫자는 실제 유효 Depth가 있을 때만 표시한다.
- 하단: 원본 Z, 필터 Z, 최근 평균(최대 3초/30개), 현재 유효 픽셀 수, 최근 유효 측정 수, 안정화 스위치, 실측 Z 입력, 1/2/3/5m 입력 버튼, 측정 시작/종료·저장·공유·이전 화면 버튼.
- 세로 화면은 카메라 아래에 스크롤 가능한 조작 영역을 두며, 가로 화면은 카메라와 조작 영역을 좌우 배치한다. 십자선은 항상 GL 카메라 viewport의 중앙에 놓인다.
- 실시간 카메라/깊이 표시는 세션이 활성화되면 동작한다. **측정 시작**은 통계 필터를 새로 시작하고 CSV 파일 기록을 시작한다. **측정 종료**와 **측정 로그 저장**은 기록을 drain하고 파일을 완성한다. 측정을 종료해도 카메라를 보며 다음 측정을 준비할 수 있다.
- 실측값은 기본적으로 비어 있다. 1/2/3/5m 버튼은 사용자가 선택한 기준값을 입력할 뿐, 해당 거리의 실제 측정 결과를 생성하지 않는다.

## Depth 데이터 처리

1. GL 렌더 루프에서 잠금으로 보호한 현재 Session의 texture/display geometry를 지정하고 `Session.update()`를 호출한다.
2. 카메라 배경은 NDC quad를 `Frame.transformCoordinates2d(OPENGL_NORMALIZED_DEVICE_COORDINATES, TEXTURE_NORMALIZED)`로 변환한 UV로 렌더링한다. 회전과 viewport 종횡비를 ARCore가 반영한다.
3. 깊이 취득은 최대 10Hz로 제한한다. TRACKING 상태에서만 `frame.acquireDepthImage16Bits()`를 호출한다.
4. 화면 중앙 `(0.5, 0.5)`를 `VIEW_NORMALIZED → TEXTURE_NORMALIZED`로 변환하고 실제 depth width/height로 픽셀 인덱스를 계산한다. NaN/Infinity 또는 `[0, 1)` 밖의 좌표는 clamp하지 않고 거부한다.
5. 단일 D_16 plane에서 `buffer.position + y*rowStride + x*pixelStride` 오프셋으로 읽는다. **little-endian unsigned 16비트** 전체를 사용하며, Android의 confidence-packed DEPTH16처럼 상위 비트를 잘라내지 않는다. 0mm는 무효다.
6. 중앙 주변 5×5 영역의 유효 값에서 중앙값을 구한다. MAD 기반으로 `max(50mm, 3*MAD)` 밖의 이상치를 제외하고, 최소 9개 유효 표본이 남을 때만 지역 중앙값을 반환한다. 경계에서는 실제 이미지 안의 픽셀만 읽는다.
7. `Z(m) = depthMillimeters / 1000.0`으로 변환한다. 이 결과는 카메라 평면에 수직인 **광축 방향 깊이 Z**다. 사선 직선거리나 기존 지면 기반 전방 거리와 섞지 않는다.
8. Image는 `use` 블록 안에서만 사용하고 성공·좌표 실패·표본 부족·예외 모두 닫는다. `NotYetAvailableException`은 정상적인 준비 상태로 취급한다.

CSV의 `rawDepthMeters`는 **이 앱의 표시 필터 전 지역 중앙값**이다. SDK의 별도 Raw Depth API나 보정 전 센서값이라는 뜻이 아니다. 이 화면은 AUTOMATIC Depth API의 결과를 사용한다.

## 표시 안정화와 만료

선택적 안정화는 최근 5개 유효 Z의 이동 중앙값이다. 원본 지역 측정값을 덮어쓰지 않는다. 최근 평균은 최대 3초 또는 30개 유효 측정의 산술 평균이며 공간상의 유효 픽셀 수와 구별해 표시한다.

중복/역순 Depth 타임스탬프는 원본 취득값을 CSV에 남기되 통계·필터에 다시 넣거나 만료 시간을 갱신하지 않는다. 이전 프레임의 Depth 이미지가 반환되면 frame/depth 타임스탬프의 나이를 반영한다. 1초 이상 오래됐거나 미래의 Depth 타임스탬프는 신규 값으로 수용하지 않는다.

새로운 깊이가 없을 때 이전 유효 표시값은 최대 1초 안에서만 유지하며 화면에 이전 값임을 표시한다. Tracking이 PAUSED/STOPPED이면 필터와 표시값을 즉시 제거한다. GL 갱신 자체가 중단돼도 메인 스레드의 200ms UI 갱신이 만료를 검사한다. 준비되지 않은 상태는 **깊이 데이터 준비 중 - 기기를 천천히 움직여 주세요.**로 표시한다.

주요 상태는 `UPDATED`, `WAITING_FOR_DEPTH`, `TRACKING_PAUSED`, `INSUFFICIENT_SAMPLES`, `CENTER_OUT_OF_BOUNDS`, `DUPLICATE_FRAME`, `DUPLICATE_DEPTH`, `STALE_DEPTH`, `DEPTH_ACQUISITION_ERROR`다. UI는 프레임 갱신 중단도 `STALE_UPDATE`로 구분한다.

## 카메라 충돌과 lifecycle

메인 → ARCore:

1. 기존 녹화·성능·ML Kit 로그 측정 중에는 전환을 시작하지 않는다. analyzer 종료 대기 중에 새 기록이 시작된 경우도 진입 직전에 다시 확인한다.
2. 기존 `CameraController.stopAsync()`로 use case를 해제하고 analyzer executor의 실제 종료를 메인 스레드를 막지 않고 기다린다. 실패 시 ARCore를 시작하지 않는다.
3. 진행 중인 ML Kit/캡처 작업의 bitmap lease가 반환될 때까지 기다린다.
4. 이전 overlay를 제거한 뒤 ARCore Activity를 연다. CameraX 코드는 수정하지 않는다.

ARCore 화면 내부:

- `resume/update/setDisplayGeometry/setCameraTextureName/pause`는 동일한 renderer lock으로 직렬화한다. Frame/이미지는 현재 GL 호출 밖으로 내보내지 않는다.
- 일시정지는 `GLSurfaceView.onPause()` → `Session.pause()` 순서로 진행한다. 로그 기록은 자동 종료·저장하고 숫자를 제거한다.
- 재개 시 설치/권한 조건을 확인해 Session을 resume하고 texture를 다시 연결한다. 회전/viewport 크기도 다시 반영한다.
- 명시적인 종료는 GL 중단·Session pause 후 CSV drain과 Session close를 Activity coroutine 취소와 독립된 작업으로 완료한 다음 `finish()`한다. OS에 의한 Activity 파괴에서도 남은 세션을 닫는다.

ARCore → 메인:

Activity 결과를 받으면 기존 앱의 종료·재생성 경로를 재사용해 detector/ML Kit를 닫고 같은 설정으로 MainActivity를 다시 만든다. 새 CameraController는 프레임 번호가 1부터 시작하므로 기존 Overlay의 역순 방지 상태와 같은 런타임/trace session 안에서 재사용하면 안 된다. 새 런타임으로 복원해 이 충돌을 피한다. 기존 무인자 `restartForSettings()`는 보존해 기존 계측 테스트와 호출 호환성을 유지한다.

## CSV 저장과 오차 정의

앱 내부 전용 폴더 `files/arcore_depth/arcore_depth_<timestamp>_<id>.csv`에 UTF-8로 스트리밍한다. 기존 평가·ML Kit·성능 로그 폴더는 사용하지 않는다. 큐는 512줄로 제한하며 수용한 줄은 순서대로 기록하고 종료 시 drain한다. 누락 줄 수와 저장 오류는 UI에 표시한다. 공유는 사용자가 버튼을 눌렀을 때 FileProvider와 Android 공유 화면으로 진행한다.

```text
timestamp,frameTimestampNs,trackingState,depthWidth,depthHeight,centerX,centerY,rawDepthMeters,filteredDepthMeters,validSampleCount,depthTimestampNs,depthStatus,groundTruthMeters,absoluteErrorMeters,relativeErrorPercent,source,distanceDefinition
```

- `timestamp`: 취득 시각 ISO-8601 UTC. frame/depth 타임스탬프는 ARCore가 반환한 ns 값 그대로다.
- `centerX/centerY`: 변환한 **Depth 이미지 픽셀 좌표**. 화면 픽셀 좌표가 아니다.
- `validSampleCount`: 현재 공간 표본의 이상치 제거 후 유효 픽셀 수. 최근 측정 횟수가 아니다.
- `source=ARCORE_DEPTH`, `distanceDefinition=CAMERA_FORWARD_Z`로 출처와 거리 정의를 명시한다.
- 측정 불가 값은 빈 필드이며 0m로 대체하지 않는다.
- 실측 기준은 렌즈 중심 기준의 **광축 방향 Z**로 입력한다. 원본 측정값에 대해 `absoluteError = abs(rawZ - truthZ)`, `relativeErrorPercent = absoluteError / truthZ * 100`을 계산한다. 실측값이 없거나 유효한 새 측정이 아니면 오차를 기록하지 않는다.
- 필터 전 원본은 항상 별도 열에 보존한다. CSV 소수점은 기기 locale과 무관하게 `.`이다.
- 화면 일시정지/종료 시 측정은 자동 저장된다. 다시 화면을 열었을 때 최신 CSV를 공유할 수 있다. 앱 제거 시 내부 로그는 함께 삭제되므로 필요한 파일은 공유해 보관한다.

## 검증 범위

실행 명령:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
```

단위 테스트에는 0mm/unsigned 상위 비트/stride와 position/little-endian/미터 변환/중앙 좌표 범위/잘린 버퍼/이상치/표본 부족, 중복·추적 실패·갱신 중단·오래된 이미지/원본 보존/실측값 검증, CSV locale/반복 기록/drain이 포함된다. 기존 지면 추정·설정·경고·성능 측정 등의 단위 회귀 테스트도 함께 실행한다. Android 계측 테스트 APK는 컴파일하지만 이번 작업에서는 실제 기기 실행을 하지 않는다.

2026-10-26 최종 실행 결과:

- 단위 테스트 41개 suite, **220개 통과**, 실패/오류 0. 기존 204개와 새 ARCore 데이터 처리/필터/CSV 테스트 16개를 포함한다.
- `:app:assembleDebug`, `:app:assembleDebugAndroidTest`: 성공. `app/build/outputs/apk/debug/app-debug.apk`와 `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` 생성.
- `:app:lintDebug`: 성공, **오류 0개 / 경고 230개**. 기존 경고와 새 화면의 문자열/locale 관련 경고를 포함한다. 한국어 테스트 앱의 기존 프로그램 방식 UI와 일관되게 작성했으며 전체 문자열 리소스화는 하지 않았다.
- 위 네 Gradle 작업의 마지막 실행은 `BUILD SUCCESSFUL`(1분 35초)이다. `git diff --check`도 통과했다.
- 실제 기기 설치/계측/실측은 실행하지 않았으며, 실제 ARCore 동작 및 정확도 통과로 해석하면 안 된다. 앞선 지면 추정 기능의 기기 검증 결과를 이 ARCore 기능의 검증 결과로 재사용하지 않았다.
- 커밋·푸시는 수행하지 않았다.

사용자의 **테스트는 패스** 지시에 따라 실제 단말 설치·카메라·실측 테스트를 생략했다. 따라서 SM-S921N의 실제 지원 응답, Depth AUTOMATIC 활성화, GPU 카메라 미리보기의 검은 화면/회전/왜곡 여부, 실제 좌표 정합, Depth 생성, 복귀 후 YOLO, 반복 실행 시 카메라/메모리 누수는 **단말에서 미검증**이다. 기기에서 얻은 것처럼 보이는 거리·오차 결과나 1/2/3/5m 실측 데이터를 만들지 않았다.

### 단말에서 확인할 항목

1. 지원/설치/권한 안내와 Depth AUTOMATIC 활성화 상태를 확인한다. 비지원/설치 취소/권한 거부에서도 뒤로 돌아갈 수 있어야 한다.
2. 밝고 질감 있는 정지 대상 앞에서 기기를 천천히 움직여 Tracking과 Depth를 준비한다. 카메라와 십자선 방향, 세로/가로 회전, 다양한 종횡비의 중앙 정합을 확인한다.
3. 렌즈 중심에서 광축 방향으로 1/2/3/5m에 놓인 평면 대상을 실측하고 해당 기준값을 직접 선택한다. 각 구간의 CSV를 따로 기록한다. 사선 줄자 거리나 지면 거리를 Z 기준과 섞지 않는다.
4. 원본 Z와 필터 Z를 비교하고 유효 표본 수·분산·오차를 CSV로 평가한다. 기기를 고정한 경우, 움직이는 경우, 조도·반사·텍스처·가림 조건을 구분한다.
5. Tracking을 잃거나 업데이트가 중단됐을 때 1초 이후 숫자가 남지 않는지 확인한다.
6. 측정 중 Home/복귀/뒤로가기/공유/화면 회전, ARCore와 YOLO 간 반복 진입·복귀를 검증한다. 카메라 점유와 메모리/이미지 자원 증가도 확인한다.

## YOLO·ToF 결합 시 추가 작업

현재 화면과 CameraX YOLO는 **동시에 카메라를 점유하지 않는다**. 향후 결합에는 공통 프레임 공급 및 카메라 소유 구조가 먼저 필요하다. ARCore camera image를 YOLO에 전달하거나 지원 가능한 SharedCamera/Camera2 구성을 검토해야 하며, CameraX와 ARCore 세션을 단순히 동시에 실행해서는 안 된다.

같은 frame/depth 타임스탬프, 원본 회전/ROI/Letterbox 역변환, ARCore texture/depth 좌표 정합을 보장한 뒤 각 BBox의 측정 지점/영역을 Depth에 연결해야 한다. 물체 경계에서 배경 깊이가 섞이는 문제, 가림·표본 부족, 거리 유효성/정확도, 지면 추정과의 출처 구분도 별도로 설계·검증해야 한다. 이번 단계에서는 기존 BBox·경고·위험도에 ARCore 값을 연결하지 않았다.

작은 인터페이스 `ForwardDepthSource<T>.measure(input): DepthSample`을 분리했다. 향후 ToF adapter는 같은 Z 단위·타임스탬프·유효성 계약의 DepthSample을 반환할 수 있다. 실제 ToF 결합에는 센서-카메라 외부 보정, 해상도/좌표 매핑과 시간 동기화가 추가로 필요하다.
