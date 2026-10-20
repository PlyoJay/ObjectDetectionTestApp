# 카메라 화면 표시 ON/OFF

기준: 2026-10-19, `origin/main` = `f3cf61d` (fetch 후 HEAD와 동일).
작업 시작 시 존재하던 Overlay freshness 관련 미커밋 변경을 보존하고 그 위에 추가했다.

## 기존 구조와 구현

- `CameraController`는 Preview와 RGBA ImageAnalysis를 같은 lifecycle에 함께 바인딩했다.
  ImageAnalysis의 단일 executor에서 bitmap 변환 후 `MainActivity.processBitmap()`을 호출한다.
- `processBitmap()`은 DetectionPipeline의 YOLO/TFLite 추론·후처리·추적을 수행하고,
  경고 판단/출력과 평가·성능 로그를 기록한 뒤 별도 UI callback으로 BBox와 디버그 정보를 갱신한다.
- 새 `CameraSettings.previewEnabled`는 기본 true이며, 설정 화면의 **카메라 및 입력 → 카메라 화면 표시 (Preview)**에서 변경한다.
  기존 `overlay.enabled`와 독립적이다. OFF에서는 Overlay 버튼을 비활성화하되 저장된 Overlay 설정을 유지한다.
- ON은 `bindToLifecycle(owner, selector, preview, analysis)`, OFF는
  `bindToLifecycle(owner, selector, analysis)`를 사용한다. OFF에서는 Preview 객체와 Surface 요청을 만들지 않는다.
  분석 포맷, KEEP_ONLY_LATEST, 해상도 선택 정책, 탐지 간격과 analyzer 본문은 동일하다.
- OFF에서는 PreviewView/BBox/화면용 디버그·경고 텍스트를 숨기고 YOLO/ML Kit 화면 갱신을 생략한다.
  실제 경고 출력, 캡처용 탐지 결과, 추론 시간·FPS·CPU·배터리 진단과 로그는 기존 경로를 사용한다.
  녹화·성능 로그 시작/종료 및 설정 진입 버튼은 유지한다.
- 중앙 상태 문구는 첫 추론 대기/실행 중/추론 오류/카메라 시작 오류를 구분한다.
  최근 처리 완료 후 5초간 완료가 없으면 ‘프레임 처리 대기 중’으로 표시한다.
  주기적인 FPS 수집은 계속되며 OFF에서는 상태 텍스트가 바뀔 때만 갱신한다.
- 설정 적용은 기존 안전한 재시작 경로를 사용한다. analyzer 종료를 기다리고 이전 Interpreter를 닫은 뒤 Activity를 재생성하므로 ON 복귀 시 Preview를 새로 바인딩한다.
  녹화/성능 로그 세션과 종료 처리 중 설정 변경 제한은 그대로 유지한다.

CameraProvider에서 ImageAnalysis use case를 lifecycle에 바인딩하는 근거:
[Android CameraX Image analysis](https://developer.android.com/media/camera/camerax/analyze).

## 저장 및 로그

- 설정 스키마 3 → 4. codec 키는 `camera.previewEnabled`이며 누락/잘못된 Boolean은 true로 읽는다.
  기존 applied 설정은 기존 Store의 migration 저장 경로로 갱신한다. 사용자 프리셋은 읽을 때 기본값을 보충하고 재저장할 때 새 스키마로 저장한다.
  기존 resize migration, 다른 설정과 rollback marker 동작은 유지한다.
- 성능 로그 헤더에 정확히 `cameraPreviewEnabled=true/false`를 추가하며 설정 스냅샷에도 저장한다.
- OFF 로그는 `Overlay Draw: NOT_DISPLAYED_PREVIEW_OFF`로 구분한다.
  FRAME 성공 기록과 성능 통계는 계속 생성하지만 OVERLAY draw 이벤트는 생성하지 않는다.
  `frameAgeOverlay=-1`, `Overlay Latency Samples: 0`은 미표시/미측정을 의미하며 ERROR 또는 dropped frame으로 기록하지 않는다.

## 주요 변경 파일

| 파일 | 변경 |
| --- | --- |
| `settings/AppSettings.kt`, `settings/AppSettingsStore.kt` | 기본값, schema 4, codec/기존 설정 호환 |
| `settings/SettingsActivity.kt` | 카메라 카테고리의 스위치·설명·요약·폼 저장/복원 |
| `camera/CameraController.kt` | Preview 선택 바인딩과 실제 분석 해상도 Logcat |
| `MainActivity.kt`, `ui/MainScreenView.kt` | 렌더링 제어, OFF 상태 문구, 로그 헤더 전달 |
| `metrics/PerformanceLogFormatter.kt` | Preview 상태·Overlay 미측정 의미를 헤더에 기록 |
| `app/build.gradle.kts` | Espresso 단말 테스트 동안 시스템 애니메이션 비활성화 |
| `AppSettingsTest`, `PerformanceLogFormatterTest`, `PerformanceLogSessionTest` | 구버전/프리셋 호환, 추론 설정 독립성, draw 없는 성공 로그·진단 |
| `SettingsActivityTest`, `CameraPreviewRuntimeTest` | UI/영구 저장/프리셋, 실제 추론·진단 로그·설정 잠금, OFF→ON→OFF 분석 프레임 |

## 검증 및 남은 단말 시험

자동 검증 결과는 `validation/camera-preview-results-20261019.json`에 기록한다.
최종 `assembleDebug`, `assembleDebugAndroidTest`, `testDebugUnitTest`, `connectedDebugAndroidTest`, `lintDebug` 모두 성공했다.
단위 테스트 126개와 Galaxy S24(SM-S921N, Android 16) 관련 단말 테스트 20개가 모두 통과했다. Lint는 오류 0건, 경고 179건이다.
OFF→ON→OFF에서 분석 프레임 수집 및 동일 해상도(센서 1440×1080 / 회전 후 1080×1440)를 확인했다.

| 실제 앱의 짧은 기능 검증 | OFF | ON |
| --- | ---: | ---: |
| 성공 프레임 | 18 | 15 |
| 실패 프레임 | 0 | 0 |
| Overlay Draw 이벤트 | 0 | 15 |
| 시스템 진단 샘플 | 8 | 8 |
| 배터리 진단 샘플 | 3 | 3 |

두 모드에서 성능 로그 측정 중 상세/빠른 설정 진입이 제한되고, 종료 후 상세 설정에 진입하는 것도 확인했다.
위 수치는 기능 확인용이며 측정 조건을 통제한 성능 비교가 아니다.
실제 앱 로그는 단말 integration test가 앱 cache에 저장한 `preview-validation-false.txt`, `preview-validation-true.txt`를 테스트 앱 정리 전에 추출했다.
저장본은 `validation/camera-preview-off-log-20261019.txt`, `validation/camera-preview-on-log-20261019.txt`이다.
테스트는 기존 applied 설정과 사용자 프리셋을 복원하고 생성한 공유 성능 로그만 정리한다.
평가 JSON의 settings도 기존 codec 기반 변환을 통해 `camera.previewEnabled`를 자동으로 포함한다.
초기 기기 잠금, 애니메이션, 기존 Android ELF 호환성 안내창으로 인한 자동화 실패를 해결한 뒤 최종 전체 테스트를 다시 통과했다.

별도 현장 검증 항목:

- 실제 위험 물체를 향한 TTS/진동/Beep 출력 및 cooldown 동작. 자동 integration test는 소리/진동 출력을 끄고 추론과 로그를 검증한다.
- MediaProjection 사용자 동의를 거치는 화면 녹화 시작/종료, 탐지 JSON 생성, 녹화 중 설정 제한.
- 실제 ON 영상/BBox 정합성, 회전/앱 재실행과 장시간 프레임 안정성, 대상 전용 단말의 카메라 호환성.
- 동일 조건에서 충분한 시간으로 ON/OFF 성능·소비 전력 비교. 짧은 기능 테스트의 수치는 성능 개선/전력 절감의 근거로 사용하지 않는다.

## 제한과 주의

- OFF는 디스플레이 전원 OFF 또는 백그라운드 실행을 의미하지 않는다. Activity lifecycle에 따른 카메라 동작은 기존과 같다.
- 화면 녹화는 디스플레이를 녹화하므로 OFF에서는 카메라 영상과 BBox 대신 상태 문구/버튼이 녹화된다. 설정 설명에도 명시했다.
- 두 모드에 같은 요청 해상도와 fallback 정책을 사용하지만 CameraX가 선택하는 실제 해상도/프레임 속도는 기기와 use case 조합에 따라 다를 수 있다.
  `[CAMERA_USE_CASES]`, `[CAMERA_SHAPE]`와 성능 로그의 실제 해상도를 함께 비교한다.
  분석 전용 바인딩이 실패하면 오류를 표시하며 Preview를 몰래 다시 켜지 않는다.
- 성능 로그를 첫 프레임 이전에 시작하면 기존처럼 헤더 해상도에 요청값이 사용될 수 있으므로 비교 측정은 ‘객체 인식 실행 중’을 확인한 뒤 시작한다.
- ROI 크롭 비율, 테스트 타이머, 전력 통계, BBox 거리 표시 및 객체 인식 알고리즘은 변경하지 않았다.
