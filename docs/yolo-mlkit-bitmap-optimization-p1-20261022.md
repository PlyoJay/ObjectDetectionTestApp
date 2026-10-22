# P1-1: ML Kit 입력 Bitmap 생성 및 복사 최적화 (2026-10-22)

## 1. 범위와 비교 기준

분석 기준은 현재 checkout의 `8fdc175` 및 작업 시작 시 소스다. 원격 fetch/pull, 커밋, 푸시는 하지 않았다. 기존 미추적 문서 `yolo-output-parsing-regression-analysis-20261022.md`, `yolo-p0-aba-test-plan-20261022.md`와 기존 성능 로그는 수정하지 않았다.

사용자가 제공한 P0 이후 B의 512×512 / 2 Threads / 3분 기준값은 다음과 같다. 이번 작업에서 다시 측정한 값이 아니며 개선 목표/보장이 아니다.

| 지표 | 제공된 기준 |
|---|---:|
| 평균 FPS | 3.716 |
| 평균 inference / P95 | 235.0ms / 279ms |
| 평균 outputParsing | 3.30ms |
| 평균 totalProcessing / P95 | 258.8ms / 306ms |

변경은 ML Kit 입력 준비와 그 입력의 비동기 수명 보장에 한정한다. 모델·클래스 매핑·512 설정·2 Threads·XNNPACK·백엔드·ROI 비율/좌표·letterbox·confidence·NMS·`parseOutput()`·인식 통합·경고/TTS/Beep/진동·1500ms 주기·기본값·P0 FRAME/LATENCY 계측은 변경하지 않았다. 저장소의 기본 모델/스레드 값을 기준 실험의 512/2로 새로 덮어쓰지 않았다.

**실기기 성능 개선 효과는 아직 검증되지 않았다.**

## 2. 기존 입력 처리 구조와 소스 근거

파일 경로는 `app/src/main/java/com/samin/objectdetection/` 기준이다. 변경 전 줄 번호는 `git show 8fdc175:<path>` 기준이다.

1. `camera/CameraController.kt:103–107`: CameraX ImageAnalysis가 RGBA_8888 및 KEEP_ONLY_LATEST를 사용한다. 단일 analyzer executor·backpressure 및 간격 검사로 전달을 제어하고 종료/간격 검사를 통과한 프레임만 변환한다.
2. `camera/ImageProxyExt.kt:27–42`: CameraX `ImageProxy.toBitmap()`으로 프레임 Bitmap을 얻는다. 회전이 있으면 `Matrix.postRotate()` / filter=true로 upright Bitmap을 만들고 회전 전 Bitmap을 recycle한다. 회전 0이면 변환 Bitmap을 그대로 전달한다.
3. `CameraController.kt:142–174`: 변환된 upright Bitmap을 listener → `MainActivity.processBitmap()`에 동기 전달한다. 반환 후 finally에서 이 Bitmap을 recycle하고 ImageProxy를 close한다. P0 `finishAnalyzerFrame()`이 close 결과와 지연을 기록한다.
4. `MainActivity.kt:572–577`: **ML Kit 준비가 YOLO pipeline보다 먼저 실행**된다. 두 detector는 같은 upright 전체 프레임에서 동일한 `FrameRoi.calculate()` 규칙을 사용한다. ML Kit가 YOLO의 이미 만들어진 ROI Bitmap을 받는 구조는 아니다.
5. `camera/RecognitionRoi.kt:36–46`: edge ROI는 회전 후 전체 프레임 좌표이며 center-square보다 우선한다. 둘을 연속 crop하지 않는다. 비율의 정수 변환, `sourceX/sourceY`의 좌표 복원/클램프를 양쪽에서 공유한다.
6. 기존 `MainActivity.maybeRunMlKitDetection():1347–1422`: enable/1500ms/AtomicBoolean 검사 → ROI Bitmap 생성 → `createScaledBitmap(..., 640, 360, true)` → 원본 alias라면 별도 ARGB copy → 임시 crop recycle → `captureWork.launch`.
7. `mlkit/MlKitObjectDetector.kt`: `InputImage.fromBitmap(bitmap, 0)` → STREAM_MODE / multiple objects의 `detector.process()` → P0 Task completion listener → `task.await()` → bbox Rect 복사와 trackingId 반환. 이미 회전된 입력이므로 InputImage 회전은 0이다.
8. 기존 `MainActivity.kt:1390–1413`: ML Kit bbox를 640×360에서 ROI의 원본 프레임 좌표로 복원하고 기존 preview 조건에 따라 overlay callback을 제출한다. 기존 finally에서 ML Kit 소유 입력 Bitmap을 recycle하고 처리 gate를 해제했다.
9. `pipeline/DetectionPipeline.kt:59–67, 203–204`: YOLO는 별도로 partial ROI Bitmap을 만들고 full-frame이면 원본을 사용한다. 동기 detect 후 자체 crop만 recycle한다.
10. `detector/VisionStyleYoloDetector.kt:155–156, 197–219, 337–349`: YOLO의 모델 크기 입력 Bitmap/Canvas/pixels/tensor buffer는 이미 재사용한다. ROI → 모델 입력 letterbox/stretch → RGB tensor 변환 → 추론 → 기존 출력 파싱/NMS를 수행한다. 이 경로는 수정하지 않았다.

## 3. 조사한 중복 연산·할당

| 조사 항목 | 실제 확인 및 판단 |
|---|---|
| 이미 생성된 ROI를 다시 crop하는가 | 아니다. ML Kit와 YOLO가 각각 전체 upright frame에서 독립적으로 crop한다. 같은 ROI 픽셀을 두 번 준비하는 비용은 있지만 YOLO ROI를 공유하면 현재 호출 순서와 수명까지 바뀌므로 이번에 공유하지 않았다. |
| 같은 이미지의 불필요한 복사 | ML Kit의 full-frame crop은 불필요하다. Android `createBitmap(source, fullRect)`는 mutable source이면 새 Bitmap을 만들 수 있다. 640×360 원본의 alias 방지 copy 자체는 비동기 소유권 확보에 필요했으며 원본 직접 전달로 대체해서는 안 된다. |
| 매 ML Kit 실행마다 Bitmap 생성 | 기존은 보통 crop 1개 + 640×360 scaled 1개를 생성한다. alias·mutable·입력 크기에 따라 실제 개수는 다르므로 항상 2개라고 가정하지 않는다. |
| 기존 재사용 가능 버퍼 | 기존 ML Kit에는 없다. YOLO의 입력 버퍼는 다음 YOLO 프레임이 덮어쓰므로 비동기 ML Kit에 공유할 수 없다. |
| 중복 resize | ML Kit는 보통 640×360 resize 1회다. YOLO의 별도 model resize는 다른 detector 입력이며 중복 resize라고 제거할 수 없다. ML Kit의 비율 변경 stretch도 그대로 유지했다. |
| 이미지 형식 변환 | 현재 CameraX RGBA → Bitmap → InputImage Bitmap 경로다. 앱이 ML Kit 직전에 JPEG/NV21/YUV로 추가 변환하는 코드는 없다. SDK 내부 변환·native 할당 여부/비용은 이번 소스만으로 확정할 수 없다. fromMediaImage로 바꾸면 회전·ROI·크기 및 ImageProxy 보유 시간이 달라져 제외했다. |
| recycle / GC | 기존 crop 및 scaled Bitmap의 반복 생성/recycle이 native pixel storage와 wrapper 할당을 유발한다. 이번에 target은 재사용하지만 partial ROI crop, InputImage, Task, 결과 객체 등 할당은 남는다. GC 감소량은 실측하지 않았다. |
| Preview OFF UI용 이미지 | `CameraController.kt:180–190`은 Preview use case 자체를 만들지 않는다. MainActivity의 YOLO/ML Kit overlay 제출은 previewEnabled로 이미 조건부다. 일반 UI용 Bitmap 생성은 발견하지 못했다. `MainActivity.buildEvaluationSnapshot():899`의 copy는 명시적 캡처 요청용이며, debug recorder의 copy도 비동기 입력 PNG 기록용이므로 유지했다. |

Android의 source alias 조건/Matrix 구현은 설치된 SDK `sources/android-36.1/android/graphics/Bitmap.java`의 createBitmap/createScaledBitmap에서도 확인했다. API 계약은 [Bitmap 공식 문서](https://developer.android.com/reference/android/graphics/Bitmap), 입력 API는 [InputImage 공식 문서](https://developers.google.com/android/reference/com/google/mlkit/vision/common/InputImage)를 참조한다. SDK 내부의 zero-copy를 보장한다는 의미가 아니다.

## 4. 최적화 전후 흐름과 실제 제거 범위

### 변경 전

```text
ImageProxy → RGBA Bitmap → upright 전체 프레임
  ├─ ML Kit: crop Bitmap → 640×360 scaled Bitmap / alias 방지 copy
  │           → 임시 crop recycle → 비동기 process/await → 입력 recycle
  └─ YOLO: 자체 ROI crop → 기존 letterbox/tensor/추론/parse/NMS → crop recycle
전체 프레임 recycle → ImageProxy close
```

### 최종 변경 후

```text
ImageProxy → RGBA Bitmap → upright 전체 프레임
  ├─ ML Kit 입력 lease 획득
  │    full-frame: 원본을 준비 단계에서만 읽음 (full-frame crop 생략)
  │    partial ROI: 기존 방식 crop → 기존 float Matrix / Paint sampling
  │    → 전용 재사용 640×360 Bitmap에 resize/copy → 임시 crop recycle
  │    → 비동기 process → 실제 Task terminal까지 await
  │    → coroutine Job 완료 callback에서 lease 반납 → 다음 실행에 재사용
  └─ YOLO: 기존 경로 그대로
전체 프레임 recycle → ImageProxy close
종료 시: analyzer drain → capture children join → 전용 버퍼 close/recycle
```

재사용 target은 640×360 ARGB_8888 한 개다. 픽셀 저장소는 921,600 bytes(약 0.879MiB)를 Activity 동안 유지한다. 첫 획득 시 한 번 만들고, 이후 정상 반복에서는 같은 Bitmap/Canvas/Matrix/Rect/Paint를 사용한다. lease와 completion callback 등 작은 객체의 할당까지 없앤 것은 아니다.

| 조건 | 제거한 연산 | 남긴 연산 |
|---|---|---|
| partial ROI, resize 필요 | 실행마다 새 640×360 scaled Bitmap 생성 및 완료 후 recycle | 실제 ROI crop 생성/복사/recycle, resize 1회, target clear |
| full-frame, resize 필요 | mutable 전체 프레임 crop 생성/복사/recycle + scaled target 반복 생성/recycle | 원본에서 재사용 target으로 resize 1회, target clear |
| full-frame 640×360 | 기존 mutable crop 또는 alias 방지용 독립 Bitmap 반복 생성/recycle | 전용 target으로 픽셀 복사 1회, target clear |
| partial ROI가 정확히 640×360 | target은 최초 한 번 생성하고 재사용하지만 crop 생성은 남음 | crop에서 target으로 소유권 분리 복사. 이 특수 조건에서 복사량 감소를 주장하지 않음 |

**중간 partial ROI crop 제거는 채택하지 않았다.** 첫 직접 srcRect→640×360 구현은 실제 Android 픽셀 비교에서 ROI 외부 색상이 경계에 섞였고 sampling 차이도 발생했다. crop을 보존하고 `createScaledBitmap`의 float Matrix 및 기본 `Paint()` 설정을 맞춘 뒤 모든 비교를 통과했다. 재사용 target은 매번 transparent clear하여 이전 입력의 alpha/경계 픽셀이 남지 않게 한다. clear 비용이 있으므로 할당 감소가 곧 시간 감소를 뜻하지 않는다.

## 5. 변경 파일과 함수

| 파일 | 변경 |
|---|---|
| `MainActivity.kt` | 전용 `mlKitInputBuffer` 보유. `maybeRunMlKitDetection()`에서 기존 gate/주기 유지 후 acquire하고 InputImage에 lease Bitmap 전달. preparation/launch 오류 시 gate 복구. Job completion에서 lease 반납. `onDestroy()`에서 기존 analyzer/capture drain 후 buffer close 추가. bbox/overlay/통합 정책은 유지. |
| `mlkit/MlKitInputBuffer.kt` (신규) | synchronized acquire/close, 단일 lease, 동일 target 재사용, full-frame crop 생략, partial crop/Matrix resize, idempotent lease close, 사용 중 close의 recycle 지연. |
| `mlkit/MlKitObjectDetector.kt` | SDK Task await만 `withContext(NonCancellable)`로 감싸 수명을 보장. 기존 InputImage trace, Task completion LATENCY listener, 오류 처리 및 결과 복사 유지. 테스트용 internal detector 주입 생성자 추가; 기본 생성자의 SDK 옵션 동일. |
| `androidTest/.../mlkit/MlKitInputBufferTest.kt` (신규) | 픽셀·회전·ROI·소유권·동시성·재사용·종료 검증. |
| `androidTest/.../mlkit/MlKitObjectDetectorTest.kt` (신규) | 제어 가능한 SDK Task로 성공/오류/취소/실행 전 취소/완료 지연/P0 callback 검증. |
| `androidTest/.../mlkit/MlKitRuntimeTest.kt` (신규) | 실제 SDK에서 source recycle 후 2회 실행, Task SUCCESS, 동일 target 재사용 및 최종 recycle 확인. |

`DetectionPipeline.kt`, `CameraController.kt`, ROI 유틸리티, YOLO detector/parser, 설정, 경고 및 metrics 구현은 수정하지 않았다.

## 6. Bitmap 생명주기와 비동기 안전성

1. **CameraX와 독립:** acquire의 crop/resize/copy는 analyzer 스레드에서 동기 완료한다. coroutine은 camera 원본이 아닌 전용 target만 참조한다. CameraX recycle/YOLO 처리 이후에도 ML Kit 입력은 별도 저장소에 남는다.
2. **완료 전 덮어쓰기 차단:** 기존 AtomicBoolean gate에 더해 buffer 자체가 leased 상태의 acquire를 거부한다. lease 해제 전 target clear/draw를 할 수 없다.
3. **실제 Task 완료 보장:** [kotlinx.coroutines Task.await 공식 계약](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-play-services/kotlinx.coroutines.tasks/await.html)에 따르면 coroutine cancellation은 SDK Task 완료 전 대기를 끝낼 수 있다. 기존 catch(Exception) 후 finally recycle은 이 경우 안전하지 않았다. 이제 NonCancellable 구간에서 SDK Task 성공/실패/취소의 terminal state까지 대기한다. 스레드를 blocking하지 않는다.
4. **반납 시점:** MainActivity의 Job completion callback에서 input.close 후 gate를 해제한다. launch 본문 진입 전에 취소되어도 이 callback이 호출되므로 prepared input이 고립되지 않는다. 동기 acquire/launch 실패에서도 gate/lease를 복구한다.
5. **실패/SDK 취소:** process의 동기 예외와 terminal failure/cancel은 기존 empty-result 동작을 유지한다. completion 또는 fallback LATENCY도 유지한다. Job 완료 시 lease를 반납하며 partial crop은 입력 준비의 finally에서 recycle한다.
6. **종료/회전/설정 적용:** 기존 analyzer 정지 → capture children join 흐름을 유지한다. 그 뒤 buffer close가 Canvas 연결을 끊고 target을 recycle한다. buffer 자체도 사용 중 close이면 반납까지 recycle을 연기한다. 새 Activity는 새 buffer를 보유한다. ROI와 0/90/180/270 회전은 기존 유틸리티의 upright 좌표를 그대로 사용한다.
7. **장애 시 보수적 보유:** SDK Task가 영원히 완료되지 않으면 입력을 안전하게 회수할 근거가 없으므로 lease와 종료 대기를 유지한다. timeout으로 강제 recycle하거나 다음 프레임으로 덮어쓰지 않는다. 정상 종료에서도 원래 SDK 완료를 기다렸지만 이제 coroutine 취소도 조기 해제 수단이 될 수 없다.

## 7. 테스트 실행 결과

실행 환경: Windows PowerShell, 기존 Gradle 9.4.1 캐시, JDK 21, debug build. Android 계측은 **Pixel_7 AVD / API 37 / x86_64의 read-only·no-snapshot 임시 세션**에서만 실행했다. 연결된 SM-S921N 실기기는 목록 조회만 했으며 앱 설치/설정/자료를 변경하지 않았다. 사용한 임시 에뮬레이터는 시험 후 종료했다.

| 실행 | 결과 / 검증 범위 |
|---|---|
| `:app:testDebugUnitTest` | **158 tests, failures 0, errors 0, skipped 0**. 기존 ROI/좌표, 설정, warning, FRAME formatter, LATENCY recorder 등 전체 로컬 단위 테스트. |
| `:app:assembleDebug :app:assembleDebugAndroidTest --offline` | **BUILD SUCCESSFUL**. 최종 production과 신규 instrumentation 3개 파일 모두 컴파일. |
| `python -B -m unittest discover -s tools -p 'test_*.py'` | **26 tests OK**. 기존 FRAME/LATENCY 성능 분석 도구 회귀. |
| `MlKitInputBufferTest` | **6 tests OK**. 전체 픽셀 exact equality: 원본 97×61 / 1281×721 / 640×360 × 4 회전 × 4 ROI = **48 조합**. 고대비 ROI 경계, 원본 변경/recycle 독립성, 동일 target 재사용, 투명 픽셀 overwrite, concurrent acquire 차단, close 지연/idempotence, invalid ROI 후 복구. |
| `MlKitObjectDetectorTest` | **6 tests OK**. 제어 가능한 SDK Task로 입력 Bitmap 참조·640×360·rotation=0, bbox 복사/trackingId, SUCCESS/ERROR/CANCELLED 및 fallback LATENCY, coroutine 취소 중 lease 보유, launch 본문 진입 전 취소 해제. |
| `RecognitionRoiInstrumentedTest` | **2 tests 통과**. 실제 quarter-turn Bitmap 회전/YOLO 단일 ROI/원본 좌표 복원 및 overlay guide 렌더링. 아래 통합 실행이 중단되기 전에 각 status=0 확인. |
| `CameraPreviewRuntimeTest` | 2GB AVD에서 실행 중 **프로세스 종료**. `dumpsys activity exit-info`에서 `reason=3 (LOW_MEMORY)`, RSS 약 251MB 확인. Preview OFF→ON→OFF 통합 검증은 **미완료**. |
| 실제 YOLO parser/NMS/ROI fixture 및 반복 raw-output regression | 별도 실행 첫 테스트 중 2GB AVD에서 **LOW_MEMORY** 종료(RSS 약 191MB). actual parser/NMS 및 raw-output 비교 완료로 보고하지 않는다. 해당 소스의 무변경과 로컬 관련 테스트 성공은 별도 근거다. |
| `MlKitRuntimeTest` 및 통합 재시도 | **컴파일 성공, 실행 미완료**. RAM 3GB read-only AVD 재시도에서 APK 교체가 `INSTALL_FAILED_INSUFFICIENT_STORAGE`로 실패하여 실제 SDK smoke까지 도달하지 못했다. 앱 코드/서명/설정을 바꿔 우회하지 않았으며, 실기기에 설치하지 않았다. |
| `git diff --check` | 통과. Git의 기존 LF→CRLF 안내 외 whitespace 오류 없음. |

초기 직접 draw 시도는 픽셀 회귀 2개가 실패하여 폐기했다. partial crop을 복구한 중간 시도에도 Paint 기본값 차이가 남아 exact comparison이 실패했다. **최종 Matrix + 기본 Paint + target clear 구현에서 신규 12개 모두 통과**했으며 비교 오차 허용치를 넓혀 통과시킨 것이 아니다. 증거는 로컬 ignored `build/p1-mlkit-instrumentation.txt`의 `OK (12 tests)`다. 초기 통합 중단 결과는 `build/p1-regression-instrumentation.txt`, `build/p1-yolo-instrumentation.txt`에 남겼다.

주요 재현 명령(저장소 root PowerShell):

```powershell
$env:JAVA_HOME = 'C:/Users/SAMIN/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2'
./gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --offline
./.venv/Scripts/python.exe -B -m unittest discover -s tools -p 'test_*.py'
# APK가 설치된 별도 테스트 기기의 실제 serial을 명시한다.
$p1Adb = 'C:/Users/SAMIN/AppData/Local/Android/Sdk/platform-tools/adb.exe'
& $p1Adb -s TEST_DEVICE_SERIAL shell am instrument -w -r `
    -e class 'com.samin.objectdetection.mlkit.MlKitInputBufferTest,com.samin.objectdetection.mlkit.MlKitObjectDetectorTest,com.samin.objectdetection.mlkit.MlKitRuntimeTest' `
    com.samin.objectdetection.test/androidx.test.runner.AndroidJUnitRunner
```

따라서 Bitmap 변환/비동기 소유권의 결정적 회귀 검증은 통과했지만, **실제 ML Kit SDK 완료·Preview ON/OFF·실제 YOLO/NMS 통합 테스트 및 3분 FRAME/LATENCY 수집은 기기에서 후속 검증이 필요하다.** 단위 테스트 성공만으로 이 항목들의 실기기 정상 동작/성능 개선/누수 부재를 실측했다고 주장하지 않는다.

## 8. 남아 있는 위험과 제약

- 테스트의 픽셀 동일성 범위는 현재 CameraX가 공급하는 software ARGB_8888 / sRGB Bitmap이다. helper를 임의의 RGB_565, F16, HDR/gainmap, hardware Bitmap 변환 API로 일반화하지 않았다.
- partial ROI Bitmap 할당과 CameraX 변환/회전 할당은 남는다. steady-state 모든 프레임의 무할당 구현이 아니다.
- 전용 target 약 0.879MiB를 유지하며 매 준비 시 clear한다. GC/CPU 경합/실제 inference overlap/thermal 영향은 실기기 비교가 필요하다.
- 픽셀 및 callback 검증은 장면별 인식 정확도 실험을 대신하지 않는다. 실제 탐지 수/좌표/추적 결과, 경고 출력, 화면 회전과 Activity 재생성은 기기에서도 확인해야 한다.
- FRAME/MLKit LATENCY 생성 코드와 필드는 유지했다. 단위 테스트와 SDK wrapper callback 테스트의 성공을 실제 3분 카메라 성능 로그 수집 성공으로 혼동하지 않는다.

## 9. 실기기 검증 방법

1. 기존 P0 이후 빌드와 P1 빌드를 동일 서명·설정으로 준비하고 기존 자료를 보존한다. 이번 작업에서는 연결 실기기에 설치하거나 설정을 변경하지 않는다. 기존 [A/B/A 절차](yolo-p0-aba-test-plan-20261022.md)의 데이터 보존·동일 조건·냉각 원칙을 따른다.
2. 512×512 / 2 Threads / 동일 XNNPACK·backend·ROI·letterbox·confidence/NMS / ML Kit ON·1500ms / 동일 경고 설정을 확인한다. ROI는 실제 적용 snapshot을 비교하고 preset 버튼으로 덮어쓰지 않는다.
3. 동일 고정 장면/조도/방향/해상도, 같은 충전·절전·밝기·시작 온도, 동일 예열 후 3분 A(P0 이후)→B(P1)→A 순서로 반복한다. Preview OFF를 기준 비교로 사용하고 Preview ON은 별도 동일 조건 비교로 확인한다.
4. 기능 확인: ML Kit callback 반복, ML Kit bbox의 원본 좌표 복원, 빠른 기기 회전/설정 재적용/백그라운드 복귀, ML Kit ON/OFF, Preview ON/OFF, 오류 이후 다음 입력, TTS/Beep/진동과 YOLO 경고 일치. SDK 작업 중 화면 재생성에서도 recycled bitmap 접근/오염이 없어야 한다.
5. 기존 FRAME에서 FPS, inference 평균/P95, outputParsing 평균, totalProcessing 평균/P95와 `mlKitPrepareNs`, `mlKitInFlight`, cameraToBitmap/rotation/resize를 비교한다. ML Kit 실행 프레임과 건너뛴 프레임을 구분한다. 1500ms는 최소 dispatch 간격이며 느린 처리 중 중첩 실행을 의미하지 않는다.
6. LATENCY에서 MLKit SUCCESS/ERROR/CANCELLED, TASK_COMPLETION_LISTENER/fallback, dispatch→request→completion, analyzer close 이벤트의 정상 흐름과 누락/drop을 확인한다. 프레임 통계와 LATENCY 표본을 합쳐 FPS를 계산하지 않는다.
7. 메모리/GC는 별도의 짧은 profiling 실행에서 Java/native allocation, Bitmap 개수/size, retained target, GC 횟수/정지 시간을 확인한다. profiler를 붙인 결과를 무계측 3분 성능 비교에 섞지 않는다. 종료/반복 재생성 후 retained Activity/Bitmap이 누적되지 않는지도 확인한다.
8. 장시간(예: 10분) 반복에서 발열·CPU 정책 상한·Task 오류·메모리 추세를 함께 관찰한다. 개선은 변동폭과 온도/CPU 조건을 포함해 평가하며 기준값보다 반드시 빨라야 한다고 판정하지 않는다.

## 10. 후속 YOLO ROI 최적화 후보 (이번 작업에서 미구현)

- YOLO partial ROI를 먼저 전체 복사하지 않고 letterbox destination으로 전달할 수 있는지 조사하되, 이번 ML Kit 직접 draw 실패에서 확인한 **경계 clamp·sampling·Paint·float Matrix 차이**를 반드시 픽셀/tensor 회귀로 검증한다.
- YOLO ROI 전용 재사용 Bitmap은 고정 ROI 크기에서 할당을 줄일 수 있으나 copy 자체와 retained memory, 크기/회전 변경 시 재할당·recycle 정책을 따로 평가한다.
- YOLO/ML Kit의 ROI 공유는 producer 호출 순서, 동기 YOLO와 비동기 ML Kit의 소유권, 겹침 실행, debug/capture 보유까지 설계해야 한다. 이번 P1-1의 전용 target에 YOLO를 결합하지 않는다.
- `parseOutput()` 및 출력 파싱 최적화는 별도 작업이며 이번 변경과 섞지 않는다.
