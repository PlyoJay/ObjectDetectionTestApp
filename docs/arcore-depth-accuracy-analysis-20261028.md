# 메인 객체 Depth 거리 오차 분석과 보완

2026-10-28. 사용자 관찰은 “영상/BBox/거리 갱신은 정상이나 일부 거리값의 실제 오차가 큼”이다. 객체·실측 Z·표시값·FULL/RAW가 묶인 측정 자료는 제공되지 않았다. 따라서 특정 기기 오차의 단일 원인을 확정하거나 MAE 개선 수치를 보고하지 않는다. **코드에서 재현할 수 있는 잘못된 표면 수락과 필터 문제를 수정하고 실제 검증에 필요한 좌표/출처/실측 기록을 추가했다.**

## 1. 가능한 원인과 소스에서 확인한 문제

| 항목 | 확인 수준 | 분석 |
| --- | --- | --- |
| 배경을 객체 표면으로 오인 | 소스·합성 테스트로 확인 | 이전 9개 패치는 BBox 외부와 비교하지 않았다. 내부가 일관된 배경이면 정상 거리로 수락할 수 있었다. 예전 테스트도 배경과 객체가 구분되지 않는 상수 Depth를 유효 객체 거리로 기대했다. |
| 작은/가린 객체의 표면 소실 | 소스에서 확인 | 고정 5×5 패치는 낮은 Depth 해상도에서 객체보다 클 수 있다. 패치별 MAD를 먼저 적용하면 소수의 객체 픽셀이 배경의 이상치로 제거될 수 있었다. |
| 군집 연결 | 소스에서 확인 | 인접 값 간격만 비교하는 군집은 작은 간격의 값들이 이어지면서 서로 다른 표면을 연결할 수 있었다. |
| 단일 큰 거리 오차 즉시 표시 | 소스·합성 테스트로 확인 | 기존 필터는 0.5m/25% 초과 급변이면 이력을 즉시 새 값으로 바꿨다. 큰 단일 오차도 부드럽게 거르지 못했다. |
| 이동 중 표시 지연 | 소스·합성 테스트로 확인 | 최대 4Hz에서 5개 중앙값은 접근·후퇴 시 이전 값들을 계속 섞는다. 정지 노이즈 감소와 이동 반응을 구분하지 않았다. |
| 재투영/출처 전환 | 소스·합성 테스트로 확인 | 같은 Depth timestamp라도 현재 pose로 재투영된 Z가 달라질 수 있는데 이전 중앙값을 그대로 표시했다. RAW/FULL 전환은 매칭 대상에서 제외해 큰 변화 방어를 우회할 수도 있었다. |
| 좌표 정합 | 실제 단말 차이는 미검증 | ROI/Letterbox/회전 복원 경로는 기존 합성 테스트에서 일치했다. 그러나 네 모서리 보간과 같은 Frame의 개별 API 출력 비교가 없어 실제 기기에서의 내부 오차를 관측할 수 없었다. |
| CPU/Depth 유효 영역 | 소스에서 확인 | Depth crop은 검사했지만 CPU Image의 실제 cropRect에 대한 Depth 표본 마스크는 부족했다. |
| RAW/FULL 및 SDK 고유 오차 | 실측 필요 | FULL 보간, RAW 희소성, 저 confidence, 카메라 이동/조명/재질/가림에 따라 오차가 달라진다. RAW가 항상 더 정확하다고 단정할 수 없다. |
| 거리 정의 불일치 | 실측 필요 | 표시값은 카메라 광축 Z다. 렌즈의 사선 거리, 지면상의 수평 거리, 객체 발끝까지의 거리와 같지 않을 수 있다. |

## 2. 좌표 정합 보완

기존 흐름은 유지한다.

`CPU Image → 회전 Bitmap → 기존 ROI/YOLO resize → detector의 Letterbox/Stretch 역변환 → ROI 복원 → 원본 CPU 역회전 → Depth`

새 `DepthCoordinateSnapshot`은 **현재 GL Frame에서** Depth 픽셀 중심 `(x+0.5,y+0.5)`을 `TEXTURE_NORMALIZED → IMAGE_PIXELS`와 `TEXTURE_NORMALIZED → VIEW_NORMALIZED`로 일괄 직접 변환한다. 프레임 timestamp와 함께 복사한 배열만 worker에 전달한다. 추론이 끝난 뒤 오래된 Frame에 API를 호출하지 않는다. 같은 크기의 FULL/RAW는 현재 프레임의 좌표 스냅샷을 공유한다.

sampler는 실제 Depth 픽셀의 직접 변환된 CPU 좌표가 회전 BBox의 내부/외부 어디에 속하는지 판정한다. 이제 읽을 픽셀을 정하는 데 네 모서리 전방 보간에만 의존하지 않는다. CPU cropRect, Depth cropRect, viewport 밖, 좌표/Depth 해상도 불일치와 다른 Frame의 스냅샷을 거부한다. 합성/레거시 스냅샷에만 검증 가능한 affine 역변환을 보조로 허용한다.

분석 설정 ON일 때 `IMAGE_PIXELS → TEXTURE_NORMALIZED` 개별 직접 변환도 같은 Frame에서 수행해 기존 네 모서리 결과와 비교한다. 로그의 최대/평균 Depth 픽셀 오차와 round-trip 오차는 현재 CPU 유효 영역·화면에서 비교한다. 유효한 round-trip이 0.5 Depth 픽셀을 넘으면 분석 결과를 거부한다. 네 모서리와 직접 API가 실제 단말에서 얼마나 다른지는 기록을 확보해야 알 수 있다. 합성 affine 변환은 일치하고, 모서리만 같은 내부 warp는 비교 검사에서 검출되는 것을 테스트했다.

## 3. 객체 표면 선택: 변경 전후

| 단계 | 이전 | 변경 후 |
| --- | --- | --- |
| 표본 | 내부 9지점의 고정 패치 | 객체 투영 크기에 따른 적응 간격의 내부 분포 + BBox 바깥 배경 띠 |
| 위치 | 패치 중심을 보간해 Depth에 투영 | 현재 Frame의 공식 API로 보존한 실제 Depth 픽셀 중심의 CPU 위치 검사 |
| 작은 객체 | 겹친 패치의 유효 표본이 배경일 수 있음 | 실제 고유 픽셀과 중앙부 공간 지지가 부족하면 null |
| 이상치 | 패치 MAD 후 전체 군집 | 여러 표면을 먼저 분리하고 각 군집 안에서 median/MAD 적용 |
| 군집 | 인접 값 간격으로 연결 | 군집 전체 폭도 제한해 bridging 방지 |
| 대표 선택 | 전체 표본의 큰 군집 | 중앙부의 지지와 외곽 배경과의 차이로 선택. 가장 가까운 값 선택 없음 |
| 모호함 | 내부 일관성만으로 수락 가능 | 외곽과 구분 불가, 여러 후보, 외곽/중앙 표본 부족이면 null |

기본 중앙부는 x=30~70%, y=25~75%, 내부 외곽 12%는 제외한다. 배경 띠는 BBox 바깥 18% 범위에서 유효한 부분만 읽는다. 표본 간격은 투영 면적과 목표 내부 표본 수 768로 결정하며 중복 픽셀을 만들지 않는다. 설정 구조로 중앙 비율·외곽 폭·표본 수·품질 기준을 조절할 수 있다.

군집 대표가 외곽 표본의 35% 이상과 비슷하면 배경과 구분되지 않는 것으로 본다. 남은 후보는 최소 12개 표본·3개 공간 영역·6개 중앙 표본 및 중앙 지지 65%가 필요하다. 복수 중앙 후보가 유의하게 남으면 null이다. 기존 50mm/MAD와 150mm/상대 간격 등의 수치는 **표면 분리와 거부 기준**이며 출력 거리에 곱하거나 더하는 보정값이 아니다.

이 방식은 출력 빈도를 희생할 수 있다. 실제로 객체와 배경이 같은 Z인 장면이나 외곽을 볼 수 없는 큰 객체도 null일 수 있다. segmentation 없이 표면의 정체를 증명할 수 없으므로, 배경과 구분할 수 없다는 사실을 거리로 단정하지 않는다.

## 4. RAW/FULL 비교와 필터

FULL과 RAW/Confidence를 같은 ARCore Frame에서 각각 취득하고 이미지 사용 직후 닫는다. FULL 모드에서는 기본적으로 RAW를 추가 취득하지 않는다. **Depth 분석 ON**이면 FULL 모드에서도 비교용 RAW를 취득하며 이 사실만으로 FULL 선택을 바꾸지 않는다. RAW_WITH_FULL_FALLBACK에서는 두 후보를 독립적으로 계산한다.

기록에는 두 대표값, 유효 픽셀 수, 군집/중앙 지지/외곽 유사도, confidence 최소/평균/최대·4구간 분포와 저 confidence 제외 수가 들어간다. RAW 취득 실패, confidence 쌍 불일치, 표본 부족과 FULL 보조 사용 사유도 구분한다. RAW confidence 128 기준은 유지한다.

두 방식이 모두 유효해도 차이가 `max(0.15m, 작은 값의 15%)`를 넘으면 RAW 우선 모드에서 어느 쪽이 맞는지 단정하지 않고 `DEPTH_SOURCE_DISAGREEMENT`로 표시를 거부한다. 어느 방식의 오차가 더 작은지는 같은 프레임의 수동 실측 기록으로 비교한다. `depthMethod`는 원본 후보 출처, `selectedDepthMethod`는 실제 표시된 출처이며 거부 시 후자는 null이다.

시간 필터는 기존 클래스/IoU 매칭·중복 timestamp·누락/만료 방어를 유지하면서 정지 구간은 최대 5개 중앙값, 연속 3개 접근/후퇴 추세는 최신 유효값을 사용한다. 큰 단일 변화는 바로 이력을 초기화하지 않고 일단 null로 보류한다. 다음 독립 표본이 새 수준을 지지해야 수락한다. 작은 정상 변동, 급변 보류, 추세, 출처 변경과 필터 지연을 원본값과 별도로 기록한다. 잘못된 원본을 부드럽게 만들었다는 이유로 정확도 향상이라고 보고하지 않는다.

출처가 바뀌어도 같은 객체의 기하학적 대응은 유지하고 큰 변화는 확인한다. 실제 필터 이력은 다른 출처끼리 섞지 않는다. 같은 Depth timestamp의 재투영 Z가 크게 바뀌면 과거 수치를 현재 값처럼 표시하지 않고 `REPROJECTED_DEPTH_CHANGE`로 새 Depth를 기다린다. Tracker ID는 같은 worker의 reset 후에도 재사용하지 않는다.

## 5. 디버그 표시와 실측 기록 사용법

설정 → 디버그 및 고급 설정 → **Depth 분석 표시 (기본 OFF)**를 켠다. 메인의 거리 ON/ARCore 설정도 필요하다. 적용 후 기존 BBox에 선택/후보/외곽/무효 지점과 원본 Z·표시 Z·출처·표본 수·실패 이유·FULL/RAW 후보·좌표 비교 오차를 표시한다.

- 녹색: 선택 표면, 노랑: 다른 유효 후보, 파랑: 외곽 배경, 빨강: 0/저 confidence 등 무효 표본.
- samplePoints는 `UPRIGHT_CPU_PIXELS`와 직접 API로 얻은 VIEW_NORMALIZED 좌표를 함께 기록하며, 실제 지점은 후자로 그린다. Canvas는 거리 계산을 하지 않는다.
- 화면/로그의 지점 수는 최대 256개로 제한하며 실제 유효 픽셀 수와 구분한다.
- 분석 OFF는 기존 UI를 유지하고 추가 RAW 취득·round-trip 비교·표본 지점 기록을 하지 않는다. 직접 픽셀 좌표 스냅샷은 정확한 표본 위치를 위해 유지한다.

같은 설정 화면에서 대상 클래스와 **실측 전방 Z(m)**를 입력한다. 1/2/3/5m 버튼은 입력 보조일 뿐 측정 결과를 생성하지 않는다. 렌즈 중심의 광축 방향 Z를 같은 객체의 대표 표면 기준으로 실측해야 한다. 대상 클래스가 정확히 하나 탐지된 프레임에만 실측값을 연결하고, 여러 개면 `AMBIGUOUS_TARGET`으로 오차 계산을 하지 않는다. 입력값은 자동으로 따라 변하지 않으므로 정지한 구간에서 비교하고 거리가 바뀌면 다시 입력하거나 비운다.

기존 평가 캡처/녹화 JSON·JSONL 또는 성능 로그를 직접 시작한다. 기본 사용에 별도의 상시 파일 writer는 추가하지 않는다. 기존 키는 유지하고 아래 정보를 추가한다.

`groundTruthMeters`, `groundTruthDefinition=CAMERA_FORWARD_Z`, `groundTruthStatus`, `rawDepthMeters`, `estimatedDistanceMeters`, `absoluteErrorMeters`, `relativeErrorPercent`, `filteredAbsoluteErrorMeters`, `depthMethod`, `selectedDepthMethod`, `depthSampleCount`, `confidence`(기존 YOLO confidence), `distanceStatus`, `frameTimestampNs`, `depthTimestampNs`, `objectLabel`, `depthTrackId`, `depthFilterStatus`, `depthFilterLagMs`, `distanceUpdateDelayMs`, `depthAnalysis`.

absolute/relative error는 **원본 선택 후보 Z**로 계산하고 표시값 오차는 별도 필드다. 추정이 거부되어도 실제 계산한 원본은 보존한다. 실측값이 없으면 오차 필드는 null이다. 필터의 표본 시각 지연과 처리/Depth-age 지연을 기록하며, 기기가 움직이기 시작한 실제 시점에 대한 물리적 지연을 측정했다고 주장하지 않는다.

분석 도구:

```powershell
python tools/analyze_depth_accuracy.py capture.json detections.jsonl performance.txt --output accuracy-summary.json
```

기존 JSON/JSONL/perf의 `depthObjects`에서 수동 UNIQUE_TARGET/CAMERA_FORWARD_Z 기록만 읽는다. 중복 프레임을 제거하고 원본·표시값의 MAE/중앙/P90 절대오차, 거리별 평균 부호 오차, 선택 출처별 통계, 거리값 제공 비율, 관측 갱신 지연을 구한다. FULL/RAW 후보가 둘 다 있는 동일 프레임 비교도 별도로 제공한다. P90은 nearest-rank다. 원본/표시값의 유효 분모가 다를 수 있어 같은 기록만의 paired 지표도 출력한다. `validDisplayRatio`는 **고유 실측 대상이 탐지된 기록 중 유효 거리값을 제공한 비율**이다. 정책 필터 이후 실제 Canvas 그리기 성공률이나 탐지 누락 recall을 측정한 지표는 아니다. 실측 기록이 없으면 수치를 만들어내지 않는다.

## 6. 변경 파일과 검증

주요 소스는 기존 `ArObjectSurfaceDepthSampler`, `ArYoloGeometry/ArObjectDepthSmoother`, `ArCoreFrameDepthContext`, `ArYoloRenderer`, `ArCorePipelineWorker`, `ArCoreMainCameraController`, `ObjectDepthEstimate`, `BoundingBoxOverlay`, `MainActivity`, 설정 3개 파일, `EvaluationJson`이다. `DepthCoordinateSnapshot.kt`, `DepthPixelCoordinates.kt`, `DepthAnalysis.kt`와 `tools/analyze_depth_accuracy.py`를 추가했다. 해당 sampler/좌표/필터/설정/JSON 테스트와 `tools/test_depth_accuracy.py`에서 변경을 검증한다.

검증 명령:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
```

최종 검증 결과: **BUILD SUCCESSFUL (1분 49초)**.

- **단위 테스트 294개 / 53개 suite 통과**, 실패·오류 0. 이전 279개와 신규 15개를 포함한다. 기존 설정·YOLO/필터/경고·지면 거리·ML Kit 진단·로그·성능 회귀 테스트도 실행했다.
- **Python 전체 테스트 33개 통과** (`.venv/Scripts/python.exe -m unittest discover -s tools -p 'test_*.py'`). 분석/파서 32개에서 수동 실측 없을 때 null, 같은 프레임 FULL/RAW, 중복 제거, 분모·P90·지연 집계와 기존 성능 파서 호환성을 검증했다. 나머지 1개는 실제 번들 YOLO 모델의 텐서·클래스·출력 계약을 검증한다. 번들 공용 Python에는 `ai_edge_litert`가 없어 처음 전체 실행이 실패했으나 저장소 `.venv`로 전체 재실행하여 통과했다.
- **Debug APK와 계측 테스트 APK 빌드 성공**. `app/build/outputs/apk/debug/app-debug.apk`, `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`.
- **Lint 오류 0개, 경고 260개**. 기존 UI 문자열/Canvas 할당 등의 경고를 포함한다. `app/build/reports/lint-results-debug.html`에서 확인할 수 있다.
- 진단 샘플 함수 진입 latch를 파일 기록 완료로 간주하던 기존 `PerformanceLogSessionTest`의 비동기 경합을 확인했다. 해당 테스트만 실제 SYSTEM 기록 발행을 기다리도록 보완했고 성능 측정 production 코드는 바꾸지 않았다.
- Android 디버그 점/실패 사유 표시와 설정 저장 계측 테스트는 추가·컴파일했으며, 실제 기기에서 실행하지 않았다. 실제 ARCore API의 수치 차이, 1/2/3/5m 오차와 움직임 지연은 미검증이다.

기존 모델/학습, confidence/NMS, 탐지·경고/위험도 정책, CameraX, 지면 거리, ARCore 단독 측정·저장·공유 경로는 바꾸지 않았다. 커밋/푸시하지 않았다. 실측 데이터가 없어 실제 MAE 향상은 입증하지 않았다.

## 7. 실제 기기에서 확인해야 하는 사항

1. FULL/RAW, 640/512, ROI·중앙 크롭·Letterbox/Stretch, 네 방향 회전과 종횡비 변화에서 실제 샘플이 객체에 올라가는지.
2. 같은 프레임의 mappingMax/Mean·roundTrip 오차. 커다란 오차가 BBox 근처에서도 발생하는지. 합성 테스트의 warp 검출 수치를 기기 수치로 사용하지 않는다.
3. 사람 팔/다리 사이 배경, 볼라드 바닥, 작은/가린 객체, 유사 Z 배경에서 잘못된 값과 null의 교환이 합리적인지.
4. 1/2/3/5m의 동일 Z 실측을 정지 구간별로 저장하고 FULL/RAW를 paired 비교한다. 실제 MAE/중앙/P90·표시 비율을 함께 본다.
5. 접근/후퇴의 반응, 단일 큰 오차 보류, 실제 큰 거리 변화 확인 지연, 객체 교차/소실·Tracking 실패·stale·pause/resume 시 과거 값 잔존 여부.
6. 분석 OFF의 화면/로그 부하, 분석 ON의 점·글자 가독성과 캡처/녹화·공유, timestamp와 crop 검사로 인한 거부 사유.
7. 직접 좌표 배열과 더 넓은 분포 검사의 실제 메모리/처리 시간. 단일 in-flight/4Hz 제한은 유지하지만 성능 개선을 목표로 한 변경은 아니다.

## 8. ARCore 한계와 후속 개선

ARCore의 motion 기반 추정은 장면 질감·움직임·가림·재질·조명과 VIO 상태의 영향을 받는다. FULL 보간으로 객체 경계가 섞이거나 RAW가 희소할 수 있다. 작은 객체는 Depth 한두 픽셀보다 작을 수 있고 ToF 없는 ARCore 추정값을 정밀 실측 거리로 보장할 수는 없다. 외곽 띠가 다른 객체/바닥일 수도 있어 이 비교가 segmentation을 대신하지는 않는다.

공식 자료도 기기 움직임의 중요성과 대략 0.5~5m 영역의 유리한 조건을 설명한다. 이를 해당 Galaxy S24에서의 보장 오차로 해석하지 않는다. [Depth 개요](https://developers.google.com/ar/develop/depth), [RAW/Confidence 가이드](https://developers.google.com/ar/develop/java/depth/raw-depth), [Frame 좌표 API와 현재 Frame 제약](https://developers.google.com/ar/reference/java/com/google/ar/core/Frame), [좌표계 정의](https://developers.google.com/ar/reference/java/com/google/ar/core/Coordinates2d).

다음 순서는 실제 실패 장면의 샘플/군집·외곽 자료 확보 → 관측된 좌표 오차 수정 여부 결정 → 클래스별 근거 있는 영역/다른 객체 마스크 검토 → 실제 Z 궤적을 갖춘 이동 지연 검증이다. 데이터가 없는 클래스별 거리 배율, 단순 최솟값, 기존 경고에의 임의 연결은 추가하지 않았다.
