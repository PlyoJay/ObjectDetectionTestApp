# YOLO11s v2: 12클래스 모델 적용 보고서 (2026-11-03)

## 1. 기존 구조와 변경 범위

작업 시작 시 소스는 640/9클래스와 512/11클래스 모델을 선택하는 구조였고,
`assets/best_512.tflite`만 사용자가 제공한 신규 바이너리로 이미 교체되어 있었다.
모델·라벨 계약은 아직 11클래스여서 해당 파일의 16채널 출력을 초기화할 수 없는 상태였다.
이 사용자 바이너리는 수정하지 않았다.

| 항목 | 기존 구현 및 이번 처리 |
|---|---|
| 모델 로딩 | `ModelAssets.load`가 압축하지 않은 assets를 읽기 전용 mmap, `VisionStyleYoloDetector`가 Interpreter 생성. 메인은 설정의 파일명을 명시 전달. 저수준 helper의 역사적 기본 `best.tflite`는 유지 |
| 입력/버퍼 | 실제 Tensor에서 layout/width/height/dtype/numBytes를 읽고 RGB /255. FLOAT32 bulk writer, 재사용 Bitmap/Canvas/IntArray/FloatArray/ByteBuffer 유지 |
| 출력 | `YoloOutputShape`가 라벨 수에 맞는 `4+classes` 채널 축과 실제 후보 수를 검증. 양쪽 layout reader 지원. 기존 동적 크기 할당 유지 |
| 후처리 | 후보당 최고 점수 클래스 하나 선택 → Confidence → 유한 좌표/유효 상자 → 크기 필터 → 점수순 최대 100후보(설정 가능) → 같은 클래스끼리 NMS. 추가 objectness/sigmoid 없음 |
| 공통 Confidence/NMS | 기본 0.20/0.45 유지. 클래스 override가 비어 있으면 공통 Confidence 사용. 기존 경고용 클래스별 Confidence 기준도 유지 |
| 클래스/정책 | `ModelConfig`에서 버전별 ID 순서 관리. overlay/size/warning 정책은 `ObjectTuningPolicyRegistry`. 0~10 ID와 기존 정책 수치 유지 |
| 클래스 설정 | 기존에는 공통 Confidence와 고정 정책만 존재. 12종에 대해 탐지 ON/OFF 및 선택적 Confidence 설정 추가 |
| 오버레이 | `BoundingBoxOverlay`의 원본 프레임/viewport 좌표 변환·라벨·confidence·거리·만료 처리 유지. cardboard_box 색상만 추가. AR 실험 화면에도 같은 색상 적용 |
| CameraX | Analyzer/`STRATEGY_KEEP_ONLY_LATEST`, 회전, 단일 프레임 처리/해제, Preview ON/OFF 유지 |
| ROI/Letterbox | `FrameRoi` 공유 크롭, RGB114 padding 및 STRETCH, 원본 좌표 복원 유지. ROI 크롭이 켜지면 Letterbox 사용 |
| ARCore | 메인 ARCore worker 및 실험 worker 모두 같은 detector 사용. FULL/RAW Depth, 샘플링·스무딩·거리 정의·좌표 투영 알고리즘 변경 없음 |
| ML Kit | 병렬 실행, STREAM/SINGLE 모드, 실행 주기, 입력 버퍼 소유권 및 로그 설정 유지 |
| 로그/기록 | 캡처 JSON, 탐지 JSONL, 화면 녹화, 성능/시스템/배터리/발열 로그 및 공유 경로 유지 |

기존 9/11 고정 클래스 관련 사용처를 `main`, `test`, `androidTest`, `tools` 전체에서 검색했다.
실행 코드의 출력 버퍼, 반복문, 필터 허용 목록, 통계 표시 상한은 이미 실제 shape/중앙 라벨 목록을
사용하므로 불필요한 교체를 하지 않았다. 9/11이라는 수치는 해당 과거 모델의 계약과 회귀 테스트에 유지한다.

## 2. 실제 모델 검사 결과

실제 ai-edge-litert 2.2.0 Interpreter 로딩·텐서 할당과 FlatBuffer 그래프/내장 ZIP `metadata.json`을 확인했다.
Android 의존성 litert 2.1.1은 변경하지 않았다.
전체 텐서/메타데이터/연산 목록은 [검사 JSON](validation/yolo-v2-twelve-class-model-inspection-20261103.json)에 있다.

| 항목 | 이전 512 v1 | 신규 512 v2 | 기존 640 |
|---|---|---|---|
| assets 파일 | `best_512_v1_11class.tflite` | `best_512.tflite` | `best.tflite` |
| 파일 크기 | 37,929,451 bytes | 37,931,072 bytes | 37,962,632 bytes |
| 클래스 수 | 11 | 12 | 9 |
| 입력 | `[1,3,512,512]` FLOAT32 | `[1,3,512,512]` FLOAT32 | `[1,3,640,640]` FLOAT32 |
| 입력 바이트 | 3,145,728 | 3,145,728 | 4,915,200 |
| 출력 | `[1,15,5376]` FLOAT32 | `[1,16,5376]` FLOAT32 | `[1,13,8400]` FLOAT32 |
| 출력 바이트 | 322,560 | 344,064 | 436,800 |
| 내장 NMS | 없음 | 없음 | 없음 |
| 출력 구성 | normalized xywh + 11 점수 | normalized xywh + 12 점수 | normalized xywh + 9 점수 |

SHA-256:

- v2: `591509278b3a1fe114a0084d4841b948a93b637c22005b7becf06309cb906667`
- v1: `cc189262fd705e7d0b48246ebe42f868ff9b7370d97a0a6d7a7f756f16d4bad1`
- 640: `32a44bc7432ef2d7ca5de802dd8e714a26568aea16566bad3fd7fcd2b85bcee2`

v2 메타데이터는 YOLO11s, Ultralytics 8.4.173, `task=detect`, `head=Detect`,
`end2end=false`, 내보내기 시각 `2026-11-03T10:35:21.408228+09:00`이다.
입출력은 모두 정적 batch 1, NCHW 입력이며 양자화 scale/zero point는 없다.
Float16/INT8 입출력으로 추측하거나 변환하지 않았다.

그래프 최종 CONCATENATION 입력은 `[1,4,5376]` 좌표와 `[1,12,5376]` 점수다.
좌표에는 최종 MUL에서 `1/512=0.001953125`가 적용되고, 점수는 LOGISTIC 출력이다.
그래프에 NMS 연산이나 custom op가 없음을 검사했다. 따라서 기존 normalized xywh 파서를
유지하고 추가 objectness 곱셈·sigmoid를 적용하지 않는다.
512는 파일 이름으로 가정한 값이 아니라 이 모델에서 실제 관측한 값이다.
검출 시 텐서 크기를 읽어 전처리/버퍼를 구성하며 등록 모델과 다른 해상도·채널 계약은 오류로 거부한다.

사용자가 부른 데이터셋 이름은 `v2_cardboard_box`다. 모델 메타데이터에는 실제 학습 경로가
`/home/samin_khj/Documents/Codex/2026-10-28/yolo-cardboard-box-1-2-roboflow/outputs/exp02_exclusion_preflight_20261030/protected_data_exp02.yaml`
로 남아 있다. YAML 원본은 이 저장소에서 확인되지 않았으므로 데이터셋 별칭의 동일성까지 검증한 것은 아니다.
클래스 순서는 추측하지 않고 해당 모델의 names 전체와 대조했다.

## 3. 클래스 매핑 및 정책

| ID | 모델 metadata.names = 앱 라벨 | v1 지원 | v2 지원 |
|---|---|---|---|
| 0 | bollard | O | O |
| 1 | person | O | O |
| 2 | bicycle | O | O |
| 3 | car | O | O |
| 4 | motorcycle | O | O |
| 5 | bus | O | O |
| 6 | truck | O | O |
| 7 | stop sign | O | O |
| 8 | bench | O | O |
| 9 | cat | O | O |
| 10 | dog | O | O |
| 11 | cardboard_box | — | O |

`labels.txt`와 v2 names의 모든 인덱스가 일치한다. v1은 앞 11개, 640은 앞 9개를 사용한다.
미등록 파일의 클래스 순서를 추정하지 않는다.
cardboard_box의 표시명/로그 이름은 원문 그대로이며 색상은 `#D4A373`이다.
cat/dog과 동일하게 overlay·통계·기존 거리 추정 대상에 포함하고 경고/진동/음성은 추가하지 않았다.
크기 필터 NORMAL에서는 신규 클래스 정책의 최소 면적 0.015와 기존 공통 너비/높이 조건을 사용한다.
기본 크기 필터 DISABLED 및 bollard 전용 geometry/temporal 필터는 유지한다.

## 4. 수정 파일

아래 Java/Kotlin 경로의 `...`는 `com/samin/objectdetection`이다.

| 파일 | 주요 변경 |
|---|---|
| `app/src/main/assets/best_512.tflite` | 작업 시작 전 이미 제공된 v2. 바이트 수정 없음 |
| `app/src/main/assets/best_512_v1_11class.tflite` | Git HEAD의 이전 11클래스 파일을 정확히 복사하여 보존. SHA 일치 |
| `app/src/main/assets/labels.txt` | ID 11 추가 |
| `main/java/.../detector/ModelConfig.kt`, `ModelAssets.kt` | v1/v2/640 클래스 계약 및 선택 항목, 라벨 검증 |
| `main/java/.../detector/YoloClassOptions.kt` | 클래스 옵션과 초기화 시 생성하는 BooleanArray/FloatArray 필터 |
| `main/java/.../detector/VisionStyleYoloDetector.kt` | 클래스별 Confidence/ON-OFF 적용. 기존 argmax·좌표·NMS 유지 |
| `main/java/.../policy/ObjectTuningPolicyRegistry.kt` | 중앙 라벨 목록에서 추가 클래스 표시 정책 생성 |
| `main/java/.../ui/BoundingBoxOverlay.kt`, `arcore/yolo/ArCoreYoloOverlay.kt` | cardboard_box 표시 색상 |
| `main/java/.../settings/AppSettings.kt`, `AppSettingsStore.kt`, `SettingsActivity.kt` | schema 11, 옵션 저장/복원/검증, 클래스 선택 UI, 새 설치 기본 v2 |
| `main/java/.../MainActivity.kt`, `arcore/yolo/ArYoloWorker.kt` | 메인/AR 실험 추론에 같은 클래스 설정 전달 |
| `main/java/.../metrics/PerformanceLogFormatter.kt` | 기존 헤더에 `Model Classes:` 추가 |
| `test/java/.../detector/TwelveClassModelTest.kt` | ID, 16채널 양 layout 복사, 옵션 경계·저장·통계·로그 검증 |
| 기존 unit/instrumented tests | v1 회귀 유지, v1/v2 파서·NMS·ROI 매개변수 테스트, 세 모델 전환/설정/색상/거리 로그 검사 |
| `tools/test_yolo_model_contract.py` | 세 실제 모델 추론, 메타데이터·dtype·정적 shape·v1 SHA·v2 그래프 검사 |
| `README.md`, 이 보고서, `docs/validation/` | 사용법 및 검증 근거 |

설정에서 **YOLO11s v2 · 12클래스**를 선택하면 신규 모델을 사용한다.
새 설치/초기화 기본값은 v2이며, 기존 저장된 640/512 선택이나 사용자 프리셋은 강제 변경하지 않는다.
과거 모델 선택 필드가 없던 schema 10 이하 프리셋은 원래 기본값 640으로 복원한다.
기존 `YOLO_512` 저장값은 같은 파일의 v2를 사용하고, v1 비교에는 별도 v1 항목을 선택한다.
지원하지 않는 클래스의 UI는 비활성화되지만 입력값은 보존해 모델 전환 시 되살린다.

클래스 Confidence가 공란이면 공통값을 사용한다. override는 공통값보다 작거나 클 수 있다.
최고 점수 클래스가 OFF라면 해당 후보를 버리며 두 번째 클래스에 재할당하지 않는다.
설정 OFF는 최종 탐지·표시·평가 기록에서 제외하며, 원시 출력 진단 로그는 모델이 낸 점수를 관찰할 수 있다.

## 5. 기존 기능 영향 및 성능 비교

- ARCore 거리 계산, CameraX/ML Kit 병렬 처리, Preview/화면 테스트 모드, ROI/Letterbox,
  녹화/캡처/공유, 시스템·배터리·발열 측정 구현은 유지했다.
- 기존 경고용 클래스별 기준과 0~10 매핑을 유지했다. 후보별 argmax, 전체 클래스 공통
  maxCandidates 제한과 class-aware NMS도 유지하므로 새 클래스 후보가 기존 후보와 경쟁할 수 있다.
- 입력 해상도/후보 수는 v1과 동일하다. 출력 ByteBuffer와 재사용 FloatArray는 각각
  21,504 bytes 증가하여 합계 43,008 bytes 증가한다. 새 Bitmap이나 텐서의 프레임별 할당은 추가하지 않았다.
- v1을 앱 안에서 선택·비교할 수 있도록 assets에 보존해 패키지에 약 37.9 MB 모델이 추가된다.
  기존 `best.tflite`, `best_float32.tflite`도 삭제하지 않았다.
- 성능 로그의 기존 모델 파일명·SHA256·입출력 shape/dtype·입력 크기 헤더 및
  inference/outputParsing/nms/post/totalProcessing/detect/visible 필드는 유지했다.
  `Model Classes:`만 헤더에 추가했고 클래스별 설정은 기존 AppSettings snapshot에도 포함된다.
  캡처/녹화 JSON의 model metadata에는 이미 classCount가 기록된다.
- 실제 A/B는 v1/v2 모델만 교체하고 ROI, 카메라 backend, Preview, 스레드, XNNPACK,
  ML Kit, 진단/녹화, 배터리·발열 환경을 같게 맞춘다. 이번 변경만으로 성능 개선을 주장하지 않는다.

## 6. 빌드 및 테스트 결과

| 검증 | 최종 결과 | 근거 |
|---|---|---|
| Gradle `testDebugUnitTest assembleDebug assembleDebugAndroidTest --offline` | 성공 | [빌드 로그](validation/yolo-v2-gradle-20261103.txt) |
| 전체 JVM 단위 테스트 | 299개 통과, 실패/오류/skip 0 | [suite별 집계](validation/yolo-v2-unit-tests-20261103.json) |
| 실제 모델·메타데이터·그래프 호스트 테스트 | 3개 통과, 세 모델 로딩·추론/유한 출력/점수 범위 정상 | [모델 테스트](validation/yolo-v2-model-tests-20261103.txt) |
| 기존 성능 로그 분석기 회귀 | 12개 통과 | [분석기 테스트](validation/yolo-v2-performance-log-tests-20261103.txt) |
| Android SM-S921N 실기기 선택 회귀 | 38개 모두 통과, 86.449초 | [instrumentation 로그](validation/yolo-v2-instrumented-20261103.txt) |

실기기에서는 디버그 앱/테스트 APK를 `install -r -t`로 설치했다. 앱 데이터는 삭제하지 않았고,
설정을 수정하는 테스트는 기존 적용값·프리셋을 백업/복원했다. 단말 화면을 깨워 UI 테스트를 실행했다.

검증된 범위:

- v1/v2 실제 Interpreter의 shape/버퍼/라벨 계약, 세 모델 전환과 설정 inspector 일치.
  기존 known-positive 이미지로 실제 추론 및 Bitmap/입출력 버퍼 재사용 검사.
- 실제 production parser에 합성 텐서를 넣어 v2의 0~11 ID 전체 처리,
  24→12 class-aware NMS, v1의 22→11 회귀, 원 점수 보존,
  LETTERBOX/STRETCH + ROI 원본 좌표 복원 확인.
- Confidence 경계/미만, 마지막 클래스의 0.1 override(공통 0.2보다 낮음), ON/OFF,
  NaN/화면 밖/폭 0 상자와 clipping 검사. 클래스 OFF 후보는 NMS 전에 제거됨을 확인.
- 세 모델 각각 CPU 2/4스레드 × XNNPACK ON/OFF에서 기존 writer/run()/동결 parser와 비교.
  입력은 byte-for-byte, 출력은 `1e-5 + abs(expected)*1e-4`, confidence `1e-4`, 좌표 0.1px 기준.
  이 비교는 수치 회귀 검사이며 속도 개선이나 delegate 적용 범위 측정이 아니다.
- 실제 Canvas의 cardboard_box 색상/클래스 이름과 기존 색상, BBox/거리값 만료·표시 gating 검사.
  클래스 옵션의 모델 전환/화면 재생성/적용·저장/사용자 프리셋 유지 확인.
- 실제 Android Bitmap/회전/ROI/파이프라인에서 합성 Depth context를 전달해 지원 모델의
  모든 클래스(상자 포함)에 거리값이 붙고 기존 필터·경고 건수는 유지되는지 검사.
  JUnit에서 상자 거리 JSON의 원 수치·라벨·좌표·confidence 보존도 확인.
- 메인 Activity를 실제 v2 기본 설정으로 실행하여 Preview OFF/ON 각각 추론과 로그 저장,
  설정 잠금, 시스템/배터리 샘플, Preview OFF에서 overlay draw 없음 확인.
  두 로그에서 파일명/전체 SHA/12클래스/입출력 텐서가 실제 v2와 일치한다.
  성공 프레임은 OFF 31개, ON 28개이며 두 세션 모두 실패 프레임 0이다.
  이 짧은 세션의 프레임 수를 모델 성능 비교 결과로 해석하지 않는다.

실제 성능 로그: [Preview OFF](validation/yolo-v2-preview-off-performance-20261103.txt),
[Preview ON](validation/yolo-v2-preview-on-performance-20261103.txt).
새 헤더가 포함된 이 두 로그를 기존 `analyze_performance_logs.analyze`로 읽는 것도 성공했다.
[파싱 결과](validation/yolo-v2-preview-analysis-20261103.json).

초기 JVM 실행에서 새 기본 해상도에 대한 과거 기대값 2건과 schema 1 기본 프리셋의
Letterbox 복원 비교 1건이 실패했다. 테스트의 새 기본값을 반영하고 마이그레이션 비교에서
복원된 모델 선택을 유지하도록 수정한 후 전체 299개 및 최종 APK 빌드가 통과했다.

산출물:

- 앱: `app/build/outputs/apk/debug/app-debug.apk`
- 테스트 APK: `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`

현재 빌드의 일부 Android deprecated API 경고는 기존 코드에서 발생하며 오류는 아니다.

## 7. 기기 검증 필요 및 알려진 문제

- 실물 cardboard_box, 기존 0~10 객체의 실제 카메라 탐지/confidence/recall/precision.
  저장소에는 정답이 있는 상자 평가 이미지/데이터셋이 없어 합성 텐서만으로 모델 정확도를 입증할 수 없다.
- 상자에 대한 실제 FULL/RAW Depth 거리 표시와 센서 실패/미지원 시 동작. 합성 거리값 전달 테스트와
  실제 센서 측정 정확도 검증은 구분한다. 기존 거리 정확도 문제와 알고리즘 개선은 별도 후속 작업이다.
- 새 가중치로 Preview ON/OFF, 화면 OFF의 현장 장시간 추론·거리·배터리·발열 및 녹화/캡처/공유 비교.
  화면이 꺼졌을 때의 기존 OS/카메라 lifecycle 제약도 그대로다.
- 신규 모델의 클래스 추가가 기존 객체 인식 정확도를 동일하게 유지한다는 보장은 하지 않는다.
  앱의 인덱스/수치 처리 회귀와 실제 학습 가중치의 정확도는 별도 검증 대상이다.
- 재학습, 재변환, 학습 데이터 변경, 거리 알고리즘 변경, 커밋/푸시는 수행하지 않았다.
