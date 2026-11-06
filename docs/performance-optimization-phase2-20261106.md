# CameraX 회전·ROI Bitmap 2차 성능 최적화 결과

작성일: 2026-11-06 (Asia/Seoul)  
기준 HEAD: `6f034b69706f28664f5d8c82f1c926d5b0573ff4`  
상태: 구현·자동 검증 완료. Git 커밋·푸시 미수행.

CameraX 회전 출력과 YOLO 부분 ROI crop에 **서로 독립적인 재사용 Bitmap/Canvas 버퍼**를 적용했다. 회전용 Matrix·Rect·Paint도 재사용한다. 모델·입력 해상도·스레드·추론 주기·CameraX 구성·1차 최적화는 유지했다. 전체 단위 테스트 210개와 기기 회귀 테스트 33개가 최종 통과했다. 기기 테스트는 최초 32개 통과 후, 잠금 상태 때문에 실패한 CameraX 1개를 잠금 해제 후 재실행해 통과한 결과다.

사용자가 제공한 기존 로그는 587프레임 모두 rotation=90°, 평균 rotation 약 6.8ms, camera bitmap 약 7.7ms, ROI crop 약 1.4ms, inference 약 262.9ms, EDGE_CROP 활성화였다. 이는 이번 변경 전후 A/B 측정값이 아니다. rotation은 camera bitmap 시간의 일부이므로 두 시간을 더하지 않는다. **장시간 실기기 성능 테스트, FPS 향상률·발열·GC 개선 측정은 수행하지 않았다.**

## 1. 수정 파일과 핵심 변경

| 파일 | 위치·메서드 | 변경 내용 |
| --- | --- | --- |
| [FrameBitmapBuffer.kt:18][buffer] — 신규 | `rotate`, `crop`, `render`, `close` | 소유자당 출력 Bitmap 1개 및 Canvas·Matrix·Rect/RectF·Paint 재사용 |
| [ImageProxyExt.kt:12][conversion] | `toBitmapSafe()` | 기존 CameraX `toBitmap()` 유지. 회전만 재사용 버퍼로 수행. 변환 원본의 성공·오류 경로 회수 |
| [CameraController.kt:68][camera-frame] | `Listener.onFrame`, analyzer | Bitmap을 동기 콜백 동안 빌려주는 계약 명시. 버퍼 소유 Bitmap은 프레임마다 recycle하지 않음 |
| [CameraController.kt:223][camera-stop] | `stopAsync()` | analyzer drain 성공 후 회전 버퍼를 해제하고 stopped future 완료 |
| [DetectionPipeline.kt:39][pipeline-process] | `process()` | 동일 ROI 계산을 유지하고 실제 crop을 재사용 storage에 그리기. process/close 직렬화 |
| [DetectionPipeline.kt:207][pipeline-close] | `close()` — 신규 | 자신이 소유한 crop storage만 해제. 원본 Bitmap·detector는 해제하지 않음 |
| [MainActivity.kt:606][main-settings] / [:1725][main-destroy] | 설정 재시작·Activity 종료 | analyzer drain 후 pipeline crop 버퍼 종료를 기존 자원 종료 순서에 연결 |

기존 `Bitmap.rotate(Float)`는 변경하지 않았다. 다른 호출부의 기존 동작과 플랫폼 기준 픽셀 비교에 사용한다. CameraX 출력 형식 RGBA_8888, KEEP_ONLY_LATEST, 단일 analyzer, Preview 설정, ImageProxy.close의 finally 경로도 그대로다.

## 2. 회전·ROI 픽셀과 좌표를 보존한 방식

로컬 Android SDK의 `android.graphics.Bitmap.createBitmap()` 소스를 확인해 같은 순서의 Canvas 연산을 사용했다.

```text
sourceRect → 원본에서 선택한 정수 픽셀 영역
destination → (0, 0, cropWidth, cropHeight), ROI 자체에는 resize 없음
matrix → 기존 postRotate(0/90/180/270)와 동일
bounds → matrix.mapRect(destination)
Canvas.translate(-bounds.left, -bounds.top)
Canvas.concat(matrix)
Canvas.drawBitmap(source, sourceRect, destination, paint)
```

- 회전은 기존과 같은 filtered Paint를 사용한다. 회전 없는 ROI crop은 플랫폼 기준처럼 paint=null로 수행한다.
- 회전 결과의 출력 폭·높이, density, alpha, colorSpace와 premultiplication 상태를 입력에 맞춘다. 같은 저장소를 다시 쓸 때 투명하게 지워 이전 프레임의 픽셀이 남거나 alpha blending이 누적되지 않게 한다.
- crop을 Letterbox와 합치지 않았다. 실제 ROI 크기의 별도 Bitmap을 유지하므로 Letterbox 보간이 ROI 밖 픽셀을 읽는 경로를 도입하지 않는다.
- 0° 회전과 full-frame ROI는 기존처럼 입력을 그대로 사용한다. 불필요한 복사본을 만들지 않는다.
- 재사용 대상은 실제 CameraX 경로의 software ARGB_8888이다. 다른 config, colorSpace가 없는 입력, HDR gainmap 입력은 플랫폼 `Bitmap.createBitmap()` 경로로 처리하고 그 출력도 버퍼가 소유·회수한다. 해당 경로의 프레임당 Bitmap 할당은 유지한다. 드문 형식의 메타데이터를 임의 변환하지 않기 위한 fallback이다.
- 폭·높이·colorSpace가 바뀌면 새 출력 저장소를 만든 뒤 이전 Canvas를 detach하고 이전 Bitmap을 recycle한다. 같은 크기의 90°↔270° 전환은 같은 출력 저장소를 덮어쓴다.

BBox 좌표 복원, 클래스·confidence 선택, Tensor 전개, Letterbox, NMS와 정책 코드는 변경하지 않았다. 소스 감사에서 pipeline의 crop 이후 검출·좌표·정책·시간 필드 생성 구간이 기존과 동일함을 확인했다. 실제 YOLO 입력/출력 바이트 비교도 통과했다.

## 3. 소유권과 자원 수명

| 자원 | 소유자 | 읽기 허용 범위 | 회수 시점 |
| --- | --- | --- | --- |
| CameraX `toBitmap()` 원본 | 현재 analyzer 프레임 | 회전 입력 또는 0°의 동기 onFrame | 회전 시 기존처럼 rotation 시간 구간 안에서 회수. 0°는 onFrame 반환 후 finally에서 회수 |
| 회전 출력 Bitmap/Canvas/Matrix | CameraController의 rotationBuffer | 동기 `onFrame()` 동안. 다음 프레임에 필요하면 별도 copy 필수 | 크기/색 공간 교체 또는 analyzer drain 성공 후 stopAsync |
| YOLO 부분 ROI Bitmap/Canvas | DetectionPipeline의 cropBuffer | 동기 `detector.detect()` 및 그 프레임 처리 | 크기/색 공간 교체 또는 pipeline.close |
| ML Kit 입력 | 기존 MlKitInputBuffer와 lease | SDK Task가 terminal 상태가 될 때까지 | 기존 lease/close 규칙 유지 |
| 캡처 이미지 | 기존 캡처 snapshot의 별도 Bitmap copy | 비동기 저장 완료까지 | 기존 saveEvaluationSnapshot finally에서 회수 |
| debug PNG 입력 | 기존 DetectionDebugRecorder의 별도 copy | 디버그 worker 완료까지 | 기존 worker finally에서 회수 |

`FrameBitmapBuffer`는 직렬 소유자를 전제로 한 scratch buffer이며, 결과는 다음 render/close까지의 borrowed reference다. CameraController의 단일 analyzer와 DetectionPipeline의 synchronized process/close가 각각 직렬 접근을 보장한다. 같은 출력 Bitmap을 자기 자신의 입력으로 사용하는 잘못된 호출은 거부한다.

### 다음 프레임·ML Kit·기록 사이의 충돌 방지

MainActivity는 analyzer 안에서 ML Kit의 640×360 입력 준비를 끝낸 뒤 비동기 SDK 작업을 시작한다. SDK가 읽는 대상은 ML Kit 전용 Bitmap이다. 따라서 다음 카메라 프레임이 회전 출력에 덮어써도 SDK 입력은 변하지 않는다. 기존 in-flight gate와 취소 후 Task drain·lease 해제는 수정하지 않았다.

캡처는 기존 `copyBitmap=true` 경로에서 별도 copy를 만들어 비동기로 저장한다. 녹화 JSON writer는 snapshot 메타데이터·검출 결과를 사용하며 `snapshot.bitmap` 픽셀을 읽지 않는다. 원래부터 프레임 Bitmap이 회수된 뒤 실행될 수 있던 기록 경로다. 이번 변경으로 비동기 writer에 새로운 픽셀 접근을 추가하지 않았다.

### 종료·설정 변경·오류

```text
closed=true / camera unbind
  → analyzer executor 종료 대기
  → 실행 중 onFrame 반환 및 ImageProxy.close 완료
  → rotationBuffer.close
  → CameraController stopped future 완료
  → MainActivity 기존 ML Kit/capture drain
  → DetectionPipeline.close → detector.close
```

analyzer 종료가 실패하면 기존처럼 아직 읽힐 수 있는 자원을 강제로 해제하지 않는다. 정상 종료에서는 회전 버퍼 해제가 완료되어야 stopped future가 성공한다. pipeline.close는 process와 같은 잠금을 사용하므로 detector가 읽는 도중 crop을 recycle하지 않는다. close 중복 호출은 안전하며 종료 후 process/render는 거부한다.

detector 실패 시 crop storage를 보존하고 다음 프레임에서 완전히 덮어쓴다. 변환·회전·타이밍 callback 실패 시 일회성 CameraX 원본은 finally로 회수한다. 캐시 출력은 소유자에게 남고 다음 프레임 또는 종료에서 처리한다. ImageProxy.close는 기존 analyzer finally에서 계속 수행한다.

## 4. 코드상 제거된 반복 할당

| 경로 | 변경 전 | 변경 후 |
| --- | --- | --- |
| 정상 90° 회전, 같은 출력 크기·형식 | 매프레임 회전 Bitmap, Matrix 및 플랫폼 draw용 객체 생성 | 출력 Bitmap/Canvas/Matrix/Rect/RectF/Paint 재사용 |
| 부분 EDGE_CROP, 같은 crop 크기·형식 | 매프레임 `Bitmap.createBitmap(...)`와 프레임 끝 recycle | crop 출력 Bitmap/Canvas 및 draw용 객체 재사용 |
| 0° / full-frame ROI | 입력 참조 사용 | 동일 |
| 크기·colorSpace 변경 | 새 Bitmap 생성 | 필요한 경우에만 교체하고 이전 storage 회수 |
| CameraX.toBitmap | 매 처리 프레임 원본 Bitmap 생성 | 유지. stride-safe CameraX 변환을 다시 작성하지 않음 |
| ML Kit 부분 ROI 임시 crop | 기존 별도 경로 | 유지. 이번 작업에서 ML Kit crop을 합치지 않음 |
| Tensor·Letterbox 입력 | 기존 재사용 | 유지 |

정상 ARGB_8888, 90° 회전, 부분 ROI, 크기·색 공간 고정 조건에서는 안정 구간의 **큰 픽셀 Bitmap 할당 두 개/처리 프레임**을 제거한다. 첫 사용·교체·fallback에는 할당이 남는다. 내부 라이브러리까지 포함한 “프레임당 할당 0”이라는 의미는 아니다.

픽셀 저장량 기준 회전 버퍼는 약 `4 × frameWidth × frameHeight` bytes, crop 버퍼는 약 `4 × roiWidth × roiHeight` bytes를 지속 보유한다. 예를 들어 1280×720의 회전 출력은 약 3.52MiB다. 이는 계산값이며 실측 heap/GC/RSS 절감량이 아니다. 할당 빈도 감소가 상주 메모리 감소를 보장하지 않으며 회전·crop의 픽셀 복사 비용 자체도 남는다.

## 5. 자동 검증 결과

### 빌드·단위 테스트·정적 확인

| 항목 | 결과 |
| --- | --- |
| Debug APK·계측 APK | 빌드 성공 |
| 전체 JVM 단위 테스트 | 40 suites, 210개 통과. 실패/오류/skipped 0 |
| Android Lint | Error/Fatal 0, Warning 196. 기존 195개에 신규 helper의 UseKtx 스타일 경고 1개 추가 |
| 전체 빌드 | 색 공간 타입 수정 후 성공: 2분 30초. 최종 테스트 harness 빌드 성공: 36초 |
| 1차 최적화 보존·변경 범위 감사 | 통과. MainActivity는 자원 종료 연결만 변경. 모델·설정·detector·경고·metrics·player 소스 변경 없음 |
| 공백·문서 참조 | git diff --check 및 로컬 참조 검사 |

[성공한 전체 빌드 기록][build-log], [최종 빌드 기록][final-build-log], [테스트 집계][test-summary], [소스 감사][audit-log]. 기존 trace API deprecated 경고를 이번 작업에 섞어 수정하지 않았다.

### SM-S921N 기기 회귀 테스트

| 테스트 | 개수 | 확인한 내용 |
| --- | --- | --- |
| [FrameBitmapBufferTest][buffer-tests] — 신규 | 5 | 0/90/180/270° 플랫폼 픽셀·density·alpha·색 공간 비교, odd size, 재사용 identity, 투명 프레임 덮어쓰기, 크기 변경 회수, RGB565 fallback, 오류 원본 해제, ML Kit lease·캡처 copy 독립성 |
| [ReusableRoiPipelineTest][pipeline-tests] — 신규 | 2 | detector 오류 후 storage 재사용, 처리 중 close 대기, detector/원본 비소유, 실제 YOLO Tensor 입력·출력 바이트 및 복원 BBox 동등성 |
| RecognitionRoiInstrumentedTest | 2 | quarter-turn 회전과 ROI→전체 프레임 좌표 복원, 영역 guide |
| [CameraPreviewRuntimeTest][camera-tests] — 보강 | 1 | 실제 CameraX OFF→ON→OFF, 회전 Bitmap identity, callback 실행 중 stop 대기, drain 후 recycle |
| MlKitInputBufferTest | 6 | ROI/회전·보간, 독립 입력, 재사용·lease·동시 close 및 오류 |
| MlKitObjectDetectorTest 선택 메서드 | 1 | coroutine 취소 후에도 SDK Task 완료까지 입력 lease 보존 |
| WarningDiagnosticsTest | 2 | 1차 진단 ON/OFF 및 경고 submit/cooldown 회귀 |
| DetectorPreprocessingRegressionTest | 4 | 전처리·버퍼·진단 ON/OFF·raw 좌표 통계 회귀 |
| DetectorCpuOptimizationTest | 2 | 기존 입력/raw 출력/검출·생성/종료 회귀 |
| ModelSelectionDetectorTest | 4 | 기존 모델별 pixels·합성 BBox/NMS·ROI·오류 회귀 |
| DetectorGoldenTest | 4 | 기존 RGB·positive image·Letterbox·실패 복구 회귀 |
| 최종 고유 테스트 합계 | **33** | **모두 통과. 최초 실행 32개 + 잠금 해제 후 CameraX 재실행 1개** |

실제 YOLO 비교는 `best_512.tflite`를 같은 Interpreter로 실행하며 full-frame, center-square, PRESET EDGE_CROP, 비대칭 EDGE_CROP 각각 0/90/180/270/90°를 확인했다. 플랫폼의 기존 회전→crop 입력과 새 버퍼 경로의 **입력 Tensor와 raw 출력 바이트가 일치**했고 class/confidence/BBox 순서 및 좌표 복원이 일치했다. 별도 픽셀 테스트에서는 97×61, 1281×721, 640×360 및 alpha 유무를 비교했다. 테스트 입력을 다양화한 것이 앱의 카메라·모델 해상도 설정을 변경했다는 의미는 아니다.

[최초 기기 테스트 원본][device-log], [잠금 해제 후 CameraX 성공 기록][camera-pass-log], [기기 설치 기록][install-log]. 실제 경고 소리·진동 출력과 캡처/녹화 E2E는 실행하지 않았다. 출력 정책·player 코드는 그대로이며 기존 정책 단위 테스트와 입력 copy/lease 독립성 검증을 사용했다.

## 6. 실패 원인과 해결 여부

1. **초기 compileDebugKotlin 실패:** `Bitmap.colorSpace`는 nullable인데 createBitmap overload는 non-null 인자를 요구했다. 로컬 val의 null 검사와 기존 플랫폼 fallback을 추가해 해결했다. 이후 전체 빌드·테스트 통과. [초기 빌드 오류 기록][initial-build-log].
2. **CameraX 프레임 미수신:** 최초 33개 실행 중 CameraPreviewRuntimeTest 1개가 Preview OFF에서 20초 안에 프레임을 받지 못했다. 카메라 변환 오류는 없었으며 기기는 Dozing·screen OFF·keyguard showing 상태였다. 테스트 Activity에만 표시·화면 유지 조건을 추가했지만 잠금 상태 재시도와 화면만 깨운 재시도도 실패했다. 기기 인증을 자동 해제하지 않았다.
3. **사용자 잠금 해제 후 해결:** 사용자가 잠금을 직접 해제한 뒤 동일 CameraX 테스트가 통과했다. 운영 CameraX 코드나 모델 설정을 변경해 통과시킨 것이 아니다. 테스트는 onFrame을 latch로 잠시 잡은 상태에서 stopAsync가 완료되지 않고 Bitmap도 살아 있음을 확인한 뒤, 반환을 허용하면 future 완료와 recycle이 발생함을 검증했다.

초기 실패를 감추거나 전체 33개가 한 번의 실행에서 모두 통과했다고 집계하지 않았다. [잠금 상태 재시도][camera-retry-log], [화면만 켠 상태 재시도][camera-awake-log], [카메라 실패 진단 로그][camera-failure-log]도 보존했다. 장시간 카메라/추론 성능 세션은 실행하지 않았다.

## 7. 재현 방법과 남은 검증 범위

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:lintDebug --offline --console=plain --max-workers=2 '-Pkotlin.compiler.execution.strategy=in-process'
```

기기 테스트는 APK를 `adb install -r -t`로 설치하고 AndroidJUnitRunner의 class 선택으로 실행했다. CameraX 테스트는 **기기 잠금 해제와 화면 ON 상태**가 필요하다. 실제 카메라 재검증의 명령은 다음과 같다.

```powershell
adb shell am instrument -w -r -e class com.samin.objectdetection.camera.CameraPreviewRuntimeTest com.samin.objectdetection.test/androidx.test.runner.AndroidJUnitRunner
```

버퍼 재사용과 픽셀·검출 동등성은 위 테스트 범위에서 확인했다. CameraX 실제 해상도 변경·앱 설정 적용 UI 전체 흐름, 캡처/녹화 저장 E2E, 다양한 카메라 OEM·색 공간/HDR 입력, 실제 안내 출력 및 장시간 heap·GC·발열 안정성은 추가 검증 범위다. 크기 변경·종료 처리는 이번 자동 테스트에서 직접 검증했지만 모든 실기기 조건을 대신하지 않는다.

다음 성능 검증에서는 기존 `rotationMs`, `bitmap`, `crop`, pipeline, LATENCY analyzer, 성공 FPS와 할당/GC를 같은 조건에서 비교할 수 있다. 이번 변경은 픽셀 저장소의 반복 할당을 줄이는 것이며 rotation 6.8ms 또는 crop 1.4ms가 그대로 사라진다는 주장은 하지 않는다. 262.9ms 수준의 추론 구간도 직접 변경하지 않았다.

[buffer]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/camera/FrameBitmapBuffer.kt:18>
[conversion]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/camera/ImageProxyExt.kt:12>
[camera-frame]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/camera/CameraController.kt:68>
[camera-stop]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/camera/CameraController.kt:223>
[pipeline-process]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/pipeline/DetectionPipeline.kt:39>
[pipeline-close]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/pipeline/DetectionPipeline.kt:207>
[main-settings]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/MainActivity.kt:606>
[main-destroy]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/MainActivity.kt:1725>
[buffer-tests]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/androidTest/java/com/samin/objectdetection/camera/FrameBitmapBufferTest.kt:14>
[pipeline-tests]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/androidTest/java/com/samin/objectdetection/camera/ReusableRoiPipelineTest.kt:21>
[camera-tests]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/androidTest/java/com/samin/objectdetection/camera/CameraPreviewRuntimeTest.kt:22>
[build-log]: <D:/develop/Projects/TestProjects/ObjectDetection/docs/validation/performance-phase2-gradle-20261106.txt>
[final-build-log]: <D:/develop/Projects/TestProjects/ObjectDetection/docs/validation/performance-phase2-gradle-final-20261106.txt>
[initial-build-log]: <D:/develop/Projects/TestProjects/ObjectDetection/docs/validation/performance-phase2-gradle-initial-20261106.txt>
[test-summary]: <D:/develop/Projects/TestProjects/ObjectDetection/docs/validation/performance-phase2-test-summary-20261106.txt>
[audit-log]: <D:/develop/Projects/TestProjects/ObjectDetection/docs/validation/performance-phase2-source-audit-20261106.txt>
[device-log]: <D:/develop/Projects/TestProjects/ObjectDetection/docs/validation/performance-phase2-device-tests-20261106.txt>
[camera-pass-log]: <D:/develop/Projects/TestProjects/ObjectDetection/docs/validation/performance-phase2-camera-unlocked-20261106.txt>
[install-log]: <D:/develop/Projects/TestProjects/ObjectDetection/docs/validation/performance-phase2-device-install-20261106.txt>
[camera-retry-log]: <D:/develop/Projects/TestProjects/ObjectDetection/docs/validation/performance-phase2-camera-retry-20261106.txt>
[camera-awake-log]: <D:/develop/Projects/TestProjects/ObjectDetection/docs/validation/performance-phase2-camera-awake-20261106.txt>
[camera-failure-log]: <D:/develop/Projects/TestProjects/ObjectDetection/docs/validation/performance-phase2-camera-failure-diagnostics-20261106.txt>
