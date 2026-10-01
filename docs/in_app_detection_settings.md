# 인앱 객체 인식 설정 분류

이 문서는 기존 기본값을 기준으로 인앱 설정에 노출한 값과 적용 방식을 정리한다. 현재 구현은 설정을 일괄 검증·저장한 뒤 Activity를 재생성한다. 따라서 실행 중 프레임, Temporal 상태, Motion history, Interpreter, CameraX가 서로 다른 설정을 공유하지 않는다.

## 변경 영향 분류

| 분류 | 설정 |
|---|---|
| 실시간 반영 가능 | 출력 진동/Beep/TTS, Overlay 표시/세부 표시, debug log 스위치, cooldown 및 안내 개수 |
| Pipeline 재생성 필요 | ROI, size/geometry/temporal filter, motion history/threshold, warning proximity threshold |
| Interpreter 재생성 필요 | resize mode, confidence, NMS, thread count, max candidates, detector diagnostics |
| CameraX 재생성 필요 | 요청 해상도, detect interval |
| 읽기 전용 | 모델명/SHA256, tensor shape, 실제 분석 해상도, 앱 버전 |
| 내부 상수 | tensor parser/shape 계약, class/label 정책, bbox 수식, 표현 및 queue 상수 |

실시간 반영이 가능한 값도 세션 원자성을 위해 현재 1차 구현에서는 다른 값과 동일하게 안전 재생성 경로를 사용한다.

## 적용 시 재생성되는 설정

- Camera: detect interval, Full Frame/Center Square ROI, 요청 분석 해상도
- YOLO: STRETCH/LETTERBOX, confidence, NMS, Interpreter thread count, max candidates
- Filter: size mode, 기본 bbox 면적/폭/높이 비율, Bollard geometry ON/OFF와 면적/종횡비, Adaptive Temporal ON/OFF와 confidence/IoU
- Motion: history 크기, 매칭 거리, 샘플 간격, stale timeout, 면적/높이 변화 기준
- Warning: cooldown, 상단 무시 영역, 최대 안내 객체 수, 높이/면적 근접도 기준
- ML Kit: ON/OFF와 실행 간격
- Debug recorder: diagnostics/logging/raw candidate 기준, 입력 PNG 저장 조건/간격, 호환 debug image

Interpreter thread 수, YOLO 전처리 및 threshold 변경은 detector를 다시 만든다. 요청 카메라 해상도와 detect interval 변경은 CameraX use case를 다시 만든다. Motion/Temporal 변경은 tracker/validator를 다시 만들어 이전 history를 버린다. 나머지 설정도 동일한 원자적 적용 경로를 사용한다.

## 출력과 화면 설정

- 실제 진동, Beep, TTS는 경고 후보 계산과 독립된 출력 스위치다.
- Overlay 전체, YOLO bbox, ML Kit bbox, confidence, FPS, debug mode, stale timeout을 설정할 수 있다.
- 출력 하나라도 비활성화되면 카메라 화면에 `TEST OUTPUT` 상태를 표시한다.
- 설정/빠른 설정 진입은 녹화 또는 성능 로그 세션 중 차단해 한 세션에 서로 다른 설정이 섞이지 않게 한다.

## 읽기 전용

- 모델 asset 이름과 SHA256
- 실제 모델 input tensor shape(640×640)
- 실제 카메라 분석 해상도
- 앱 버전

모델 input 크기와 tensor layout은 패키지의 TFLite 모델 및 parser 구현과 결합되어 있어 변경 설정으로 노출하지 않는다. 실제 분석 해상도는 기기의 CameraX 협상 결과이므로 요청 해상도와 구분해 성능 로그에 기록한다.
해상도 드롭다운은 후면 카메라의 Camera2 YUV 출력 크기만 표시하며, CameraX use-case 조합 결과가 요청값과 다를 수 있다.

## 내부 상수로 유지

- YOLO output tensor parser의 `xywh + class scores` 구조, channel order와 normalization
- LETTERBOX padding 색상(114, 114, 114)
- 모델/labels class count 일치 검사와 tensor 차원 검사
- bbox 좌표 유효성 검사, IoU 수식, buffer 크기 및 tensor element 크기
- per-class `ObjectTuningPolicyRegistry`의 label 목록, warning/voice 정책과 class별 confidence/size 기준
- Warning scenario별 메시지와 feedback 매핑
- Overlay paint, 글꼴, 색상, padding 같은 표현 상수
- 비동기 로그 queue/flush 크기와 analyzer shutdown 안전 timeout

이 값들은 모델 계약, 데이터 구조, 정책 정의 또는 단순 구현/표현 상수다. 필드테스트 중 임의 변경 시 parser 오류나 정책 의미 변경이 발생할 수 있어 인앱 설정 대상에서 제외했다.

## 프리셋

- `DEFAULT`: `DetectionConfig` 기본값 (LETTERBOX, 입력 640×640, confidence 0.20, NMS 0.45, size DISABLED)
- `FIELD TEST - STRETCH`: 기존 `DetectionConfig.fieldTest(STRETCH)` 조건
- `FIELD TEST - LETTERBOX`: STRETCH 프리셋과 resize mode만 다름
- `CUSTOM`: 빠른 설정 또는 임의 편집 결과
- 사용자 프리셋: 이름 지정 저장, 이름 변경, 삭제 및 재선택 지원

저장 형식은 schema version이 포함된 Properties snapshot이다. schema v2는 v1의 `DEFAULT` snapshot이 이전 기본값 전체와 같을 때만 resize를 LETTERBOX로 이관한다. 다른 값 하나라도 수정했거나 사용자 이름/`CUSTOM`/명시적 FIELD TEST - STRETCH를 사용한 설정은 STRETCH를 보존한다. v1에서 누락된 resize는 이전 STRETCH 기본값으로 읽은 뒤 이 조건을 적용한다. 이관한 적용 설정은 v2로 저장하며 사용자 preset과 복구 snapshot을 삭제하지 않는다. 기존 FIELD TEST를 새 진단 조건으로 사용하려면 프리셋을 다시 선택하고 적용한다.

숫자 파싱 실패나 검증 실패 시 안전 기본값을 사용한다. 새 설정 적용 후 런타임 구성요소 생성이 완료되기 전에 앱이 종료되면 다음 실행에서 이전 설정 snapshot으로 복구한다. 전체 모델/평가/갤러리 검증 절차는 [model-evaluation-reliability.md](model-evaluation-reliability.md)에 정리했다.
