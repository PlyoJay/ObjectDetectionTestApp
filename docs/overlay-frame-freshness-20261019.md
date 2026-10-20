# 보행 중 Bounding Box 위치 불일치: 프레임 표시 지연 개선

작성일: 2026-10-19 (Asia/Seoul). 분석 기준 커밋: `f3cf61d`.

**소스에서 확인한 문제는 촬영 후 경과 시간이 아닌 UI 갱신 후 경과 시간으로 박스를 만료시키던 구조다. 촬영 시각 기준 만료로 변경했다. 이 수정은 오래된 박스의 잔존을 제한하며, 모델 정확도나 보행 중 위치 정합 개선을 실측으로 입증한 것은 아니다.** 10월 16일의 약 1.35 FPS / 표시 시 평균 약 811ms는 사용자 제공 측정값이며 이번 작업에서 다시 측정하지 않았다.

## 1. 실제 데이터 흐름과 원인

아래 경로는 `app/src/main/java/com/samin/objectdetection/` 기준이다. 변경 전 동작은 기준 커밋, 링크의 행 번호는 수정 후 소스 기준이다.

```text
카메라 ImageProxy (imageInfo.timestamp)
  → CameraController: 프레임 ID, analyzer 수신 wall/elapsed 시각, timestamp 신뢰성 판정
  → ImageProxyExt: Bitmap 변환 + 회전, analyzer가 원본 Bitmap 소유
  → MainActivity.processBitmap
      ├─ ML Kit: 원본 Bitmap을 640×360으로 복사 → IO 비동기 탐지 → 원본 좌표 복원 → 별도 UI 갱신
      └─ YOLO: DetectionPipeline → ROI → 모델 입력 변환 → 추론 → 원본 좌표 복원 → 필터/경고 계산
          → FrameTrace에 완료 시각 기록 → 최신 결과만 UI에 전달 → BoundingBoxOverlay
  → onDraw: 현재 Preview 위에 FIT_CENTER로 박스 표시
```

- [CameraController.kt:108](../app/src/main/java/com/samin/objectdetection/camera/CameraController.kt#L108): 단일 analyzer executor, `KEEP_ONLY_LATEST`. 카메라가 `SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME`을 보고하고 timestamp가 수신 시각 이하의 양수일 때만 `captureTimestampReliable=true`다. 센서 촬영 시각은 기존에 수집되고 있었다.
- [MainActivity.kt:520](../app/src/main/java/com/samin/objectdetection/MainActivity.kt#L520): YOLO는 analyzer에서 동기 추론하고 ML Kit는 같은 원본에서 만든 축소 Bitmap으로 별도 실행한다. ML Kit의 실행 간격/처리 시간 때문에 화면에 남는 두 소스의 결과는 서로 다른 프레임일 수 있다.
- CameraX Preview는 추론 완료를 기다리지 않고 별도 surface에서 진행된다. Overlay는 추론에 사용한 이미지를 화면에 고정하거나, 그 이미지와 Preview를 프레임 단위로 맞추지 않는다. 따라서 **오래된 프레임에서 올바르게 검출한 박스도 최신 Preview에서는 바닥이나 빈 공간에 있는 것처럼 보일 수 있다.** 이것만으로 모델 오탐을 판정할 수 없다.

### 확인된 결함

| 확인 내용 | 변경 전 위치/동작 | 영향 |
| --- | --- | --- |
| 촬영 시각을 만료에 사용하지 않음 | `BoundingBoxOverlay.updateDetections/updateMlKitDetections`: `System.currentTimeMillis()`로 마지막 UI 갱신 시각 저장 | 이미 오래된 결과도 도착할 때마다 1,500ms 수명을 새로 받음 |
| 경계 시점에 정리 누락 가능 | `clearYoloIfStale/clearMlKitIfStale`: `age > timeout`이며 한 번만 예약 | 정확히 timeout에 실행되면 지우지 않고 재예약도 하지 않음. 다음 갱신/정리까지 잔존 가능 |
| draw 시 유효성 검사 없음 | `onDraw`: 저장된 리스트를 그대로 `freshDetections`로 사용 | main thread 지연이나 정리 누락으로 오래된 결과가 계속 그려질 수 있음 |
| 최신 UI 콜백을 이전 콜백으로 덮어쓸 경쟁 조건 | `MainActivity.postLatestDetectionUiUpdate`: `pending.get()?.let(::postLatestDetectionUiUpdate)` | pending B를 읽은 직후 C가 도착하면 B를 다시 `set`하여 C를 덮어쓸 수 있음. 기존에는 순서 방어도 없음 |
| ML Kit의 원본 프레임 정보 유실 | `maybeRunMlKitDetection`: 탐지 시작 wall 시각을 `frameTimestampMs`로 사용, Overlay에 `FrameTrace` 미전달 | 촬영 후 경과 시간과 두 소스의 프레임 차이를 판단할 수 없음 |
| 소스별 좌표 크기 공유 | `BoundingBoxOverlay`: YOLO/ML Kit가 공통 `frameWidth/frameHeight`를 덮어씀 | 현재 동일한 원본 크기에서는 문제가 드러나지 않을 수 있지만 비동기 크기 변경 시 다른 소스의 좌표 변환에 영향 |

UI 병합 경쟁 조건은 소스 분석으로 확인한 가능 경로이며, 10월 16일 보행 현상의 직접 원인으로 재현한 것은 아니다.

### 좌표 변환 검토

[MainScreenView.kt:28](../app/src/main/java/com/samin/objectdetection/ui/MainScreenView.kt#L28)의 Preview는 `FIT_CENTER`이며 Preview와 Overlay가 같은 부모에서 전체 크기를 차지한다. `ImageProxyExt`는 입력을 회전하고, `YoloResizeTransform`이 Letterbox padding/scale을 역변환한다. [DetectionPipeline.kt:221](../app/src/main/java/com/samin/objectdetection/pipeline/DetectionPipeline.kt#L221)은 ROI offset을 더해 회전된 원본 프레임 좌표를 복원한다. ML Kit도 축소 입력 좌표에 각각 X/Y 배율을 적용하여 원본 좌표로 복원한다.

이 경로에서 무조건적인 scale/offset 오류는 확인하지 못했다. Preview와 Analysis는 각각 resolution selector로 바인딩되고 공통 ViewPort나 Preview 출력 행렬을 직접 적용하지는 않는다. 실제 단말의 Preview crop/해상도/회전까지 일치하는지는 정지 장면에서 추가 확인해야 한다. 이번 수정에서는 CameraX 바인딩, 모델 전처리 및 Letterbox 설정을 변경하지 않았다.

## 2. 수정한 파일과 동작

| 파일 | 주요 변경 |
| --- | --- |
| `metrics/FrameTrace.kt` | `captureAgeMsAt()` 추가. 신뢰 가능한 센서 시각, analyzer 수신 시각, 현재 monotonic 시각의 순서를 검증. 모르면 `-1` 반환 |
| `ui/OverlayFrameState.kt` (신규) | 소스별 프레임 ID, 결과, 크기, 촬영/수신 기준 시각, UI 갱신 시각 보관. 갱신·만료·설정·초기화 규칙을 Android와 독립적으로 테스트 가능하게 분리 |
| `ui/BoundingBoxOverlay.kt` | YOLO/ML Kit 상태 및 변환 분리. UI 갱신과 `onDraw`, 독립 타이머에서 만료 검사. 남은 수명만 예약하고 조기 실행 시 재예약. OFF/ON, 설정 변경, detach에서 상태 정리 |
| `ui/LatestUiUpdateDispatcher.kt` (신규) | 기존 최신 결과 병합 유지. 예약 과정은 이전에 읽은 콜백을 pending에 다시 쓰지 않음. 닫힌 Activity의 pending/후속 작업 폐기 |
| `MainActivity.kt` | 두 소스에 각각 UI dispatcher 사용. ML Kit에 원본 `FrameTrace`와 완료 시각 전달, 원본 `frameTimestampMs` 유지. 설정 적용/파괴 시 dispatcher 및 Overlay 정리 |
| `metrics/PerformanceLogRecorder.kt` | 기존 `OVERLAY` 로그에 `drawingEnabled` 추가. 만료/OFF로 그려지지 않은 프레임의 draw 콜백을 실제 표시 지연으로 혼동하지 않도록 구분 |
| `settings/AppSettings.kt`, `settings/SettingsActivity.kt` | 기존 `overlay.staleTimeoutMs`의 의미를 설명. 저장 키, 범위 100~60000ms, 기본값 1500ms 유지. 경고 수명과 별개임을 UI에 표시 |
| `test/.../ui/OverlayFrameStateTest.kt` (신규) | 결정론적 시각으로 만료·순서·소스 독립성·설정·초기화·비신뢰 timestamp 검사 10개 |
| `test/.../ui/LatestUiUpdateDispatcherTest.kt` (신규) | 병합, drain 중 도착, 동시 producer, 종료 후 폐기 검사 4개 |
| `androidTest/.../ui/BoundingBoxOverlayTest.kt` (신규) | Canvas 픽셀, UI 지연, 소스별 좌표, OFF/ON/설정, 실제 타이머 및 재생성 검사 5개 |

### 기존 방식과 개선 방식 비교

| 항목 | 기존 | 개선 |
| --- | --- | --- |
| 만료 기준 | UI 갱신 wall 시각 + timeout | 촬영 elapsed 시각 + timeout |
| 촬영 시각 비신뢰/비정상 | 화면 만료에 고려하지 않음 | analyzer 수신 elapsed 시각부터 계산. 수신 시각도 없거나 미래면 표시 거부 |
| UI 도착 시 이미 만료 | 표시하고 timeout 새로 시작 | 즉시 표시 거부, 기존 결과도 재사용하지 않음 |
| 타이머 | 매번 전체 timeout, `>` 조건 한 번 검사 | 촬영/수신 시각부터 남은 시간만 예약, `>=` 만료, 조기 실행 재예약 |
| 늦은 draw | 저장한 결과 사용 | draw 입구에서 두 소스 다시 검사 |
| 이전/중복 결과 | 명시적 프레임 순서 검사 없음 | 소스별 마지막 frame ID 이하의 결과 거부. 만료/빈 결과 이후에도 이전 박스 부활 방지 |
| 소스 간 상태 | 별도 리스트/갱신 타이머, 공통 크기 | 각자의 trace·크기·수명·타이머·UI 병합 상태 |
| OFF → ON | 이전 결과 재표시 가능 | 양쪽 결과 정리, ON 이전 촬영/수신 프레임 거부. 새 결과부터 표시 |
| timeout 변경 | 기존 예약 시간 재계산 없음 | 현재 프레임의 남은 수명 즉시 재계산. 만료 후 timeout을 늘려도 제거된 결과는 복원하지 않음 |

예를 들어 촬영 후 811ms에 UI에 도착한 결과는 기존에 이론상 약 2311ms의 프레임 나이까지 남을 수 있었다(다음 결과가 교체하지 않는 경우이며 경계 누락 시 더 길 수 있음). 개선 후에는 1500ms에 만료되어 남은 수명이 약 689ms다. 이는 로직 예시이며 새로운 단말 측정값이 아니다.

새 결과를 기다리며 추가로 숨기는 시간이나 최소 표시 시간을 넣지 않았다. 유효한 결과는 기존처럼 다음 결과 또는 자신의 기한까지 유지한다. 표시를 위해 YOLO analyzer/inference thread를 대기시키지 않는다.

### 경고 및 기존 기능 보존

Overlay 만료는 화면의 박스에만 적용한다. `WarningPolicy`, 후보 선택, cooldown, TTS·진동·beep 출력 경로는 변경하지 않았으며, 표시에서 폐기한 결과도 기존 경고 계산/기록 정책은 그대로 따른다. 모델 파일, 클래스, Confidence, NMS, ROI/Letterbox 설정도 변경하지 않았다.

성능 로그, 프레임 평가 기록, 화면 녹화 경로를 유지했다. 유효한 YOLO 프레임의 draw 경로를 통과한 경우만 Overlay age 통계에 포함하며 빈 정상 탐지 결과는 여전히 프레임으로 기록한다. `drawingEnabled=false`인 `OVERLAY` 행은 실제 박스 표시 샘플로 해석하지 않아야 한다. 촬영 시각이 비신뢰이면 성능 지표의 capture age는 계속 `-1`이고, 화면 만료용 analyzer age를 촬영 지연으로 대체 보고하지 않는다.

## 3. 선택적 프레임 동기화 진단

기존 `Debug Detection Logging` 설정(`debug.debugDetectionLogging`)을 켰을 때만 기존 bounded 비동기 debug writer에 다음을 기록한다. PNG 저장은 별도 옵션이므로 이번 진단을 위해 켤 필요가 없다.

- `OVERLAY_UPDATE`: 소스, session ID/frame ID, `cameraTimestampNs`, 신뢰성, analyzer 수신 elapsed 시각, 완료 elapsed 시각, UI 갱신 elapsed 시각, capture/analyzer age, 수락/폐기 사유.
- `OVERLAY_DRAW`: 소스별 프레임 최초 draw에 한 번. 완료/갱신/draw elapsed 시각, 표시 시 age, 시간 기준(`CAMERA_CAPTURE`/`ANALYZER_RECEIVED`), 프레임/뷰 크기, 보이는 결과 수, 다른 소스의 frame ID.
- `OVERLAY_CLEARED`: 만료(`EXPIRED`) 또는 설정 변경(`CONFIG_CHANGED`)과 frame ID/age. `OVERLAY_SKIPPED`: draw 전에 교체된 YOLO 결과.
- 갱신 거부 사유: `EXPIRED_AT_UPDATE`, `OUT_OF_ORDER`, `INVALID_TIME`, `DISABLED`, `BEFORE_RESET`.

기본 상태에서는 debug 문자열 생성/파일 기록을 하지 않는다. 로그를 켜도 화면의 매 draw마다 같은 프레임 상세 로그를 반복하지 않는다. 기존 성능 로그는 사용자가 세션을 기록할 때만 동작한다. `completedElapsedNs`는 YOLO의 경우 pipeline 완료 시각이며 순수 native 추론 종료만의 시각은 아니다. draw 시각은 `View.onDraw` 실행 시각으로, 디스플레이에 실제 표시된 시각이나 녹화 인코더의 presentation timestamp는 아니다.

## 4. 검증 결과

실행 명령:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
```

- 전체 단위 테스트 **124개 통과, 실패 0, 오류 0, skip 0**. 신규 14개와 기존 모델 계약/좌표/정책/경고/설정/성능/로그 테스트를 포함한다.
- 앱 Debug APK 및 AndroidTest APK 빌드 성공. 신규 기기 테스트 5개 컴파일 성공.
- `lintDebug` 성공: 오류 0개, 경고 176개. 주요 분류는 `SetTextI18n`, `UseSwitchCompatOrMaterialCode`, `UseKtx`, 의존성/설정 관련 경고다. 상세 분류는 아래 검증 JSON에 기록한다. 변경 전 lint를 별도로 재실행하여 경고 증감까지 비교하지는 않았다.
- `git diff --check` 통과.
- ADB 서버 실행 후 `adb devices`에서 연결 기기 **0대**. instrumentation 실행과 실제 카메라 보행 재검증은 수행하지 못했다. AndroidTest APK 빌드 성공을 기기 테스트 통과로 간주하지 않는다.

증거: [최종 Gradle 출력](validation/overlay-frame-freshness-build-20261019.txt), [테스트·lint 요약](validation/overlay-frame-freshness-results-20261019.json).

| 요청 시나리오 | 자동 검증 |
| --- | --- |
| 새 프레임 표시 | 단위: capture age/잔여 수명. 기기 코드: Canvas에 실제 픽셀 존재 |
| 오래된 결과 비표시 | 단위: 정확한 경계 포함 만료. 기기 코드: stale 결과 및 main thread 지연 후 Canvas 비표시 |
| 연속 도착에도 오래된 결과 재사용 방지 | 단위/기기 코드: 연속 stale 결과, 중복/역순 frame ID, 빈 결과 대체 |
| YOLO와 ML Kit 독립 만료 | 단위: 별도 수명·크기. 기기 코드: 다른 원본 크기의 픽셀 위치 및 YOLO 만료 후 ML Kit 유지 |
| OFF/ON, 설정 변경, 재생성 | 단위: 비활성/재활성 경계, timeout 변경, clear, 새 인스턴스. 기기 코드: View 토글/설정, 실제 Activity 재생성 및 detach |
| 비신뢰 카메라 시각 | 단위: analyzer fallback, 누락/미래 시각 거부, capture 지표 `-1`. 기기 코드: fallback 결과 실제 표시 |

기기 테스트는 연결 후 다음으로 실행할 수 있다.

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.samin.objectdetection.ui.BoundingBoxOverlayTest"
```

## 5. 남은 한계와 실제 단말 재검증

1. **추론 자체가 느린 문제는 남아 있다.** 811ms 전에 촬영한 박스는 처음 표시하는 순간에도 이미 늦다. 이번 수정은 시간 예산을 넘은 박스를 제거하며 이동 보정/추적/Preview 지연 동기화를 구현하지 않는다. 1500ms 이내에서도 빠른 보행/카메라 회전 시 위치 불일치가 보일 수 있다.
2. 촬영 시각이 비신뢰이면 analyzer 수신 이전의 센서/카메라 큐 대기 시간은 알 수 없다. fallback은 수신 이후의 노후화만 제한한다. timestamp가 신뢰 가능한 단말과 같은 정확도의 capture age라고 해석하면 안 된다.
3. YOLO와 ML Kit를 동일 frame ID로 강제 결합하지 않는다. 각자의 촬영 기준 기한을 적용하고 프레임 차이를 로그로 확인한다. 느린 소스 때문에 빠른 소스의 결과나 추론을 대기시키지 않는다.
4. main thread가 정지한 동안 이미 제출된 화면의 픽셀을 타이머만으로 즉시 지울 수는 없다. thread가 재개되면 다음 draw에서 만료 결과를 제거한다. compositor/Preview surface의 실제 표시 시점까지 동기화하지 않는다.
5. timeout을 추론 지연보다 짧게 설정하면 결과 대부분이 표시되지 않거나 빈 구간이 늘어난다. 박스 수 감소를 모델 recall 저하로 집계하면 안 된다. 기본값을 임의로 낮추지 않았다.

단말 재검증 절차:

1. 동일 모델·클래스·Confidence·NMS·Letterbox·경고 설정을 유지하고, 10월 16일과 같은 CPU 2 threads 조건 및 `Overlay Stale Timeout=1500ms`로 먼저 비교한다. 현재 적용값을 내보내 보관한다.
2. 정지 상태에서 가까운/먼 볼라드의 중앙·네 모서리 위치를 확인한다. ML Kit OFF/ON 각각 촬영하고 로그의 입력/회전 크기, `frameSize`, `view`, FIT_CENTER offset 관계를 확인한다. 정지 상태의 지속적인 scale/offset 오차는 시간 지연과 별도 문제로 조사한다.
3. 동일한 짧은 보행 구간과 카메라 좌우 이동을 반복하며 화면 녹화와 성능 로그를 수집한다. 상세 원인 확인용 짧은 실행에서만 `Debug Detection Logging`을 켜고 PNG 저장은 끈다. 로그 OFF 조건도 별도로 측정하여 진단 오버헤드를 구분한다.
4. `sessionId/frame`별 capture → completion → UI update → draw를 연결한다. `CAMERA_CAPTURE`인 수락/표시 프레임의 age와 만료 사유를 확인하고, 만료된 결과가 후속 update로 되살아나는지 점검한다. `otherFrame`으로 ML Kit와 YOLO의 프레임 차이를 확인한다. age는 draw 진입/로그 시점 기준이므로 경계 주변의 실행 시간과 화면 합성 지연을 별도로 본다.
5. Overlay OFF/ON을 반복하고 새 프레임이 도착하기 전 이전 박스가 나타나지 않는지 확인한다. timeout을 짧게/길게 변경하여 Activity가 재생성된 뒤 새 결과부터 표시되는지 확인한다. 녹화·성능 세션 중 설정 변경 제한은 기존대로 유지한다.
6. 같은 설정에서 실제 TTS·진동·beep, cooldown, 경고 메시지, 성능 로그 저장 및 녹화 종료를 재검증한다. 화면에서 만료되어 사라진 박스와 장애물 경고 정책의 수명은 독립적임을 확인한다.
7. 기본 조건 결과를 보관한 뒤 timeout만 단계적으로 변경해 표시 age 분포·박스 없는 구간·만료율을 비교한다. 탐지 자체의 정확도는 평가 프레임/원본 입력 위에서 별도로 판단한다. 개선 후 FPS/표시 age/보행 정합이 좋아졌다는 결론은 이 재측정 이후에만 내린다.
