# best.tflite Android inference 검증 (2026-10-06)

## 1. 발견한 문제

작업 시작 시 `HEAD`와 fetch한 `origin/main`은 모두 `05e7da4`였다. 모델 파일이나 threshold를 변경하지 않았다.

`VisionStyleYoloDetector` 초기화는 `[1,3,H,W]`에서 크기를 읽었지만,
`fillInputBuffer`는 항상 `RGBRGB...` 순서로 FLOAT32를 썼다. 새 모델은
`RRR...GGG...BBB...` 순서의 NCHW를 요구한다. shape를 읽는 것과 실제 버퍼를
그 layout으로 쓰는 것은 별개의 작업이다.

실제 Interpreter와 FlatBuffer를 검사한 결과:

| 항목 | best.tflite | best_float32.tflite |
|---|---|---|
| 입력 | `[1,3,640,640]`, NCHW | `[1,640,640,3]`, NHWC |
| 출력 | `[1,5,8400]` | `[1,5,8400]` |
| dtype | 입력/출력 FLOAT32 | 입력/출력 FLOAT32 |
| quantization | scale=0, zeroPoint=0, 비양자화 | scale=0, zeroPoint=0, 비양자화 |
| 클래스 | bollard 1개 | bollard 1개 |
| 출력 의미 | normalized xywh + class score | normalized xywh + class score |

새 모델 SHA-256:
`eb605f541257c2ee216e8198d796172201435e03ba5f2d16d9c7bc9372602281`.
내장 metadata는 Ultralytics 8.4.173, YOLO11s Detect, `end2end=false`다.
첫 연산은 `[0,2,3,1]` transpose, 이후 padding/conv이며 입력에 `/255`를
적용하는 graph 연산은 없다. 마지막 box 경로에는 `1/640` 곱셈이 있다.
기존 모델은 box decode에 stride/640 (`0.0125..0.05`)를 적용한다.
따라서 두 asset의 RGB /255 입력과 normalized xywh 출력 계약을 유지한다.

shape 자체만으로 RGB/BGR 또는 정규화 범위를 추론하지 않았다. export metadata,
graph 경계와 실제 PC/Android 동일 입력 추론을 함께 확인했다.
전체 metadata/graph 근거는 [tflite-model-inspection.json](tflite-model-inspection.json)에 있다.

추가로 발견한 문제:

- 입력/출력 버퍼는 dtype와 무관하게 4-byte float로 할당하고 파싱했다.
- 출력 축은 두 차원의 대소 관계로 정했다. 이제 `4 + labels.size`인 축으로 결정한다.
- bbox의 값이 1.1보다 크면 pixel 단위라고 판단했다. normalized bbox도 이미지
  밖으로 나갈 수 있으므로 이 휴리스틱을 제거하고 검증된 normalized 계약을 적용했다.
- 카메라 Bitmap 변환은 첫 plane의 RGBA와 마지막 행까지 완전한 row padding을 가정했다.
  CameraX 1.3.4의 `ImageProxy.toBitmap()`으로 교체했다.

기존 output parser는 objectness를 곱하지 않았으며, 이번 `[1,5,8400]` 모델에는
그 동작이 맞다. 추가 sigmoid도 필요하지 않다. unsupported objectness/end-to-end/
multi-output 모델은 metadata 출력 후 명확한 오류로 거부한다.

## 2. Root Cause와 재현 근거

연결된 SM-S921N의 기존 세션
`debug_detection/20261006_161158_421/input/frame_000205.png`를 사용했다.
이는 앱이 이미 저장한 실제 최종 640×640 입력이며 볼라드가 포함돼 있다.
threshold 0.2, NMS 0.45, 동일 `best.tflite`로 비교했다.

| 경로 | 최대 class score | 후보 ≥0.2 | NMS 이후 검출 |
|---|---:|---:|---:|
| 수정 전 실제 Android 로그 | 0.00027467744 | 0 | 0 |
| PC에서 기존 NHWC writer를 NCHW 모델에 재현 | 0.000274676917 | 0 | 0 |
| PC 올바른 NCHW 입력 | 0.928821266 | 10 | 1 |
| 수정된 Android Self Test | 0.92882127 | 10 | 1 |

기존 Android와 PC의 잘못된 layout 결과까지 일치한다. CameraX 저장 이미지,
모델, 정규화, threshold를 그대로 두고 저장 순서만 바꿨을 때 검출이 복원된다.
이번 detection=0의 직접 원인은 입력 layout 불일치로 확인됐다.

Android/PC 입력 tensor SHA-256은 모두
`f36db8c06df5f2128ce41d8bd18eba206c4a64448e3fc19588b2c507a2018c87`.
입력 mean=0.5216720033, RGB mean=[0.5488508154,0.5308599312,0.4853052632].
Android `input.bin`뿐 아니라 `input.png`에서 다시 만든 PC tensor도 byte 단위로 일치했다.

전체 raw output 비교: 최대 절대 오차 `1.013278961e-6`, 평균 절대 오차
`1.700205762e-8`. `atol=1e-5, rtol=1e-4`로 allclose 통과.
PC LiteRT 2.2.0과 Android LiteRT 2.1.1의 CPU 부동소수점 결과에 미세한 차이가
있으므로 output SHA-256의 일치를 요구하지 않는다.
최종 box도 약 `[292.112,250.214,382.999,600.896]`로 일치한다.

## 3. 수정한 파일

| 파일 | 변경 |
|---|---|
| `detector/VisionStyleYoloDetector.kt` | 모든 tensor metadata 로그, shape/dtype에 맞춘 버퍼, raw 통계, normalized 좌표, Self Test/golden export |
| `detector/YoloTensorContract.kt` | NCHW/NHWC writer, 출력 축 검증, FLOAT32/UINT8/INT8 codec, 실제 tensor 통계/checksum |
| `detector/DetectionDiagnostics.kt` | 좌표 계약을 `normalized_xywh`로 기록 |
| `camera/ImageProxyExt.kt` | CameraX 제공 Bitmap 변환, 실제 shape/stride/crop/rotation 로그 |
| `camera/CameraController.kt` | 요청 해상도와 실제 ImageProxy 해상도 로그 |
| `src/debug/.../DetectorSelfTestActivity.kt`, debug manifest/assets | 카메라 없는 실행 화면과 PC-positive 고정 볼라드 PNG; debug APK에만 포함 |
| `YoloTensorContractTest.kt`, `DetectorGoldenTest.kt`, `EvaluationJsonTest.kt` | layout/quantization/axis/통계, 실제 모델, 고정 이미지, portrait letterbox, metadata 회귀 검증 |
| `app/build.gradle.kts` | DEBUG 전용 값 출력/export를 위한 BuildConfig 생성 |
| `tools/inspect_tflite.py` | 실제 모델 구조와 내장 export metadata 검사 |
| `tools/compare_tflite_golden.py`, `requirements-golden.txt` | 같은 TFLite/입력으로 PC 추론, 이전 오류 재현, raw tensor 수치 비교 |
| `.gitignore`, docs | 로컬 환경/대형 tensor 산출물 제외 및 재현 절차 |

성능 구조, detector threshold, NMS threshold, geometry/size/temporal/경고 정책은 유지했다.
`DetectionDebugRecorder`의 기존 최종 입력 PNG 저장 경로도 유지했다.

## 4. 추가한 진단 로그

`VisionStyleYoloDetector` 태그:

- `[TENSOR_METADATA]`: input/output 개수. `InputTensor[i]`, `OutputTensor[i]`는 모든
  tensor의 shape/dtype/scale/zeroPoint/bytes를 Interpreter 생성 직후 출력.
- `[INPUT_CONTRACT]`: 실제 layout, RGB /255, codec, parser 계약.
- `[INPUT_TENSOR]`: 실제 버퍼 min/max/mean, RGB mean, non-finite 수, SHA-256.
- `[OUTPUT_TENSOR]`: 추론 완료 직후 전체 raw output min/max/mean, non-finite 수, SHA-256.
- `[RAW_OUTPUT]`: confidence/NMS 적용 전 전체 후보의 최대 점수와
  `candidateAbove0.01/0.05/0.1/0.2`, confidence 통과 수, invalid box 수.
- `[SELF_TEST]`: 입력 이름, tensor 후보 수, confidence 통과 수, 최종 검출 수, 저장 경로.
- `[SELF_TEST_ERROR]` 또는 `[DETECTOR_ERROR]`: 추론 실패와 정상적인 0건을 구분.

통계는 `enableDetectorDiagnostics` 또는 `debugDetectionLogging`이 켜졌을 때
첫 프레임과 이후 약 2초 간격으로 기록한다. 첫 12개 값은 DEBUG 빌드에서만
표시한다. 파일 로그는 기존 `debugDetectionLogging=true` 조건을 따른다.
Self Test는 통계를 강제로 기록하고 `self_test.txt`에도 저장한다.
`diagnosticRawConfidenceThreshold`는 기존 `[RAW_YOLO]` 상세 후보에 적용되며,
전체 최대 점수/구간별 후보 수는 그 값과 상세 기록 개수 제한에 영향을 받지 않는다.

카메라 태그에는 `[CAMERA_SHAPE]`, `[CAMERA_INPUT]`, `[CAMERA_BITMAP]`이 추가됐다.
기존 세션의 실제 proxy는 1440×1080, rotation=90이었고 회전 후 Bitmap은
1080×1440이었다. 1280×720 요청값과 이 크기가 달라도 그 자체는 오류가 아니다.
CameraX가 YUV→RGBA 변환하며 앱은 Bitmap의 RGB를 읽는다. 프로젝트에 설치된
CameraX 1.3.4 소스의 byte 순서는 R,G,B,A였다. 형식은 런타임 로그에 남긴다.
공식 guide의 채널 순서 서술에만 의존해 임의로 R/B를 뒤집지 않았다.

회전 → 선택적 앱 ROI crop → letterbox 순서를 유지한다. `ImageProxy.cropRect`를
새로 적용하지 않으며 로그에 `proxyCropApplied=false`로 명시한다.
1080×1440 입력의 letterbox는 480×640, scale=4/9,
padding L/R=80, T/B=0, RGB=114다. 기기 테스트에서 실제 tensor 값까지 확인했다.

## 5. PC vs Android 검증 방법

저장소 루트의 PowerShell에서:

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r tools/requirements-golden.txt
.\.venv\Scripts\python.exe tools/inspect_tflite.py app/src/main/assets/best.tflite
```

고정 이미지 Self Test (카메라 권한/CameraX 불필요):

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.samin.objectdetection/.detector.DetectorSelfTestActivity
```

기본 입력은 debug asset `self_test/known.png`다. 다른 이미지를 사용할 경우:

```powershell
adb push C:/path/known.jpg /sdcard/Android/data/com.samin.objectdetection/files/known.jpg
adb shell am start -n com.samin.objectdetection/.detector.DetectorSelfTestActivity --es input /sdcard/Android/data/com.samin.objectdetection/files/known.jpg
```

원본 크기의 RGB 이미지를 주면 Android에서 동일한 letterbox를 적용한다.
Self Test는 별도 detector를 worker에서 실행하므로 카메라 analyzer와 Interpreter를 공유하지 않는다.
이 테스트는 geometry/size/ML Kit/temporal을 제외하고 detector/NMS까지 비교한다.

화면/`[SELF_TEST]` 로그에 출력된 디렉터리를 가져온다:

```powershell
adb pull /sdcard/Android/data/com.samin.objectdetection/files/golden/self_test_<timestamp> golden_runs/android
```

산출물: 최종 `input.png`, 실제 입력 `input.bin`, 실제 raw 출력 `output_0.bin`,
model/input/output metadata와 통계·최종 box가 담긴 `report.json`, `self_test.txt`.
`.bin`은 report에 기록된 dtype/shape/native byte order이며 Android 기기는 LITTLE_ENDIAN이다.
파일은 추론에 사용한 버퍼에서 그대로 복사한다.

```powershell
.\.venv\Scripts\python.exe tools/compare_tflite_golden.py --image golden_runs/android/input.png --android-report golden_runs/android/report.json --android-output golden_runs/android/output_0.bin --directory golden_runs/pc_png --min-detections 1
.\.venv\Scripts\python.exe tools/compare_tflite_golden.py --tensor golden_runs/android/input.bin --android-report golden_runs/android/report.json --android-output golden_runs/android/output_0.bin --directory golden_runs/pc_exact --min-detections 1
```

첫 명령은 PNG→RGB /255→실제 layout의 입력 checksum을 비교한다. 두 번째 명령은
전처리를 완전히 제외하여 런타임/output 차이를 분리한다. 모델 또는 입력 checksum이
다르면 비교를 거부한다. raw output 허용오차 초과도 실패한다.
최종 PNG는 이미 letterbox가 끝났으므로 PC 도구는 resize/crop/회전을 추가 적용하지 않는다.
640×640이 아닌 PNG는 오류로 거부한다.

이전 버그를 고정 asset에서 재현:

```powershell
.\.venv\Scripts\python.exe tools/compare_tflite_golden.py --image app/src/debug/assets/self_test/known.png --simulate-old-layout --directory golden_runs/old_layout
.\.venv\Scripts\python.exe tools/compare_tflite_golden.py --image app/src/debug/assets/self_test/known.png --directory golden_runs/correct_layout --min-detections 1
```

PC 도구의 최종 bbox는 모델 입력 pixel 좌표, Android report bbox는 원본 입력 이미지
pixel 좌표다. 기본 asset은 640×640이므로 동일하다. 다른 크기의 원본은
Android report의 scale/padding으로 역변환해서 비교한다. raw tensor 비교는 원본 크기와 무관하다.

## 6. 테스트 순서와 실행 결과

1. Android Self Test로 고정 볼라드 이미지가 검출되는지 확인.
2. 최종 PNG의 색상/방향/볼라드와 padding을 확인.
3. 같은 `best.tflite`로 PC PNG 입력 재추론 및 input checksum 비교.
4. Android `input.bin`을 그대로 PC에 넣어 raw output 수치 비교.
5. 화면을 켜고 앱의 `FIELD TEST - LETTERBOX` 설정으로 실제 CameraX 테스트.
   저장 PNG와 `[RAW_OUTPUT]`/`[DETECTION_FLOW]`를 같은 frame ID로 확인.

실행한 검사:

- `:app:testDebugUnitTest`: 53개 통과.
- `:app:assembleDebug`, `:app:assembleDebugAndroidTest`, `:app:compileReleaseKotlin`: 성공.
- 연결된 SM-S921N에서 `DetectorGoldenTest`: 실제 NCHW 모델, 고정 볼라드,
  portrait letterbox의 3개 테스트 모두 통과.
- 고정 볼라드 PC/Android 입력 byte 일치, 전체 raw output allclose, 검출 1건 일치.
- 수정 debug APK를 테스트 기기에 데이터 보존 설치했다.

기기 테스트 재실행:

```powershell
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class com.samin.objectdetection.detector.DetectorGoldenTest com.samin.objectdetection.test/androidx.test.runner.AndroidJUnitRunner
```

## 7. 남아 있는 문제와 범위

- 수정 앱의 MainActivity 초기화는 확인했으나 기기가 `Dozing` 상태여서 이번
  작업 중 새 CameraX 프레임의 실시간 검출은 재촬영하지 못했다. 잠금 해제 후
  실제 볼라드를 향해 5번 절차를 실행해야 한다. 기존 필드 PNG의 실제 저장과
  수정 detector의 기기 검출은 이미 검증했다.
- 사용자가 지정한 `samin_khj@192.168.0.174:~/datasets/raw/.../0. 양호`의 추가
  원본 이미지 검증은 SSH 인증 실패(`publickey,password`)로 수행하지 못했다.
  고정 fixture는 접근 가능한 기기 저장 PNG에서 선정했으며 PC-positive임을 직접 확인했다.
- 다른 필드 프레임의 사람이 bollard로 높은 점수를 받는 사례도 관찰했다.
  입력 정확성 수정 이후의 모델 오탐/학습 품질 문제이며 threshold 변경으로 숨기지 않았다.
  데이터셋 전체의 precision/recall을 평가한 결과는 아니다.
- 장시간 latency 증가 원인은 아직 분석하지 않았다. 상세 tensor 진단과 PNG 저장에는
  비용이 있으므로 correctness 확인 후 진단 OFF 조건에서 성능을 별도로 측정한다.
- 다른 출력 계약(SSD, YOLOv5 objectness, end-to-end NMS, multi-head/다중 output),
  FLOAT16 및 per-axis quantized IO는 이번 지원 범위 밖이다. UINT8/INT8 codec은
  per-tensor scale/zeroPoint를 사용하며 실제 현재 모델은 비양자화 FLOAT32다.

참고: [LiteRT Interpreter API](https://ai.google.dev/edge/api/tflite/python/tf/lite/Interpreter),
[Ultralytics 예측 전처리](https://docs.ultralytics.com/modes/predict),
[CameraX image analysis](https://developer.android.com/media/camera/camerax/analyze).
