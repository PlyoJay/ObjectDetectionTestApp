# ML Kit Detector 모드 선택 및 비교

기준: 최신 로컬 HEAD `5ad6d93`와 이번 작업 diff, 2026-10-23 (Asia/Seoul). 시작 시 작업 트리는 깨끗했다. 커밋/푸시는 하지 않았다. 기존 Bitmap 최적화, 진단 파일 내보내기 및 [메인 화면 검증 문서](mlkit-main-screen-logging-and-validation-20261023.md)는 보존했다.

## 1. 변경 사항

**설정 → 탐지 필터 및 경고 → ML Kit → ML Kit Detector Mode**에서 `STREAM_MODE` / `SINGLE_IMAGE_MODE`를 선택한다. **적용 및 저장** 후 기존 설정 적용 경로로 안전하게 재생성한다. 기본값은 STREAM_MODE이고 자동으로 SINGLE_IMAGE_MODE로 바꾸지 않는다. ML Kit Detection OFF에서는 기존 간격 입력과 같이 모드 선택도 비활성화한다.

| 파일 (`app/src/` 기준) | 주요 변경 |
|---|---|
| `main/.../mlkit/MlKitDetectionMode.kt` | 순수 Kotlin enum, 저장/표시/로그 이름 공통화 |
| `main/.../settings/AppSettings.kt` | `MlKitSettings.mode=STREAM_MODE`, schema 7 |
| `main/.../settings/AppSettingsStore.kt` | `mlKit.mode` 저장/복원, 누락/빈 값/알 수 없는 값은 STREAM_MODE |
| `main/.../settings/SettingsActivity.kt` | 선택 항목, draft/복원/적용/요약/활성 상태 연결 |
| `main/.../mlkit/MlKitObjectDetector.kt` | 선택 모드로 SDK options 생성, immutable `mode`, 요청/완료 로그의 실제 모드와 지연, 중복 close 방지 |
| `main/.../MainActivity.kt` | 적용 모드의 Detector 생성, 실제 runtime 모드 전달, 기존 Task/lease/Detector 종료 후 설정 재시작 |
| `main/.../mlkit/MlKitDiagnosticLogRecorder.kt` | runtime Detector 모드를 헤더와 헤더 CONFIG에 기록 |
| JVM/Android 테스트 | 모드 round-trip/기존 설정 호환, SDK options, 헤더, 안전한 모드 전환, 실제 SDK, 메인 기록 검증. `SettingsLayoutWait.kt`는 테스트에서 카드 스크롤/레이아웃 완료 대기만 제공 |

기존 schema 6 이하 설정은 모든 다른 값을 보존한 채 새 키의 기본값 STREAM_MODE로 읽으며, 기존 store의 migration 규칙으로 schema 7을 저장한다. 사용자 프리셋/백업·복구도 같은 codec을 사용한다. 설정 편집으로 프리셋과 달라지면 기존 규칙대로 이름이 CUSTOM으로 바뀔 수 있다.

ROI 설정값·좌표·640×360 stretch/Bitmap 재사용·1500ms 기본 간격·multiple objects·classification 기본 OFF·Overlay stale/BBox 변환·YOLO 모델/추론/NMS·경고/TTS·P0 FRAME/LATENCY 형식은 변경하지 않았다.

## 2. 모드 전환과 비동기 안전성

```text
적용 및 저장 → restartForSettings (재진입 차단)
  → 새 UI update 차단 / 이전 Overlay clear (기존 동작)
  → CameraX unbind 및 Analyzer 종료 대기
  → captureWork children join (ML Kit SDK Task terminal까지 NonCancellable await)
  → captureWork parent cancelAndJoin (늦은 child도 남기지 않음)
  → Bitmap lease 반환 확인 가능한 Job 종료 후 입력 buffer close
  → 이전 ML Kit Detector close
  → 이전 source의 진단 TXT END/flush/close 완료
  → 이전 YOLO Interpreter close (기존 순서의 해제)
  → Activity recreate → 새 input buffer / 선택 모드 SDK Detector 생성
```

기존 코드는 설정 재시작에서 SDK 작업을 기다렸지만 ML Kit client의 close는 onDestroy의 후속 작업에 남겨 두었다. 이번에는 **recreate 전에** 명시적으로 닫는다. `MlKitObjectDetector.close()`는 AtomicBoolean으로 한 번만 SDK에 전달하여 뒤이은 onDestroy 정리가 중복 close하지 않는다. `MlKitInputBuffer`의 단일 lease/재사용/recycle 구현은 수정하지 않았다.

SDK Task가 끝나지 않으면 기다린다. timeout으로 강제로 입력을 반환하거나 재활용하지 않는다. 성공/실패/SDK 취소 모두 기존 detect의 완료/예외 처리와 Job completion을 거쳐 lease를 반환한다. Analyzer 종료 또는 자원 종료가 실패하면 새 Detector를 만들지 않고 기존 화면에 종료 실패를 안내한다. 일반 Activity 종료에서도 같은 capture parent drain을 사용한다. OS의 강제 프로세스 종료는 기존 `.open` 복구 규칙을 유지한다.

모드는 Detector 객체의 읽기 전용 값이며 실행 중에 options만 바꿔 끼우지 않는다. 이전 Task는 이전 객체/입력만 참조하고, 새 Activity는 별도 입력 buffer를 가진다. 중간에 설정 draft를 변경해도 실제 적용 전 SDK 모드/로그가 바뀌지 않는다.

## 3. 진단 파일과 비교 항목

헤더에는 적용 snapshot의 `mlKit.mode`와 별도로 **실제 생성한 client의** `sdkMode`를 기록한다. MainActivity가 client 생성 후 immutable `mode`를 process recorder에 전달한다. 활성 Main Detector가 없는 독립 설정/테스트 상황은 값을 추측하지 않고 `UNKNOWN_NO_ACTIVE_DETECTOR`라고 기록한다.

예시:

```text
sdkMode=SINGLE_IMAGE_MODE
mlKit.mode=SINGLE_IMAGE_MODE
debug.enableMlKitDiagnostics=false
recording.temporaryDiagnostics=true recording.effectiveDiagnostics=true
[MLKIT_DIAG] ... stage=CONFIG ... sdkMode=SINGLE_IMAGE_MODE
[MLKIT_DIAG] ... stage=SDK event=PROCESS_REQUEST input=640x360 rotation=0 mode=SINGLE_IMAGE_MODE
[MLKIT_DIAG] ... stage=SDK event=TASK_COMPLETE outcome=SUCCESS mode=SINGLE_IMAGE_MODE elapsedMs=30.95 sdkCount=4 boxes=...
```

`elapsedMs`는 `detector.process()` 호출 직전부터 완료 callback 관측까지의 monotonic 경과 시간이다. 모델 초기화/스케줄링/일부 wrapper·callback 비용을 포함하며 순수 native kernel 시간은 아니다. 기존 P0 측정값과 형식은 그대로다. ML Kit 로그가 없더라도 P0가 켜진 경우의 기존 Task completion 계측은 유지된다.

비교할 값은 성공 요청 수, `sdkCount`, 양성 요청 비율, `boxes`와 MAPPED 좌표, `elapsedMs`, ERROR/CANCELLED, lease 반환, UI/Overlay 결정이다. SDK SUCCESS/0은 오류와 구분한다. SDK count가 양성이어도 결과가 stale timeout을 넘으면 기존 Overlay 정책상 표시되지 않을 수 있다. 모드 비교를 위해 timeout이나 ROI를 자동 변경하지 않는다.

메인 **ML Kit 로그 시작/종료/공유**와 Diagnostics OFF 임시 활성화는 기존 기능을 사용한다. 기록·공유 writer/queue/flush/보관 정책을 복제하거나 변경하지 않았다. P0 성능 로그와 ML Kit 파일 기록의 기존 UI 상호 배제도 유지한다.

## 4. 실제 휴대폰 SDK 비교

기기: 연결된 SM-S921N / Android API 36. 원본 앱을 보호하기 위해 `.mlkitmode` applicationIdSuffix의 별도 APK를 사용했다. 원본 앱 설정의 테스트 전후 SHA-256이 `266027da29d02347af463f3834516406a7bd7e67b59b0f10fea3cae94ff80900`으로 동일했다. 검증 후 두 분리 테스트 패키지를 제거했다. 일반 package APK는 빌드만 했으며 원본 앱을 덮어쓰지 않았다.

`MlKitSelectedModeSdkTest`는 **production `MlKitObjectDetector(mode)` 생성자와 실제 SDK**를 사용한다. Google 공식 신발 fixture에서 top=.20 / left=.10 / right=.10 ROI를 기존 `MlKitInputBuffer`로 640×360 변환했다. 같은 target과 lease를 양쪽 비교가 끝날 때까지 유지하고 매 요청 후 모든 픽셀이 그대로인지 검증했다. STREAM 먼저 4회, SINGLE 다음 4회이며, 같은 client 내에서는 완료 후 1500ms를 기다렸다. 실제 카메라의 시작 시각 throttle을 완전히 재현한 시험은 아니다. 새 이미지 저장 기능을 추가하지 않았다.

| 모드 | outcome | 요청별 sdkCount | 요청별 elapsedMs |
|---|---|---|---|
| STREAM_MODE | 모두 SUCCESS | 0, 0, 0, 0 | 81.707344, 31.405976, 6.947188, 40.462539 |
| SINGLE_IMAGE_MODE | 모두 SUCCESS | 4, 4, 4, 4 | 66.804336, 33.852774, 29.146289, 30.954609 |

SINGLE의 네 요청은 모두 다음 BBox를 반환했다. 좌표는 640×360 입력 기준이며 trackingId는 null이었다. STREAM의 BBox는 없었다.

```text
[300,9][504,323]
[0,30][331,250]
[475,3][639,214]
[163,1][352,145]
```

이 기기의 이 정적 입력에서는 SINGLE_IMAGE_MODE 양성과 STREAM_MODE의 SUCCESS/0을 실제 SDK로 확인했다. FakeDetector 결과를 인식 정상화의 근거로 쓰지 않는다. 일반 실장면의 정확도나 STREAM 내부의 0개 원인을 확정한 것은 아니며, 각 4회의 시간은 성능 벤치마크/평균·P95 보장 자료가 아니다. 초기화·전력·온도 조건 영향도 있다.

로컬 원시 결과: `build/mlkit-mode/physical-sdk/results.tsv`, `build/mlkit-mode/physical-sdk/events.txt`, `build/mlkit-mode/sdk-regression.txt`. fixture 출처/라이선스와 SHA-256은 기존 `app/src/androidTest/assets/mlkit_validation/README.md`를 따른다. `build/` 결과는 Git 추적 대상이 아니며 핵심 수치는 이 문서에 보존했다.

## 5. 테스트 결과

- 전체 JVM: **186개 통과** (기존 184 + 모드 설정 호환/헤더 세션 분리 2개).
- Python 성능 로그 분석 회귀: **26개 통과**.
- 실기기 SDK/Bitmap/파일/YOLO 회귀: **23개 통과**. 실제 SDK 1, wrapper 11, buffer 6, 파일 1, YOLO 두 모델/parser/NMS 4.
- 실기기 생명주기/설정/메인 UI: **18개 개별 항목 최종 통과**. Task 성공/오류/취소 중 모드 전환 3, 메인 로그 2, 설정 모드/전체 컨트롤 2, Overlay 9, 설정 로그 컨트롤 1, 작은 화면 레이아웃 1. 위 23개와 합쳐 **Android 41개 개별 항목 통과**다.
- 일반 `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --offline`: **BUILD SUCCESSFUL**. 정상 applicationId `com.samin.objectdetection`과 테스트 `com.samin.objectdetection.test`를 metadata로 확인했다. APK: `app/build/outputs/apk/debug/app-debug.apk`.
- `git diff --check`: 통과. 커밋/푸시 없음, HEAD `5ad6d93` 유지.

18개 UI/생명주기 시험의 첫 실행은 14개 통과/4개 실패였다. 설정 카테고리의 `scrollTo(), click()` 연속 실행에서 스크롤 애니메이션이 탭을 소비해 대상 페이지가 GONE인 것이 실패 화면/상태로 확인됐다. 테스트에서 실제 카드의 접근성 설명을 선택하고 스크롤 후 500ms 및 대상 control 레이아웃을 기다리도록 보완했다. 실패한 4개는 모두 재실행하여 통과했다(`build/mlkit-mode/ui-final.txt`, 4 tests). 제품 UI 코드는 이 시험 보완으로 변경하지 않았다.

| 검증 항목 | 결과 |
|---|---|
| 모드 설정 draft/Activity 재생성/저장/재진입/custom preset | 통과. 모드와 기존 CUSTOM 프리셋 이름 규칙 외 설정 동일 |
| 기존 전체 설정 컨트롤 | 72개 접근 가능. 모드 항목 및 이전 테스트 기대 목록에서 누락됐던 Diagnostics 항목 포함 |
| Diagnostics OFF 메인 원터치 기록 | STREAM + Preview ON, SINGLE + Preview OFF 모두 통과. 임시 opt-in만 적용 |
| 상태/경과 시간/반복 시작·종료/중복 시작 방지 | 통과. 파일명 분리, END/droppedLines, 설정과 메인 상태 일치 |
| TXT/header/PROCESS_REQUEST/SDK count/elapsedMs | 통과. 실제 모드와 적용 snapshot 일치 |
| 파일 공유 | Chooser/ACTION_SEND/URI와 읽기 접근, 파일 내용 확인 통과. 다른 앱에 실제 전송 완료는 미검증 |
| 기록 중 Activity 재생성/정상 종료/재실행 | 통과. 이전 파일 종료, 재실행 시 Diagnostics 설정 OFF 보존 |
| 성능 로그/YOLO | 기존 상호 배제와 P0 FRAME/LATENCY 파일 생성, 기존 모델/parser/NMS 시험 통과 |
| Overlay/좌표/Bitmap | 기존 stale gate와 Preview 양쪽 작은 화면 레이아웃, ROI/lease/pixel 무변경 시험 통과 |

`MlKitModeChangeLifecycleTest`의 제어 Task는 자원 순서만 검증한다. Task 미완료 동안 mode apply를 요청해도 이전 SDK가 닫히거나 입력이 recycle되지 않아야 한다. Task 성공/오류/취소를 해제한 뒤에만 입력 recycle/이전 SDK close/새 SINGLE client가 나타나는지 확인한다. 실제 인식은 위 별도 SDK 시험으로 구분한다.

초기 고정 이미지 SDK 시험 후 사용자가 잠금을 해제한 상태에서 카메라/설정 UI를 실행했다. 테스트 창의 KEEP_SCREEN_ON은 테스트에만 적용했으며 휴대폰의 화면 timeout/애니메이션 설정은 바꾸지 않았다. 원본 앱에는 시험을 위한 설정을 저장하지 않았다.

실행 명령의 핵심은 다음과 같다. 실기기 시험 APK에만 `build/mlkit-mode/isolated-tests.init.gradle`로 `applicationIdSuffix='.mlkitmode'`를 적용했다.

```text
gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --offline
python -m unittest discover -s tools -p test_*.py
adb -s R3CX506B3RH shell am instrument -w -r -e class <위 시험 클래스/메서드 목록> com.samin.objectdetection.mlkitmode.test/androidx.test.runner.AndroidJUnitRunner
```

## 6. 실기기에서 같은 조건으로 비교하기

1. 새 일반 APK를 설치하고 기존 ROI·1500ms·Preview·Overlay 및 YOLO 설정을 확인한다. 다른 조건은 변경하지 않는다. Diagnostics 기본 OFF를 그대로 둬도 된다.
2. **설정 → 탐지 필터 및 경고 → ML Kit → ML Kit Detector Mode → STREAM_MODE → 적용 및 저장**한다. 메인 화면이 돌아올 때까지 기다린다.
3. 같은 물체·거리·조명·카메라 방향을 고정한다. 메인 **ML Kit 로그 시작**으로 일정 시간(예: 1분) 기록한 후 **ML Kit 로그 종료 → ML Kit 로그 공유**한다.
4. 같은 설정 항목에서 **SINGLE_IMAGE_MODE → 적용 및 저장**하고, 같은 장면에서 같은 시간 기록/종료/공유한다. 필요하면 STREAM으로 돌아가 A/B/A로 반복한다.
5. 각 TXT에서 헤더/PROCESS_REQUEST/TASK_COMPLETE의 모드가 일치하는지 확인하고, `sdkCount`·BBox·`elapsedMs`·오류 및 Overlay stale 결정을 비교한다. 첫 요청의 초기화 지연은 따로 본다. 처리 시간이 길면 요청 수가 달라질 수 있으므로 총 검출 합계뿐 아니라 요청당 count와 양성 비율도 비교한다.

모드 변경 전에 기록을 명시적으로 종료하는 편이 파일 구간을 해석하기 쉽다. 기록 중 적용하더라도 이전 Task와 파일을 끝낸 뒤 재생성하며, 새 모드에서는 **로그 시작을 다시 눌러 별도 파일**로 기록한다. 모드 변경만으로 로깅 설정을 영구 ON으로 만들지 않는다.

## 7. 남은 확인 사항

- 다양한 실장면/장시간/조도·속도별 정확도와 모드별 성능 분포는 미검증이다. 이 기능은 비교 도구이며 SINGLE의 개선을 보장하지 않는다.
- 모든 Android 통합 테스트/벤치마크를 실행한 것은 아니다. 실제 전원 차단/OS 강제 종료/저장소 고갈 전체 통합, 외부 앱 전송 완료, 다른 기기/OS는 별도 검증 대상이다.
- SINGLE에서 SDK 양성이나 노란 BBox가 안 보이면 sdkCount와 MAPPED/UI/OVERLAY 로그를 먼저 나누어 본다. 기존 stale/Preview/표시 gate는 바꾸지 않았다.
