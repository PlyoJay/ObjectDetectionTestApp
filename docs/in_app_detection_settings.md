# 인앱 객체 인식 설정 분류

이 문서는 기존 기본값을 기준으로 인앱 설정에 노출한 값과 적용 방식을 정리한다. 현재 구현은 설정을 일괄 검증·저장한 뒤 Activity를 재생성한다. 따라서 실행 중 프레임, Temporal 상태, Motion history, Interpreter, CameraX가 서로 다른 설정을 공유하지 않는다.

## 설정 화면 탐색

기존의 프리셋·모델 정보·모든 설정이 한 ScrollView에 있던 화면을 **카테고리 목록 → 5개 상세 화면**으로 분리했다. 목록에는 아이콘, 제목, 설명과 현재 적용값 요약을 표시한다. 프리셋 관리는 목록 아래에 유지한다. ‘적용 및 저장’, ‘초기화’, ‘취소’는 모든 화면에서 사용할 수 있다.

| 카테고리 | 항목 수 | 기존 설정 항목 / AppSettings 필드 |
|---|---:|---|
| 카메라 및 입력 | 4 | Camera Resolution (`camera.requestedWidth`, `requestedHeight`), ROI (`camera.useCenterSquareCrop`), Detect Interval (`camera.detectIntervalMs`), Resize Mode / Letterbox (`yolo.resizeMode`) |
| YOLO 모델 및 추론 | 4 | Interpreter Thread Count (`yolo.interpreterThreadCount`), Confidence Threshold (`confidenceThreshold`), NMS Threshold (`nmsThreshold`), Max Candidates (`maxCandidates`); 모델명·input/output tensor shape/type·SHA256 읽기 전용 정보 |
| 탐지 필터 및 경고 — 객체 필터 | 11 | Size Filter Mode (`filters.sizeFilterMode`), Geometry Filter (`geometryFilterEnabled`), Min Box Area/Width/Height Ratio (`minBoxAreaRatio`, `minBoxWidthRatio`, `minBoxHeightRatio`), Bollard Min Area / Max Width/Height (`bollardMinAreaRatio`, `bollardMaxWidthToHeightRatio`), Adaptive Temporal (`adaptiveTemporalEnabled`), Temporal Immediate/Confirmation Confidence / Match IoU (`temporalImmediateConfidence`, `temporalConfirmationConfidence`, `temporalMatchIouThreshold`) |
| 탐지 필터 및 경고 — Motion / Tracking | 9 | Max/Min History Size (`motion.maxHistorySize`, `minHistorySize`), Max Match Distance Ratio (`maxMatchDistanceRatio`), Min Sample Interval (`minSampleIntervalMs`), Stale Track Timeout (`staleTrackTimeoutMs`), Min Absolute/Relative Area Change (`minAbsoluteAreaChange`, `minRelativeAreaChangeRatio`), Min Absolute/Relative Height Change (`minAbsoluteHeightChange`, `minRelativeHeightChangeRatio`) |
| 탐지 필터 및 경고 — ML Kit | 2 | ML Kit Detection (`mlKit.enabled`), Detection Interval (`detectionIntervalMs`) |
| 탐지 필터 및 경고 — Warning / Feedback | 12 | Warning Cooldown (`warning.cooldownMs`), Ignore Top Ratio (`ignoreTopRatio`), Max Guide Object Count (`maxGuideObjectCount`), Very Near/Near/Mid Height Ratio (`veryNearHeightRatio`, `nearHeightRatio`, `midHeightRatio`), Very Near/Near/Mid Area Ratio (`veryNearAreaRatio`, `nearAreaRatio`, `midAreaRatio`), 실제 진동/Beep/TTS (`enableActualVibration`, `enableActualBeep`, `enableActualTts`) |
| 성능 측정 및 로그 | 2 | System Performance Diagnostics (`performanceLog.systemDiagnosticsEnabled`), Sample Interval (`sampleIntervalMs`); Performance Log 설명 및 최근 로그·입력 PNG ZIP 공유 |
| 디버그 및 고급 설정 — Debug / Logging | 8 | Enable Detector Diagnostics (`debug.enableDetectorDiagnostics`), Debug Detection Logging (`debugDetectionLogging`), Diagnostic Raw Confidence Threshold (`diagnosticRawConfidenceThreshold`), Diagnostic Max Raw Candidates (`diagnosticMaxRawCandidates`), Debug Save Inference Input (`debugSaveInferenceInput`), Debug Save Interval (`debugSaveIntervalMs`), Debug Save On Detection (`debugSaveOnDetection`), 기존 호환 Debug Image (`legacyDebugImageEnabled`) |
| 디버그 및 고급 설정 — Overlay / Display | 7 | Overlay (`overlay.enabled`), Overlay Debug Mode (`debugMode`), YOLO/ML Kit bbox (`showYoloBoxes`, `showMlKitBoxes`), Confidence/FPS 표시 (`showConfidence`, `showFps`), Overlay Stale Timeout (`staleTimeoutMs`) |

총 **59개 편집 컨트롤(60개 설정 필드, 해상도는 폭·높이를 함께 선택)**을 모두 유지한다. 기존 화면에 별도의 Performance Log ON/OFF 설정은 없으며, 기록 시작·종료 버튼은 카메라 화면에 그대로 있다. Detailed Debug에 해당하는 상세 로그·진단 및 Overlay Debug Mode도 기존 항목을 그대로 사용한다. 새 설정이나 내부 상수를 추가하지 않는다.

- 상세 화면의 시스템 뒤로 가기 또는 ‘설정 목록’은 목록으로 돌아간다. 목록에서 뒤로 가기 또는 어느 화면에서든 ‘취소’는 편집 종료다. 변경값이 있으면 기존의 ‘계속 편집 / 버리기’ 확인을 표시한다.
- 화면 이동은 설정을 파싱·검증·저장하지 않는다. 기존 폼을 같은 Activity 안에서 유지하며 각 화면의 스크롤 위치를 유지한다. Activity 재생성 시에도 현재 화면, 모든 입력 원문(빈 값 포함), 비활성 필드, 프리셋 선택과 스크롤 위치를 Bundle로 복원한다.
- ‘초기화’와 프리셋 선택은 편집값만 변경한다. ‘프리셋 저장/이름 변경/삭제’는 기존대로 사용자 프리셋 저장소에 즉시 반영되며, ‘취소’는 이 프리셋 관리 작업을 되돌리지 않는다. 실행 중인 적용 설정은 ‘적용 및 저장’에서 전체 검증 후 변경한다.
- 값 비교로 변경 여부를 판단한다. 포커스 이동, Spinner 초기 콜백, 원래 값으로 되돌린 입력은 변경으로 취급하지 않는다. 적용값이 같으면 재저장·CUSTOM 이름 변경·pending marker 생성을 하지 않는다.
- 기기가 현재 지원 목록에 제공하지 않는 저장 해상도도 선택지에 보존한다. CameraX의 실제 해상도 협상 동작은 그대로다.
- 시스템 진단 OFF 시 Sample Interval 입력을 비활성화하되 입력값을 유지·저장·복원한다. Motion의 Min Sample Interval과 시스템 진단의 Sample Interval은 서로 다른 항목이다.
- 긴 설명·스위치·선택값은 줄바꿈하며, 적용 버튼과 프리셋 관리 버튼을 세로로 배치한다. 화면 상·하단 시스템 바 및 키보드 영역을 피한다.

AppSettings, AppSettingsStore, schema v2, Properties 키, 프리셋 값, rollback/healthy 처리, MainActivity의 적용 후 재생성 경로는 수정하지 않았다. 설정 Activity가 재생성될 때는 적용 snapshot을 Bundle에서 복원하여 저장소의 rollback 상태를 추가로 진행시키지 않는다.

자동화 검증은 `SettingsActivityTest`에서 전체 항목 접근, 편집·적용·재진입, 시스템 진단 ON/OFF 및 간격 보존, 미완성 입력 복원·검증·취소, 변경 없는 탐색·적용, 지원되지 않는 기존 해상도 보존, 내장/사용자 프리셋 및 관리 동작, v1 이관·rollback 상태 보존과 스크롤 복원을 다룬다. 실제 기기 테스트 시 설정 preferences 전체를 백업·복원하며 카메라 파이프라인은 시작하지 않는다.

### 이번 변경 검증 결과 (2026-10-14)

- 기준: 원격 `main`과 동일한 `2b76f297333f887344f931d19d77e59eeff2d858`.
- 기존 컨트롤과 변경 후 컨트롤의 필드 이름을 비교하여 59개 모두 동일함을 확인했다. UI 테스트에서도 각 카테고리를 열고 59개 항목 모두 스크롤하여 접근을 확인한다.
- `testDebugUnitTest`: 25개 테스트 클래스, 96개 테스트, 실패/오류/건너뜀 0. 기존 기본값·codec round trip·v1 이관·프리셋·성능 진단 독립성 테스트를 포함한다.
- `assembleDebug`, `assembleDebugAndroidTest`: 성공. 기본 Interpreter Thread Count는 4로 유지한다.
- `SettingsActivityTest`: Android 16 / SM-S921N / 360dp 폭에서 8개 테스트 통과. 설정 화면 종료 후 재진입, Activity 재생성 및 저장소 재로드를 검증한다. 전체 앱 프로세스 종료 후 카메라 파이프라인 재실행이나 추론·녹화 성능 재측정은 이번 검증에 포함하지 않는다.
- 목록 및 5개 카테고리 캡처는 로컬 `app/build/reports/settings-ui/`에 저장한다. 상태바와 내비게이션 바는 밝은 화면에 맞는 어두운 아이콘을 사용한다.
- 실기기에서 기존 네이티브 의존성의 Android 16KB ELF 정렬 호환성 안내가 나타났다. 테스트 앱의 ‘다시 표시 안함’으로 안내만 닫고 검증했으며, 네이티브 의존성·모델·추론 코드는 수정하지 않았다.
- Gradle 기기 테스트 러너는 실행 후 테스트 설치 앱을 자동 제거했다. 최종 검증은 아래의 직접 실행 방식으로 수행하여 설치 앱을 유지했다. 테스트 실행 전부터 있던 기기 앱 데이터의 보존 여부는 확인하지 못했으므로, 개인 기기의 기존 데이터를 검증하려면 별도 백업 또는 테스트 전용 기기를 사용한다. 저장 형식 호환성은 테스트 fixture로 검증했다.

```powershell
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
./gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest --offline
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e class com.samin.objectdetection.settings.SettingsActivityTest -e settingsScreenshots true com.samin.objectdetection.test/androidx.test.runner.AndroidJUnitRunner
```

기기는 화면이 켜지고 잠금이 해제된 상태여야 하며, 시스템 안내가 앱을 가리지 않아야 한다. `settingsScreenshots`는 선택 인자로, 앱의 테스트 캐시에 화면 캡처를 저장한다. 최종 직접 실행 로그는 `app/build/reports/settings-ui/instrumentation.txt`에 보관했다.

## 변경 영향 분류

| 분류 | 설정 |
|---|---|
| 실시간 반영 가능 | 출력 진동/Beep/TTS, Overlay 표시/세부 표시, debug log 스위치, cooldown 및 안내 개수 |
| Pipeline 재생성 필요 | ROI, size/geometry/temporal filter, motion history/threshold, warning proximity threshold |
| Interpreter 재생성 필요 | resize mode, confidence, NMS, thread count, max candidates, detector diagnostics |
| CameraX 재생성 필요 | 요청 해상도, detect interval |
| 읽기 전용 | 모델명/SHA256, tensor shape, 실제 분석 해상도, 앱 버전 |
| 내부 상수 | tensor parser/shape 계약, class/label 정책, bbox 수식, 표현 및 queue 상수 |

실시간 반영이 가능한 값도 세션 원자성을 위해 현재 1차 구현에서는 다른 값과 동일하게 안전 재생성 경로를 사용한다.

## 적용 시 재생성되는 설정

- Camera: detect interval, Full Frame/Center Square ROI, 요청 분석 해상도
- YOLO: STRETCH/LETTERBOX, confidence, NMS, Interpreter thread count, max candidates
- Filter: size mode, 기본 bbox 면적/폭/높이 비율, Bollard geometry ON/OFF와 면적/종횡비, Adaptive Temporal ON/OFF와 confidence/IoU
- Motion: history 크기, 매칭 거리, 샘플 간격, stale timeout, 면적/높이 변화 기준
- Warning: cooldown, 상단 무시 영역, 최대 안내 객체 수, 높이/면적 근접도 기준
- ML Kit: ON/OFF와 실행 간격
- Debug recorder: diagnostics/logging/raw candidate 기준, 입력 PNG 저장 조건/간격, 호환 debug image

Interpreter thread 수, YOLO 전처리 및 threshold 변경은 detector를 다시 만든다. 요청 카메라 해상도와 detect interval 변경은 CameraX use case를 다시 만든다. Motion/Temporal 변경은 tracker/validator를 다시 만들어 이전 history를 버린다. 나머지 설정도 동일한 원자적 적용 경로를 사용한다.

## 출력과 화면 설정

- 실제 진동, Beep, TTS는 경고 후보 계산과 독립된 출력 스위치다.
- Overlay 전체, YOLO bbox, ML Kit bbox, confidence, FPS, debug mode, stale timeout을 설정할 수 있다.
- 출력 하나라도 비활성화되면 카메라 화면에 `TEST OUTPUT` 상태를 표시한다.
- 설정/빠른 설정 진입은 녹화 또는 성능 로그 세션 중 차단해 한 세션에 서로 다른 설정이 섞이지 않게 한다.

## 읽기 전용

- 모델 asset 이름과 SHA256
- 실제 모델 input tensor shape(640×640)
- 실제 카메라 분석 해상도
- 앱 버전

모델 input 크기와 tensor layout은 패키지의 TFLite 모델 및 parser 구현과 결합되어 있어 변경 설정으로 노출하지 않는다. 실제 분석 해상도는 기기의 CameraX 협상 결과이므로 요청 해상도와 구분해 성능 로그에 기록한다.
해상도 드롭다운은 후면 카메라의 Camera2 YUV 출력 크기만 표시하며, CameraX use-case 조합 결과가 요청값과 다를 수 있다.

## 내부 상수로 유지

- YOLO output tensor parser의 `xywh + class scores` 구조, channel order와 normalization
- LETTERBOX padding 색상(114, 114, 114)
- 모델/labels class count 일치 검사와 tensor 차원 검사
- bbox 좌표 유효성 검사, IoU 수식, buffer 크기 및 tensor element 크기
- per-class `ObjectTuningPolicyRegistry`의 label 목록, warning/voice 정책과 class별 confidence/size 기준
- Warning scenario별 메시지와 feedback 매핑
- Overlay paint, 글꼴, 색상, padding 같은 표현 상수
- 비동기 로그 queue/flush 크기와 analyzer shutdown 안전 timeout

이 값들은 모델 계약, 데이터 구조, 정책 정의 또는 단순 구현/표현 상수다. 필드테스트 중 임의 변경 시 parser 오류나 정책 의미 변경이 발생할 수 있어 인앱 설정 대상에서 제외했다.

## 프리셋

- `DEFAULT`: `DetectionConfig` 기본값 (LETTERBOX, 입력 640×640, confidence 0.20, NMS 0.45, size DISABLED)
- `FIELD TEST - STRETCH`: 기존 `DetectionConfig.fieldTest(STRETCH)` 조건
- `FIELD TEST - LETTERBOX`: STRETCH 프리셋과 resize mode만 다름
- `CUSTOM`: 빠른 설정 또는 임의 편집 결과
- 사용자 프리셋: 이름 지정 저장, 이름 변경, 삭제 및 재선택 지원

저장 형식은 schema version이 포함된 Properties snapshot이다. schema v2는 v1의 `DEFAULT` snapshot이 이전 기본값 전체와 같을 때만 resize를 LETTERBOX로 이관한다. 다른 값 하나라도 수정했거나 사용자 이름/`CUSTOM`/명시적 FIELD TEST - STRETCH를 사용한 설정은 STRETCH를 보존한다. v1에서 누락된 resize는 이전 STRETCH 기본값으로 읽은 뒤 이 조건을 적용한다. 이관한 적용 설정은 v2로 저장하며 사용자 preset과 복구 snapshot을 삭제하지 않는다. 기존 FIELD TEST를 새 진단 조건으로 사용하려면 프리셋을 다시 선택하고 적용한다.

숫자 파싱 실패나 검증 실패 시 안전 기본값을 사용한다. 새 설정 적용 후 런타임 구성요소 생성이 완료되기 전에 앱이 종료되면 다음 실행에서 이전 설정 snapshot으로 복구한다. 전체 모델/평가/갤러리 검증 절차는 [model-evaluation-reliability.md](model-evaluation-reliability.md)에 정리했다.
