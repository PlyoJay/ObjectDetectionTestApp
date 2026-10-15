# XNNPACK 설정 및 카메라 성능 비교 지원

작업일: 2026-10-14 (Asia/Seoul).

YOLO 설정에 **XNNPACK 사용** Switch를 추가했다. 기본값은 ON이며 CPU 스레드와 독립적으로 저장·복원한다. 기존 Bulk Write, `best.tflite`, 클래스 매핑, confidence 0.2, NMS 0.45, Letterbox, Geometry Filter, ML Kit, Overlay/TTS와 추론 파서는 변경하지 않았다.

## 변경 파일

기준 경로: `app/src/main/java/com/samin/objectdetection/`.

| 파일 | 변경 |
|---|---|
| `settings/AppSettings.kt` | schema 3, `YoloSettings.useXnnpack=true`, DetectionConfig 매핑 |
| `settings/AppSettingsStore.kt` | `yolo.useXnnpack` Properties 저장·복원; 누락/잘못된 값은 ON |
| `settings/SettingsActivity.kt` | 기존 YOLO 카테고리에 Switch·설명 추가, draft/프리셋/적용 요약 연결, 현재 모델 정보 재사용 |
| `camera/DetectionConfig.kt` | CPU XNNPACK 요청 필드 추가 |
| `MainActivity.kt` | 설정을 detector 생성자로 전달, analyzer/Interpreter 종료 후 설정 재시작, 성능 로그 시작·종료 처리 중 설정 진입 방지 |
| `metrics/PerformanceLogFormatter.kt` | 요청값의 의미와 세션 고정 정책을 로그 header에 명시 |
| `test/.../AppSettingsTest.kt`, `PerformanceLogFormatterTest.kt`, `PerformanceLogSessionTest.kt` | 기본값·마이그레이션·CPU 독립성·세션 분리 검증 |
| `androidTest/.../SettingsActivityTest.kt`, `XnnpackRuntimeSettingsTest.kt` | Switch 복원·프리셋·rollback, 실제 CameraX 종료/재초기화·로그 검증 |
| `androidTest/.../DetectorCpuOptimizationTest.kt` | 기존 모델 회귀 비교를 CPU 2/4 × XNNPACK ON/OFF로 확장 |

앞선 병목 분석 작업의 수정이 아직 작업 트리에 함께 남아 있다. 위 표는 이번 설정 작업의 변경 범위다. 이번 작업에서 detector의 Bulk writer와 입력·출력 처리 구현을 추가 변경하지 않았다.

## 저장 및 적용 구조

```text
YOLO 모델 및 추론 → XNNPACK 사용 → 적용 및 저장
  → AppSettingsStore (SharedPreferences / Properties)
  → MainActivity의 설정 적용 결과 callback
  → 기존 analyzer 종료·drain → 기존 Interpreter.close()
  → Activity 재생성 → loadApplied → DetectionConfig.useXnnpack
  → VisionStyleYoloDetector(useXnnpack = ...)
  → Interpreter.Options.setUseXNNPACK(...)
```

- schema 1/2 설정은 기존 값과 프리셋을 보존하며 schema 3으로 읽는다. 새 키가 없으면 ON이다. 기존 v1 Resize migration도 유지했다. 사용자 프리셋의 OFF 값도 저장·복원된다.
- 미적용 편집값은 기존 draft 복원 방식에 따라 카테고리 이동·Activity 재생성 시 유지한다. 실제 실행 설정은 ‘적용 및 저장’ 이후 바뀐다.
- 기존 YOLO 요약에 `CPU Threads: 4 · XNNPACK: ON/OFF (요청)`을 표시한다. 모델은 기존 읽기 전용 영역의 `best.tflite` 정보를 사용한다. 별도 비교 화면은 만들지 않았다.
- 메인 화면에서 설정을 열 때 이미 로드된 모델의 shape/type/SHA를 전달해 메타데이터 표시용 Interpreter를 추가 생성하지 않는다. 설정 Activity를 독립 실행하면 기존 `Interpreter.use { ... }` 방식으로 메타데이터를 읽고 즉시 닫는다.

## 안전한 Interpreter 재생성

기존 Activity 재생성은 `onDestroy`의 비동기 종료와 새 `onCreate`의 초기화가 겹칠 수 있었다. 설정 적용 시에는 `restartForSettings()`가 새 프레임/UI 제출을 차단하고, 메인 스레드에서 CameraX unbind를 요청한다. 백그라운드에서 analyzer executor의 실제 종료와 실행 중 ML Kit/캡처 작업 완료를 기다린 다음 기존 detector를 닫고 Activity를 재생성한다. 메인 스레드에서 추론 종료를 기다리지 않는다.

중복 요청은 `destroying` guard로 무시한다. `CameraController.stopAsync()`와 detector `close()`는 기존 idempotent 정책을 유지하므로 후속 `onDestroy` 정리에서 네이티브 Interpreter를 두 번 해제하지 않는다. 종료 실패 시 기존 정책대로 Interpreter를 보존하고 새 인스턴스를 만들지 않으며 앱 재시작 안내를 표시한다. 새 초기화가 실패하면 기존 pending/attempting/backup rollback 정책을 유지한다.

빠른 설정도 동일한 재시작 경로를 사용하고, `yolo.copy(...)`가 XNNPACK 값을 보존한다. 별도의 GPU/NNAPI 실행 경로는 추가하지 않았다.

## 성능 로그 및 세션

- 기존 FRAME/INTERVAL/CUMULATIVE 형식을 유지했다. `xnnpackRequested`, `cpuThreads`, `requestedBackend`, `appliedBackend`, `delegateVerification`을 기존 execution 정보로 기록한다.
- `inferenceAvgMs`, `inferenceP95Ms`, `preprocessAvgMs`, `pipelineAvgMs`, `totalProcessingAvgMs`, 정확한 완료율 `inferenceFps`는 기존 interval/cumulative 집계를 사용한다. 기존 FRAME의 FPS와 getPixels/rgbWrite/resize 등 세부 필드도 유지한다.
- header에 `XNNPACK Requested: true/false`와 “CPU 요청값이며 위임 검증 또는 backend 변경을 뜻하지 않는다”는 설명을 추가했다. ON/OFF 모두 backend는 CPU이고 `delegateVerification=UNAVAILABLE_JAVA_API`를 유지한다.
- 녹화 또는 성능 로그 활성화 중에는 설정 진입을 막는 기존 정책을 유지했다. 로그의 비동기 시작·종료/drain 중에도 진입을 막도록 보완했다. 종료 후 설정을 적용하면 새 Activity의 session UUID, recorder, metrics collector를 사용한다. 로그를 자동 재개하지 않으며 다음 측정은 사용자가 새로 시작한다.

## 빌드 및 검증

검증 기기: Galaxy S24 `SM-S921N`, Android 16/API 36. 기기는 사용자가 잠금을 해제한 상태에서 테스트했다.

| 검증 | 결과 |
|---|---|
| Debug 앱·androidTest APK 빌드 | 성공 |
| 기존 전체 단위 테스트 및 신규 테스트 | 104개 통과, 실패/오류 0 |
| 설정 UI·draft·저장소·v1/v2 마이그레이션·프리셋·rollback | 10개 통과 |
| 실제 CameraX/Interpreter 재생성·성능 로그 | 1개 통과 (16.998초) |
| 모델 입력·raw 출력·기존 parser/NMS·Letterbox·버퍼 재사용 | 9개 통과 (31.028초) |
| 기존 Python 로그 분석 테스트 | 4개 통과 |
| 모델 SHA-256 | `32a44bc7432ef2d7ca5de802dd8e714a26568aea16566bad3fd7fcd2b85bcee2`, 기존과 동일 |

설정 UI 테스트는 OFF/ON 및 CPU 2/4 저장 후 새 저장소 인스턴스와 편집 화면 재생성·재진입에서 복원을 확인했다. CameraX 테스트는 UI 편집과 분리해 저장 후 실제 `restartForSettings()` callback을 실행했다. CPU 4 ON→4 OFF→2 OFF→2 ON→4 ON의 5개 실행 조건, 4회 재생성에서 새 detector 생성 전 이전 `closed=true` 및 analyzer 종료 완료를 확인했다. 이전 detector는 재사용 시 예외를 냈고, 새 camera inference는 오류 없이 성공했다.

각 조건에서 별도 성능 로그에 실제 camera 완료 프레임을 3개 이상 기록하고 drain한 뒤 header/FRAME/cumulative 필드를 읽어 설정·세션 ID가 섞이지 않음을 확인했다. 측정 중 설정 진입 차단도 확인했다. 테스트가 만든 MediaStore 로그만 삭제하고 기존 기기 설정·프리셋은 복원했다. 앱 전체 heap/native allocation 프로파일을 수행한 것은 아니며, 재생성 소유권·종료 순서와 기존 재사용 객체 회귀 검증에 근거한다.

동일 `known.png` 입력에서 CPU 2/4 × XNNPACK ON/OFF 모두 원래 division 입력 작성 및 기존 ON 기준 raw 출력/검출과 비교했다. 입력은 바이트 단위 동일, raw 출력은 절대 1e-5 + 상대 1e-4, confidence 1e-4, 좌표 0.1px 허용 오차를 통과했다. 양성/합성/크기 변경 입력의 Letterbox 및 반복 버퍼 검증도 유지했다.

로컬 결과는 Git ignored `build/xnnpack-settings-build.log`, `xnnpack-settings-instrumentation.log`(설정 UI 10개 통과 기록), `xnnpack-runtime-instrumentation.log`, `xnnpack-model-regression.log`에 보관했다. 중간 runtime 테스트의 일시적인 frameDiagnostics null 및 UI layout 대기 문제는 테스트 helper를 정리해 해결했고, production 결과/threshold를 변경해 테스트를 맞추지 않았다.

재현 명령:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest --offline
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class com.samin.objectdetection.settings.SettingsActivityTest,com.samin.objectdetection.settings.XnnpackRuntimeSettingsTest com.samin.objectdetection.test/androidx.test.runner.AndroidJUnitRunner
```

## 실제 기기 비교 방법 및 제한 사항

1. CPU 4, 모델/카메라 해상도/Confidence/NMS/Letterbox/Geometry/ML Kit/Overlay/TTS를 같은 값으로 고정한다. 상세 텐서·PNG 진단과 화면 녹화는 성능 비교 중 OFF로 고정하고 기존 성능 로그는 ON으로 측정한다.
2. XNNPACK ON으로 적용하고 워밍업 후 새 성능 로그를 시작한다. 같은 장면에서 일정 시간(예: 2분) 측정한 뒤 로그를 종료하고 저장 완료를 기다린다.
3. 설정의 XNNPACK 사용을 OFF로 바꾸고 적용한다. 요약과 로그 header/FRAME의 `xnnpackRequested=false cpuThreads=4 appliedBackend=CPU`를 확인한다. 같은 조건으로 새 로그를 수집한다.
4. ON을 재적용해 ON→OFF→ON 순서로 반복한다. CPU 2 비교는 별도 시험으로 구분한다. 발열·충전·절전·배경 앱 상태를 기록한다.
5. 정확한 `inferenceFps`, 추론 평균/P95, preprocess/pipeline/totalProcessing 평균과 세부 시간을 비교한다. 요청값만으로 실제 XNNPACK 위임을 판정하지 말고 모델 생성 PID/시간에 맞춘 native Logcat을 함께 확인한다.

실제 카메라에서 설정 재초기화와 로그 저장의 짧은 동작 검증은 수행했지만, 통제된 장시간 성능 비교를 수행한 것은 아니다. **이번 작업으로 FPS가 개선됐다고 확정하지 않는다.** OFF가 더 빠른 기기가 있을 수 있고, CPU kernel 차이로 confidence/NMS 경계의 검출 결과가 달라질 수 있다. 회귀 시험의 허용 오차 통과가 모든 이미지·클래스에서 동일 결과를 보장하지는 않는다. Java API로 위임 범위와 실제 worker 수를 조회할 수 없다는 기존 제한도 유지된다.
