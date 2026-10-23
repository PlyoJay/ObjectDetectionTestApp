# ML Kit Diagnostics TXT 저장 및 공유

작성일: 2026-10-23 (Asia/Seoul). 현재 checkout `9f182e0`와 기존 진단 기능의 작업 트리를 분석했다. 기존 [검출·표시 분석](yolo-mlkit-detection-visibility-analysis-20261023.md), [P1-1 Bitmap 최적화](yolo-mlkit-bitmap-optimization-p1-20261022.md), 성능 로그 및 측정 결과를 보존했다. 커밋·푸시는 하지 않았다.

## 1. 변경 전후 구조

변경 전에는 `MainActivity`에서 생성한 선택적 `MlKitDiagnostics`의 sink가 `Log.i("MlKitDiagnostics", line)`만 호출했다. SDK callback, ROI 입력, lease, 결과 매핑, UI 및 Overlay의 진단 이벤트는 Logcat으로만 확인할 수 있었다.

변경 후에는 **같은 sink**에서 Logcat 출력 후 `MlKitDiagnosticLogRecorder.record(sessionId, line)`에도 전달한다. Logcat을 조회하거나 파싱하지 않는다. 기존 `MlKitDiagnostics`, `MlKitObjectDetector`, Overlay의 이벤트 생성 방식은 이번 변경으로 수정하지 않았다.

```text
기존 ML Kit 진단 이벤트 (Analyzer / SDK callback / UI / onDraw)
  → MlKitDiagnostics의 기존 문자열 생성
  → Log.i (기존 출력 유지)
  → 기록 중이면 bounded enqueue / 중지 상태이면 즉시 반환
  → 전용 OrderedLogWriter → files/mlkit_diagnostics/*.txt

설정 화면 시작·중지·목록·삭제·공유 요청
  → process 소유 control executor (파일 관리)
  → 중지: enqueue 차단 → writer drain → END → flush / close
  → 공유: 완료 TXT를 cache/shared_logs에 복사 → 기존 FileProvider → ACTION_SEND
```

CONFIG, SKIP와 생략 사유, LEASE_ACQUIRED/RELEASED, PROCESS_REQUEST, TASK_COMPLETE의 SUCCESS/ERROR/CANCELLED, sdkCount, MAPPED, UI_SUBMIT/APPLY, OVERLAY_UPDATE/DRAW/CLEARED, 입력 Bitmap·ROI 메타데이터와 기존 session/frame/requestIndex 정보가 그대로 전달된다. 키 이름은 기존 출력의 `session`, `frame`, `requestIndex`를 유지한다. 기존 SKIP 억제 및 Overlay 진단 빈도 정책도 유지한다.

실행 시 CONFIG는 보통 기록 시작보다 앞서 출력되므로, 파일 헤더에 `origin=RECORDING_HEADER`인 CONFIG와 **기록 시작 시 적용된 전체 설정 snapshot**을 별도로 넣는다. 이 헤더 CONFIG는 SDK 실행 이벤트가 아니다. 중지/한도 도달 후 발생한 이벤트와 기록 시작 전 Logcat은 파일에 소급 수집하지 않는다.

## 2. UI 변경

기존 **설정 → 디버그 및 고급 설정 → Debug / Logging → ML Kit Diagnostics** 아래에 같은 스타일의 버튼과 상태 텍스트를 추가했다.

- ML Kit 진단 로그 기록 시작
- 기록 중지 및 파일 저장
- 최근 ML Kit 기록 보기: 최근 순 목록 → 앞 120줄 미리보기 → 공유/삭제
- 최근 ML Kit 로그 공유: 기록 중이면 먼저 중지·저장
- 기록 상태, 경과 분:초, 확인된 누락 줄 수, 최근 파일명·크기, 오류 표시

Diagnostics 토글은 기존 적용/저장 규칙을 따른다. 기록 버튼은 **현재 적용된 설정**을 사용하며 미저장 draft를 임의로 저장하지 않는다. 기본 OFF 정책을 유지한다. 진단 ON 상태에서도 시작 버튼을 누르기 전에는 파일을 기록하지 않는다.

## 3. 저장 및 보관 구조

| 항목 | 구현 |
|---|---|
| 원본 | 앱 내부 `filesDir/mlkit_diagnostics/`의 UTF-8 TXT |
| 파일명 | `mlkit_yyyyMMdd_HHmmss_SSS_<UUID 8자>.txt` (연속 시작 시 충돌 방지) |
| 헤더 | 시작 ISO 시각/Unix ms/monotonic ns, sourceSessionId, 적용 ML Kit·ROI·Preview 등 전체 설정 |
| 이벤트 | `unixMs=... elapsedNs=...` 접두사 + Logcat과 같은 진단 문자열 |
| 정상 종료 | END에 종료 시각·경과 시간·종료 사유·droppedLines·truncatedLines |
| 진행 표식 | `<파일명>.open`; 성공적으로 close한 뒤 제거 |
| 공유 복사본 | 기존 `cacheDir/shared_logs/`에 고유 이름으로 복사. 임시 파일을 완성한 뒤 rename |
| 권한 | 기존 `${applicationId}.files` FileProvider와 cache-path 재사용. 새 저장소 권한 없음 |
| 보관 | 원본 및 ML Kit 공유 복사본 각각 최근 20개 / 합계 50MiB / 7일. 시작·목록·종료·공유 작업에서 정리 |
| 단일 기록 | 최대 30분 또는 보수적으로 계산한 5MiB 한도에서 자동 중지 |
| 과도한 이벤트 | queue 256개, 행 길이 8192 UTF-16 문자 제한. 거절·잘림 수 기록 |

보관 정리는 `mlkit_*.txt`에만 적용한다. 기존 성능 로그, P0 공유 ZIP 및 다른 파일에는 적용하지 않는다. 현재 쓰는 파일은 정리 대상에서 제외되므로 기록 중에는 닫힌 파일 20개에 활성 파일 1개(최대 5MiB)가 일시적으로 더해질 수 있다. 종료 후 다시 정리한다. 삭제 버튼은 선택한 원본과 그 파일의 ML Kit 공유 복사본을 삭제한다. 이미지·비디오·원본 Bitmap을 새로 저장하지 않는다.

## 4. 변경 파일 및 함수

경로는 `app/src/` 기준이다.

| 파일 | 주요 변경 |
|---|---|
| `main/.../mlkit/MlKitDiagnosticLogRecorder.kt` (추가) | `start/record/stop`, runtime/source 종료, 목록/미리보기/삭제, `prepareShare`, 복구·보관 |
| `main/.../mlkit/MlKitDiagnosticLogs.kt` (추가) | applicationContext 기반 process singleton; Activity 참조 없음 |
| `main/.../MainActivity.kt` | 런타임 생성 시 적용 설정 전달, 기존 sink에 파일 enqueue 추가, 비동기 작업 drain 후 source 종료 |
| `main/.../settings/MlKitLogControls.kt` (추가) | 기존 debug 영역의 버튼·상태·목록·미리보기 및 비동기 작업 |
| `main/.../settings/SettingsActivity.kt` | 기존 Diagnostics 토글 아래 컨트롤 배치와 사용 안내 |
| `main/.../settings/LogShareManager.kt` | `shareMlKit`, `mlKitShareIntent`; 기존 성능 ZIP 공유 보존 |
| `test/.../mlkit/MlKitDiagnosticLogRecorderTest.kt` (추가) | writer·복구·한도·공유·OFF·독립성 테스트 |
| `androidTest/.../mlkit/MlKitDiagnosticExportTest.kt` (추가) | SDK 진단 sink와 완료 TXT, FileProvider·Intent 검증 |
| `androidTest/.../settings/MlKitLogControlsTest.kt` (추가) | 설정 화면 기록·재생성·중지·보기·공유 요청·OFF 검증 |

YOLO 모델/추론/파싱/NMS, ML Kit SDK 옵션과 STREAM_MODE, 1500ms 실행 간격, ROI·Bitmap 재사용, BBox 변환, stale timeout, 경고 및 출력 코드는 이번 기능을 위해 변경하지 않았다. `OrderedLogWriter`와 P0 FRAME/LATENCY 구현도 수정하지 않았다. 작업 트리에는 선행 진단 작업의 변경이 함께 있으므로 전체 HEAD diff와 이번 변경을 구분해야 한다.

## 5. 성능·스레드·생명주기 안전성

- **생산자**: 활성 기록 확인, 짧은 admission lock, 시간값 취득, bounded enqueue만 수행한다. Analyzer, SDK 완료 callback, Overlay onDraw에서 파일 I/O, 디스크 대기, 디렉터리 조회, 파일 인코딩을 수행하지 않는다. 파일용 타임스탬프 문자열 포맷은 writer에서 한다.
- **writer**: 기존 `OrderedLogWriter`를 별도 인스턴스로 재사용한다. 파일마다 전용 스레드로 순서대로 쓰고 각 행을 flush한다. P0 writer와 queue/executor를 공유하지 않는다. 큐가 찼을 때 생산자는 기다리지 않고 손실 수를 올린다.
- **control**: 단일 executor가 파일 생성·헤더·목록·복구·보관·삭제·공유를 직렬화한다. writer close/drain 대기도 이 스레드에서만 한다. 설정 Activity는 코루틴 IO에서 결과를 기다린다. 상태 폴링은 1초마다 메모리 상태만 읽는다.
- **중지·공유**: admission을 먼저 닫아 새 이벤트를 차단한다. 이미 수락한 이벤트 뒤에 END를 쓰고 flush/close한 다음 저장 완료를 알린다. 공유 파일은 이 과정과 완전한 복사가 끝난 후에만 URI를 만든다. 읽기 권한만 부여하고 ClipData에도 같은 URI를 넣는다.
- **Activity**: recorder는 process 소유다. 설정 전환/설정 Activity 재생성/화면을 떠나는 중의 작업은 파일 작업을 취소하지 않는다. MainActivity가 정상 종료될 때는 기존 Analyzer·ML Kit 비동기 drain 후 기록을 종료한다. Main 런타임이 새로 만들어지거나 적용 설정이 바뀌면 기존 기록을 닫으며, 이전 Activity의 늦은 완료는 새 source 기록을 닫지 못한다. 재시작 후에는 새 파일로 기록을 시작한다.
- **프로세스 강제 종료**: Android는 force-stop, crash, LMK 때 종료 callback을 보장하지 않는다. 이미 flush된 텍스트와 `.open`을 보존하고, 다음 목록/시작/공유 때 중단 종료 표시를 덧붙인다. 종료 시각과 손실 수는 `UNKNOWN`이며 임의로 0으로 표시하지 않는다. 정상 END가 있지만 표식 제거만 실패했다면 END를 중복 추가하지 않는다.
- **쓰기 오류**: 가능한 TXT를 보존하며 UI에 오류와 확인된 누락을 표시한다. 실패한 행은 부분 기록일 수 있으므로 전체 손실을 정확히 셀 수 없고, 복구 footer도 UNKNOWN을 사용한다. 파일 시스템 자체의 손상·영구 I/O 오류나 전원 차단 시 데이터 보존은 보장할 수 없다. flush는 fsync와 다르다.
- **OFF/중지**: 기존 Diagnostics가 OFF이면 이벤트 생성기 자체가 없으며, 기록 중지 상태의 `record`는 즉시 반환한다. 설정에서 저장 파일을 확인·공유하는 관리 작업은 OFF에서도 가능하다.

## 6. 테스트 결과

검증 기준과 실행 결과는 아래에 기록한다. 실기기 성능 개선이나 오버헤드 수치를 측정한 작업은 아니다.

### 자동 테스트

- 전체 JVM: **182개 통과 / 실패 0**. 추가 recorder 테스트 15개 포함.
- 기존 Python 성능 로그 분석 회귀: **26개 통과**.
- `:app:assembleDebug`, `:app:assembleDebugAndroidTest`: 성공. 기존 trace API deprecation 경고는 남아 있다.
- Android 통합: **27개 통과 / 실패 0** (Pixel_7 AVD, API 37 / x86_64 / 16KiB 페이지 환경). 별도 applicationId `.mlkitexporttest`로 설치하여 기존 앱과 데이터를 격리했다.
  - 추가 2개: 실제 SDK wrapper에 주입한 2개 객체의 진단/파일 메시지, UTF-8, sdkCount·MAPPED·Overlay 메타데이터, END, FileProvider 읽기, MIME/URI/ClipData/읽기 grant 및 내부 원본 경로 비공개 검증. 설정 UI 시작/중지, Activity 재생성 중 기록 유지, 최근 파일 미리보기, 공유 요청 Intent, 적용 설정 불변, OFF 시작 차단 검증.
  - 기존 25개: ML Kit wrapper 성공/실패/취소, 비동기 lease, 입력 Bitmap 픽셀·재사용·회전/ROI, Overlay 좌표/표시 gate/stale/clear 회귀.
  - 공유 화면 호출은 instrumentation monitor로 Intent를 검증하며 외부 전송은 차단했다. 테스트 프로세스에서 content URI를 읽었다. 별도 외부 앱 UID에서의 실제 수신을 검증한 것은 아니다.
  - 최초 실행은 emulator System UI ANR 창이 가린 상태여서 UI 1개 실패/나머지 26개 통과했다. 시스템 창을 정리한 뒤 최신 코드로 전체 27개를 재실행하여 통과했다. 기존 16KiB 네이티브 호환성 안내도 확인했으며 라이브러리를 변경하거나 영구 억제하지 않았다.

JVM 테스트에서 UTF-8/한글, Logcat sink와 파일 메시지 동일성, OFF 시 파일 미생성, start/stop idempotence, 중지 후 기록 차단, queue 포화/순서/손실 집계, IOException 보존, 런타임 변경/진단 OFF/이전 source 완료 및 시작 헤더 작성 중 런타임 전환 경합, 중단 복구의 UNKNOWN, 정상 END 중복 방지, 고유 파일명, 개수/용량/기간 보관, 삭제·경로 검증, 크기/시간 자동 중지, 공유 전 drain/완료 복사본, P0 writer 독립성을 검증했다.

재실행 명령 (이 환경의 JDK 21, Gradle offline cache 사용):

```powershell
./gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --offline
.venv/Scripts/python.exe -m unittest discover -s tools -p 'test_*.py'
# 별도 테스트 패키지로 빌드/설치한 후:
adb -s emulator-5554 shell am instrument -w -r -e class "com.samin.objectdetection.mlkit.MlKitDiagnosticExportTest,com.samin.objectdetection.settings.MlKitLogControlsTest,com.samin.objectdetection.mlkit.MlKitObjectDetectorTest,com.samin.objectdetection.mlkit.MlKitInputBufferTest,com.samin.objectdetection.ui.BoundingBoxOverlayTest" com.samin.objectdetection.mlkitexporttest.test/androidx.test.runner.AndroidJUnitRunner
```

테스트용 applicationIdSuffix는 ignored `build/mlkit-export/isolated-tests.init.gradle`로 주입했다. 저장소의 빌드 설정/기본 applicationId는 바꾸지 않았다. Android 결과는 로컬 `build/mlkit-export/instrumentation.txt`에 보존한다.

Android 통합 검증 후 최종 점검에서 세션 source의 스레드 간 가시성과 런타임 검증·종료 상태 전환의 admission lock 범위를 보강했다. 이 마지막 동기화 보강 후에는 전체 JVM 182개와 기본 applicationId APK/AndroidTest APK 빌드를 다시 통과했다. Android 27개는 이 동기화 보강 직전 APK의 실행 결과이며, 해당 APK로 에뮬레이터 재실행은 하지 않았다. 테스트 전용 패키지와 임시 에뮬레이터 세션은 정리했다.

### 미검증 및 해석 범위

- 현재 실기기 연결이 없어 이번 기능의 실기기 테스트는 수행하지 않았다. 이전 분석 문서의 실기기 결과를 이번 기능의 검증 결과로 전용하지 않는다.
- Gmail/메신저/파일 앱 각각에서 실제 전송 완료와 상대 앱의 URI 접근 시점은 별도 확인이 필요하다. 공유 Intent 생성과 테스트 중 읽기 성공만으로 외부 전송 완료를 보장하지 않는다.
- 실카메라 장시간 기록과 P0 기록 동시 실행, 실제 Android 강제 종료/저장 공간 부족/전원 손실 및 기종별 회전은 별도 확인이 필요하다. JVM 오류 주입·동시 writer 테스트와 실제 OS 전체 통합은 구분한다.
- 성능 오버헤드는 실기기에서 아직 측정하지 않았다. bounded queue로 디스크 지연이 추론에 직접 전파되지 않게 했지만, 진단 문자열 생성과 Logcat 및 디스크 쓰기의 CPU·전력 비용은 존재한다.

## 7. 휴대폰에서 기록·공유하는 방법

1. **설정 → 디버그 및 고급 설정 → Debug / Logging**에서 **ML Kit Diagnostics ON → 적용 및 저장**한다.
2. 같은 항목을 다시 열고 **ML Kit 진단 로그 기록 시작**을 누른다. 기록 중/경과 시간을 확인한다.
3. 뒤로 가기로 설정 목록과 카메라 화면으로 돌아가 테스트한다. 설정 화면이 카메라를 중지하는 기존 동작은 유지되므로 카메라 화면에서 시험해야 한다.
4. 같은 항목에서 **기록 중지 및 파일 저장**을 누르고 저장 완료를 확인한다.
5. **최근 ML Kit 로그 공유**를 누르고 Android 공유 메뉴에서 Gmail·메신저·파일 앱 등을 선택한다. 기록 중 바로 공유해도 중지·저장 후 공유한다.
6. 이전 파일은 **최근 ML Kit 기록 보기**에서 선택해 미리보기·공유·삭제한다. 테스트가 끝나면 Diagnostics를 OFF로 적용/저장한다.

파일 내용에서 헤더의 ML Kit/ROI/Preview 설정, CONFIG, PROCESS_REQUEST, TASK_COMPLETE의 `sdkCount`, MAPPED, UI 및 Overlay 이벤트, 마지막 END와 `droppedLines`를 확인한다. `sdkCount=0`은 SDK 결과가 0개라는 의미이며 이 내보내기 기능이 인식 결과를 수정하지 않는다.

## 8. 남은 위험 및 실기기 확인 사항

- 동일한 장면에서 기록 OFF/ON을 비교하여 P0 평균 FPS, 추론 평균/P95, 전체 처리 평균/P95, LATENCY의 ML Kit 대기·완료 및 UI·draw 지연을 확인한다. 기존 기준값을 성능 보장 목표로 사용하지 않는다.
- Preview ON/OFF 각각에서 파일 헤더 설정과 실제 SKIP/SDK/UI 경로를 확인한다. OFF에서는 Overlay draw가 없는 기존 동작을 정상적으로 해석한다.
- SDK 반환 0개 및 양성 결과를 각각 기록하고 sdkCount → MAPPED → UI_APPLY → OVERLAY_DRAW를 frame/session별로 비교한다. ERROR/CANCELLED와 LEASE_RELEASED도 확인한다.
- 기록 중 설정 화면 전환/재생성은 유지되는지, Main 재생성은 이전 세션을 닫는지, 다시 시작할 때 파일명이 달라지는지 확인한다.
- 강제 종료 후 다시 열어 미완료 기록의 UNKNOWN 표기와 읽기/공유를 확인한다. 정상 중지는 END가 있고 누락 0인지, 부하/오류 상황은 실제 누락·오류를 숨기지 않는지 확인한다.
- 공유 캐시는 Android가 지울 수 있고 오래된 파일은 보관 정책에 따라 삭제된다. 보존할 테스트 결과는 외부 앱으로 공유해 따로 보관한다.
