# YOLO11s / YOLO11n 모델 정리 결과

작성일: 2026-11-09 (Asia/Seoul). 커밋·푸시·학습·재학습·모델 변환 없음.

## 모델 등록 상태

| 모델 | asset | 등록 ID | 상태 | 파일 크기 |
| --- | --- | --- | --- | ---: |
| YOLO11s | `best_512.tflite` | `YOLO_512` | 기존 기준·기본 모델 유지 | 37,931,072 B |
| YOLO11n | `yolo11n_512.tflite` | `YOLO_11N_512` | 신규 경량 모델 등록 | 10,599,278 B |
| 구형 640 | `best.tflite` | 구 `YOLO_640` | asset·등록·라벨 분기 제거 | — |
| 구형 11클래스 | `best_512_v1_11class.tflite` | 구 `YOLO_512_V1` | asset·등록·라벨 분기 제거 | — |

YOLO11n은 사용자 제공 `D:/develop/Data/GOTORO/train_run_261109/yolo11n_512.tflite`를 그대로 가져왔다. 대체 파일을 생성하거나 YOLO11s를 복사하지 않았다.

- YOLO11s SHA-256: `591509278b3a1fe114a0084d4841b948a93b637c22005b7becf06309cb906667` (변경 전후 동일).
- YOLO11n SHA-256: `fa8f25724210a15c4259c82cbf8d87a4169d0ab7f1bb644a6620173aa52bae32` (원본과 동일).
- 설정 선택지: **YOLO11s · 기준 모델**, **YOLO11n · 경량 모델**. 각각 클래스 수·입력 크기·파일명을 표시한다.

## 사용처 조사 및 변경

저장소 전체 파일명·enum 참조를 조사했다. 실제 의존성은 `ModelConfig`의 기본 asset·등록·라벨, `AppSettingsCodec`의 구형 schema 기본값, 설정 화면의 enum 선택/모델 정보, 모델 전환·정책·초기화·성능 회귀 테스트 및 PC golden 도구 기본값이었다.

- 공통 로더·디버그 Self Test·SHA 조회의 기본 asset도 YOLO11s로 통일했다.
- 설정 schema를 12→13으로 올렸다. 누락·빈 값·삭제 enum·알 수 없는 모델 값은 YOLO11s로 복구한다. 정상 YOLO11s/YOLO11n 선택은 유지한다.
- 공통 codec을 사용하는 적용 설정·사용자 프리셋·복구 백업에서 모델만 복구하며 ROI·threshold·threads·XNNPACK·클래스 옵션·경고 등 나머지 값은 보존한다. 기존 pending/attempting 복구 절차를 유지한다.
- 모델별 회귀 테스트를 두 모델 대상으로 수정했다. 이전 640 크기를 전제로 하던 golden/전처리/호출 버퍼 테스트도 실제 512 입력에 맞췄다.
- 경고 대상 9클래스 집합은 `WARNING_CLASS_LABELS`로 명확히 이름만 바꿨다. cat/dog/cardboard_box는 기존대로 표시·통계만 활성화하고 경고·음성은 제외한다.
- 1~3차 최적화의 버퍼 재사용·Interpreter 호출·threads/XNNPACK·후처리 구현은 보존했다. 작업 시작 전 있던 3차 detector 변경과 다른 미커밋 작업도 유지했다.
- 과거 마이그레이션 문서·검증 원본 로그는 이력으로 보존했다. 삭제 이름은 복구/거부 테스트 및 역사 기록에만 남는다. README와 현재 실행 도구의 안내는 갱신했다.

## 두 모델의 검증된 계약

| 항목 | 두 모델 공통 |
| --- | --- |
| 입력 | 단일 정적 FLOAT32 `[1,3,512,512]`, NCHW, 3,145,728 B |
| 출력 | 단일 정적 FLOAT32 `[1,16,5376]`, 344,064 B |
| 클래스 ID 순서 | `bollard, person, bicycle, car, motorcycle, bus, truck, stop sign, bench, cat, dog, cardboard_box` |
| 전처리 | 기존 Letterbox/Stretch, RGB `/255`, Letterbox padding RGB 114. 그래프 입구는 transpose→pad→conv이며 추가 입력 정규화 없음 |
| 후처리 | 정규화된 xywh + 12 sigmoid 점수, objectness 없음, 추가 sigmoid 없음, 그래프 내 NMS 없음. 기존 앱 class-aware NMS·threshold·크롭/ROI 좌표 복원 유지 |

실제 metadata의 `Detect`, `detect`, `end2end=false`, 클래스 순서와 그래프 경계를 검사했다. 두 모델의 graph 출력 좌표에 `1/512` 정규화, 점수에 LOGISTIC이 있음을 확인했다. 등록 모델의 shape·dtype·클래스 수가 다르면 Android 로더도 명시적으로 거부한다.

## 검증 결과

- Android Debug 앱·AndroidTest APK 빌드, JVM 테스트 및 lint: 통과. JVM 212개, 실패 0. lint 오류 0, 경고 196개(상세는 빌드 산출물의 lint 보고서).
- Python 모델/기존 분석기 테스트 40개 + Perfetto 도구 테스트 8개: 통과.
- PC LiteRT 2.2.0: 검은 입력에서 유한 출력·0~1 점수 통과. 기존 bollard 이미지의 host BILINEAR 512 입력에서 두 모델 모두 최종 bollard 1개 검출(s 약 0.9349, n 약 0.8933). Android와의 수치 일치·정확도·성능 비교를 의미하지 않는다.
- APK 검사: YOLO 두 asset만 포함, 둘 다 무압축이며 소스 SHA와 일치. ML Kit 의존성의 별도 내장 모델 두 개는 유지했다.
- Android 실행 테스트: 읽기 전용 Pixel_7(API 37/Android 17, x86_64 16KB, RAM 4GB)에서 고유 테스트 **27개 최종 통과**. 모델 전환·실제 이미지 추론·12클래스 파싱/NMS·ROI 복원·전처리/버퍼 재사용·threads/XNNPACK·3차 최적화·프리셋/초기화/백업 복구를 검증했다.
- 최초 26개 중 24개 통과, UI 2개는 SystemUI 응답 없음 대화상자의 포커스 점유로 실패했다. 환경 정리 후 해당 2개와 수정한 호출 버퍼 테스트 1개를 실행해 3개 모두 통과했다. 초기 2GB AVD 실행은 메모리 부족으로 중단하고 4GB에서 다시 실행했다. AVD는 읽기 전용이며 검증용 백그라운드 앱 정지·화면 유지 설정은 저장하지 않는다. 실기기·카메라 통합·장시간 성능 검증은 미수행이다.

근거: [검증 요약](validation/yolo-two-model-test-summary-20261109.json), [모델 검사](validation/yolo-two-model-inspection-20261109.json), [모델 계약 테스트](validation/yolo-two-model-contract-tests-20261109.txt), [빌드·JVM·lint](validation/yolo-two-model-gradle-20261109.txt), [Python](validation/yolo-two-model-python-tests-20261109.txt), [Perfetto](validation/yolo-two-model-perfetto-tests-20261109.txt), [PC smoke](validation/yolo-two-model-host-smoke-20261109.json), [APK](validation/yolo-two-model-apk-20261109.json), [Android 최초 실행](validation/yolo-two-model-android-tests-20261109.txt), [Android 재검증](validation/yolo-two-model-android-retry-20261109.txt).

재현: JBR을 `JAVA_HOME`으로 설정한 뒤 `./gradlew.bat --offline :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`. Python은 `.venv/Scripts/python.exe -m unittest discover -s tools -p 'test_*.py' -v` 및 동일 명령의 `-s tools/perfetto`로 실행한다.
