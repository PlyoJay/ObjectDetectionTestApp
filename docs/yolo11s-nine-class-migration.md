# YOLO11s 9클래스 앱 계약 (2026-10-12)

## 1. 기존 문제와 현재 모델 검사

- `labels.txt`는 `bollard` 한 줄이었다.
- 실행은 `best.tflite`인데 Settings는 `best_float32.tflite`의 SHA256과 고정 NHWC 입력 문자열을 표시했다.
- YOLO 정책에는 과거 COCO 라벨과 미래 custom 라벨이 섞여 있었고, Overlay에는 현재 모델이 COCO YOLO11n이라는 오래된 주석이 있었다.
- detector의 클래스 score 순회, `4 + labels.size` 채널 검사, 같은 label끼리의 NMS는 이미 다중 클래스를 지원하므로 유지했다.

현재 assets를 Python LiteRT Interpreter와 FlatBuffer로 직접 검사한 결과:

| Asset | Input / dtype | Output / dtype | 내장 class names |
|---|---|---|---|
| `best.tflite` | `[1, 3, 640, 640]` / FLOAT32 | `[1, 5, 8400]` / FLOAT32 | `0: bollard` |
| `best_float32.tflite` | `[1, 640, 640, 3]` / FLOAT32 | `[1, 5, 8400]` / FLOAT32 | `0: bollard` |

검사 당시 `best.tflite` SHA256:
`eb605f541257c2ee216e8198d796172201435e03ba5f2d16d9c7bc9372602281`

따라서 현재 binary는 새 9클래스 모델이 아니다. 변경한 라벨과 함께 실행하면 detector 초기화에서
`labels=9 expectedChannels=13 actualOutput=[1, 5, 8400]`을 포함한 오류로 실패한다.
기존 5채널에 9클래스를 억지로 맞추거나 parser 계약을 느슨하게 만들지 않았다. 두 모델 binary는 변경하지 않았다.

## 2. 수정 파일

모든 Kotlin 경로는 `app/src` 아래 `java/com/samin/objectdetection` 패키지를 기준으로 한다.

| 파일 | 변경 |
|---|---|
| `main/assets/labels.txt` | 요청한 9개 라벨을 class ID 순서대로 저장 |
| `main/.../detector/ModelConfig.kt` | 모델명, 라벨 파일명, class ID 순서의 단일 계약 |
| `main/.../detector/ModelAssets.kt` | 모델 mapping, SHA256, 기존 `ModelIdentity` 생성 공통화 |
| `main/.../MainActivity.kt` | detector, 기록, 화면의 모델명을 공통 설정으로 통일 |
| `main/.../evaluation/EvaluationDataRecorder.kt` | 사용되지 않던 과거 YOLO11n 기본 모델명도 공통 설정으로 통일 |
| `main/.../detector/VisionStyleYoloDetector.kt` | 공통 assets 사용, 라벨 순서 검사, 모델/라벨 불일치 설명 보강; 기존 parser/NMS 유지 |
| `main/.../settings/SettingsActivity.kt` | 실제 `best.tflite`에서 읽은 input/output shape와 dtype, SHA256 표시; 읽기 실패도 화면에 표시 |
| `main/.../policy/ObjectTuningPolicyRegistry.kt` | 현재 9클래스와 예약 3클래스 구분, 과거 COCO 정책 제거, 현재 모델만 overlay/warning 허용 |
| `main/.../policy/OverlayObjectFilter.kt` | 현재 라벨과 예약 라벨 명시, 과거 YOLO11n/COCO 주석 제거 |
| `main/.../policy/YoloDefaultPolicyRegistry.kt` | 현재 모델의 warning 정책만 반환 |
| `main/.../warning/WarningPolicy.kt` | 일반 `DetectionCategory`에서도 bollard 및 예약 custom 객체를 OBSTACLE로 분류 |
| `main/.../ui/BoundingBoxOverlay.kt` | 기존 사람/장애물 색상에 차량 주황색, 교통 제어 녹색 추가 |
| `debug/.../detector/DetectorSelfTestActivity.kt` | self-test 기본 모델명 공통화; 명시적 model override는 유지 |
| `test/.../detector/YoloTensorContractTest.kt` | 양방향 13채널 shape, 잘못된 채널, 모호한 축 테스트 추가; 기존 단일 클래스 테스트 유지 |
| `test/.../policy/NineClassModelPolicyTest.kt` | 9클래스 정책 연결, 예약/과거 클래스 제외, geometry/temporal Bollard 한정, size/warning/voice 회귀 검증 |
| `test/.../settings/AppSettingsTest.kt` | 640, full frame, size DISABLED 기본값 검증 보강 |
| `androidTest/.../detector/DetectorGoldenTest.kt` | 실제 입력 NCHW/NHWC에 맞는 RGB 검사, 9클래스 output/SHA256 검사, layout별 letterbox padding 검사 |
| `README.md`, 기존 추론 검증 문서, 이 문서 | 현재 전환 상태와 과거 1클래스 golden 기록 구분 |

## 3. 9클래스 처리 구조

고정 class ID 순서는 다음과 같다:

```text
0 bollard
1 person
2 bicycle
3 car
4 motorcycle
5 bus
6 truck
7 stop sign
8 bench
```

1. 입력 Tensor shape로 NCHW/NHWC를 결정하고 RGB `/255` 입력을 작성한다. 입력 크기는 640을 유지한다.
2. `YoloOutputShape`가 `[1, 13, 8400]` 또는 `[1, 8400, 13]`의 채널 축을 판별한다. objectness 추가/graph NMS 출력은 현재 계약이 아니다.
3. 각 candidate에서 9개 score 중 최대값과 class ID를 선택하고, 라벨 파일의 동일 index를 `DetectionResult.label`로 사용한다.
4. confidence, 유효 bbox, detector size, candidate limit, 같은 label끼리의 NMS를 적용한다.
5. pipeline은 원본 프레임으로 좌표를 매핑하고 기존 WarningPolicy의 기본 근접도 정보를 계산한다. 이어 geometry → pipeline size → 현재 overlay label → temporal 필터를 적용한다.
6. motion tracker 이후 risk/scenario를 다시 계산하고 voice tuning을 적용한다. overlay는 그 결과를 그리며 warning 후보는 클래스별 confidence와 cooldown/선택/출력 정책을 거친다.

실제 코드 순서를 유지했다. 기본 근접도 계산이 필터 전에도 수행되지만, 최종 motion/risk 및 warning 출력은 필터 이후다.

| 클래스 | Risk 분류 | 기본 warning 최소 confidence | Voice |
|---|---|---|---|
| bollard | TEMPORARY_OBSTACLE | 0.20 | 활성 |
| person | HUMAN_FLOW | 0.50 | 비활성, 기존 beep/vibration 유지 |
| bicycle / car / motorcycle / bus / truck | VEHICLE_RISK | 0.50 | 활성 |
| stop sign | TRAFFIC_CONTROL | 0.45 | 활성 |
| bench | STATIC_OBSTACLE | 0.45 | 활성 |

이 표는 warning 후보 허용 기준이다. 실제 알림은 거리/위치/motion/scenario/출력 설정에 따라 달라진다.
overlay의 confidence는 detector의 0.20을 사용하므로 warning 기준 미만의 객체도 표시할 수 있다.
기존 scenario의 risk priority와 per-class tuning 값은 유지했다.

`stairs`, `curb`, `low_obstacle`는 예약 정책으로 조회할 수 있지만 현재 모델의 overlay/warning 목록에는 포함하지 않는다.
기존 WarningPolicy의 일부 legacy 라벨 분류는 공통 결과 변환 호환성을 위해 유지되지만, 현재 YOLO 클래스나 활성 tuning 정책으로 등록되지는 않는다.

BollardGeometryValidator와 AdaptiveTemporalValidator는 bollard만 제한한다. 다른 8클래스는 그대로 통과한다.
`BollardDiagnostics`는 로깅이며 다른 클래스에 필터를 적용하지 않는다. 전역 candidate/NMS 통계도 이 기존 tag로 기록된다.

기본값은 640 / LETTERBOX / full frame / confidence 0.20 / NMS 0.45 / SizeFilterMode.DISABLED를 유지했다.
카메라, 성능, 모션, TTS/beep/vibration 출력 로직과 dependency는 변경하지 않았다.

## 4. 테스트 결과

실행 명령:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat testDebugUnitTest compileDebugAndroidTestKotlin --console=plain
```

전체 17개 suite, 63개 unit test 통과, failure/error/skipped 0.
`YoloTensorContractTest`, `BollardDetectionPolicyTest`, `AppSettingsTest`, 모든 warning tests와 새 `NineClassModelPolicyTest`가 포함된다.
Android instrumentation 테스트 코드는 컴파일에 성공했다. 새 9클래스 binary가 없으므로 단말에서 실제 추론 및 instrumentation 실행은 수행하지 않았다.

실제 asset 검사는 다음 도구를 사용했다:

```powershell
.\.venv\Scripts\python.exe tools/inspect_tflite.py app/src/main/assets/best.tflite app/src/main/assets/best_float32.tflite
```

## 5. 앱 실행 시 확인할 사항

새 모델을 넣고 다시 빌드/설치한 뒤:

- 초기화 log에 `modelAsset=asset://best.tflite`, `labels=9`, `classCount=9`가 있어야 한다.
- 실제 input은 `[1, 3, 640, 640]` 또는 `[1, 640, 640, 3]` 등 parser가 지원하는 RGB layout이고 input/output dtype도 확인한다.
- output은 `[1, 13, 8400]` 또는 `[1, 8400, 13]`이어야 한다. 다른 shape면 실제 tensor와 export 조건부터 비교한다.
- Settings의 Model/Input/Output/SHA256이 초기화 log 및 PC 검사와 일치해야 한다.
- 9개 객체별로 label, bbox 위치, confidence를 확인한다. SIMPLE 모드에서 confidence 표시를 켜거나 FULL 모드를 사용한다. NONE 모드는 기존대로 label을 숨긴다.
- person은 분홍색, 차량 5종은 주황색, stop sign은 녹색, bollard/bench는 기존 파란색이다. ignored 객체는 기존 회색이다.
- Bollard geometry/temporal ON 상태에서 넓거나 작은 다른 8클래스 bbox가 Bollard 전용 조건으로 제거되지 않는지 확인한다.
- 차량 접근, stop sign 안내, 가까운 bench/bollard 경고 및 person 음성 비활성 동작을 실제 출력 설정과 함께 확인한다.

## 6. 남은 작업

`app/src/main/assets/best.tflite`를 학습한 9클래스 binary로 교체해야 앱 초기화가 성공한다.
내장 class names도 위 ID 순서와 일치하는지 확인한다. 같은 이름의 파일을 교체하므로 새 SHA256으로 식별한다.
현재 parser는 normalized xywh를 기대한다. 새 export의 좌표 단위를 graph/기존 PT↔TFLite 비교로 확인하고,
pixel xywh나 end-to-end/NMS export이면 현재 계약과의 차이를 먼저 분석한다.

교체 후 빌드/설치 및 실제 9클래스 단말 검증, `connectedDebugAndroidTest`를 수행한다.
`self_test/known.png`는 이전 bollard 모델의 positive fixture이므로 새 모델에서도 positive인지 확인하거나 검증한 PNG를 별도로 제공한다.

`best_float32.tflite`는 현재 앱 및 기본 self-test에서 더 이상 참조하지 않는다.
이전 검증 문서/inspection 자료에만 남고 debug self-test의 명시적 model override로 선택할 수 있다.
다만 현재 9클래스 라벨과는 맞지 않아 override로도 초기화에 실패한다. 보관/비교용 필요가 없으면 삭제할 수 있으며 이번에는 삭제하지 않았다.
