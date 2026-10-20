# YOLO 640/512 모델 선택 — 구현·검증 결과

검증일: 2026-10-20. 커밋·푸시는 실행하지 않았다.

## 사용 방법

**상세 설정 → YOLO 모델 및 추론 → 모델 선택 → 적용 및 저장**.

- YOLO 640×640 (`best.tflite`): 기본 선택. 기존 파일은 변경하지 않았다.
- YOLO 512×512 (`best_512.tflite`): 사용자가 제공한 파일을 assets에 그대로 복사했다. 원본·복사본 SHA256이 일치한다.
- 선택 파일명, 실제 입력 크기, 입출력 shape/type, 전체 SHA256을 표시한다.
- 적용 전 별도 Interpreter로 파일과 요청 Threads/XNNPACK 옵션을 검증한다. 실패하면 오류를 표시하고 적용 설정을 저장하지 않는다.
- 적용 후 기존 카메라 분석과 Interpreter를 종료하고 Activity·탐지기를 재생성한다. 새 런타임 초기화 실패 시 보관된 전체 이전 설정으로 복구를 시도한다.
- 성능 로그·화면 녹화 중 기존 설정 잠금 정책을 유지한다.

## 실제 모델 호환성

| 모델 | 입력 (FLOAT32, NCHW) | 출력 (FLOAT32) |
|---|---|---|
| best.tflite | `[1,3,640,640]` | `[1,13,8400]` |
| best_512.tflite | `[1,3,512,512]` | `[1,13,5376]` |

SHA256:

- 640: `32a44bc7432ef2d7ca5de802dd8e714a26568aea16566bad3fd7fcd2b85bcee2`
- 512: `f312b541c86426c53c02ef1674ee539c6f87fcf97102061fade628317b3c10b9`

두 파일의 내장 metadata와 labels.txt 순서는 `bollard, person, bicycle, car, motorcycle, bus, truck, stop sign, bench`로 일치한다. 출력은 xywh 4채널과 클래스 점수 9채널이다. 그래프의 마지막 좌표 정규화 배율은 각각 1/640과 1/512이며, RGB /255 입력과 기존 normalized xywh 파서를 유지한다.

호스트 검사는 기존 `tools/inspect_tflite.py`와 ai-edge-litert 2.2.0, Android 실행 검사는 앱의 LiteRT 2.1.1로 수행했다. [모델 검사 자료](validation/model-selection-models-20261020.json)에 실제 메타데이터를 남겼다.

## 주요 변경

- 실제 입력 텐서에서 너비·높이를 읽어 Bitmap, 픽셀·입출력 버퍼, Stretch 대상 사각형을 초기화한다. 공통 검증이 단일 정적 batch-1 RGB 입력, 지원 dtype/양자화, 9클래스 raw 출력과 바이트 수를 확인한다. 선택 파일명과 입력 해상도가 다르면 실제 shape를 포함해 거부한다.
- 출력 layout과 후보 수는 실제 출력 텐서에서 읽는다. 앱 구현에 8400/5376 고정 후보 수를 추가하지 않았다.
- `YoloTensorContract.kt`, `YoloResizeTransform.kt`, ROI·NMS·Geometry·Overlay·ML Kit 알고리즘은 이미 해상도에 독립적이므로 유지하고 테스트를 확장했다. 사용하지 않는 `DetectionResult.mapToOriginalFrame` 확장 함수의 기본 640 값은 실행 경로에 연결하지 않았다. ML Kit의 별도 640×360 입력도 유지한다.
- 스키마 6의 `yolo.model`에 선택값을 저장한다. 기존 적용 설정·사용자 프리셋은 키가 없으면 640으로 읽고 기존 값과 v1 Resize 마이그레이션을 보존한다. 내장 프리셋의 기본 모델은 640이며, 사용자 프리셋에는 모델 선택도 포함한다.
- 로그의 기존 `Input Size`는 로드된 모델의 실제 크기를 쓴다. `Input Tensor`, `Output Tensor` shape/type을 추가하고 파일명·SHA256·Threads·XNNPACK 요청·ROI·Preview·평균 FPS·평균/P95·시스템/배터리 진단 기록을 유지했다. 평가 JSON에도 실제 입력 폭·높이를 기록한다.

## 수정·추가 파일 전체 목록

`app/src/main/java/com/samin/objectdetection/` 아래:

- `MainActivity.kt`
- `camera/DetectionConfig.kt`
- `detector/ModelConfig.kt`, `ModelAssets.kt`, `VisionStyleYoloDetector.kt`, `DetectionDiagnostics.kt`
- `settings/AppSettings.kt`, `AppSettingsStore.kt`, `SettingsActivity.kt`
- `metrics/PerformanceLogFormatter.kt`
- `evaluation/EvaluationDataRecorder.kt`
- `pipeline/DetectionPipeline.kt`

모델·테스트:

- `app/src/main/assets/best_512.tflite` (제공 파일 추가)
- `app/src/test/java/com/samin/objectdetection/settings/AppSettingsTest.kt`
- `app/src/test/java/com/samin/objectdetection/detector/YoloTensorContractTest.kt`, `YoloResizeTransformTest.kt`
- `app/src/test/java/com/samin/objectdetection/camera/RecognitionRoiTest.kt`
- `app/src/test/java/com/samin/objectdetection/metrics/PerformanceLogFormatterTest.kt`
- `app/src/androidTest/java/com/samin/objectdetection/detector/ModelSelectionDetectorTest.kt` (추가)
- `app/src/androidTest/java/com/samin/objectdetection/settings/SettingsActivityTest.kt`, `XnnpackRuntimeSettingsTest.kt`

문서·검증 자료:

- `docs/yolo-model-selection-20261020.md` (본 문서), `docs/in_app_detection_settings.md`
- `docs/validation/model-selection-build-20261020.txt`
- `docs/validation/model-selection-models-20261020.json`
- `docs/validation/model-selection-results-20261020.json`
- `docs/validation/model-selection-ui-20261020.txt`
- `docs/validation/model-selection-python-20261020.txt`
- `docs/validation/model-selection-512-perf-20261020.txt`
- `docs/validation/model-selection-640-perf-20261020.txt`

## 빌드·검증 결과

| 검증 | 결과 |
|---|---|
| `gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest` | 성공. 단위 테스트 146개, 실패/오류/건너뜀 0 |
| Galaxy S24 SM-S921N, Android 16 계측 | 최종 유효 결과 36개 통과: 탐지·카메라·ROI·Overlay 19개 + 설정 UI 재실행 17개 |
| `python -m unittest discover -s tools -p test_analyze_performance_logs.py` | 12개 통과 |
| 기존 분석기로 실제 640/512 로그 읽기 | 두 로그 모두 오류·누락 0, 프레임 로그 완전. 헤더·평균/P95·시스템/배터리 기록 확인 |
| `git diff --check` | 통과 |

단말에서 기본 640 실행, 카메라 추론 중 640→512→640 교체, 이전 Interpreter 종료와 새 세션 생성, Threads/XNNPACK 전환, 512 ROI·Preview OFF, 성능 로그 중 설정 잠금을 검증했다. 두 모델의 알려진 bollard 이미지 탐지, Letterbox·Stretch 전체 입력 바이트 대조, 반복 버퍼 재사용, 유한 출력도 확인했다. ML Kit 병행 실행 로그에서 오류가 없었다.

합성 박스를 실제 출력 버퍼에 넣어 두 모델·두 Resize 방식의 실제 파서·NMS·Geometry·ROI 전체 프레임 좌표 복원을 검증했다. 홀수 크기·가로·세로·회전별 역변환도 보강했다. 파일 누락 오류, 잘못 붙인 입력 해상도, 미지원 출력 계약 거부, 복구 저장소 동작은 각각 검증했다. 설정 재생성·다시 열기·사용자 프리셋·기존 스키마 마이그레이션도 통과했다.

최초 실행에는 화면 꺼짐·권한 부재·기존 UI 항목 목록 누락·테스트 대기 조건 문제로 실패/건너뜀이 있었다. 화면과 승인받은 권한을 준비하고, UI 목록에 기존 ROI/타이머 및 새 모델 항목을 포함하고, 비동기 적용·하위 레이아웃 완료를 기다리도록 보정했다. 직접 UI 실행은 Gradle의 animationsDisabled와 같은 조건으로 17개 전부 재검증했다. 초기 실패를 최종 통과 수에 중복 합산하지 않았다. [집계 자료](validation/model-selection-results-20261020.json)에 사례별 결과와 초기 실패 이력을 기록했다.

일시 허용한 카메라·정확/대략 위치·알림 권한은 기존 미허용으로, 애니메이션 설정 3개는 기존 1.0으로 복원하고 확인했다. 테스트는 적용 설정·사용자 프리셋을 백업·복원했다.

APK: `app/build/outputs/apk/debug/app-debug.apk`.

## 미검증 항목과 추가 단말 확인

- 장시간 동일 조건의 성능·발열·배터리 A/B 비교는 수행하지 않았다. 보관 로그는 각 3프레임이며 512/640의 ROI·Preview 조건이 다르다. 성능 우열로 해석할 수 없다. USB 충전 중이어서 방전 소비 비교에도 사용할 수 없다.
- 실제 화면 녹화 세션과 녹화 중 모델 변경 차단은 이번에 재현하지 않았다. 기존 녹화 잠금 코드는 유지했다.
- 3분 자동 종료 테스트는 이번에 재실행하지 않았다.
- 누락·손상 모델을 넣은 APK를 별도로 설치해 오류 UI부터 MainActivity 복구까지 이어지는 전체 실패 경로는 재현하지 않았다. 파일 오류·계약 거부·복구 저장소는 각각 테스트했다.
- 실환경에서 조명·거리·각도·ROI·Preview·Threads·XNNPACK·ML Kit를 고정해 작은 bollard 검출률과 BBox 정렬을 비교하고, 장시간 로그로 FPS·P95·발열 추이를 확인해야 한다. 다른 칩셋/단말과 실제 보행 장면도 추가 확인 대상이다.
