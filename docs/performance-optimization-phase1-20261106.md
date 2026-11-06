# CameraX + YOLO 1차 성능 최적화 구현 결과

작성일: 2026-11-06 (Asia/Seoul)  
기준: `26354217718e7e9a3b421c3b523d68c4950608c6` 및 기존 성능 분석 문서  
상태: 코드 수정·자동 검증 완료. Git 커밋·푸시 미수행.

기존 `enableDetectorDiagnostics` 설정을 상세 경고 Logcat에도 적용하고 YOLO의 진단 전용 계산 두 종류를 조건화했다. 운영 코드 변경은 요청한 세 파일에 한정했다. **JVM 단위 테스트 210개와 연결된 SM-S921N의 계측 테스트 16개가 모두 통과했고, Debug 빌드·테스트 APK 빌드·Lint도 성공했다.** 고정 이미지 회귀 테스트를 실행했으며, 장시간 CameraX FPS·발열 개선은 측정하지 않았다.

## 수정된 파일과 메서드

| 파일 | 메서드·위치 | 변경 내용 |
| --- | --- | --- |
| [MainActivity.kt:732][main-process] | `processBitmap()` L816, L863 | 프레임별 진동·Beep·TTS 및 종합 경고 상세 Logcat을 진단 조건 안에서 생성 |
| [MainActivity.kt:1403][main-selected] | `logSelectedWarningCandidate()` | 진단 OFF이면 문자열 생성·Log.d 이전에 반환. 후보 없음 로그도 생략 |
| [MainActivity.kt:1439][main-output] | `logWarningOutput()` | 같은 early return 적용 |
| [MainActivity.kt:1476][main-crowd] | `logCrowdDecision()` | 같은 early return 적용 |
| [MainActivity.kt:288][main-wiring] | 기존 controller 생성부 | `detectionConfig.enableDetectorDiagnostics`를 controller에 전달 |
| [WarningOutputController.kt:34][controller-submit] | `submit()`에서 호출하는 진단 경로 | 제출 분기·반환값·상태 전이는 그대로 두고 공통 `logOutput()` 입구에서 제어 |
| [WarningOutputController.kt:309][controller-log] | `logOutput()` | 생성자에 받은 `enableDiagnostics`가 false이면 문자열 생성·Log.d 생략 |
| [VisionStyleYoloDetector.kt:351][parser] | `parseOutput()` L412, L433–435 | `maxClassScore`는 텐서 진단 프레임에서만 누적. `rawMin/rawMax`는 해당 상세 로그가 실제 사용될 때만 계산 |

설정 스키마나 저장값은 추가·변경하지 않았다. controller의 새 인자는 내부 진단용 Boolean이며 기존 detector 진단 설정과 연결된다. 앱은 설정 적용 시 기존 재생성 경로를 사용하므로 controller도 새 설정으로 다시 생성된다. 독립 생성 시 인자의 기본값은 false다.

## 변경 전후 코드의 핵심 차이

### MainActivity와 경고 출력 controller

변경 전에는 매프레임 상세 로그의 문자열 보간·연결을 먼저 수행한 뒤 `Log.d()`를 호출했다. `ENABLE_VERBOSE_LOG=false`와 별개인 경로였다.

```kotlin
// 변경 전
Log.d(TAG, "...${candidate?.label}...${result.skippedReason}...")

// 변경 후: 실제 Log.d 호출과 문자열 생성을 함께 건너뜀
if (!enableDiagnostics) return
Log.d(TAG, "...${candidate?.label}...${result.skippedReason}...")
```

MainActivity의 세 helper는 `if (!detectionConfig.enableDetectorDiagnostics) return`을 사용한다. `processBitmap()`의 직접 로그도 같은 설정으로 감쌌다. 경고 후보 선택·cooldown 검사·`warningOutputController.submit()`·통계·LATENCY 기록은 이 조건 밖에 그대로 있다.

진단 ON이면 기존 태그와 문자열 내용, 줄바꿈, 필드를 유지한다. 진단 OFF이면 MainActivity의 정상 프레임당 상세 로그 7개와 controller의 반복 상세 로그를 생략한다. 실제 출력 player의 동작·출력 관측 이벤트·오류 로그는 변경하지 않았다.

### YOLO 출력 파싱

```kotlin
// 변경 전: 텐서 진단을 하지 않는 프레임에서도 실행
maxClassScore = maxOf(maxClassScore, bestScore)

// 변경 후
if (diagnoseTensorThisFrame) maxClassScore = maxOf(maxClassScore, bestScore)
```

```kotlin
// 변경 전: 상세 후보 로그 OFF에서도 계산
val rawMin = minOf(cx, cy, w, h)
val rawMax = maxOf(cx, cy, w, h)
if (enableDiagnostics) { /* 기존 후보 로그 */ }

// 변경 후
if (enableDiagnostics) {
    val rawMin = minOf(cx, cy, w, h)
    val rawMax = maxOf(cx, cy, w, h)
    // 기존 후보 로그
}
```

`maxClassScore`의 소비는 이미 `diagnoseTensorThisFrame` 안이었다. 동일 조건으로 계산을 제한하므로 상세 진단·self-test에서 출력되는 최대 점수는 유지된다. 이 조건에는 기존 2초 제한과 debug recorder/self-test 활성화 경로가 그대로 적용된다.

`rawMin/rawMax`는 유효한 confidence 통과 후보의 상세 Logcat에만 사용된다. 계산 위치만 로그 조건 안으로 옮겼다. 전체 raw 후보의 **`rawCoordinateMin/Max` 누적·반환은 그대로 유지**했으며 캡처·녹화 JSON도 수정하지 않았다.

## 제거하거나 줄인 연산

- 진단 OFF 프레임의 상세 경고 문자열 보간·연결, enum/Boolean/숫자 문자열 변환, 해당 Log.d 호출.
- 후보가 없어도 반복되던 `[WarningSelected] none`, `[WarningOutput] none`, controller의 `candidate_none` 등 상세 출력.
- 텐서 진단을 하지 않는 프레임의 raw 후보별 `maxOf(maxClassScore, bestScore)`.
- 상세 후보 로그 OFF에서의 `minOf(cx,cy,w,h)`와 `maxOf(cx,cy,w,h)`.

이번에 별도 포맷터·람다 기반 공용 logger·새 설정 UI를 도입하지 않았다. 기존 인식·경고에 필요한 데이터 계산, 결과 객체, UI용 필드, raw 좌표 통계와 상세 진단 ON의 top-5 집계는 유지했다.

## 객체 인식 및 경고 기능에 영향이 없는 이유

| 보존 대상 | 근거 |
| --- | --- |
| Tensor 입력·출력 | 모델, Interpreter 호출, 텐서 codec·버퍼·전처리를 수정하지 않음. 세 모델의 동일 Interpreter에서 진단 OFF→ON→OFF raw 출력 바이트 일치 확인 |
| confidence·클래스·BBox·NMS | 최고 클래스 선택·threshold·좌표·정렬·NMS 코드는 수정하지 않음. 고정 이미지의 결과 목록 전체와 후보/NMS 수 일치 확인 |
| 캡처·녹화 | `rawCoordinateMin/Max` 누적·반환과 기록 소비 경로 유지. 진단 ON/OFF 값 일치 확인. 실제 캡처/녹화 E2E는 이번에 별도 실행하지 않음 |
| 우선순위·cooldown·상태 | `submit()`의 조건·상태 전이와 `runOutput()`·cooldown 코드를 수정하지 않음. 기존 정책·cooldown 단위 테스트와 진단 ON/OFF 제출 결과 비교 통과 |
| TTS·진동·Beep | 실제 enable 조건, interrupt, 출력 계획, player 호출, delay, markNotified를 수정하지 않음. 물리 장치의 실제 소리·진동 재생 검증은 수행하지 않음 |
| 필수 관측·성능 기록 | `FrameLatencyEvent.Warning`, player의 OUTPUT 관측, 성능 FRAME/LATENCY/SYSTEM/오류 및 캡처·녹화 필드 생성 코드를 수정하지 않음 |
| 기존 설정·카메라 | 모델·해상도·Confidence/NMS·ROI·CameraX·XNNPACK·threads·Bitmap 수명과 설정 파일 변경 없음 |

소스 감사에서는 새 조건과 전달 인자만 제거해 복원한 운영 소스를 HEAD와 비교했다. 공백을 제외한 실행 소스가 같고, 세 파일의 기존 문자열 리터럴 목록도 동일함을 확인했다. 이 정적 확인은 모델·경고 동작에 변경이 섞이지 않았다는 근거이며 실기기 안전성 검증 전체를 대신하지는 않는다.

## 실행한 테스트와 결과

### 빌드·전체 단위 테스트

Windows / Android Studio JBR 21 / 기존 Gradle·SDK 오프라인 캐시에서 실행했다. Gradle 및 프로젝트 설정 파일은 변경하지 않았다.

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:lintDebug --offline --console=plain --max-workers=2 '-Pkotlin.compiler.execution.strategy=in-process'
```

| 검증 | 결과 |
| --- | --- |
| `assembleDebug` | 성공. `app/build/outputs/apk/debug/app-debug.apk` |
| `testDebugUnitTest` | 40 suites, 210개 통과. 실패 0, 오류 0, skipped 0 |
| 기존 Warning 정책·혼잡도·선택·cooldown 단위 테스트 | 총 15개 통과. 전체 210개에 포함 |
| `assembleDebugAndroidTest` | 성공. 계측 테스트 APK 컴파일·패키징 완료 |
| `lintDebug` | 성공. Error/Fatal 0, Warning 195. 새 `WarningDiagnosticsTest` 관련 지적 없음 |
| 전체 Gradle 실행 | `BUILD SUCCESSFUL in 2m 18s` |
| 변경 범위·문자열·실행 코드 감사 | 통과. 운영 파일 세 개 외 모델·설정·카메라·정책·metrics·player 구현 변경 없음 |

[Gradle 원본 기록][build-log], [테스트 집계][test-summary], [소스 감사 결과][audit-log]. 컴파일의 기존 trace API deprecated 경고와 Lint 경고는 남아 있으며 이번 변경에 섞어 수정하지 않았다.

### 실제 Android 기기 회귀 테스트

연결된 **SM-S921N**에 Debug APK와 테스트 APK를 `adb install -r -t`로 설치했다. 카메라를 시작하거나 저장된 앱 설정을 바꾸지 않는 다음 다섯 테스트 클래스만 선택 실행했다.

| 테스트 클래스 | 개수 | 검증 내용 | 결과 |
| --- | --- | --- | --- |
| `WarningDiagnosticsTest` — 신규 | 2 | 실제 Logcat OFF 부재/ON 내용 확인, none/후보/혼잡도, 제출 거절·허용·critical interrupt·cooldown 불변 | 통과 |
| `DetectorPreprocessingRegressionTest` | 4 | 기존 전처리·버퍼·반복 결과 회귀 3개 + 신규 진단 ON/OFF 출력 비교 1개 | 통과 |
| `DetectorCpuOptimizationTest` | 2 | 기존 원본 입력/raw 출력/검출 비교, 지원 모델·CPU 2/4 threads·XNNPACK ON/OFF 및 생성/종료 회귀 | 통과 |
| `ModelSelectionDetectorTest` | 4 | 기존 모델별 입력/출력·Letterbox/Stretch·합성 BBox/NMS/ROI 및 잘못된 모델 오류 처리 | 통과 |
| `DetectorGoldenTest` | 4 | 기존 RGB·정규화·positive 이미지·Letterbox padding 및 실패 후 복구 | 통과 |
| 합계 | **16** | 기존 13개 + 신규 3개 | **실패 0, `OK (16 tests)`** |

추가 테스트 소스:

- [WarningDiagnosticsTest.kt:55][warning-tests]: Activity를 attach하지 않고 세 로그 helper만 호출한다. 실제 Logcat을 PID·전용 marker로 구분하여 OFF에서는 출력이 없고 ON에서는 기존 필드와 none 로그가 보이는지 확인한다. controller 테스트는 취소된 scope와 비활성 물리 출력으로 submit 상태 전이를 결정적으로 비교한다. 실제 출력 완료·음성/진동 검증으로 해석하지 않는다.
- [DetectorPreprocessingRegressionTest.kt:101][detector-tests]: 세 모델 각각 진단 OFF 기준 결과를 저장하고 ON→OFF에서 raw Tensor 바이트·검출 목록·raw 좌표 min/max·후보 및 NMS 개수가 일치하는지 비교한다. 텐서 진단 시간 및 raw top confidence의 활성화/비활성화도 확인한다.

실행 명령의 테스트 클래스는 다음과 같다.

```powershell
$adb='C:\Users\SAMIN\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb -s R3CX506B3RH shell am instrument -w -r -e class 'com.samin.objectdetection.warning.output.WarningDiagnosticsTest,com.samin.objectdetection.detector.DetectorPreprocessingRegressionTest,com.samin.objectdetection.detector.DetectorCpuOptimizationTest,com.samin.objectdetection.detector.ModelSelectionDetectorTest,com.samin.objectdetection.detector.DetectorGoldenTest' com.samin.objectdetection.test/androidx.test.runner.AndroidJUnitRunner
```

[기기 설치 기록][install-log], [기기 테스트 원본][device-log]. 계측 runner의 마지막 `INSTRUMENTATION_CODE: -1`은 정상 완료 코드이며, 각 테스트의 `STATUS_CODE: 0` 및 `OK (16 tests)`를 함께 확인했다.

### 실패·문제와 해결 여부

- Android 빌드·단위 테스트·선택 계측 테스트 실패는 없었다.
- 최초 sandbox ADB 조회는 서버 접속에 실패했다. 허용된 실행 환경에서 같은 읽기 조회를 다시 실행하여 연결을 확인했고 설치·회귀 테스트를 완료했다. 앱 코드를 바꾸어 해결한 문제가 아니다.
- 보조 소스 감사 스크립트의 첫 실행은 복원용 문자열 치환이 **기존** `logDetectionTiming()` gate까지 제거해 assertion이 실패했다. 치환을 새 종합 경고 로그 위치로 한정한 뒤 통과했다. 제품 코드 실패나 로그 회귀는 아니었으며 운영 코드 수정은 필요하지 않았다.

## 예상되는 효과와 아직 검증되지 않은 부분

진단 OFF에서 analyzer 후반의 상세 문자열 할당·Logcat 호출과 출력 scan의 진단 전용 산술이 줄어든다. 단순 Boolean 검사와 helper 호출은 남는다. 진단 ON에서는 기존 상세 관측을 의도적으로 유지한다.

이번 기기 검증은 **정확성·진단 gate 회귀 테스트**다. 기존 테스트가 출력한 단일 detector 단계 시간은 CameraX 전체 처리량 또는 최적화 전후 성능 실험이 아니다. FPS 향상률, 장기 추론 시간 개선, 발열·CPU 제한·전력·GC 개선 수치는 산출하지 않았다.

실제 효과는 같은 모델·설정·장면·초기 열 상태에서 3분/10분 A/B를 반복해 확인해야 한다. FRAME의 `inference`뿐 아니라 pipeline·최종 30초 구간의 성공 FPS·LATENCY analyzer callback을 함께 비교한다. 특히 경고 로그는 pipeline 완료 후이므로 `totalProcessing`만으로 절감 효과를 전부 볼 수 없다. 입력 텐서·경고 동작을 보존하는 이 변경만으로 기존 228→344ms 추론 시간 증가가 해결된다고 보장하지 않는다.

## 2차 최적화에서 다룰 항목

1. 기존 FRAME/LATENCY/SYSTEM으로 초기·후반 구간을 먼저 비교하고, 필요할 때 Perfetto로 native 실행·스케줄링·GC를 구분한다.
2. 출력 scan이 병목으로 확인되면 캡처·녹화 소비를 보존하는 `rawCoordinateMin/Max` 계산 조건과 진단 ON의 top-5 집계 개선을 검토한다. 이번에는 변경하지 않았다.
3. 회전 비용/할당이 유의미하면 analyzer 소유 회전 Bitmap/Canvas 재사용을 검토하고 이후 실제 ROI 사용 시 crop storage 재사용을 검토한다. 소유권·회전·경계 픽셀 회귀가 선행되어야 한다.
4. writer·Overlay가 실제 병목일 때만 별도 개선한다. 성능 로그 flush 정책이나 GOTORO 무화면 lifecycle 분리는 별도 범위로 유지한다.

모델 재학습·입력 해상도·추론 주기 제한·임계값 변경은 이번 결과의 후속 자동 적용 항목이 아니다.

[main-process]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/MainActivity.kt:732>
[main-selected]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/MainActivity.kt:1403>
[main-output]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/MainActivity.kt:1439>
[main-crowd]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/MainActivity.kt:1476>
[main-wiring]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/MainActivity.kt:288>
[controller-submit]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/warning/output/WarningOutputController.kt:34>
[controller-log]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/warning/output/WarningOutputController.kt:309>
[parser]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/detector/VisionStyleYoloDetector.kt:351>
[warning-tests]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/androidTest/java/com/samin/objectdetection/warning/output/WarningDiagnosticsTest.kt:55>
[detector-tests]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/androidTest/java/com/samin/objectdetection/detector/DetectorPreprocessingRegressionTest.kt:101>
[build-log]: <D:/develop/Projects/TestProjects/ObjectDetection/docs/validation/performance-phase1-gradle-20261106.txt>
[test-summary]: <D:/develop/Projects/TestProjects/ObjectDetection/docs/validation/performance-phase1-test-summary-20261106.txt>
[audit-log]: <D:/develop/Projects/TestProjects/ObjectDetection/docs/validation/performance-phase1-source-audit-20261106.txt>
[install-log]: <D:/develop/Projects/TestProjects/ObjectDetection/docs/validation/performance-phase1-device-install-20261106.txt>
[device-log]: <D:/develop/Projects/TestProjects/ObjectDetection/docs/validation/performance-phase1-device-tests-20261106.txt>
