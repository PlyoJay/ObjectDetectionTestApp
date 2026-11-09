# YOLO 추론 경로 3차 검토·최적화 결과

작성일: 2026-11-06 (Asia/Seoul)  
기준 HEAD: `2228ea499e1afe3a1494a94a5b36e16b0f6e5f47`  
상태: 작은 후처리 개선·기존 로그 재분석·자동 검증 완료. 커밋·푸시 미수행.

**기존 Interpreter 실행 경로는 이미 대부분 최적화되어 있으며, native 추론 구간을 크게 줄일 안전한 추가 변경의 근거는 확보되지 않았다.** 운영 코드에서는 출력 파싱의 불필요한 threshold 조회와 반복 크기 필터 range 생성만 줄였다. Interpreter·I/O 버퍼 소유권·XNNPACK·threads는 유지했다. 이 변경을 228→344ms 장기 추론 저하의 해결책으로 제시하지 않는다.

JVM 단위 테스트 210개, Python 분석기 테스트 32개, 기기 회귀 테스트 35개가 최종 통과했다. 기기 테스트는 최초 34개 통과 후 잠금 때문에 실패한 CameraX 1개를 사용자가 잠금 해제한 뒤 재실행한 결과다. 새 3분·10분 측정, 장시간 성능 테스트, 모델 학습·변환·교체는 하지 않았다.

## 1. 기존 로그 분석 결과

### 1.1 자료 범위와 모델 구분

저장소의 실제 FRAME 행이 있는 검증 로그를 검색했다. 사용자 제공 **587프레임·3.26 FPS·262.9ms 세션의 원본은 확인되지 않았다.** 따라서 그 세션의 nativeInvoke, CPU·thermal 상관, 초기/마지막 30초를 실측 재집계한 것처럼 작성하지 않는다.

대신 현재 기본 모델과 SHA가 같은 2026-11-03의 짧은 검증 로그 두 개 및, 30초 비교가 가능한 과거 640 모델 로그 두 개를 별도로 분석했다. 서로 다른 모델·설정의 수치를 합치지 않았다.

| 원본 로그 (`docs/validation/`) | 모델·조건 | 분석창 길이 / 성공 FRAME | 추론 평균 / P95 ms | nativeInvoke 평균 ms |
| --- | --- | --- | --- | --- |
| [yolo-v2-preview-off-performance-20261103.txt][v2-off] | 512 v2, 12클래스, threads=4, Preview OFF, ML Kit ON | 7.282초 / 31 | 216.645 / 231 | 216.876 |
| [yolo-v2-preview-on-performance-20261103.txt][v2-on] | 같은 모델, threads=4, Preview ON, ML Kit ON | 7.378초 / 28 | 226.821 / 250 | 227.078 |
| [timer-roi-auto-off-4threads-20261020.txt][old-four] | `best.tflite` 640, threads=4, Preview OFF, ML Kit ON | 180.037초 / 237 | 685.924 / 1079 | 685.204 |
| [timer-roi-auto-on-1thread-20261020.txt][old-one] | 같은 640 모델, threads=1, Preview ON, ML Kit ON | 180.016초 / 374 | 436.837 / 543 | 436.927 |

분석창 끝은 최종 CUMULATIVE_SUMMARY를 사용한다. 11-03 ON의 TEST_SESSION actualDurationMs=7377과 summary 끝=7.378초는 기록 정밀도 차이가 있어 구분한다. 위 짧은 로그로 Preview의 인과 효과나 장기 성능을 결론내리지 않는다. 10-20 두 세션도 Preview·threads·시스템 진단 상태 등이 달라 스레드 성능 A/B로 사용할 수 없다. 네 로그 모두 FRAME 오류 0, dropped log lines 0이며 기존 분석기의 frameLogComplete=true다.

- 512 v2 SHA: `591509278b3a1fe114a0084d4841b948a93b637c22005b7becf06309cb906667`. 현재 asset과 일치한다. 하지만 당시 schema=11, 실제 카메라=1080×1440, full-frame ROI이며 현재 1·2차 변경 이전이다.
- 640 SHA: `32a44bc7432ef2d7ca5de802dd8e714a26568aea16566bad3fd7fcd2b85bcee2`. 현재 640 asset과 일치하지만 기본 선택 모델 512 v2와 다르다. 당시 schema=5다.
- 모델 SHA 일치는 APK 전체 소스 일치의 증거가 아니다. 과거 문서의 10-26 장기 조사도 참고했지만, 문서에 기재된 저장소 외 원본을 이번에 다시 분석한 것으로 취급하지 않았다.

### 1.2 초기·마지막 30초

7초 로그에는 두 개의 완전하고 겹치지 않는 30초 창이 없다. 초기/마지막 30초는 **N/A**다. 다음은 640 과거 로그만의 실제 재집계다. 마지막 창은 정확히 `[실제 분석 끝-30, 끝)`으로 잡았다.

| 과거 로그·구간 | 성공 수 / FPS | 추론 평균 / P95 ms | native 평균 ms | 전처리 평균 ms | pipeline 평균 ms |
| --- | --- | --- | --- | --- | --- |
| 640 / 4 threads, 0–30초 | 44 / 1.467 | 608.477 / 1084 | 608.000 | 7.068 | 631.727 |
| 640 / 4 threads, 150.037–180.037초 | 39 / 1.300 | 698.077 / 1027 | 697.325 | 7.718 | 722.949 |
| 640 / 1 thread, 0–30초 | 77 / 2.567 | 352.260 / 376 | 352.316 | 3.117 | 364.610 |
| 640 / 1 thread, 150.016–180.016초 | 51 / 1.700 | 540.059 / 544 | 540.124 | 5.039 | 559.451 |

느려지는 위치에 native 구간도 포함된다. 전체 평균의 `추론 평균 / pipeline 평균`은 위 네 로그 모두 약 96.5–97.1%다. 이는 같은 프레임의 중첩된 시간 비율이며 CPU 사용률이나 개선 가능 비율이 아니다. 전처리·후처리만 수정해 native 지연 증가가 직접 해결된다고 볼 수 없다.

### 1.3 CPU·Thermal·headroom

| 자료 | 확인된 값·관계 | 해석 한계 |
| --- | --- | --- |
| 512 v2, Preview OFF | SYSTEM 8개, 유효 CPU 7개 평균 117.058%, thermal=NONE, headroom=0.533 | 30초 완전 창 0개. 장기 상관·변화 N/A. headroom은 캐시된 동일 값 |
| 512 v2, Preview ON | SYSTEM 8개, 유효 CPU 7개 평균 131.909%, thermal=NONE, headroom=0.607 | 동일한 한계. 두 짧은 세션의 CPU 차이를 인과 효과로 해석하지 않음 |
| 과거 640 / 4 threads | SYSTEM 177개, thermal 전부 NONE. 전체 headroom 0.783→0.797, 최대0.803. CPU 평균123.172% | NONE이 CPU 제한 부재의 증거는 아님. headroom은 SoC 온도가 아니며 갱신 사이 캐시됨 |
| 과거 640 / 1 thread | SYSTEM 0개 | CPU·frequency·thermal·headroom 및 해당 상관 모두 N/A |

640/4 threads의 **완전한 30초 창 6개**를 같은 가중치로 비교한 Pearson r은 다음과 같다. 마지막 0.037초 partial 창, 누락 표본은 제외했다.

| 구간 평균 vs 추론 평균 | r |
| --- | --- |
| processCpuUsage | +0.374 |
| thermalHeadroom | +0.446 |
| policy9 currentKHz | -0.984 |
| policy9 scalingMaxKHz | -0.613 |
| policy7 currentKHz / scalingMaxKHz | -0.822 / -0.518 |
| policy4 currentKHz / scalingMaxKHz | +0.579 / -0.369 |

policy9의 평균 current는 첫 30초 1,552,000→마지막 완전 창 1,297,655kHz, 평균 상한은 1,782,400→1,684,966kHz였다. 기기 policy 스냅샷과 추론 지연 사이 연관은 있으나, **앱의 실제 실행 코어·유효 클럭 또는 thermal 인과를 증명하지 않는다.** 정책마다 current 상관 방향도 다르다. n=6, 시간 추세·스케줄링·다른 작업·열 이력이 교란 요인이다. process CPU는 모든 앱 스레드 합이고 100%=한 코어이므로 100% 초과가 정상이다.

### 1.4 분석 코드와 재현

신규 [analyze_yolo_phase3.py][analysis-tool]는 기존 분석기를 재사용해 원본 SHA, 모델 헤더, 초기/마지막 30초, 완전 창의 CPU/headroom/frequency 상관을 저장한다. 60초 미만, 누락·상수 지표, 3개 미만 쌍은 N/A로 남긴다. 서로 다른 모델을 합산하지 않는다. 원본은 수정하지 않았다.

[재집계 JSON][log-analysis]에 전체 표본·구간·기존 상세 타이밍·무결성 플래그와 원본 SHA를 보존했다.

```powershell
.\.venv\Scripts\python.exe tools/analyze_yolo_phase3.py docs/validation/yolo-v2-preview-off-performance-20261103.txt docs/validation/yolo-v2-preview-on-performance-20261103.txt docs/validation/timer-roi-auto-off-4threads-20261020.txt docs/validation/timer-roi-auto-on-1thread-20261020.txt
```

## 2. 실제 추론 병목과 원인

### 2.1 Interpreter·JNI·버퍼 검토

실제 캐시의 `litert-2.1.1.aar/classes.jar`를 JBR `javap -c -p`로 확인했다. [검토 결과와 AAR SHA][runtime-inspection]를 기록했다.

```text
앱: 재사용 input Object[] + 재사용 output Map
  → NativeInterpreterWrapper.run
      → input shape 검사 (Buffer 경로)
      → allocateTensorsIfNeeded (이미 할당되었으면 즉시 반환)
      → input Tensor.setTo (direct/native-order는 writeDirectBuffer JNI 경로)
      → native run
      → 요청 output Tensor.copyTo
          → output ByteBuffer.put(native tensor buffer)
앱: output FloatBuffer → 재사용 FloatArray rows → 후보 scan/NMS
```

- direct buffer, native byte order, 입력 array·출력 map, RGB bulk writer, 출력 row bulk copy는 이미 적용되어 있다. 매프레임 Interpreter 생성·모델 로드·resizeInput·allocateTensors 호출을 새로 도입하지 않았다.
- Java API 밖의 input native aliasing까지 확인한 것은 아니다. direct buffer를 쓴다는 이유로 내부 복사가 전혀 없다고 단정하지 않는다. output destination으로의 복사는 Java bytecode에서 확인된다.
- `detect/runSelfTest/close`의 synchronized와 2차 pipeline의 process/close 직렬화는 자원 수명을 위한 것이다. 단일 analyzer 경로에서 잠금이 장기 저하를 유발했다는 증거가 없으므로 제거하지 않았다.
- `setNumThreads`와 `setUseXNNPACK`은 생성 시 적용한다. **XNNPACK 요청·옵션 수락과 실제 partition/worker 검증은 다르다.** 기존 Java API의 `UNAVAILABLE_JAVA_API` 한계 및 cpuFallbackCount 의미를 유지한다. 이번에는 native partition 범위나 특정 코어 실행을 새로 입증하지 못했다.

### 2.2 짧은 Interpreter 호출 검증

기기에서 같은 Interpreter·direct buffer·재사용 컨테이너를 사용하고 2회 준비 후 **3개 표본/모델**만 기록했다. 카메라가 없는 고정 이미지 유래 입력이며 일부 픽셀을 바꾸었다. 최적화 전후 A/B가 아니다.

| 모델 | Java 호출 wall 평균 ms | native 평균 ms | wall-native 평균 ms |
| --- | --- | --- | --- |
| best.tflite / 640 | 551.771 | 551.263 | 0.509 |
| best_512.tflite / v2 | 351.973 | 351.677 | 0.295 |
| best_512_v1_11class.tflite | 351.660 | 351.358 | 0.302 |

[호출 검증 로그][probe-log]. 차이는 입력 처리·출력 복사·Java 검사·JNI 경계 등을 포함하며 순수 JNI 비용이 아니다. native도 wall time이므로 순수 kernel CPU 시간과 같지 않다. 표본이 작고 열 상태·입력·카메라 부하가 기존 3분 세션과 달라 262.9ms와 개선/악화 비교를 하지 않는다.

확인된 호출 범위에서는 native가 지연 대부분을 차지한다. 작은 Java 최적화로 수십~수백 ms를 줄일 근거는 없다. 과거 정수 inference가 소수 nativeInvoke보다 약간 작은 경우는 ms 내림·측정 경계가 섞이므로 음수 JNI 비용으로 해석하지 않는다.

### 2.3 채택하지 않은 변경과 장기 저하 판단

빈 output map + `Tensor.asReadOnlyBuffer()`는 지원되는 API이며 새 테스트에서 기존 output buffer와 바이트 일치를 확인했다. 그러나 뷰는 다음 invoke/resize/close를 넘어 보관하면 안 된다. 매번 즉시 소비하는 수명 계약과 기존 raw 출력·self-test·진단 경로 조정이 필요하다. 현재 작은 API/native 차이만으로 순효과가 입증되지 않아 운영에 적용하지 않았다. [공식 Tensor 문서](https://developers.google.com/edge/api/tflite/java/org/tensorflow/lite/Tensor), [InterpreterApi 문서](https://developers.google.com/edge/api/tflite/java/org/tensorflow/lite/InterpreterApi).

장기 native 지연 증가의 원인은 여전히 CPU 정책·발열·스케줄링/경쟁 가설이다. ML Kit, UI, writer, 진단 worker는 다른 스레드여도 CPU·메모리를 공유한다. 하지만 이번 자료에는 해당 587프레임 세션의 sched/GC/heap 추이가 없다. 메모리 누수, 특정 코어 제한, ML Kit 단독 원인, Interpreter 누적 결함으로 확정하지 않는다. 3/5/10분 기록기의 bounded queue·표본 제한과 정상 종료 drain도 유지한다.

## 3. 수정한 파일 및 메서드

| 파일 | 변경 |
| --- | --- |
| [VisionStyleYoloDetector.kt:85][detector-range] | immutable sizeFilterMode의 area range를 detector 생성 시 한 번 계산 |
| [VisionStyleYoloDetector.kt:358][detector-parse] | `parseOutput()`의 debug-only threshold 조회를 실제 debugFrame 존재 조건 안으로 이동 |
| [tools/analyze_yolo_phase3.py][analysis-tool] | 기존 로그의 모델·시간 창·누락·상관 분석 도구 추가 |
| [tools/test_analyze_yolo_phase3.py][analysis-tests] | 30초 경계, 짧은 기록, 누락/상수/partial 지표의 회귀 테스트 5개 |
| [InterpreterExecutionRegressionTest.kt][interpreter-tests] | 실제 모델 native view/복사 출력 비교, 호출 검증, 크기 필터·순서/NMS 회귀 2개 |

운영 코드 변경은 detector 한 파일에 한정했다. 실제 Interpreter 호출과 I/O 수명은 유지했다. 모델·학습·export 파일·CameraX·settings·metrics·경고 코드에는 변경이 없다.

## 4. Before / After 핵심 코드

```kotlin
// Before: confidence 통과 후보마다 동일 range 생성/분기
val detectorAreaRange = when (sizeFilterMode) {
    SizeFilterMode.DISABLED -> null
    SizeFilterMode.RELAXED -> RELAXED_MIN_AREA_RATIO..RELAXED_MAX_AREA_RATIO
    SizeFilterMode.NORMAL -> NORMAL_MIN_AREA_RATIO..NORMAL_MAX_AREA_RATIO
}

// After: 같은 식을 detector의 private val로 이동, 생성 시 1회 계산
// parseOutput의 area 포함 여부 검사·로그 내용·필터 값은 그대로 사용
```

```kotlin
// Before: debugFrame이 없어도 모든 raw 후보에서 조회
val threshold = if (bestClassId >= 0) classFilter.threshold(bestClassId) else confidenceThreshold
debugFrame?.raw(/* 기존 값 */, threshold)

// After
debugFrame?.let { frame ->
    val threshold = if (bestClassId >= 0) classFilter.threshold(bestClassId) else confidenceThreshold
    frame.raw(/* 기존 값 */, threshold)
}
```

이는 최종 후보 판정인 `classFilter.accepts()`를 생략하거나 변경한 것이 아니다. debugFrame이 있는 경우 기존 threshold와 raw 기록은 같다. PNG-only 등 debugFrame이 존재하는 경로는 기존 동작을 유지한다.

기본 v2 shape에서 debugFrame이 없는 프레임의 threshold 조회 5,376회와, NORMAL/RELAXED에서 유효 후보 수만큼 반복되던 range 생성을 줄인다. DISABLED에서는 기존에도 range 객체는 없었으며 이번에는 반복 mode 분기를 줄이는 정도다. 최종 class/score 선택·좌표·min/max 통계·stable 정렬·NMS·경고 결과는 바꾸지 않았다.

## 5. 기존 최적화 유지 여부

- 1차: 상세 경고 Logcat gate, maxClassScore/rawMin/rawMax 조건 계산 유지. 캡처·녹화용 rawCoordinateMin/Max 유지.
- 2차: 회전·ROI Bitmap/Canvas/Matrix 재사용, borrowed frame 계약, analyzer drain·pipeline close 유지.
- Tensor/Letterbox: direct buffer·배열·FloatBuffer·호출 컨테이너·Bitmap 재사용 유지. RGB LUT/bulk writer 및 출력 bulk reader 유지.
- 모델·임계값·ROI·클래스·스레드·XNNPACK·추론 주기·경고 정책·출력·성능 로그 필드/의미 유지.

[소스 감사][source-audit]에서 새 두 변경만 복원하면 baseline detector 소스가 공백 외 동일함을 확인했다. 실제 Interpreter 호출/타이밍/오류 구간도 동일하다. 세 TFLite asset의 SHA가 모두 HEAD와 일치한다. 저장소 밖 YOLO11n 학습에 접근하거나 파일을 바꾸지 않았다.

작업 도중 별도로 stage된 `app/src/main/assets/best_float32.tflite` 삭제가 확인되었다. 이 파일과 Git index에는 손대지 않았으며 이번 detector 변경과 구분해 그대로 남겼다. 위 SHA 검증은 현재 선택 가능한 `best.tflite`, `best_512.tflite`, `best_512_v1_11class.tflite` 세 파일에 대한 것이다.

## 6. 테스트 및 빌드 결과

| 검증 | 결과 |
| --- | --- |
| Debug APK / 계측 APK | 성공 |
| 전체 JVM 단위 테스트 | 40 suites, 210개 통과, 실패/오류/skipped 0 |
| Python 기존 분석·latency + 신규 분석 회귀 | 32개 통과 |
| Android Lint | Error/Fatal 0, Warning 196. 2차 결과와 같은 개수 |
| 전체 Gradle 검증 | BUILD SUCCESSFUL, 2분 56초 |
| 최초 SM-S921N 계측 | 35개 중 34개 통과. 잠긴 기기에서 CameraX 프레임 수신 1개 실패 |
| 사용자 잠금 해제 후 CameraX만 재실행 | 1개 통과. 최종 고유 테스트 35개 통과 (34+1, 단일 전체 성공 재실행은 아님) |

기기 선택 범위는 2차의 33개 회귀 테스트와 신규 InterpreterExecutionRegressionTest 2개다. 기존 모델 raw Tensor·positive 검출·NMS·좌표/ROI, 초기화 실패/복구·중복 종료·종료 후 거절, 1차 로그·warning submit/cooldown, ML Kit lease/cancellation 및 CameraX 재바인딩·active reader drain을 포함한다.

신규 검증은 세 모델의 변경 입력에서 재사용 output buffer와 즉시 소비한 native view가 같은 바이트를 반환하는지 확인했다. 또한 DISABLED/RELAXED/NORMAL의 여러 경계 크기와 동일 클래스 중복 후보에서 기존 면적 판정·confidence 순서·NMS 결과를 확인했다. 운영에서 사용하지 않는 native view를 테스트한 것이 zero-copy 경로를 배포했다는 의미는 아니다.

최초 새 Python fixture 2개는 기존 분석기가 요구하는 CUMULATIVE_SUMMARY를 넣지 않아 ValueError가 발생했다. fixture에 확정 summary를 추가해 해결했고, 정확한 종료 시각을 요구하는 기존 분석기 검증은 완화하지 않았다. CameraX 실패는 잠금 상태에서 발생했으며 사용자 해제 후 같은 코드로 통과했다. Android 빌드·JVM 테스트 자체의 실패는 없었다.

[빌드 기록][build-log], [Python 결과][python-log], [기기 원본][device-log], [CameraX 재실행][camera-log], [검증 집계][test-summary]. 실제 물리 소리·진동 재생, 캡처/녹화 E2E, 장시간 FPS/열/GC는 실행하지 않았다.

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:lintDebug --offline --console=plain --max-workers=2 '-Pkotlin.compiler.execution.strategy=in-process'
```

## 7. 기대 효과와 아직 확인되지 않은 사항

확인된 효과는 불필요한 Kotlin 분기·조회·range 할당의 제거다. 후처리 CPU/할당 부담이 줄 방향이지만 **기기에서 절감 시간·FPS·전력·GC·발열 개선량을 측정한 것은 아니다**. NORMAL/RELAXED의 range 개선은 해당 모드에서만 의미가 크고 기본 DISABLED의 이득은 작을 수 있다.

native 계산량·delegate·threads는 그대로다. native 호출 100ms 이상 증가의 원인과 장기 성능 유지 효과는 미확인이다. 짧은 native 호출 표본과 과거 다른 조건의 로그로 개선율을 만들지 않는다. 불명확한 이유로 런타임/API 경로를 재작성하지 않은 것이 이번 판단의 핵심이다.

## 8. YOLO11n 적용 시 고려사항

**네트워크가 11s인지 11n인지에 따라 Interpreter/parser를 따로 작성할 필요는 없다.** 다만 동일한 export 계약을 만족하는 새 TFLite여야 한다. 현재 파일을 덮어쓰지 않았으며 실제 11n 파일이 없어 호환성을 실행 검증한 것은 아니다.

| 항목 | 현재 자동 처리·검증 | 11n 도입 시 확인·수정 |
| --- | --- | --- |
| 입력 shape | `RgbTensorShape.from`: static batch=1, RGB NCHW/NHWC에서 width/height 자동 해석 | 등록 YoloModel의 inputSize와 실제 양쪽 크기도 검사한다. 다른 크기는 새 enum metadata 필요. 임의 동적/다중 입력은 지원하지 않음 |
| 출력 shape | `[1,4+C,N]` 또는 `[1,N,4+C]`, N·배치 방향 자동 해석 | 단일 raw xywh+class scores 계약 필요. objectness 추가, embedded NMS/end-to-end, multi-head, ambiguous shape는 별도 parser 계약 없이는 거절 |
| dtype/버퍼 | 실제 numBytes로 할당, FLOAT32/UINT8/INT8 codec·scale 검증 | FLOAT16 입출력은 현재 지원하지 않음. weight precision과 Tensor 입출력 dtype을 혼동하지 않음 |
| 전처리·좌표 | RGB /255, Letterbox padding114, normalized xywh | shape만으로 normalization·색 순서·좌표 단위를 판별할 수 없다. 현재 coordinateScale 표시는 선언값이며 새 그래프 의미의 자동 증명이 아님 |
| 클래스 수 | output 채널=4+등록 labels 수 검사, labels.txt와 기준 클래스 목록 대조 | **클래스 순서는 metadata.names를 런타임에서 자동 해석하지 않는다.** 새 파일의 names/ID 순서를 오프라인으로 검증하고 registry에 정확히 등록해야 함 |
| 모델 선택 | `YoloModel.entries` 기반 UI, enum name 설정 저장·복구 | 예: 별도 `yolo11n_512.tflite` asset + YoloModel enum + ModelConfig.labelsFor 등록. 현재 기본 선택·이전 파일은 유지 |
| 새 클래스 | 기존 ObjectTuning/Overlay/Warning 정책 | 기존 12클래스와 순서가 같으면 기존 정책 재사용 가능. 클래스 추가/변경 시 labels·필터·경고 tuning·회귀 검증 별도 필요 |
| 성능 비교 | 로그에 asset·SHA·class count·shape/dtype·threads·XNNPACK·실제 카메라·ROI·Preview·ML Kit·구간 지표 기록 | 별도 파일/선택값으로 동일 설정 비교 가능. 설정 snapshot·모델 SHA와 APK/source revision도 별도 기록하고 열 이력 통제 |

핵심 위치: [ModelAssets.validate():35][model-assets], [ModelConfig/YoloModel][model-config], [YoloTensorContract][tensor-contract], [SettingsActivity 모델 선택:350][model-ui]. 현재의 단일 입력/출력·정적 shape·클래스 순서 whitelist를 우회해 새 모델을 무조건 로드하도록 변경하지 않았다.

새 모델 도입 시에는 기존 golden/CPU/모델 선택 테스트를 확장해 입력 Tensor·raw output·BBox·confidence·class order·경고 시나리오를 먼저 검증한다. `YoloModel.entries`를 순회하는 테스트도 새 모델에 실행되므로 fixture·클래스 기대값을 점검해야 한다. 이번 작업은 학습 중인 모델이나 export 설정을 변경하지 않는다.

## 9. 향후 추가 개선이 가능한 부분

1. 해당 587프레임 원본이 확보되면 새 분석기로 현재 모델의 초기/후반 30초와 native/CPU/thermal을 먼저 재집계한다. 최신 동일 조건 증거 없이 과거 640 로그를 원인 확정에 재사용하지 않는다.
2. 장기 현상을 다음 별도 검증에서 재현할 때만 짧은 초기/후반 Perfetto sched/frequency/GC trace로 running·runnable 대기·실제 코어 배치와 GC pause를 나눈다. 이번에는 실행하지 않았다.
3. 기존 설정으로 threads 2/4 등의 장기 비교는 가능하지만 초기값만으로 기본값을 바꾸지 않는다. XNNPACK partition·worker 수는 native 진단으로 별도 확인한다.
4. native 출력 뷰는 공개 API로 가능하나 매 호출 후 수명·진단/raw export·버퍼 래퍼 할당과 순효과를 함께 비교한 뒤 별도 채택한다. 이번 작은 API/native 차이만으로 운영 버퍼 계약을 바꾸지 않는다.
5. 11n 준비 완료 후 동일 export 계약으로 별도 asset을 등록해 기존 11s와 비교한다. 네트워크 계산량 변경과 CPU 열 유지 효과가 필요하다면 이 비교는 작은 Java 후처리 개선과 분리한다.

추론 주기 제한, 열 보호 해제, 강제 클럭/affinity, 임의 GPU/NNAPI 전환, 주기적 Interpreter 재생성은 이번 또는 후속 자동 적용 항목으로 추가하지 않았다.

[v2-off]: <D:/develop/Projects/TestProjects/ObjectDetection/docs/validation/yolo-v2-preview-off-performance-20261103.txt>
[v2-on]: <D:/develop/Projects/TestProjects/ObjectDetection/docs/validation/yolo-v2-preview-on-performance-20261103.txt>
[old-four]: <D:/develop/Projects/TestProjects/ObjectDetection/docs/validation/timer-roi-auto-off-4threads-20261020.txt>
[old-one]: <D:/develop/Projects/TestProjects/ObjectDetection/docs/validation/timer-roi-auto-on-1thread-20261020.txt>
[analysis-tool]: <D:/develop/Projects/TestProjects/ObjectDetection/tools/analyze_yolo_phase3.py>
[analysis-tests]: <D:/develop/Projects/TestProjects/ObjectDetection/tools/test_analyze_yolo_phase3.py>
[log-analysis]: <D:/develop/Projects/TestProjects/ObjectDetection/docs/validation/performance-phase3-log-analysis-20261106.json>
[runtime-inspection]: <D:/develop/Projects/TestProjects/ObjectDetection/docs/validation/performance-phase3-runtime-inspection-20261106.txt>
[probe-log]: <D:/develop/Projects/TestProjects/ObjectDetection/docs/validation/performance-phase3-interpreter-probe-20261106.txt>
[detector-range]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/detector/VisionStyleYoloDetector.kt:85>
[detector-parse]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/detector/VisionStyleYoloDetector.kt:358>
[interpreter-tests]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/androidTest/java/com/samin/objectdetection/detector/InterpreterExecutionRegressionTest.kt:16>
[source-audit]: <D:/develop/Projects/TestProjects/ObjectDetection/docs/validation/performance-phase3-source-audit-20261106.txt>
[build-log]: <D:/develop/Projects/TestProjects/ObjectDetection/docs/validation/performance-phase3-gradle-20261106.txt>
[python-log]: <D:/develop/Projects/TestProjects/ObjectDetection/docs/validation/performance-phase3-python-tests-20261106.txt>
[device-log]: <D:/develop/Projects/TestProjects/ObjectDetection/docs/validation/performance-phase3-device-tests-20261106.txt>
[camera-log]: <D:/develop/Projects/TestProjects/ObjectDetection/docs/validation/performance-phase3-camera-unlocked-20261106.txt>
[test-summary]: <D:/develop/Projects/TestProjects/ObjectDetection/docs/validation/performance-phase3-test-summary-20261106.txt>
[model-assets]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/detector/ModelAssets.kt:35>
[model-config]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/detector/ModelConfig.kt:3>
[tensor-contract]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/detector/YoloTensorContract.kt:12>
[model-ui]: <D:/develop/Projects/TestProjects/ObjectDetection/app/src/main/java/com/samin/objectdetection/settings/SettingsActivity.kt:350>
