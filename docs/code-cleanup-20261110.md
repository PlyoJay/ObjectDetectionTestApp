# Device App 이식 전 코드 정리

작성일: 2026-11-10 (Asia/Seoul). 대상: `ObjectDetectionTestApp` (`ObjectDetection` 루트의 `:app`). 시작 HEAD `ab00e47`, 시작 작업 트리 변경 없음. 커밋·푸시·신규 기능·추론 알고리즘·성능 튜닝 없음.

## 조사 범위와 판정

전체 추적 파일 494개를 조사 목록으로 기록하고, 앱의 main/debug/test/androidTest Kotlin·Manifest·XML·텍스트 리소스 179개에서 선언 후보와 참조를 수집했다. Android 앱 소스에는 Java가 없으며 `.guardian-work`의 Java는 별도 서버 프로젝트다. Gradle·버전 카탈로그·CI·ProGuard·도구·assets·과거 문서도 교차 확인했다.

| 근거 자료 | 내용·한계 |
| --- | --- |
| [파일별 목록](validation/code-cleanup-files-before-20261110.tsv) | 삭제 **전** 파일별 유지/수정·통합/삭제 가능/확인 필요, 근거, 크기, SHA-256 |
| [선언별 목록](validation/code-cleanup-symbols-before-20261110.tsv) | 클래스·함수·프로퍼티·지역 선언 후보 5,562건의 위치·어휘 참조 수·최대 8개 참조 파일·분류. 동일 이름은 다른 심벌일 수 있어 컴파일러의 호출 그래프가 아니며, 단독 출현은 삭제 근거로 사용하지 않음 |
| [설정·의존성 원본](validation/code-cleanup-config-before-20261110.tsv) | Gradle·카탈로그·프로퍼티의 변경 전 값. 설정 codec의 직렬화 키·프리셋·복구 경로는 별도 직접 확인 |
| [수집 스크립트](validation/code-cleanup-audit-20261110.py) | 수정 전 실행용, 자동 삭제 없음. 결과 파일은 baseline 스냅샷이므로 정리 후 덮어쓰지 않음 |
| [보존 검증](validation/code-cleanup-preservation-20261110.json) | 핵심 64개 파일(assets는 바이트, 텍스트는 CRLF/LF 정규화), `MainActivity.processBitmap`, 분리한 스냅샷 본문의 동일성 확인 |

정적 검색 외에 Manifest의 Activity·Service·FileProvider, adaptive icon/XML 테마·공유 경로, debug Manifest·Self Test 이미지, Android 테스트의 `getDeclaredField`/`getDeclaredMethod`, 설정 Properties codec·schema migration·적용/이전 설정/사용자 프리셋을 확인했다. 런타임 동적 클래스명·리소스명 조회로 삭제 대상을 사용하는 경로는 발견하지 못했다. 불확실한 항목은 유지했다.

| 영역·항목 | 판정 | 사용처·결정 |
| --- | --- | --- |
| `detector/*`, 두 모델, labels | 유지 | `MainActivity → DetectionPipeline → VisionStyleYoloDetector`, debug Self Test, 모델·tensor·NMS·golden 회귀 테스트 |
| `camera/*`, `pipeline/*` | 유지/수정·통합 | CameraX 프레임·ROI·좌표 복원·재사용 버퍼. 테스트만 쓰는 할당형 `Bitmap.rotate`만 androidTest로 이동 |
| `policy/*`, `motion/*`, `location/*`, `model/*`, `warning/*` | 유지 | 필터·추적·위험/경고 정책·GPS 상태·실제 출력 및 테스트. Composite 구현만 호출 없음 |
| `MainActivity`, `ui/*` | 수정·통합 | 최신 UI 전달·카메라/비동기 작업 수명은 유지하고 독립적인 테스트 앱 UI·진단·스냅샷 생성만 분리 |
| `SettingsActivity` | 수정·통합 | 화면·draft·카테고리·프리셋은 유지. 중복된 IO+Mutex+모델 검사 경계를 별도 클래스로 통합 |
| `AppSettings`, `AppSettingsStore`, codec·presets | 유지 | 모든 현재 키·schema 13·삭제 모델 복구·pending/attempting·이전 설정·프리셋 보존. 설정 항목 삭제 없음 |
| `metrics/*`, `logging/*`, `evaluation/*` | 유지/수정·통합 | 성능·배터리·열·지연·순서 보장 로그·캡처·녹화의 현재 테스트 경로. 캡처 데이터 생성만 별도 파일로 분리 |
| `mlkit/*`, `MlKitLogControls`, `LogShareManager` | 유지 | 메인 비교·진단 로그·설정 화면·SDK/lease/cancellation 테스트. 테스트 앱 책임으로 기존 전용 패키지를 유지하며 Device App 필수 의존성으로 취급하지 않음 |
| `Camera2DistanceGeometry` | 삭제 가능 → 삭제 | 카메라 bind/capture callback 연결 없음. 자기 선언 외 코드·테스트·Manifest·XML·reflection 참조 없음 |
| `GroundPlaneDistanceEstimator` 및 같은 파일의 타입 | 유지 | 추정기 자체는 현행 pipeline에서 호출하지 않지만 JVM 9개 테스트가 사용. `DistanceSettings`는 codec/설정, `DistanceEstimate`·intrinsics는 `DetectionResult`·JSON·거리 회귀 테스트에 사용하므로 통째 삭제 불가 |
| `tof/TofMeasurement`, `TofStatus`, `TofMeasurementSource` | 유지 | 명시적으로 보호한 외부 센서 확장 계약. UART·매칭 추가 없음 |
| `dto/DetectionEvent`, `RoiInfo` | 확인 필요 → 보존 | 앱 실행 경로 참조 없음. Device App 통신 계약으로 사용할지 결정되지 않아 삭제 유보 |
| 구 YOLO 모델·tensor 호환 분기 | 유지 | 구 asset/enum은 11/9 작업에서 이미 제거. 현행 `YoloTensorContract`의 layout/dtype/shape 방어와 codec 복구는 회귀 테스트·오류 검출에 사용 |
| `legacyDebugImageEnabled` 등 디버그 설정 | 유지 | 저장·이전 설정·입력 이미지 진단/테스트 사용. 이름에 legacy가 있다는 이유로 삭제하지 않음 |
| main/debug Manifest·ProGuard·리소스·테스트 assets | 유지 | Activity/Service/provider/테마/icon/backup/공유·debug Self Test 및 실제 이미지 추론. 미사용 색상 XML만 삭제 |
| 전체 JVM·Android 테스트, Python 분석기·Perfetto 도구 | 유지 | 이전 9/11클래스 이름의 테스트도 현행 정책·호환성을 검증. 예제 테스트도 실행 중이므로 미사용으로 분류하지 않음 |
| `docs/validation`, 과거 ARCore/성능 문서·분석기 | 유지 | 과거 검증 원본과 로그 분석 회귀 근거. ARCore 런타임 의존성·Manifest 설정은 이미 없음 |
| `.idea` 공유 설정·wrapper·CI | 유지 | 프로젝트 설정과 빌드 재현에 사용. 로컬 IDE 선택 상태만 ignore 보완 |

## 1. 삭제·이동 근거

삭제 전 SHA·정확한 경로는 파일별 목록과 [실제 삭제/이동 목록](validation/code-cleanup-deleted-20261110.tsv)에 보존했다. 삭제는 프로젝트 내부의 확정된 경로를 확인한 뒤 수행했다.

| 파일/코드 | 조치 | 참조 확인·삭제 근거 |
| --- | --- | --- |
| `.guardian-work/` 78개 추적 파일 | 삭제 | `pas.*` Spring/JSP/SQL의 baseline/staged/revert 복사본·컴파일 class·로컬 실행 자료. `:app` source set, Gradle include, CI, Android/도구 참조에 포함되지 않음 |
| `distance/Camera2DistanceGeometry.kt` | 삭제 | 미연결 capture metadata 수집기. 현재 `CameraController`는 이 수집기를 생성하거나 호출하지 않음 |
| `ui/theme/Color.kt`, `Theme.kt`, `Type.kt` | 삭제 | 외부에서 부르지 않는 Compose 템플릿 섬. 실제 두 화면은 native View이며 Manifest 테마는 XML의 플랫폼 테마 |
| `res/values/colors.xml` | 삭제 | 전체 앱/테스트 XML·코드에 `@color/…`·`R.color.…` 또는 동적 리소스 조회 없음. launcher 색은 자체 drawable에 정의 |
| `CompositeWarningPlayer` 구현 | 삭제 | 생성·호출·reflection 없음. 실제 출력은 `WarningOutputController`가 제어. **사용 중인 `WarningPlayer` 인터페이스는 별도 파일로 보존** |
| `hs_err_pid21060.log` | 삭제 | JVM native memory 부족 시 생성된 crash dump. 코드·빌드·분석 도구 입력 참조 없음 |
| `EvaluationDataRecorder` writer import 2개, 테스트 import 2개 | 삭제 | 호출/타입 참조 없음. 실제 사용하는 백틱 Hamcrest import는 복원 후 빌드 확인 |
| `camera/ImageProxyExt.kt`의 할당형 `Bitmap.rotate` | androidTest로 이동 | main은 `FrameBitmapBuffer.rotate`를 사용. 기존 계측 테스트가 비교 기준으로 사용하는 extension과 패키지·연산을 보존 |
| 루트 `mlkit_diagnostics.txt` | 이력 보관으로 이동 | 10/23의 과거 실제 로그이므로 삭제하지 않음. [보관 파일](validation/mlkit-diagnostics-legacy-20261023.txt)의 SHA-256이 원본과 동일 |
| `arcore_depth.tar` | 확인 필요, 원위치 보존 | UTF-16 BOM으로 시작하며 `tar -tf`에서 인식 실패. 원본 실측 데이터일 수 있어 복구·변환·삭제하지 않음 |
| `.gradle/`, `.kotlin/`, `build/`, `app/build/`, `.idea/`, `.venv/`, `golden_runs/` | 필요성 점검·보존 | 재현 캐시·로컬 환경·검증 산출물. 앱 산출물은 기존 ignore, Kotlin/guardian/crash/루트 export 패턴은 추가. 증거·공유 IDE 설정을 일괄 삭제하지 않음 |

| Gradle 항목 | 분류·조치 | 이유 |
| --- | --- | --- |
| `kotlinx-coroutines-play-services:1.11.0` | 수정·통합: 중복 선언 2→1 | ML Kit의 `tasks.await`에 필요 |
| Compose plugin·buildFeature·BOM·UI/Material3/tooling·Compose 테스트 의존성/alias | 삭제 | 사용처는 삭제한 템플릿뿐. 실제 UI·테스트에는 Compose API 없음 |
| `activity-compose:1.13.0` | 수정·통합: 기존 버전의 `activity-ktx` | 사용 중인 ComponentActivity·Activity Result를 위한 명시 의존성. 새로운 라이브러리 계열/버전 도입 없음 |
| AppCompat 직접 의존성/alias | 삭제 | AppCompat Activity/테마/API 사용 없음. native View·플랫폼 XML 테마 유지 |
| `lifecycle-service:2.8.4` | 삭제 | 녹화 서비스는 `android.app.Service`를 직접 상속 |
| 미사용 `kotlin-android` plugin alias | 삭제 | AGP의 기존 내장 Kotlin 경로 사용; plugin 적용 이력 없음 |
| CameraX 1.3.4 네 모듈·ML Kit·LiteRT·core/lifecycle·JUnit/JSON/Espresso/AndroidX JUnit | 유지 | 프레임/Preview/Camera2 backend·SDK·Interpreter·생명주기·실행 테스트 사용. 엔진 버전 변경 없음 |

## 2. 통합·분리한 책임

| 기존 | 정리 후 | 보존 조건 |
| --- | --- | --- |
| `MainActivity.logSelectedWarningCandidate/logWarningOutput/logCrowdDecision`의 문자열 생성 | `warning/output/WarningDiagnostics` | 기존 private 진입점은 reflection 테스트 때문에 위임으로 유지. 태그·본문·diagnostics gate 그대로 |
| `MainActivity.buildEvaluationSnapshot` | `evaluation/EvaluationSnapshotFactory.kt` | 함수 본문·인수·복사 조건 그대로. capture Bitmap 복사와 녹화 borrowed Bitmap/ROI 복사 계약 유지 |
| `MainActivity.openQuickSettings`의 위젯·검증 | `settings/QuickSettingsDialog.kt` | 세션 중 변경 금지·설정 저장·camera drain/recreate는 Activity가 계속 소유 |
| `SettingsActivity`의 모델 정보/적용 검사 IO+Mutex | `settings/SettingsModelInspector` | 단일 Mutex·IO 실행·같은 모델/threads/XNNPACK 검사. 화면 cache·draft·save/rollback 수명 그대로 |
| `SettingsActivityTest` 설정 개수 검사 | 일반 72개 + 클래스별 24개 = 전체 96개로 fixture 갱신 | 기존 테스트에 누락된 12클래스의 enable/confidence 항목 추가. 실제 화면 전체 목록 비교·모든 설정 스크롤 검증 유지 |
| `BoundingBoxOverlayTest` 라벨 배치 검사 | 이 테스트의 debugMode NONE→SIMPLE | NONE은 라벨을 숨기는 기존 동작. 라벨 경계/개수/겹침 검증을 실제 라벨 표시 모드로 실행 |
| `ImageProxyExt.Bitmap.rotate` | `androidTest/camera/BitmapTestTransforms.kt` | package·참조 연산 유지; 운영 회전은 기존 재사용 구현만 사용 |
| Composite 파일에 섞인 `WarningPlayer` | `warning/output/WarningPlayer.kt` | 기존 세 출력기의 interface·기본 release 계약 유지 |

## 3. 반드시 유지한 동작

| 기능 | 보존 근거 |
| --- | --- |
| KEEP_ONLY_LATEST·Preview ON/OFF·최신 프레임 UI·종료 drain | CameraController/LatestUiUpdateDispatcher·관련 수명 경로 그대로. 카메라 스케줄링·간격 변경 없음 |
| 회전·ROI·Bitmap/Canvas/Matrix·tensor/배열/호출 컨테이너 재사용 | 최적화된 프레임·pipeline·detector 코드 불변. 테스트용 할당형 회전만 source set 이동 |
| YOLO11s/YOLO11n, 12클래스 ID | 두 asset·labels·registry 동일. 순서: `bollard, person, bicycle, car, motorcycle, bus, truck, stop sign, bench, cat, dog, cardboard_box` |
| Letterbox/Stretch·RGB 변환·NMS·confidence·클래스별 설정·BBox/ROI 복원 | detector·tensor 계약·resize transform·pipeline·필터 구현 불변 |
| Threads·XNNPACK·모델 선택·설정/프리셋 호환 | 기존 버전·기본값·schema·복구 코드 그대로. inspector는 기존 검사를 옮긴 것 |
| 11/6~9 성능 최적화 1~3차 | 조건부 진단/경고 문자열, 회전·ROI 재사용, bulk tensor IO·출력 스캔·면적 범위 선계산·Interpreter 호출 불변. 핵심 64개 파일 및 Main frame 처리 동일성 검증 |
| ML Kit·성능/배터리/열·로그 추출·캡처/녹화 | 테스트 앱 전용 패키지와 UI 경로 보존. 프로덕션 이식 대상과 역할을 구분할 뿐 현재 테스트 앱에서 비활성화하지 않음 |
| 경고·미측정 거리·외부 ToF 계약 | 기존 warning policy/output·null/NOT_SUPPORTED 직렬화 그대로. Ground-plane 계산을 다시 연결하거나 UART·ToF 매칭을 구현하지 않음 |

## 4. 구조 차이

| 구분 | 정리 전 → 정리 후 |
| --- | --- |
| MainActivity | 1,762줄 → 1,597줄. frame 처리/수명 orchestration은 유지, 3개 독립 책임을 해당 패키지로 이동 |
| SettingsActivity | 모델 IO·상호배제·표시 포맷을 화면 안에 구현 → 검사 경계를 helper로 통합, UI 상태 관리는 화면에 유지 |
| 객체 인식 핵심 | 기존 camera → pipeline → detector/policy/motion → result 구조 그대로 |
| 테스트 앱 보조 | mlkit/metrics/evaluation/logging 패키지 유지 + 진단/스냅샷 분리; Device App에 자동으로 따라갈 필요 없음 |
| 빌드/파일 | 미사용 Compose·중복 의존성·서버 작업 복사본 제거; 과거 실측/검증 자료 보존·로그 위치 정돈 |

## 5. 빌드·테스트

JDK 기본 PATH는 8이므로 설치된 Android Studio JBR을 `JAVA_HOME`으로 지정했다. 초기 sandbox의 Gradle cache lock/ADB 서버 접근 제한은 권한을 갖춘 실행으로 해결했다. baseline JVM은 `--rerun-tasks`로 실제 실행했다.

| 검증 | 변경 전 | 변경 후 |
| --- | --- | --- |
| Debug·AndroidTest APK | 성공 | 성공 (앱 정리 후 2분 6초, 최종 테스트 보완 후 재검증 36초) |
| JVM 단위 테스트 | 41 suites / 212개 통과, 실패·오류·skip 0 | 동일: 41 suites / 212개 통과, 실패·오류·skip 0 |
| Android Lint | Error/Fatal 0, Warning 196 | Error/Fatal 0, Warning 185. 새 경고 종류 없음 |
| SM-S921N 계측 | baseline 15개 통과: 두 모델·실제 이미지·parser/NMS·ROI·Interpreter·재사용·warning gate | 고유 86개 최종 통과: 초기 UI 24/25 + core 60/61, 두 기존 테스트 보완 후 최종 재실행 2/2. 단일 86개 전체 성공 재실행은 아님 |
| Python 모델 계약·과거 로그 분석 | 과거 결과와 별도로 현행 실행 | 40개 통과 |
| Python Perfetto 도구 | 과거 결과와 별도로 현행 실행 | 8개 통과 |
| APK 모델 패키징 | baseline 원본 SHA 기록 | 두 YOLO asset의 SHA 동일·무압축, labels 동일. ML Kit 내장 모델 2개 유지 |
| 핵심 소스/모델 보존 | 기준 `ab00e47` | 64개 동일, Main frame 처리·snapshot 본문 동일 |

첫 수정 후 계측 APK 컴파일은 사용 중인 Hamcrest 백틱 import를 잘못 제거해 실패했다. import를 복원해 해결했으며 [초기 실패 원본](validation/code-cleanup-build-initial-20261110.txt)을 보존했다. 앱 추론 코드/알고리즘 실패는 아니지만 이번 정리로 유발한 오류로 기록한다.

UI 계측 첫 실행은 25개 중 24개 통과했다. 기존 `SettingsActivityTest`는 일반 설정 72개의 fixture를 고정 기대값 77과 비교하고 있었다. [HEAD 동일성과 fixture 수 검증](validation/code-cleanup-existing-test-failure-20261110.json)으로 정리 이전부터의 문제임을 확인했다. 72로 바로잡은 첫 재실행에서 실제 View 전체 목록에는 fixture에 누락된 클래스별 enable/confidence 24개도 있음을 확인했다. 최종 fixture는 일반 72개 + 12클래스×2개 = **96개**를 검증한다. 전체 tag 일치·각 컨트롤 스크롤/표시·취소 시 미저장 검증을 유지·확장했으며 앱 설정 UI를 삭제하거나 개수를 줄이지 않았다.

핵심/Overlay 계측 첫 실행은 61개 중 60개 통과했다. 나머지 기존 라벨 배치 테스트는 helper가 `NONE`으로 설정한 상태에서 라벨 2개를 기대해 실패했다. 테스트와 운영 Overlay가 모두 HEAD와 동일함을 확인하고, 해당 테스트에서만 `SIMPLE`을 명시했다. 라벨 개수·화면 경계·겹침 검증과 운영 렌더링 코드는 변경하지 않았다. 두 기존 fixture 오류와 이번 import 오류를 환경 실패로 분류하거나 숨기지 않았다.

재현 명령:

```powershell
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
./gradlew.bat --offline :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:lintDebug --console=plain --max-workers=2 '-Pkotlin.compiler.execution.strategy=in-process'
./.venv/Scripts/python.exe -m unittest discover -s tools -p 'test_*.py' -v
./.venv/Scripts/python.exe -m unittest discover -s tools/perfetto -p 'test_*.py' -v
```

최종 계측 범위는 CameraX Preview/drain, 모델 선택·12클래스/NMS·ROI·Letterbox/Stretch·threads/XNNPACK·진단 ON/OFF 동일성·버퍼 재사용, 설정/프리셋/이전 schema·복구·클래스 옵션·전체 96개 컨트롤, ML Kit lease/cancellation·mode 변경 수명, 실제 배터리/시스템 진단과 MediaStore stop/drain, 캡처 복사/녹화 borrowed snapshot, 거리 미지원·Overlay 최신성·경고 gate/cooldown이다. 새 스냅샷 소유권 테스트도 포함한다.

원본: [baseline 빌드](validation/code-cleanup-baseline-20261110.txt), [baseline JVM 실제 재실행](validation/code-cleanup-baseline-unit-20261110.txt), [baseline 실기기](validation/code-cleanup-baseline-device-20261110.txt), [최종 빌드](validation/code-cleanup-final-complete-20261110.txt), [앱 정리 후 실제 JVM 실행 포함 빌드](validation/code-cleanup-final-build-20261110.txt), [초기 UI 계측](validation/code-cleanup-final-device-ui-20261110.txt), [초기 core 계측](validation/code-cleanup-final-device-core-20261110.txt), [fixture 보완 중 재실행](validation/code-cleanup-final-device-retry-20261110.txt), [최종 재실행](validation/code-cleanup-final-device-complete-20261110.txt), [계측 고유 86개 집계](validation/code-cleanup-device-summary-20261110.json), [빌드·Lint·APK 집계](validation/code-cleanup-final-summary-20261110.json), [Python](validation/code-cleanup-python-20261110.txt), [Perfetto](validation/code-cleanup-perfetto-20261110.txt).

## 6. Device App 이식 순서

| 순서 | 재사용 코드 | 이식 시 확인 |
| --- | --- | --- |
| 1 | `ModelConfig`, `ModelAssets`, 두 tflite·labels, `YoloTensorContract`, `YoloResizeTransform`, `DetectionResult` | asset SHA·12클래스 순서·FLOAT32 NCHW 512/raw output 계약. LiteRT 버전·noCompress·native ABI 유지 |
| 2 | `VisionStyleYoloDetector`, `YoloClassOptions`, `DetectionConfig` | 현재 전처리/후처리·NMS·버퍼 수명·threads/XNNPACK과 모델 전환·초기화 실패 처리. Android Bitmap/Context 의존성 있음 |
| 3 | `DetectionPipeline`, ROI·FrameBitmapBuffer, policy/motion, 위치 snapshot 계약 | 원본 좌표·최신 frame 소유권·serial process/close·필터·경고 대상/임계값 보존 |
| 4 | `CameraController`, ImageProxy 변환·AnalyzerShutdown·LatestUiUpdateDispatcher | Device App 카메라/화면 수명에 연결, KEEP_ONLY_LATEST·Preview 토글·drain 후 close 보존 |
| 5 | warning 정책·cooldown·WarningOutputController·출력기 | Gotoro Device App 출력·권한·수명에 연결. 기존 출력/중단 정책부터 동등성 확인 |
| 6 | `TofMeasurementSource`·TofMeasurement·거리 결과/직렬화 계약 | 현재는 null/NOT_SUPPORTED 유지. UART·동기화·매칭은 별도 사양/검증 후 작업 |
| 7 | 필요 시 settings/metrics/evaluation/mlkit | Device App 설정과 명시 매핑. 테스트 앱 UI·ScreenRecordService·비교 SDK·진단 로그 전체를 필수 core로 복사하지 않음 |

이번 작업에서는 새 모듈·인터페이스·라이브러리를 만들지 않았다. 실제 이식에서 검증된 경계를 먼저 옮기고, `MainActivity` 전체를 복사하기보다 기존 pipeline 결과를 Device App의 UI/출력에 연결한다.

## 7. 추가 확인·미검증

| 항목 | 상태·후속 확인 |
| --- | --- |
| `arcore_depth.tar` | 원본 보존. UTF-16으로 잘못 저장된 archive인지, 독립적인 원본이 있는지 확인 전 삭제/재인코딩 금지 |
| `DetectionEvent` DTO | Device App 통신 계약 채택 여부 확인. 현재 미참조지만 이번에는 보존 |
| Ground-plane 추정 코드 | 현행 실행 경로 없음. 관련 설정/JSON 호환과 단위 테스트를 유지할지 별도 결정한 뒤 추가 정리 |
| Activity 잔여 수명 orchestration | ML Kit 취소/lease, 녹화 handshake·종료 drain은 상호 의존성이 있어 대규모 이동 유보. 테스트 앱 전용 경계는 현재 패키지로 구분 |
| 실측 성능 | 장시간 FPS·GC·배터리/발열 A/B·성능 향상률 미측정. source 동일성/회귀 통과가 실제 속도 동등성의 실측 증거는 아님 |
| 장시간 자동 종료 테스트 | 기존 3분×2 자동 종료 계측 시나리오는 이번 실행에서 제외. JVM PerformanceTestWindow·성능 로그 종료/저장 및 실기기 수동 종료 경로는 검증 |
| 물리 출력·녹화/캡처 E2E | 자동화는 물리 음성/진동/beep를 끄고 실행. MediaProjection 사용자 승인 포함 실제 영상·갤러리·외부 공유 앱 수신은 수동 확인 필요 |
| 기존 deprecation/Lint 경고 | 동작을 바꾸는 API 교체는 이번 범위에서 제외. 최종 경고 차이는 결과와 함께 기록 |
