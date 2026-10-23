# P1-1 이후 ML Kit 검출·BBox 표시 분리 분석

작성일: 2026-10-23 (Asia/Seoul). 분석 기준: 현재 checkout `9f182e0` 및 이번 작업 diff. 원격 fetch/pull, 커밋, 푸시는 하지 않았다. 기존 [P1-1 문서](yolo-mlkit-bitmap-optimization-p1-20261022.md)와 [출력 파싱 분석](yolo-output-parsing-regression-analysis-20261022.md)은 보존했다.

## 1. 증상과 재현 조건

사용자 보고: Preview ON에서 여러 물체를 비추어도 ML Kit의 노란 BBox가 보이지 않는다. YOLO와 ML Kit는 독립 경로다.

연결된 SM-S921N / Android API 36 / owner user 0의 **기존 앱 설정을 읽기만 하여** 다음을 확인했다.

| 항목 | 실제 저장값 |
|---|---|
| 모델 / Threads / XNNPACK | YOLO_512 / 2 / true |
| Preview / ML Kit Detection | true / true |
| Overlay / Show ML Kit Boxes | true / true |
| Show YOLO Boxes / Overlay Debug Mode | false / SIMPLE |
| ML Kit 간격 / Overlay stale | 1500ms / 1500ms |
| 요청 해상도 / center square | 1280×720 / false |
| ROI | enabled, top=.20, bottom=0, left=.10, right=.10 |
| Resize / confidence / NMS | LETTERBOX / .2 / .45 |
| detector diagnostics / debug detection / PNG | 모두 false |

기존 CameraController 로그의 sensor 입력은 1440×1080, rotation=90이다. upright 프레임은 1080×1440이므로 사용자가 제시한 좌표 기준과 일치한다. 저장된 requested 크기와 실제 협상 크기는 구분했다.

### 직접 확인한 SDK 결과

기존 Logcat의 **2026-10-23 09:05:31.190–09:05:54.435, PID 24192**에서 `MlKitObjectDetector: detections=0`가 15회 연속 존재했다. 이는 `process(image)`의 Task await와 결과 변환이 성공한 경우에만 출력되는 행이다. 각 wrapper 소요 시간은 2–28ms, 행 간격은 약 1.48–1.77초다.

따라서 **이 구간은 SDK 목록 자체가 0개였으며, 양성 결과가 Overlay에서만 사라진 사례가 아니다.** 다만 읽기 시점 앱 PID는 25246으로 달랐다. 과거 PID의 15개 행을 현재 실행 전체나 최초 15회라고 간주하지 않았다. wrapper 소요 시간이 곧 native 모델 kernel 시간이라는 의미도 아니다.

## 2. 실제 실행 흐름과 차단 지점

소스 경로는 `app/src/main/java/com/samin/objectdetection/` 기준이다.

| 단계 | 실제 코드 / 의미 | 전달되지 않거나 사라질 수 있는 조건 |
|---|---|---|
| CameraX 수신 | `camera/CameraController.kt`, RGBA_8888 / KEEP_ONLY_LATEST / 단일 executor | lifecycle 정지, interval skip, 변환 오류, 화면 잠금/비전경의 카메라 제약 |
| Bitmap·회전 | `camera/ImageProxyExt.kt`, CameraX toBitmap → clockwise rotate | 변환 예외. upright Bitmap은 listener 동기 반환 후 recycle |
| ML Kit 실행 gate | `MainActivity.processBitmap()` → `maybeRunMlKitDetection()`이 YOLO pipeline보다 먼저 실행 | ML Kit OFF, 1500ms 미경과, 기존 inFlight=true |
| ROI·640×360 | `FrameRoi.calculate()` → `MlKitInputBuffer.acquire()` | invalid ROI/재진입/closed/recycled source 오류 |
| SDK 입력 | `InputImage.fromBitmap(input, 0)` | 이미 회전된 Bitmap이므로 추가 회전 없음. 입력 오류는 기존 empty-result 처리 |
| SDK 실행 | `MlKitObjectDetector.detect()` → `detector.process()` | SDK 동기 예외, Task ERROR/CANCELLED, 종료되지 않는 Task |
| 완료·목록 | Task completion listener → NonCancellable await → bbox Rect 복사 | **SUCCESS라도 빈 목록 가능**. 오류 후 empty list와 구분해야 함 |
| 좌표 복원 | MainActivity의 `roi.sourceX/Y(..., 640/360)` | 현 코드에 별도 confidence/NMS/YOLO 정책 필터 없음 |
| UI 전달 | Preview ON → 별도 `mlKitUiUpdates` → `updateMlKitDetections()` | Preview OFF, Activity destroying, dispatcher close/replacement |
| 상태 갱신 | `ui/OverlayFrameState.update()` | DISABLED, OUT_OF_ORDER, INVALID_TIME, BEFORE_RESET, EXPIRED_AT_UPDATE |
| 화면 변환·표시 | `BoundingBoxOverlay.onDraw()`의 ML Kit 전용 full-frame FIT_CENTER | global overlay OFF, ML Kit boxes OFF, stale, 빈 결과, View 크기/가시성/화면 밖 bbox |
| lease 반납 | coroutine Job completion → input.close → gate 해제 | SDK가 끝나지 않으면 안전하게 입력을 보유하고 다음 ML Kit 실행을 막음 |

YOLO와 ML Kit의 dispatcher/state/transform은 독립적이다. ML Kit 결과를 YOLO 필터로 지우는 통합 경로는 없다. `OverlayDebugMode.NONE`은 라벨만 숨기며 BBox 자체를 숨기지 않는다. 현재 코드에 `showMlKitDiagnostics`라는 표시 설정은 없다. 녹색 ROI 가이드는 bbox enabled 검사 전에 그려지므로, 가이드가 보인다는 사실만으로 노란 BBox 활성화를 입증할 수 없다.

## 3. 좌표와 stale 정책의 확인

1080×1440 upright에서 top20% / sides10%를 적용하면 다음과 같다.

| 항목 | 값 |
|---|---|
| ROI | `(108,288)–(972,1440)` |
| ROI 크기 | 864×1152 |
| ML Kit 입력 | 640×360 stretch (기존 동작) |
| 원본 복원 | `x=108+1.35*xML`, `y=288+3.2*yML`, 기존 clamp 유지 |
| ML 전체 bbox 복원 | `(0,0,640,360) → (108,288,972,1440)` |
| ML 중앙 복원 | `(320,180) → (540,864)` |

Overlay는 **ML 입력 640×360이 아닌 원본 upright 1080×1440**을 기준으로 FIT_CENTER한다. View가 W×H일 때 `scale=min(W/1080,H/1440)`, `offsetX=(W-1080*scale)/2`, `offsetY=(H-1440*scale)/2`다. 테스트의 200×200 View에서는 scale=200/1440, offsetX=25이며 ROI 세로 경계는 x=40,160이다. 실제 노란 픽셀로 검증했다. 이중 crop, 축 교환, ROI 오프셋 누락은 발견하지 못했다.

stale의 시작점은 SDK 완료 시각이 아니다. `FrameTrace.captureAgeMsAt()`이 유효하면 CAMERA_CAPTURE, 아니면 ANALYZER_RECEIVED를 사용한다. age가 **1500ms 이상이면 도착 즉시 EXPIRED_AT_UPDATE**로 폐기하며 draw 시에도 다시 만료를 확인한다.

1500ms 간격과 1500ms timeout이 같다고 항상 BBox가 없어지는 것은 아니다. 예를 들어 촬영→SDK/UI 갱신이 300ms면 표시 가능한 잔여 시간이 약 1200ms이고 다음 dispatch 전 빈 구간이 생길 수 있다. 촬영→UI가 1500ms 이상이면 양성 결과도 한 번도 그려지지 않을 수 있다. 이 조건은 자동 테스트로 재현했지만, **기존 실기기 15개 zero-result 로그의 원인으로 적용할 수는 없다.** 주기·timeout·시간 기준은 변경하지 않았다.

## 4. P1-1과의 관련성 및 SDK 제약

P1-1 이전 `8fdc175`와 현재 `9f182e0` 사이의 ML Kit SDK 설정은 STREAM_MODE / enableMultipleObjects로 같다. 1500ms 간격, ROI, 640×360 stretch, bbox 복원, 표시 gate와 stale 시간 기준도 같았다. 이번 작업에서도 이 정책은 유지했다.

재사용 Bitmap은 clear 후 crop/Matrix draw를 동기로 완료하며 camera/YOLO 저장소와 독립이다. 처리 중 단일 lease가 추가 acquire를 거부하고, Task 완료 전에는 반납·덮어쓰기·recycle하지 않는다. partial crop은 기존 경계 sampling 때문에 유지하며 full-frame crop만 생략한다. 48개 픽셀 조합, source 변경/recycle 독립성, Task 취소 중 수명 테스트가 실기기에서도 통과했다.

### 실제 SDK에서 legacy/P1 비교

기존 앱을 덮어쓰지 않은 별도 패키지에서 `self_test/known.png`(640×640)로 비교했다. ROI20/10/10 → 실제 fixture ROI `(64,128)–(576,640)` → 640×360이다. 각 경로마다 새 default STREAM client를 만들고, 1500ms 간격으로 4회 실행했다.

| 입력 생성 방식 | SDK Task | 반환 개수 |
|---|---|---|
| 기존 crop + createScaledBitmap + 소유권 분리 | 4회 모두 SUCCESS | `[0,0,0,0]` |
| P1 전용 Bitmap 재사용 | 4회 모두 SUCCESS | `[0,0,0,0]` |

각 SDK 호출 전 legacy와의 **전체 픽셀 exact equality**를 검사했다. P1 target 동일성·반복 사용·정상 반납도 확인했다. 이 조건에서는 P1만의 zero-result 회귀가 재현되지 않았다. 이 이미지는 YOLO 양성 fixture이며 **ML Kit 양성 fixture로 인증된 것은 아니다**. 서로 다른 STREAM client의 trackingId·결과 결정성을 가정하지 않았고 nonzero 결과를 요구하는 테스트로 위장하지 않았다. 4회 비교로 모든 장면/장시간 인식 정확도가 동일하다고 결론내릴 수 없다.

공식 [STREAM_MODE API](https://developers.google.com/android/reference/com/google/mlkit/vision/objects/ObjectDetectorOptionsBase#STREAM_MODE)는 연속 입력 간격이 수백 ms처럼 길어지면 이전 객체 정보를 잃을 수 있다고 설명한다. [Android 가이드](https://developers.google.com/ml-kit/vision/object-detection/android)는 초기 탐지까지 기기에 따라 30개 이상의 프레임이 필요할 수 있다고 안내한다. 따라서 **1500ms sparse STREAM의 준비·추적 연속성은 기존부터의 원인 후보**다. 정확한 reset 임계값이나 이번 장면의 원인을 확정하는 근거는 아니다. 주기나 mode를 임의로 변경하지 않았다.

## 5. 확인한 원인과 미확정 항목

- **확인:** 기존 PID 24192의 관찰 구간은 SDK가 성공적으로 0개를 반환했으므로 그 결과들에 대해 그릴 BBox가 없었다. 단순히 Preview/ML Kit/box 설정이 OFF였다는 설명도 읽은 설정과 맞지 않는다.
- **확인:** 동일 fixture의 기존 입력 방식에서도 zero-result가 발생한다. Bitmap 재사용을 유일 원인으로 지목할 수 없다.
- **확인:** 1500ms 이상 늦은 양성 결과는 기존 stale 정책으로 표시되지 않는다. 실제 현재 화면이 이 경우인지는 세션을 연결한 로그가 필요하다.
- **미확정:** 실제 장면에서 SDK가 왜 0개인지. STREAM 초기 준비·긴 간격·장면/객체의 시각적 특성·ROI/stretch 영향 등은 아직 분리하지 못했다.
- **미확정:** 관찰한 과거 PID와 다른 현재 실행에서도 모두 0개인지, 혹은 양성 결과가 stale/View 조건에서 사라지는지.

실제 인식 실패의 단일 원인이 확정되지 않았으므로 알고리즘, 입력 형식, ROI, SDK mode, 주기, timeout, 기본값을 바꾸는 기능 수정은 하지 않았다. 확인된 부족함은 **현재 세션에서 검출과 표시의 실패 지점을 함께 추적할 수 없는 진단 공백**이며, 아래 선택적 로그로 보완했다.

## 6. 변경 파일·함수와 전후 차이

| 파일 / 함수 | 변경 |
|---|---|
| `mlkit/MlKitDiagnostics.kt` (신규) | session/frame/stage 공통 prefix, 획득 번호·처리 중 시간, 전체 skip 사유를 공유하는 5초 제한·생략 횟수. sink 예외 격리. |
| `MainActivity.onCreate()` | YOLO 설정 복구 **이후의 최종 적용 설정**으로 진단 opt-in 판단 및 CONFIG 기록. |
| `maybeRunMlKitDetection()` | skip 사유, lease 획득/반납, 입력 크기·ROI·generation·32개 샘플 brightness 범위/fingerprint, SDK 요청, mapped count/boxes, UI 제출/적용/생략 연결. |
| `MlKitObjectDetector.detect()` | P0와 독립적인 optional callback. PROCESS_REQUEST, Task SUCCESS/ERROR/CANCELLED, **sdkCount**, bbox·오류를 별도 기록. diagnostic callback 예외가 SDK 결과나 P0 fallback을 바꾸지 않도록 방어. |
| `BoundingBoxOverlay` | 기존 상태 결정·draw·expiry에 ML Kit 전용 진단만 추가. 수신 count/decision/age/표시 gate/View 상태, drawCalls/inViewCount/화면 bbox, clear 사유. 렌더링·만료 계산은 그대로. |
| `settings/AppSettings.kt`, `AppSettingsStore.kt` | `debug.enableMlKitDiagnostics=false` 추가. 기존/누락/invalid 키는 OFF. additive 설정이며 기존 schema/default 및 DetectionConfig는 유지. |
| `settings/SettingsActivity.kt` | 디버그 및 고급 설정 → Debug / Logging → **ML Kit Diagnostics** 스위치 및 설명. 저장·복원 연결. |
| `androidTest/.../mlkit/*`, `ui/BoundingBoxOverlayTest.kt` | SDK 0/1/3개, 반복 오류·취소 복구, 진단 예외 격리, 실제 SDK legacy/P1 비교, 표시 gate/stale/좌표 테스트. |
| `test/.../camera/RecognitionRoiTest.kt`, `settings/AppSettingsTest.kt`, 신규 `mlkit/MlKitDiagnosticsTest.kt` | 요청한 ROI 수치, opt-in·기존 설정 독립성, throttle/frame 연결 테스트. |
| `androidTest/.../detector/ModelSelectionDetectorTest.kt` | P0에서 추가된 inferenceEndedElapsedNs를 반사 호출에 빠뜨린 기존 테스트 harness만 보정. 생산 `parseOutput()`은 무변경. |

수정 전에는 SDK count Logcat과 별도 opt-in YOLO/debug Overlay 기록을 함께 대조해야 했다. 수정 후에는 ML Kit 전용 설정 하나로 같은 session/frame의 SDK 결과부터 draw/skip까지 추적한다. **기본 OFF에서는 logger/callback이 null이므로 샘플링·문자열·추가 Task listener·진단 기록이 실행되지 않는다.** 기존 SDK 기본 로그와 P0 FRAME/LATENCY는 유지한다. 사용자의 이미지를 자동 저장하거나 새 PNG 옵션을 켜지 않는다.

## 7. 진단 로그 읽는 방법

Logcat tag는 `MlKitDiagnostics`, prefix는 `[MLKIT_DIAG] session=… frame=… stage=…`다. bbox 목록은 최대 5개이며 실행 생략은 전체 사유 합쳐 5초에 최대 1행, 나머지 개수는 suppressedSkips로 기록한다. 정상 1500ms 실행에서는 요청 1회당 단계별 소수의 행만 발생한다. 진단 ON은 측정 오버헤드를 추가하므로 성능 비교 시 OFF를 유지한다.

| 관찰 로그 | 판단 |
|---|---|
| CONFIG enabled=false / SKIP DISABLED | 실제 적용 설정에서 실행 OFF. 설정 복구 여부도 확인 |
| SKIP INTERVAL, inFlight=false | 정상 간격 제한. 프레임마다 SDK를 실행하지 않는 기존 동작 |
| LEASE_ACQUIRED만 있고 PROCESS_REQUEST 없음 | launch 취소/실행 지연, 다음 UI/오류/반납 로그를 대조 |
| PROCESS_REQUEST 이후 TASK_COMPLETE 없음, IN_FLIGHT 시간 증가 | Task가 미완료일 가능성. 입력을 강제 recycle/재사용하지 말고 해당 세션 보존 |
| TASK_COMPLETE SUCCESS sdkCount=0 | SDK 정상 완료·검출 0개. MAPPED/UI count도 0이 정상이며 양성 BBox가 사라진 것은 아님 |
| TASK_COMPLETE ERROR/CANCELLED sdkCount=-1 | 0개 검출과 다름. 오류 detail과 이후 LEASE_RELEASED/다음 요청 확인 |
| sdkCount>0, mappedCount 동일 | wrapper·좌표 복원까지 전달됨. raw bbox와 mapped bbox 대조 |
| UI_SUBMIT reason=PREVIEW_OFF / UI_SKIP DESTROYING | UI 제출 경로에서 의도적으로 생략 |
| OVERLAY_UPDATE DISABLED | global overlay 또는 ML Kit boxes OFF |
| OVERLAY_UPDATE EXPIRED_AT_UPDATE | 촬영/수신부터 age가 timeout에 도달. SDK 완료 시간이 빨라도 UI 지연 포함 |
| OUT_OF_ORDER / BEFORE_RESET / INVALID_TIME | 역순·Activity/reset 이전 결과·시간 유효성 문제 |
| OVERLAY_DRAW reason=DRAWN, drawCalls>0, inViewCount>0 | View draw에서 그릴 명령과 화면 범위가 존재. display compositor/다른 View에 의한 가림까지 픽셀 표시를 보장한다는 뜻은 아님 |
| OVERLAY_CLEARED EXPIRED / CONFIG_CHANGED / RESET | 만료·표시 설정·Activity/View 초기화로 제거됨 |

`requestIndex`는 lease 획득 누적 번호다. 실제 SDK 호출은 같은 frame의 PROCESS_REQUEST로 별도 확인한다. `sampleBrightness=[0,0]`는 32개 지점이 검다는 단서이지 전체 이미지가 검다는 증명이 아니고, 같은 fingerprint는 고정 장면에서도 정상이다. 이미지 저장이나 전체 pixel 배열 할당은 없다.

## 8. 자동 테스트와 기기 보존

JDK21 / Gradle9.4.1 / 기존 offline cache로 일반 빌드와 별도 테스트 빌드를 수행했다. 실기기는 SM-S921N / API36 / ARM64 / owner user0이며 실제 SDK `object-detection:17.0.2`를 사용했다. 에뮬레이터 결과를 실제 기기 결과로 대신하지 않았다.

| 검증 | 최종 결과 |
|---|---|
| 전체 JVM `:app:testDebugUnitTest` | **167 tests, failures/errors/skipped 0**. 기존158 + 진단6 + 설정2 + ROI1. |
| 기존 Python 성능 로그 분석기 | **26 tests OK**. FRAME/LATENCY 분석 유지. |
| 실기기 `MlKitInputBufferTest` | **6 통과**. 기존48개 픽셀 조합, 경계, 독립 소유권, 투명 overwrite, 재진입/종료 수명. |
| 실기기 `MlKitObjectDetectorTest` | **10 통과**. SDK 0/1/3개, 오류/취소 후 반복 복구, 조기 recycle 차단, callback/P0 완료 이벤트, 진단 예외 격리. |
| 실기기 `MlKitRuntimeTest` | **2 통과**. 실제 SDK source recycle 후 반복 실행 + legacy/P1 1500ms 비교. 실제 비교 결과는 두 경로 모두4회SUCCESS·0개. |
| 실기기 `BoundingBoxOverlayTest` | **9 통과**. ML Kit 노란 픽셀, 표시 gate, full-frame FIT_CENTER, 빈 결과 clearing, stale 도착 거절/그리기 직전 재검사, timer 및 View 재생성. |
| 실기기 `RecognitionRoiInstrumentedTest` | **2 통과**. 실제 회전·ROI/원본 좌표 복원·가이드. |
| 실기기 `CameraPreviewRuntimeTest` | **1 통과**. Preview OFF→ON→OFF, 모드별 실제 프레임 수신 및 크기 일치. |
| 기존 실제 YOLO parser/NMS fixture | **1 통과**. 512/640 및 resize 모드의 NMS·ROI 복원. 누락된 테스트 인자만 보정한 뒤 실행. |
| 기존 실제 YOLO positive 반복 출력 | **1 통과**. 동일 영상의 raw output 및 최종 detection 유지. |
| 실기기 최종 묶음 | **OK (32 tests)**, 약24.98초. `build/p1-visibility/device-final-regression.txt`. |
| 일반 빌드 | suffix 없이 `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --offline` **BUILD SUCCESSFUL**. 최종 app/build 출력은 원래 applicationId. |
| diff 검사 | `git diff --check` 통과. 기존 line-ending 안내만 있으며 whitespace 오류 없음. |

첫 UI 포함 실행은 SIGKILL로 중단됐고, 분리한 카메라 실행은20초 동안 프레임을 받지 못했다. 이때 `mWakefulness=Dozing`, keyguard showing=true를 확인했다. 사용자가 잠금을 해제한 후 Preview/Overlay10개 및 최종32개가 모두 통과했다. 새 SDK 비교 테스트의 중간 JUnit 반환형 오류와 기존 YOLO 테스트의 인자 수 오류도 테스트 코드에서 고친 뒤 재실행했다. 초기 실패를 통과로 계산한 것이 아니다.

기존 앱을 교체하지 않도록 ignored 임시 init script `build/p1-visibility/isolated-tests.init.gradle`로 debug applicationIdSuffix를 **`.mlkitdiagnostic`**으로만 덧붙였다. main APK와 instrumentation target의 실제 manifest/applicationId를 검증한 후 `com.samin.objectdetection.mlkitdiagnostic` 및 `.test`에만 설치했다. 기존 앱의 설정/프리셋을 새 패키지에 덮어쓰지 않았다. 시험 종료 후 이번에 만든 두 패키지는 owner user0에서 제거했다.

기존 `com.samin.objectdetection`의 시험 전후 applied settings XML은 바이트 동일하며 SHA-256은 `4bee24aba9d748e8a3c1d74e1c43102695063eb09c8bd2a1b13a61bd583a6365`다. 원래 codePath/versionCode/firstInstallTime/lastUpdateTime도 동일했고 lastUpdateTime은2026-10-22 17:48:38로 보존됐다. 확인 결과는 `build/p1-visibility/user-app-preservation.json`에 남겼다. 사용자 이미지 자동 캡처, 기존 로그 초기화/삭제, 기존 앱 교체 설치, 커밋·푸시는 하지 않았다.

재현할 때는 일반 빌드를 기존 앱에 바로 설치하는 방식과 **별도 패키지 테스트**를 구분해야 한다. 이번에 실제 사용한 빌드 명령은 다음과 같다.

```powershell
$env:JAVA_HOME = 'C:/Users/SAMIN/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2'
./gradlew.bat -I build/p1-visibility/isolated-tests.init.gradle :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --offline
./.venv/Scripts/python.exe -B -m unittest discover -s tools -p 'test_*.py'
```

이 init script는 제품 설정/추적 파일을 바꾸지 않는 로컬 시험용이다. 실제 설치 대상과 active user를 확인하지 않고 일반 앱을 자동 교체하는 재현 명령을 제공하지 않는다.

## 9. 실기기 확인 절차

1. 이번 소스의 일반 앱 빌드를 사용한다. 이번 시험은 `.mlkitdiagnostic` 별도 패키지였으며 기존 설치 앱을 교체하지 않았다. 일반 빌드 설치는 기존 자료 보존·동일 서명 원칙을 따른다.
2. 기존 조건 512×512 / 2 Threads / ROI 상단20%·좌우10% / ML Kit 1500ms / stale 1500ms / confidence .2·NMS .45·letterbox를 유지한다. 전체 Overlay ON, Show ML Kit Boxes ON, Preview ON, ML Kit Detection ON을 확인한다. Show YOLO Boxes는 비교하려는 기존 값 유지.
3. **상세 설정 → 디버그 및 고급 설정 → ML Kit Diagnostics ON → 적용 및 저장**. SIMPLE이면 충분하며 FULL이나 Detector Diagnostics/Debug Detection Logging/PNG 저장은 필요 없다.
4. 화면을 켜고 잠금을 해제하여 앱을 전경에 둔다. 고정된 선명한 물체를 ROI 안에 두고 60–90초 이상 관찰하되, 이 시간/호출 수가 nonzero를 보장하지는 않는다. 초기 준비와 지속적 0개를 나눠 보기 위해 CONFIG부터 requestIndex를 함께 보존한다.
5. 아래처럼 해당 tag의 텍스트만 수집한다. 기존 로그를 지우는 `logcat -c`나 앱 데이터 초기화는 필요 없다.

```powershell
$mlkitAdb = 'C:/Users/SAMIN/AppData/Local/Android/Sdk/platform-tools/adb.exe'
& $mlkitAdb -s YOUR_DEVICE_SERIAL logcat -v time -s MlKitDiagnostics:I MlKitObjectDetector:D
```

6. session/frame별 PROCESS_REQUEST → TASK_COMPLETE outcome/sdkCount → MAPPED → UI_SUBMIT/UI_APPLY → OVERLAY_UPDATE → OVERLAY_DRAW/CLEARED → LEASE_RELEASED를 대조한다. 입력 generation/brightness/fingerprint와 원본/ROI 크기도 확인한다.
7. sdkCount>0인데 표시되지 않으면 그 frame의 decision/age/가시성/화면 bbox를 우선 조사한다. sdkCount=0이면 화면 timeout을 바꾸는 대신 입력 의미와 SDK의 준비·장면 조건을 조사한다. 이때만 명시적 별도 디버그 실험을 설계하며 자동 이미지 저장을 추가하지 않는다.
8. 앱 종료/재실행, 설정 재적용, Preview ON/OFF 전환 후 새 session 및 다음 요청/반납을 확인한다. 검증 후 ML Kit Diagnostics를 OFF로 되돌리고 성능 시험은 진단 OFF로 실시한다.

## 10. 남은 문제와 후속 작업

- 실제 사용자 장면의 지속적 zero-result 원인은 아직 확정하지 못했다. 동일 fixture 비교와 현재 세션의 live camera 재현은 서로 다른 근거다.
- 필요하면 별도 시험에서 **legacy/P1 × dense/sparse STREAM**을 분리하여 초기 누적 프레임과 간격 영향을 측정한다. SINGLE_IMAGE_MODE도 참조 실험 후보지만 이번 제품 설정/코드에는 도입하지 않았다.
- 특정 객체가 ML Kit 양성으로 검출되는 fixture를 별도로 확보하면 SDK 검출 정확도 회귀를 더 강하게 검사할 수 있다. 현재 known.png의 YOLO 양성을 ML Kit 양성으로 대신하지 않는다.
- Task가 영원히 종료되지 않는 경우 NonCancellable/lease는 입력을 계속 보유한다. 완료 확인 없이 timeout으로 gate를 풀거나 Bitmap을 덮어쓰는 수정은 하지 않는다.
- 이번 작업은 성능 최적화가 아니다. 기존 P0 이후 기준 FPS/추론/P95 자료를 수정하지 않았고, 실기기 3분 성능 개선이나 모든 장면의 정확도 보존을 새로 실측했다고 주장하지 않는다.
