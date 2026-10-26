# YOLO 512: 11개 클래스 대응 및 검증 (2026-10-26)

## 확인한 모델 계약

현재 `app/src/main/assets/best_512.tflite`를 읽기 전용으로 검사했다. 사용자 제공 모델 및
이미 수정되어 있던 `labels.txt`는 수정하지 않았다. 모델 SHA-256은 작업 시작과 종료 시 동일했다.

- 새 512 모델: 37,929,451 bytes,
  SHA-256 `cc189262fd705e7d0b48246ebe42f868ff9b7370d97a0a6d7a7f756f16d4bad1`.
- 입력: `[1,3,512,512]`, NCHW, FLOAT32, 3,145,728 bytes.
- 출력: `[1,15,5376]`, FLOAT32, 322,560 bytes, 4개 normalized xywh + 11개 클래스 점수.
- 내장 `metadata.json`: YOLO11s, `task=detect`, `head=Detect`, `end2end=false`,
  Ultralytics 8.4.173, export date `2026-10-26T09:25:50.449592+09:00`.
- 그래프 마지막 연산에서 xywh에 `1/512`를 곱하고 11개 LOGISTIC 점수와 결합함을 확인했다.
  기존 parser처럼 objectness 곱셈이나 추가 sigmoid를 적용하지 않는다.
- Git HEAD의 이전 512 바이너리는 메모리에서만 읽어 입력 `[1,3,512,512]`, 출력 `[1,13,5376]`,
  기존 9개 클래스 순서를 확인했다. 작업 트리 모델에 쓰거나 교체하지 않았다.
- 함께 제공되는 640 모델은 입력 `[1,3,640,640]`, 출력 `[1,13,8400]`, FLOAT32, 9개 클래스다.
  SHA-256 `32a44bc7432ef2d7ca5de802dd8e714a26568aea16566bad3fd7fcd2b85bcee2`.

실제 Interpreter 검사 및 그래프 정보:
[model-inspection JSON](validation/yolo-eleven-class-model-inspection-20261026.json).
호스트 검사 런타임은 ai-edge-litert 2.2.0이며 Android 앱의 의존성 litert 2.1.1은 변경하지 않았다.

## 클래스 순서의 근거

새 모델 안의 `metadata.json.names`를 직접 읽었다. 로컬 `labels.txt`와 모든 인덱스가 일치한다.
학습 경로는 메타데이터에 `/home/samin_khj/datasets/03_merged/v1/data.yaml`로 기록되어 있다.
학습 YAML 원본은 이 저장소에 없으며, 클래스 순서는 추정하지 않고 모델에 포함된 names를 사용했다.

| ID | 모델 클래스명 | 640 모델 | 512 모델 |
|---|---|---|---|
| 0 | bollard | 지원 | 지원 |
| 1 | person | 지원 | 지원 |
| 2 | bicycle | 지원 | 지원 |
| 3 | car | 지원 | 지원 |
| 4 | motorcycle | 지원 | 지원 |
| 5 | bus | 지원 | 지원 |
| 6 | truck | 지원 | 지원 |
| 7 | stop sign | 지원 | 지원 |
| 8 | bench | 지원 | 지원 |
| 9 | cat | 없음 | 지원 |
| 10 | dog | 없음 | 지원 |

## 변경 파일과 동작

| 파일 (`app/src/` 기준) | 변경 내용 |
|---|---|
| `main/java/.../detector/ModelConfig.kt` | 기존 9개 순서 보존, cat/dog 추가, 모델별 명시적인 클래스 목록. 미확인 모델의 인덱스 추정 금지 |
| `main/java/.../detector/ModelAssets.kt` | 공용 labels.txt 11개 순서 검증 후 선택 모델의 9/11개 계약 반환. 설정 검사와 탐지기가 같은 경로 사용 |
| `main/java/.../detector/VisionStyleYoloDetector.kt` | 모델별 라벨 사용, 초기화 로그에 ID:이름 전체 순서 기록 |
| `main/java/.../policy/ObjectTuningPolicyRegistry.kt` | cat/dog 표시 허용, 경고·음성 비활성화, 모든 출력 피드백 제거 |
| `main/java/.../ui/BoundingBoxOverlay.kt` | cat 보라색 `#BA68C8`, dog 청록색 `#26C6DA`. 기존 색상 분기 유지 |
| `main/java/.../metrics/DetectionMetricsCollector.kt` | 클래스별 건수 표시 상한을 지원 클래스 수로 확장, 표시된 클래스의 `Visible avg conf` 추가 |
| `main/java/.../settings/SettingsActivity.kt` | 선택 모델의 클래스 수·순서 표시, 512/640 지원 범위와 경고 제외 안내 |
| `test/java/.../detector/YoloTensorContractTest.kt` | 15채널 양쪽 layout, 불일치 거부, 11개 점수 채널 전체 복사 검증 |
| `test/java/.../policy/NineClassModelPolicyTest.kt` | 기존 9개 클래스의 매핑·정책·필터 회귀 검증 유지 |
| `test/java/.../policy/ElevenClassModelPolicyTest.kt` (신규) | 모든 ID, 미확인 모델 거부, 경고 제외, 상세 로그·통계 검증 |
| `androidTest/java/.../detector/ElevenClassDetectorTest.kt` (신규) | 실제 새 모델 shape/버퍼, 11종 파서·NMS·confidence·잘못된 BBox·ROI 좌표·필터 통합 검증 |
| `androidTest/java/.../detector/ModelSelectionDetectorTest.kt` | 640→512→640 전환에서 모델별 클래스 계약, 설정 inspector 일치 검증 |
| `androidTest/java/.../detector/DetectorCpuOptimizationTest.kt` | 두 모델 각각 CPU 2/4스레드 × XNNPACK ON/OFF에서 기존 I/O·파서와 비교 |
| `androidTest/java/.../ui/BoundingBoxOverlayTest.kt` | cat/dog 및 기존 클래스 실제 Canvas 색상·라벨 회귀 검증 |

그 외 `tools/test_yolo_model_contract.py`를 추가해 실제 모델의 텐서, 모든 metadata names,
labels.txt 및 유한한 출력/점수 범위를 반복 검사할 수 있게 했다. README에 현재 모델 사용법을 반영했다.

`YoloTensorContract.kt`와 출력 reader는 이미 `4 + classes` 및 실제 candidates를 사용한다.
탐지기의 `outputData`도 `Array(outputDim) { FloatArray(boxCount) }`이고 출력 ByteBuffer는
`outputTensor.numBytes()`로 할당한다. 따라서 이 연산 자체는 수정할 필요가 없었다.
새 shape 및 모든 채널 복사 테스트를 추가했고, NMS는 기존의 **같은 label끼리만 억제**하는 구현을 유지했다.

## 기존 기능과 경고 정책에 미치는 영향

- 기존 0~8번의 confidence 기준, geometry/size/temporal 필터 및 경고 정책 값은 변경하지 않았다.
- 새 동물 클래스는 `enableOverlay=true`, `enableWarning=false`, `enableVoice=false`,
  위험 분류 `UNKNOWN`이다. `YoloDefaultPolicyRegistry`는 계속 기존 9종만 반환한다.
- 경고 대상 필터에서 제외하며, display-only 정책의 `warningFeedback`도 NONE으로 지워
  beep·진동·음성 출력에 포함되지 않게 했다. 기존 person 음성 조정은 그대로다.
- `SmallBoxFilterPolicy`의 크기 필터를 사용하면 새 클래스에도 기존 모드별 기준을 적용한다.
  NORMAL에서 cat/dog의 최소 면적 비율은 0.015이고 최소 너비/높이는 공통 설정을 사용한다.
  기본 DISABLED는 유지했다. Bollard 전용 geometry/temporal 조건은 동물에 적용되지 않는다.
- 기존 `Top labels`/`Avg conf` 및 경고 건수는 기존처럼 경고 후보 기준이다.
  `Label raw`/`Label visible`과 신규 `Visible avg conf`에서 동물을 포함한 11종을 확인한다.
- RGB /255, normalized xywh, LETTERBOX RGB114 padding, STRETCH, ROI 및 원본 좌표 복원,
  maxCandidates, NMS threshold, CPU/XNNPACK 설정, 타이머·성능 로그 및 ML Kit 코드는 유지했다.
- 기본 모델 및 저장된 설정을 강제로 변경하지 않았다. **새 동물 검출은 설정 → YOLO 모델 및 추론 →
  모델 선택에서 YOLO 512×512를 선택하고 적용/저장**한다. 기존 640 모델은 9종만 검출한다.
- 512 출력은 13→15채널로 증가한다. 출력 버퍼와 재사용 FloatArray가 각각 43,008 bytes 증가한다.
  입력 크기·후보 수는 동일하다. 처리 시간 동일 여부는 이 정적 계산만으로 판단하지 않는다.

## 검증

- `gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest --offline`: 성공.
  전체 JVM 단위 테스트 191개 통과, 실패/오류/skip 0.
- `.venv/Scripts/python.exe -m unittest discover -s tools -p test_yolo_model_contract.py -v`:
  실제 두 모델/메타데이터/labels/출력 추론 검사 통과.
- Android SM-S921N 실기기에 디버그 앱 및 테스트 APK를 `install -r -t`로 설치했다.
  앱 데이터를 삭제하지 않았다. 최초 44개 중 43개가 통과했다.
  기존 `attachedTimerExpiresWithoutFurtherResultsAndRecreationClearsOldView` 1건은 단말 화면이
  Dozing 상태로 꺼져 Activity가 STOPPED에 머물며 실패했다. 화면을 깨우고 재실행하자 통과했다.
  단말의 화면 timeout/전원 설정은 변경하지 않았다.
  [실기기 회귀 로그](validation/yolo-eleven-class-instrumented-20261026.txt).
- UI 재생성 재검증 및 두 모델 CPU 2/4스레드 × XNNPACK ON/OFF 비교: 3개 모두 통과.
  [재검증 로그](validation/yolo-eleven-class-recheck-20261026.txt).
  CPU 비교는 동일 모델/런타임 옵션에서 기존 입력 writer/run() 및 동결한 parser와 대조한다.
  입력은 byte-for-byte, 원 출력은 `1e-5 + abs(expected)*1e-4`, confidence는 `1e-4`,
  좌표는 0.1px 오차 기준을 사용한다.
- 설정 화면에서 512 모델 선택 → 재생성 → 적용/저장 → 재진입 → 사용자 프리셋 유지 검증도 통과했다.
  테스트 종료 시 기존 단말 설정을 복원했다. [설정 UI 로그](validation/yolo-eleven-class-settings-20261026.txt).
  재실행 후 최종 결과를 기준으로 서로 다른 실기기 테스트 **45개 모두 통과**했다.
- 11개 클래스의 동일 위치 BBox에 각 클래스별 중복 후보를 넣어 22→11 NMS 결과를 확인한다.
  다른 클래스의 겹치는 BBox는 모두 보존하며, 같은 클래스라도 겹치지 않는 BBox는 보존한다.
  ROI+LETTERBOX/STRETCH 좌표 오차 허용치는 0.001px이고 confidence는 원 점수 그대로 비교한다.
- 0.2 confidence 경계, 아래 threshold, NaN score/좌표, 폭 0, 화면 밖/경계 clipping을 검사한다.
- 기존 known-positive bollard 이미지로 두 모델의 실제 추론, 유한 출력, 입력 버퍼·Bitmap 재사용,
  640→512→640 모델 전환을 확인한다.

## 미확인 및 검증 범위

- 학습 YAML 원본은 로컬에 없다. 모든 클래스 ID는 모델 내보내기 메타데이터와 일치함을 확인했다.
- 저장소에 정답이 있는 cat/dog 실제 이미지나 평가 데이터셋이 없다. 합성 출력 테스트는
  앱의 매핑·파싱·좌표·NMS·표시·정책 처리를 입증하며, 동물 recall/precision이나 모델 자체 정확도는
  실물 이미지 테스트가 필요하다.
- 재학습으로 바뀐 가중치의 기존 9종 confidence/recall이 이전 모델과 동일하다는 의미는 아니다.
  앱의 기존 수치 처리와 정책 및 기존 640 모델의 회귀를 검증했다.
  기존의 후보별 최고 점수 클래스 선택 및 전 클래스 공통 `maxCandidates=100` 제한도 유지하므로,
  새 클래스의 후보가 추가되면 이 제한 안에서 기존 클래스와 함께 점수순으로 선택된다.
- 새 모델의 장시간 카메라 FPS/배터리/발열 및 이전 512 모델 대비 지연 차이는 측정하지 않았다.
  기존 성능 시험 설정/진단 및 측정 환경은 유지했다.
- 작업 시작 전부터 존재한 모델·라벨 변경과 `docs/yolo-longrun-performance-degradation-20261026.md`는 보존했다.
  커밋과 푸시는 실행하지 않았다.
