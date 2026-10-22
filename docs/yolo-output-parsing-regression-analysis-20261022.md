# YOLO outputParsing 회귀 및 P0 계측 부하 분석

작성일: **2026-10-22, Asia/Seoul**  
대상: D:/develop/Projects/TestProjects/ObjectDetection (ObjectDetectionTestApp)  
비교: P0 이전 **53560e44e2b01c6d3bee88b44147e90a7dc2261d** → 현재 HEAD **8fdc17549e1a1dc0bec16eae2b50521bce42f07b**

**판단:** P0 전후 출력 복사·후보 순회 알고리즘과 outputParsing 측정 범위는 같다. 따라서 “P0가 파서 코드를 바꿔 약 5ms가 80ms가 됐다”는 설명은 소스와 맞지 않는다. 우선 조사할 대상은 동일한 복사/관리 코드 순회의 실행 조건 변화(CPU 정책·실행 코어·ART/빌드·선점/GC) 및 후보 수/상세 진단 설정 차이다. P0의 이벤트·writer가 이를 간접적으로 악화했을 가능성은 남지만, **현재 자료로 특정 원인을 가장 유력한 단일 원인으로 확정할 수 없다.**

이번에는 이 분석 문서만 작성했다. 앱 소스·모델·labels·설정·백엔드·기존 로그와 보고서는 수정하지 않았으며 커밋·푸시·APK 빌드/설치·실기기 시험·프로파일링을 수행하지 않았다. 아래의 실험과 수정안은 후속 제안이다.

## 1. 분석 목적 및 테스트 결과 요약

### 1.1 원본 가용성과 근거 수준

요청된 세 원본은 현재 환경에서 **찾지 못했다.** 저장소 전체(숨김/ignored 파일 포함), C:/Users/SAMIN/Downloads 및 하위, 기존 문서가 명시한 C:/Users/SAMIN/OneDrive/Documents/카카오톡 받은 파일 및 하위, C:/Users/SAMIN/.codex/attachments 및 하위를 정확한 파일명으로 검색했다. Git 추적 파일과 본문의 참조도 확인했다. 이 범위 밖 디스크 전체에 없다는 뜻은 아니다.

| 원본 | 이번 확인 | 수치의 취급 |
|---|---|---|
| perf_20261020_174051.txt | 미발견 | [기존 분석 문서](yolo-inference-optimization-analysis-20261020.md) §2.1–2.2의 과거 재집계 기록을 인용하는 **2차 근거** |
| perf_20261022_092718.txt | 미발견 | **사용자 보고값, 원본 미검증** |
| perf_20261022_095231.txt | 미발견 | **사용자 보고값, 원본 미검증** |

이전 문서에는 10/20 원본의 SHA-256이 2566b0b54df66d93a86eb2c5466f0656af5bf051255eae028fcfc36caadcaaef로 기록되어 있다. 당시 존재했다는 기록과 현재 원본 가용성은 별개다. [P0 문서](yolo-inference-instrumentation-p0-20261021.md) §7의 같은 수치는 같은 자료를 다시 기록한 것이므로 독립 재현으로 세지 않는다. 기존 validation 로그는 날짜·설정·길이가 달라 세 원본의 대체물로 사용하지 않았다.

### 1.2 결과 비교

공통 조건은 사용자 요청 기준 best_512.tflite, 512×512 FLOAT32, 2 Threads, CPU/XNNPACK 요청 ON, ROI 상단 20%·좌우 10%, Letterbox, confidence .2/NMS .45, ML Kit 및 시스템·배터리 진단 ON이다. **10/22 실제 설정 헤더와 APK는 원본 부재로 대조하지 못했다.**

| 지표 | P0 전 10분 / Preview OFF | P0 후 3분 / Preview ON | P0 후 10분 / Preview OFF |
|---|---:|---:|---:|
| 출처 수준 | 기존 문서의 재집계 | 사용자 보고 | 사용자 보고 |
| 성공 프레임 / 시간 | 1,560 / 600.034초 | 미검증 | 미검증 |
| 평균 FPS | 2.59985 | 약 2.57 | 약 1.77 |
| inference 평균 / P95 ms | 339.128 / 390 | 약 283 / 미제공 | 약 423 / 687 |
| totalProcessing 평균 / P95 ms | 373.333 / 431 | 약 372 / 미제공 | 약 548 / 844 |
| outputParsing 평균 ms | 4.846 | 약 58.16 | 약 79.53 |
| outputParsing 첫/마지막 30초 ms | 미집계 | 약 47 / 82 | 약 54.23 / 87.46 |
| 시작/종료 배터리 온도 | 30.8 / 41.7°C | 미제공 | 34.2 / 39.1°C |
| Thermal 상태 | NONE → LIGHT → MODERATE | 미제공 | NONE → LIGHT |
| inference 오류 | 새 원본 재검증 안 함 | 미검증 | 사용자 보고 0, 재검증 안 함 |

사용자의 P0 이전 반올림 수치는 기존 문서와 일치한다. 최신 두 로그의 평균·P95·30초 통계·오류/누락 수에 대해서는 **정정 또는 검증 완료를 주장할 자료가 없다.** FPS로부터 프레임 수를 역산하거나 새 프레임/SYSTEM/LATENCY 행을 재구성하지 않았다.

같은 scope의 평균이라는 전제 아래, 이전 문서와 최신 10분 보고값의 **참고용 산술 차이**는 totalProcessing 약 +174.7ms, inference 약 +83.9ms, outputParsing 약 +74.7ms다. 따라서 추론 저하와 출력 파싱 증가를 따로 조사할 가치가 있다. 이는 원본으로 확인한 인과적 기여율이 아니다. FPS 약 32% 감소 역시 조건이 다른 두 세션의 보고값 비교다.

## 2. P0 적용 전후 Git 변경 사항

### 2.1 비교 범위와 소스 근거 표기

작업 시작 시 git status --short는 비어 있었다. 8fdc175의 부모가 53560e4이며, 두 커밋 사이에는 24개 파일 변경이 있다. 현재 로컬 HEAD를 기준으로 분석했으며 원격을 새로 fetch한 결과로 표현하지 않는다.

아래 표의 기호와 행 번호는 **현재 HEAD** 기준이다. 공통 소스 접두사는 app/src/main/java/com/samin/objectdetection/이다.

| 기호 | 실제 파일 경로 / 핵심 함수 |
|---|---|
| D | [detector/VisionStyleYoloDetector.kt](../app/src/main/java/com/samin/objectdetection/detector/VisionStyleYoloDetector.kt): init, detect, parseOutput, nms, elapsedMs |
| Y | [detector/YoloTensorContract.kt](../app/src/main/java/com/samin/objectdetection/detector/YoloTensorContract.kt): YoloOutputReader.copy |
| P | [pipeline/DetectionPipeline.kt](../app/src/main/java/com/samin/objectdetection/pipeline/DetectionPipeline.kt): process |
| M | [MainActivity.kt](../app/src/main/java/com/samin/objectdetection/MainActivity.kt): processBitmap, frameTrace, maybeRunMlKitDetection |
| C | [camera/CameraController.kt](../app/src/main/java/com/samin/objectdetection/camera/CameraController.kt): bindUseCases, Analyzer |
| L | [metrics/FrameLatency.kt](../app/src/main/java/com/samin/objectdetection/metrics/FrameLatency.kt): FrameLatencyRecorder, finishAnalyzerFrame, TtsStartTracker |
| S | [metrics/PerformanceLogSession.kt](../app/src/main/java/com/samin/objectdetection/metrics/PerformanceLogSession.kt): beginFrameLatency, recordFrame |
| F | [metrics/PerformanceLogFormatter.kt](../app/src/main/java/com/samin/objectdetection/metrics/PerformanceLogFormatter.kt): formatFrame, formatTrace, formatLatency |
| W | [logging/OrderedLogWriter.kt](../app/src/main/java/com/samin/objectdetection/logging/OrderedLogWriter.kt): append |
| A | [tools/analyze_performance_logs.py](../tools/analyze_performance_logs.py): fields, analyze, latency_analysis |

### 2.2 실제 변경과 미변경

| 대상 | Git 비교로 확인한 내용 | 회귀와의 관련성 |
|---|---|---|
| D:276–318, 358–365, 560–562 | Interpreter 반환/throw 종료 ns를 저장하고 diagnostics/예외/parseOutput 인자로 전달. 파일 diff 11줄 규모 | 종료 시각 보존은 변경. 파싱 연산 증가의 직접 증거 없음 |
| D:369–579 parseOutput 본체 | 종료 ns 전달 외 출력 복사·scan·후보 생성·정렬·NMS·각 timer 위치 동일 | 새 알고리즘/새 순회가 추가된 것 아님 |
| D:142–155, Y:127–142 | direct buffer·FloatBuffer view·재사용 outputData 생성 및 복사 경로 동일 | 프레임별 출력 배열 신규 생성/전치로 바뀌지 않음 |
| P 전체, M:768–772 | pipeline 처리 및 outputParsing/post 산출식 동일 | outputParsing의 의미를 확대하지 않음 |
| metrics/FrameTrace.kt, detector/DetectionDiagnostics.kt, detector/InferenceException.kt | 종료 timestamp field 추가. FrameTrace 객체가 그 값을 보유 | 데이터 크기/전달의 작은 증분은 있으나 내부 scan의 신규 할당 아님 |
| L 신규, S 및 metrics/PerformanceLogRecorder.kt, C/M | originating session에 고정된 recorder·typed LATENCY 이벤트·Analyzer/경고 계측 추가 | 프레임별 할당·lock·큐 제출 증가의 직접 근거 |
| F, evaluation/FrameTraceJson.kt, A | rotationMs/rotationDegrees 분리, 종료 시각/별도 latency 분석 추가 | rotation 키 오류 수정. outputParsing 키/통계 범위 변경 아님 |
| mlkit/MlKitObjectDetector.kt, warning/output/*Player.kt 및 WarningOutputController.kt | ML Kit 완료 listener, 출력 API/TTS callback 관측 추가 | 완료/출력 스레드에서 추가 비용 발생 가능 |
| W 전체 | **P0 변경 없음** | 줄별 flush는 기존 구현. 새 이벤트가 기존 flush 횟수를 늘리는 점이 검증 대상 |
| 모델/labels, settings/AppSettings.kt, camera/DetectionConfig.kt, YoloResizeTransform.kt, 경고 정책, Gradle 의존성 | 해당 비교에서 변경 없음 | 모델·ROI·Letterbox·class/threshold·백엔드 변경으로 설명할 근거 없음 |

정상 inference 종료의 기존 elapsedMs(start)는 현재 endNs를 보존한 뒤 (endNs − startNs) / 1,000,000으로 계산하는 형태로 바뀌었다. 기존 helper도 같은 clock과 나눗셈이다(D:729–730). **정상 경로의 종료 clock을 한 번 더 읽도록 바뀐 것은 아니다.** 예외 경로의 종료 시각 관측은 추가됐다.

### 2.3 APK와 실행 조건의 식별 한계

app/build.gradle.kts:15–19의 applicationId/versionCode/versionName은 동일하고 버전은 1/1.0이다. 로그 헤더에는 appVersion/model SHA가 있지만 Git SHA·APK SHA·debuggable/ART 상태를 입증할 정보가 부족하다(F:9–42, 128–132). 특히 10/22 09:27 세션은 P0 커밋 시각 09:45보다 이르므로, Git 커밋이 식별됐다는 사실만으로 그 APK와 바이트 단위 동일성을 증명할 수 없다.

현재 app/build/outputs/apk/debug/app-debug.apk와 output-metadata.json은 존재하고 variantName=debug, versionCode=1이다. 파일 수정 시각은 10/21 09:52이지만 **수정 시각/파일명만으로 A 또는 B APK로 인증하지 않았다.** 보존된 P0 이전 APK는 이번에 식별하지 못했다. 53560e4는 A 소스 재빌드 기준으로 사용할 수 있다.

## 3. 실제 코드 호출 구조 및 시간 측정 범위

### 3.1 Interpreter 반환 이후의 순서

~~~text
CameraX 단일 Analyzer executor
  수신 timestamp → P0 recorder/Received enqueue
  ImageProxy → Bitmap 변환/회전
  MainActivity.processBitmap (동기)
    ML Kit ROI/640×360 준비 → Dispatchers.IO 비동기 실행
    DetectionPipeline.process
      YOLO ROI crop → detector.detect (synchronized)
        Letterbox → input buffer 채우기 → output buffer rewind
        inferenceStart → trace + runForMultipleInputsOutputs → inferenceEnd
        lastNativeInferenceDurationNanoseconds 조회
        선택적 output tensor 진단
        parseOutput
          output direct buffer → 재사용 FloatArray 행 복사 [copy]
          5,376 후보 × 좌표/9클래스 scan + 유효 후보 생성 [scan]
          진단 요약
          전체 후보 confidence 정렬 → 전역 상위 최대 100개
          같은 클래스 NMS → detector diagnostics
      ROI offset/경고 정책/필터/추적 등 pipeline 후처리
      pipeline timer 산출 → finally의 crop recycle/debug finish → 복귀
    completedNs (FRAME 성공 완료/totalProcessing 끝)
    경고 후보 선택·submit 및 P0 Warning enqueue
    metrics/평가 기록/FRAME enqueue → Preview ON이면 UI 제출
  Bitmap recycle → ImageProxy.close → P0 Analyzer enqueue → callback 복귀
~~~

근거: C:70, 109–174; M:560–602, 623–637, 748–800, 1347–1419; D:273–328, 369–579; P:42–205. ML Kit 완료/출력 callback/writer는 위 동기 경로와 다른 스레드에서 겹칠 수 있다. Java API 이름은 단일 run() 대신 **runForMultipleInputsOutputs()**가 실제 호출이다.

### 3.2 필드의 포함·제외 관계

| 필드 | 시작/끝과 포함 연산 | 제외/중첩 주의 |
|---|---|---|
| outputParsing | M:768–769 = D:370–372의 outputCopyTimeMs + D:391–505의 candidateScanTimeMs | **parseOutput 전체가 아님.** 후보 리스트 준비, scan 뒤 요약·전역 정렬·NMS·diagnostics 작성은 제외 |
| post | M:771–772 = detector post(D:369→556) + P의 정책 후처리(P:69→150) | copy/scan·전역 정렬·NMS를 포함하므로 outputParsing/nms와 더하지 않음 |
| nms | D:532–540, nms(nmsInput) 및 debug 결과 기록 | D:528의 전체 후보 정렬/상위 100개 선택은 제외. 정수 0ms는 1ms 미만일 수 있음 |
| pipeline | P:42→192, ROI crop·detector·정책 후처리 등 | inference/post 포함. 반환 객체 인자 평가 뒤 finally(P:201–205)의 정리와 return tail은 제외 |
| inference | D:276→289, Java Interpreter 호출과 기존 trace 경계 | JNI/입출력 전달·대기 등이 포함될 수 있음. 순수 kernel CPU 시간 아님 |
| nativeInvoke | D:292에서 Interpreter가 제공한 lastNativeInferenceDurationNanoseconds | 별도 독립 단계가 아님. inference와 더하거나 두 값의 차이를 정확한 Java overhead로 부르지 않음 |
| totalProcessing | M:578의 pipeline 복귀 직후 completedNs − analyzerReceivedElapsedNs(M:794–795) | 변환·회전·ML Kit 입력 준비·pipeline 정리 포함. 이후 경고/평가/metrics/기록 제출·Bitmap recycle/close/UI draw 제외 |
| ANALYZER callback | L:64–79, analyzer 수신부터 close 뒤 종료 관측까지 | totalProcessing을 포함. 마지막 enqueue/실제 return 직전 tail은 관측 끝 바깥 |

output tensor 진단(D:299–307)은 **outputParsing 및 detector post 바깥**, detector/pipeline 안이다. rawTopConfidences 정렬(D:543)은 post 안/outputParsing 밖이고, D:569 이후 최종 진단 로그 및 detect의 also(D:322–327)는 detector post 밖이다. 입력 tensor 진단은 inputBuffer 안이다. 시스템·배터리 진단 ON만으로 tensor/상세 후보 진단 ON을 뜻하지 않는다.

outputCopy/candidateScan 및 기존 정수 ms 처리 지표는 elapsedRealtimeNanos 차이를 정수 ms로 내림한다. nativeInvoke 등 ns 상세 지표와 LATENCY timestamp는 이 정수화 설명과 구분한다. outputParsing의 두 정수 항 합과 실제 두 구간 합의 차이는 **2ms 미만**이므로 +50~75ms를 설명할 수 없다. Unix ms/카메라 시각을 outputParsing 계산에 섞는 코드나 ms/ns의 1,000배 혼동은 찾지 못했다(D:370–372, 391–505, 729–730; M:768–769). 이 경과 시간에는 스레드가 CPU를 못 받은 시간과 GC 중단도 포함될 수 있다.

## 4. outputParsing 지연 증가 원인 분석

### 4.1 출력 크기와 복사: 이미 재사용 중

[1,13,5376] FLOAT32는 **69,888 float = 279,552 bytes = 273KiB**다. D:init:142–155에서 output direct ByteBuffer/FloatBuffer view와 Array(13) { FloatArray(5376) }를 한 번 만들고 재사용한다. Y:YoloOutputReader.copy:127–142의 이 shape 경로는 13개 행에 floatView.get(row)를 수행한다. 프레임마다 새 2차원 출력 배열을 만들거나 전체 전치하지 않는다.

Interpreter API 내부에서 앱 출력 버퍼로 전달하는 비용과 parseOutput 안에서 행 배열로 복사하는 비용은 구분해야 한다. 이 앱의 outputCopy timer는 **후자의 호출만** 감싼다. 273KiB라는 고정 크기만으로 80ms를 정상이거나 불가능하다고 판단하지 않는다. outputCopyTimeMs와 candidateScanTimeMs의 개별 원본 값이 없어 어느 쪽이 증가했는지부터 미확인이다.

### 4.2 후보 scan과 장면 의존 비용

D:393–420은 매 프레임 5,376개를 순회하며 좌표 4×5,376=21,504개와 클래스 score 9×5,376=48,384개를 기본적으로 읽는다. 9개 score의 최대값을 선택하며 objectness 곱·sigmoid·softmax를 새로 수행하지 않는다. 채널별 FloatArray를 후보마다 접근하지만 접근 방식은 P0 이전과 같다.

confidence 통과 뒤에는 좌표 재읽기·유효성 검사·Letterbox 역변환·면적 검사·DetectionResult 생성이 추가된다(D:422–503). 따라서 기본 scan량은 일정해도 **confidence 통과 수·유효 후보 수에 따라 비용이 달라진다.** 전역 상위 100개 제한은 이 scan/생성 **이후**다. FRAME detect는 pipeline의 mappedDetections.size(M:776)로, threshold 통과 전 후보 수나 NMS 이전 후보 수가 아니다. 최종 탐지가 적다는 이유로 scan/할당 증가를 배제할 수 없다.

### 4.3 할당과 상세 진단: 기존 비용과 새 비용의 구분

| 경로 | 소스상 반복 생성/비용 | P0 신규 여부 |
|---|---|---|
| 일반 scan | candidates 리스트(D:382), 유효 후보별 DetectionResult(D:495), 후보별 계산. DetectionResult 기본 timestamp도 생성 시 읽음. 4인자 minOf/maxOf(D:434–435)의 비용/런타임 할당 여부도 확인 대상 | 기존 |
| 면적 필터 | NORMAL/RELAXED에서 범위 표현식(D:474–478). 실제 객체/escape 제거 여부는 런타임 미측정 | 기존 |
| scan 뒤 | 정렬/상위 후보/NMS 리스트(D:528, 582 이후), diagnostics 및 pipeline map/filter/copy | 기존. 대부분 outputParsing 바깥 |
| detector enableDiagnostics=true | rawConfidences의 ArrayList<Float>(5376), score boxing(D:383, 413), 후보별 Log.d | 기존. scan 안의 boxing/로그는 outputParsing에 포함 가능 |
| debugFrame/tensor diagnostics | raw 기록·diagnosticBox·문자열/추가 집계(D:187–190, 390, 419, 456 등) | 기존. 설정·실제 활성 주기 확인 필요 |
| P0 recorder/events | trace/context·event·queue closure/Runnable, writer 문자열 | 신규. parseOutput 안에서 직접 생성하지 않지만 GC/CPU 경쟁 가능 |

Detector diagnostics는 M:290의 detectionConfig.enableDetectorDiagnostics, debug recorder는 별도 설정이다. 사용자 조건의 “시스템·배터리 진단 ON”을 상세 후보 로깅 ON으로 해석하면 안 된다. 최신 헤더가 없으므로 상세 진단 설정 불일치가 실제로 있었는지 확인하지 못했다.

### 4.4 현재 설명 가능한 범위와 우선 조사 대상

1. **측정 경계 확대/단위 오류:** 소스상 해당 변경이 없어 직접 설명으로 지지되지 않는다. rotation 중복 키 수정도 outputParsing 필드를 바꾸지 않는다(F:166–194; A:fields).
2. **동일 copy/scan의 실행 상태 변화:** CPU 정책 상한·실행 코어 이동·ART JIT/AOT/빌드·debugger/profiler 조건, Runnable 대기·GC 중단을 먼저 구분해야 한다. 이 계열은 코드를 바꾸지 않아도 경과 시간이 늘어나는 설명이지만, 어느 하나가 실제 원인인지는 미확정이다.
3. **후보/진단 부하 차이:** 같은 5,376개라도 scan 안의 추가 연산/객체 수는 달라질 수 있다. confidencePassedCount/nmsInputCount와 상세 진단 설정이 필요하다.
4. **P0 간접 부하:** writer/ML Kit/출력 listener가 실행 코어·메모리/GC를 공유할 수 있다. 이벤트 수가 늘었다는 사실만으로 75ms를 계측 비용으로 배정할 수 없다.

약 4.846→58.16/79.53ms는 보고값상 약 12/16배다. 이 크기를 시작 온도 +3.4°C나 CPU 정책 상한 하나만으로 설명할 실측 근거가 없다. 3분 세션은 inference가 이전 10분 평균보다 짧으면서 파싱이 길다고 보고됐지만 Preview·시간 길이·장면이 달라 통제 비교가 아니다. **“열만의 문제”나 “P0의 새 파서 문제”로 결론 내리기보다 copy/scan 분리와 관리 스레드의 실제 CPU 실행/대기 비중을 먼저 확보해야 한다.**

## 5. FPS 저하와 CPU·발열 관계

### 5.1 온도/주파수 증거의 한계

이전 [10/20 분석 문서](yolo-inference-optimization-analysis-20261020.md) §3.1에는 다음 과거 재집계가 있다.

| 이전 2T 세션 | 첫 60초 | 마지막 60초 |
|---|---:|---:|
| FPS | 3.900 | 2.250 |
| inference 평균 ms | 224.115 | 392.585 |
| policy9 scalingMax 구간 평균 GHz | 2.560 | 1.440 |

이전 문서는 첫 SYSTEM 표본 policy9 상한 3.072GHz, 약 100초 1.632GHz를 기록하며 NONE 상태인 초기 1–2분에도 지연 증가가 있었다고 설명한다. **이 값들은 이번 원본 재검증 결과가 아니다.** 최신 10/22 세션의 시작/구간 CPU 상한은 현재 알 수 없다. 사용자 요청의 “상한 차이가 존재한다”는 해석을 새 로그로 입증한 사실로 채택하지 않았다.

metrics/AndroidSystemMetricSource.kt:48–75의 CpuFrequencyReader는 scaling_cur_freq(대체 cpuinfo_cur_freq), scaling_max_freq, cpuinfo_max_freq, related_cpus, governor를 읽는다. 이는 **기기 CPU policy 값**이며 YOLO 스레드가 실제 어느 코어에서 얼마의 유효 클럭으로 실행했는지 보여주지 않는다. metrics/SystemPerformanceDiagnostics.kt:35–43의 프로세스 CPU%는 모든 프로세스 스레드의 CPU time 합으로, 100%=한 코어이며 YOLO 전용 사용률이 아니다.

배터리 온도 30.8/34.2°C는 시작 조건 차이의 근거지만 SoC/CPU junction 온도가 아니다. 최신 종료 39.1°C가 이전 41.7°C보다 낮다는 보고도 실행 성능이 더 좋았다는 뜻은 아니다. Thermal NONE은 API 상태 관측이고, 그 자체로 DVFS·전력 정책/코어 배치가 동일했음을 보장하지 않는다. Headroom도 배터리 온도가 아닌 느린 센서 기반 추정치다. 상태와 headroom의 관계에는 기기별 제약이 있다([Android PowerManager 공식 문서](https://developer.android.com/reference/android/os/PowerManager)).

따라서 기존 지속 성능 저하에는 CPU 정책 제한과 지연의 동행 근거가 있지만, **새 세션의 추론 +84ms 또는 파싱 +75ms를 같은 원인으로 확정할 수 없다.** 정책 상한 하락이 실제로 있었는지, 원인이 thermal인지 다른 전력 정책인지도 분리 확인해야 한다.

### 5.2 ML Kit와 Preview OFF

ML Kit 입력 crop/resize는 Analyzer에서 준비하고 실행은 Dispatchers.IO로 전달한다(M:131, 1347–1380). single-in-flight 및 인식 간격 검사는 유지됐다. P0는 완료 callback 계측을 추가했지만 ROI/640×360 입력·기존 coroutine 실행 구조를 바꾸지 않았다. ML Kit는 Preview OFF에서도 계속 실행되고 UI 제출만 조건부다(M:1403).

기존 문서 §3.2의 mlKitInFlight=true/false inference 평균 348.185/336.381ms는 과거 상관이며 시간대·열·장면이 통제되지 않았다. 이 차이를 ML Kit의 인과 비용이나 새 회귀 기여량으로 재사용하지 않는다. dispatch 직후 Boolean도 실제 네이티브 연산의 전체 겹침을 나타내지 않는다.

Preview OFF는 Preview use case/표시만 제외하고 ImageAnalysis·RGBA 변환·회전·YOLO crop/Letterbox·ML Kit·경고·통계/로그는 계속 수행한다(C:181–190; M:573, 623–637, 759–800). “화면 OFF이므로 카메라/ML Kit 비용이 없다”는 설명은 실제 경로와 다르다.

### 5.3 FPS와 totalProcessing의 차이

경고/이벤트 제출·FRAME 기록·Bitmap recycle/close는 성공 완료 timestamp 뒤에도 Analyzer를 점유한다. 그래서 이 비용이 다음 프레임의 시작을 늦추면 FPS는 떨어지면서 현재 FRAME totalProcessing에는 직접 나타나지 않을 수 있다(C:158–174; M:578, 623–800). 1 / 평균 totalProcessing을 실제 FPS와 동일시하지 않는다. 새 LATENCY의 callback span/프레임 간격이 이를 검증할 자료지만 현재 원본이 없다.

## 6. P0 계측 부하 분석

### 6.1 프레임당 추가 작업

| 항목 | recorder가 붙은 경로의 소스상 추가 작업 | 한계 |
|---|---|---|
| 수신 context | source FrameTrace 1개, FrameLatencyRecorder 1개와 sink closure, CameraFrameTiming.copy 1개(M:517–518, 1177–1179; S:65–73; C:122) | JVM 내부 전체 객체/bytes 실측 아님 |
| 정상 성공 기본 이벤트 | Received / Warning / Analyzer **3개**, 각각 1행(L:7–45; S:73; M:635–637; C:174) | 세션 창/종료/큐 drop 때문에 파일 수와 항상 같지는 않음 |
| skip 경로 | Received + Analyzer **2개** | 성공 FRAME/FPS와 다름 |
| 가변 이벤트 | ML Kit 시도당 완료 1개, 실제 Beep pulse당 1개, 진동 요청당 1개, TTS 요청/시작 또는 시작 미확인 | 경고/비동기 완료 수에 의존 |
| 기본 추가 clock | Warning 3회 + Analyzer finally 4회 = **7회**(M:623,628,636; L:67,68,74,77) | latency null이면 해당 호출은 실행되지 않음. Received는 기존 timestamp 재사용 |
| 가변 clock | ML Kit dispatch/request/완료 각 1회, 출력 API 요청 전/후 각 1회, TTS register/callback 등 | 호출 횟수로 ms 비용을 확정할 수 없음 |

성공 2.6 FPS, skip 없음이라면 기본 LATENCY 추가량은 산술적으로 약 7.8행/초 + ML Kit/출력이다. **이번 최신 로그의 실제 이벤트율이나 측정 CPU 비용이 아니다.** 프레임당 추가 context/event 외에 writer closure/Runnable·큐 노드·포맷 문자열 할당도 가능하다.

### 6.2 동기화·큐·flush·스레드

S:65–73은 session monitor를 잡아 recorder를 만들고 이벤트 sink에서도 같은 monitor를 사용한다. Received 기록은 같은 monitor의 재진입이다. 그 안에서 W:25–30의 writer monitor → semaphore.tryAcquire → executor 제출을 거친다. System/Battery/ML Kit/출력 callback도 session monitor를 공유한다(S:35–59). **tryAcquire가 비차단이어도 monitor 획득 대기까지 사라지는 것은 아니다.**

문자열 생성은 S:70의 lambda가 W:30–32의 단일 writer worker에서 평가될 때 수행된다(F:198–216). 매 줄 writer.flush()도 worker에서 수행하며 Analyzer가 디스크 쓰기 완료를 직접 기다리는 구조는 아니다. 하지만 CPU·메모리 대역폭·GC·I/O 경합은 공유한다. W는 P0 이전부터 최대 512개 outstanding line 및 줄별 flush 구조였고, P0로 **행 수/flush 호출 수가 늘었다.** flush를 매번 물리 디스크 fsync하는 것으로 해석하지 않는다.

Analyzer와 YOLO는 같은 단일 executor의 동기 경로다(C:70, 109, 158; M:577). 별도 “후처리 전용 스레드”가 새로 생기지 않았다. Received enqueue는 pipeline 전, Warning은 pipeline 후, Analyzer 이벤트는 close 후다. **parseOutput의 copy/scan 안에서 session lock/큐 제출/formatLatency를 직접 호출하지 않는다.**

ML Kit 완료 listener는 DIRECT_EXECUTOR이므로 완료를 전달한 스레드에서 이벤트를 만들고 enqueue한다(mlkit/MlKitObjectDetector.kt:43–52, 81). TTS/출력도 해당 callback/API 경로가 비용을 부담한다. 별도 writer로 포맷을 넘겼다는 것과 호출 스레드 비용이 0이라는 것은 다르다.

### 6.3 메모리/GC 및 OFF 경로

P0가 큰 Bitmap/tensor/후보 배열 사본을 추가한 증거는 없다. writer outstanding 512개와 TTS 연결 32개 상한은 있다(W:13,18; L:100,114–117). 그러나 추가 event/closure/문자열은 할당률을 높일 수 있고 기존 Bitmap/후보/ML Kit 할당과 GC를 공유한다. GC 원인·pause·누수·실제 allocation rate는 새 로그와 profiler 없이 확정할 수 없다.

기록 OFF이면 새 수신 프레임 recorder/events와 위 기본 7회 clock은 없다. TTS listener는 계속 등록되어 callback 시 clock/pending lookup이 남고, 기록 중 시작한 작업의 늦은 callback은 닫힌 sink에서 폐기될 수 있다(L:134–143; warning/output/TtsWarningPlayer.kt:42–48). OFF를 완전 무부하 기준으로 취급하지 않는다.

### 6.4 FRAME/LATENCY 중복 집계 여부

S:65–73의 LATENCY 경로는 stats.record를 호출하지 않고, 성공 FRAME만 S:76–83에서 통계에 추가된다. metrics/PerformanceIntervalCollector.kt:29–33,76–80도 성공 프레임 입력을 집계한다. Python A:278–280은 “FRAME ” 행만 선택하고 A:323 이후 latency_analysis는 별도 처리한다. **현 코드에서 새 LATENCY를 성공 프레임/FPS로 중복 집계한 증거는 없다.**

원본이 없어 실제 행 수·drop·완료 수 일치는 검증하지 못했다. 설령 drop 0이어도 queue가 포화되지 않았다는 정보일 뿐, 포맷/flush/lock/GC 부하가 없다는 뜻은 아니다.

## 7. 원인별 근거와 신뢰도

“높음”은 해당 **코드 사실의 신뢰도**이며 성능 원인 확률이 아니다.

| 판단/가설 | 근거 | 현재 신뢰도 / 반증·추가 확인 |
|---|---|---|
| P0가 파싱 알고리즘/출력 배열 경로를 바꿈 | Git diff, D/Y/P 및 M:768–769 | 해당 변경 **없음: 높음**. 설치 APK가 소스와 같은지는 별도 |
| timer 범위/단위 오류로 5→80ms | 동일 elapsed clock/copy+scan 경계 | 지지 안 됨: 높음. 원본 집계 필드 선택 오류는 아직 확인 필요 |
| LATENCY가 FPS에 중복 집계됨 | S/A의 별도 집계 경로 | 현 구현상 없음: 높음. 실제 기록 완전성은 미검증 |
| 출력 copy 또는 scan 실제 경과 시간 증가 | 최신 평균/30초 값은 사용자 보고 | 보고 현상, 원본 미검증. copy/scan별 증가량도 모름 |
| CPU 정책/전력 관리가 지속 추론 저하에 기여 | 이전 문서의 상한 하락·지연 동행, 최신 시작 온도 차이 보고 | 이전 현상에는 근거. **최신 원인 기여는 미확정** |
| 스케줄링/실행 코어·ART/빌드 조건 변화 | 동일 관리 코드의 시간 증가를 설명 가능한 후보, APK 식별 한계 | 가설. trace/variant/compilation 조건 필요 |
| 후보 수/상세 진단 차이 | D:383–503의 조건부 연산·할당 | 코드 가능성 높음, 실제 차이 미확인 |
| P0 이벤트/flush가 CPU/GC 경쟁 유발 | 이벤트/clock/lock/행 추가는 확인 | 추가 작업 존재: 높음. **75ms 설명 여부 미확정** |
| ML Kit 동시 실행/기존 Bitmap/객체 할당 | 실제 비동기 경로와 반복 할당 | 경합 가능. GC/CPU 비용 및 새 차이는 미측정 |

현 단계에서 바로 고쳐야 할 **확정된 파서 회귀 버그·파서 내부 새 lock·단위 오류는 발견하지 못했다.** “미발견”은 P0 무혐의 또는 무부하를 입증한 것이 아니다.

## 8. 필요한 추가 검증 방법

### 8.1 우선 원본과 APK 식별

세 원본을 확보해 파일 SHA-256, sessionId, 설정 snapshot/model SHA, 실제 카메라 크기/회전, 진단 설정, FRAME/ERROR/LATENCY 수와 확정 summary를 확인한다. 10/20 원본은 §1.1의 과거 SHA와 대조한다. APK는 A/B별 SHA·Git commit·build variant/debuggable·빌드 도구/의존성·설치 방법을 함께 보관한다. 버전 1.0이나 파일 수정 시각만으로 구분하지 않는다.

현재 512 asset은 읽기 전용 SHA 재확인 결과 **f312b541c86426c53c02ef1674ee539c6f87fcf97102061fade628317b3c10b9**, labels는 **ef1d20e200c6ec8a7a6e888c823abfb86b5b0fb92f67dab022aaf3b35309edc4**다. 두 Git 버전 사이 assets diff는 없지만 새 로그의 모델 SHA 일치까지 확인한 것은 아니다.

### 8.2 원본 확보 후 재집계 규칙

- 성공 FRAME만 완료 ns 기준으로 집계한다. t=(completedElapsedNs − Start Elapsed Ns)/1e9, 첫 30초 [0,30), 마지막 30초 [실제 종료−30,실제 종료)로 나누고 표본 수를 표시한다. 명목 600초 기준을 썼다면 그 기준도 명시한다.
- 전체 FPS는 성공 수/실제 측정 시간, P95는 원 프레임의 nearest-rank로 계산한다. 구간 P95 평균이나 구간 평균으로 전체 P95를 만들지 않는다.
- **A:310–315의 기본 JSON에는 outputParsing/post/nms/resize가 직접 포함되지 않는다.** fields로 읽은 FRAME에서 outputParsing은 detail_stats 또는 stats로, post/nms는 stats로 별도 산출해야 한다. 잘못된 JSON 항목을 파싱 시간으로 대체하지 않는다.
- 프레임별 outputParsing 대 inference/nativeInvoke/detect를 비교하고 30초 구간 평균도 함께 본다. confidencePassedCount/nmsInputCount가 없는 FRAME detect만으로 후보량을 대체하지 않는다.
- SYSTEM은 통상 5초 간격의 policy 표본이다. 같은 시간 구간의 상한/온도와 FRAME 지연을 비교하며, 최근 표본을 붙였다면 시간차를 명시한다. 이를 프레임 순간 실행 클럭으로 표현하지 않는다. 전체 상관 외 시간대/온도 구간을 맞춘 비교로 공통 시간 추세의 영향을 확인한다.
- LATENCY는 sessionId+frameId로 결합하고 event clock 순서를 검증한다. FRAME 성공 수에는 합산하지 않는다. 창 밖/결측 timestamp는 0으로 채우지 않는다.

### 8.3 최소 A/B/A: 각 약 3분부터

1. **A=53560e4, B=8fdc175, A=동일 A APK 재측정.** 동일 기기/OS, 모델 SHA, 512×512/2T, CPU/XNNPACK 요청 ON, Preview OFF, ROI/Letterbox/confidence/NMS/ML Kit/경고·시스템·배터리 진단 설정을 고정한다. 장면·조도·카메라 실제 크기/회전·화면 밝기·충전/절전 상태도 기록한다.
2. 매 회 충분히 냉각하고 시작 배터리 온도는 예를 들어 ±0.5~1°C 범위, 잔량·실내/표면 조건도 최대한 맞춘다. 이것은 **시험 제안값**이다. 각 policy의 scalingMaxKHz와 Thermal/headroom도 시작 전 여러 표본으로 확인한다. 온도 하나만 같아졌다고 열 상태가 완전히 같다고 가정하지 않는다.
3. debugger/profiler를 연결하지 않은 실행을 먼저 한다. 두 APK의 variant·ART compilation/워밍업 절차·앱 재시작 조건을 맞추고 기록한다. 최초 실행/설치 효과와 냉각 조건을 혼동하지 않도록 각 반복에 같은 절차를 적용한다.
4. 각 회 약 3분 기록 후 냉각한다. 무수정 APK의 기본 비교는 FPS, inference/nativeInvoke, outputParsing 합, post/total, 첫/마지막 30초, detect, 온도/상한/drop 등 기존 공통 필드로 수행한다. callback·이벤트율은 B의 추가 관측으로만 구분한다. copy/scan·NMS 이전 후보 수는 §8.5의 별도 동일 계측 비교에서 분리한다. A/B/A의 반복 흔들림보다 큰 B 차이가 재현되는지 보고, 작은 차이면 교차 반복으로 분산부터 확인한다.
5. A2도 B처럼 느려졌으면 시간/열/실행 상태의 교란을 먼저 의심한다. B에서만 반복되면 P0 차이가 원인 후보로 강화되지만 아직 개별 이벤트/GC/lock 원인은 정해지지 않는다. **원인이 구분된 뒤에만 10분 지속 시험**으로 확장한다.

Debug와 release는 실행 비용이 다를 수 있으므로 두 버전의 variant를 맞추는 것이 먼저다. 이후 별도 성능 시험은 profileable release/샘플링 방식을 고려한다. allocation/heap 수집은 별도 debug 시험으로 분리한다([Android 성능 측정 준비](https://developer.android.com/topic/performance/measuring-performance), [Android Profiler](https://developer.android.com/studio/profile)). 이번에 ART/프로파일러 차이를 실제 원인으로 확인한 것은 아니다.

### 8.4 이전 APK가 없을 때의 별도 빌드 방법

A 소스는 53560e4로 식별된다. 다음은 **실행하지 않은 후속 PowerShell 예시**다. 원본 checkout의 앱 소스/설정/기존 build 산출물을 건드리지 않도록 A/B 모두 별도 worktree에서 같은 도구로 빌드한다. 경로가 이미 있으면 덮어쓰지 않고 다른 새 경로를 선택한다. worktree 등록은 원본 Git 저장소에 관리 메타데이터를 추가하지만 원본 소스 작업 트리를 체크아웃/리셋하지 않는다.

~~~powershell
git worktree add --detach D:/develop/Projects/TestProjects/ObjectDetection-P0-A 53560e44e2b01c6d3bee88b44147e90a7dc2261d
git worktree add --detach D:/develop/Projects/TestProjects/ObjectDetection-P0-B 8fdc17549e1a1dc0bec16eae2b50521bce42f07b
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
$env:ANDROID_HOME = 'C:/Users/SAMIN/AppData/Local/Android/Sdk'
& 'D:/develop/Projects/TestProjects/ObjectDetection-P0-A/gradlew.bat' -p D:/develop/Projects/TestProjects/ObjectDetection-P0-A assembleDebug --offline --console=plain
& 'D:/develop/Projects/TestProjects/ObjectDetection-P0-B/gradlew.bat' -p D:/develop/Projects/TestProjects/ObjectDetection-P0-B assembleDebug --offline --console=plain
~~~

각 명령 성공을 확인한 뒤 다음 단계로 진행하고, 캐시/SDK/JDK 조건이 다르면 먼저 두 빌드 환경을 맞춘다. 모델과 APK SHA를 별도 보관한다. 설치 전 앱 데이터/설정 백업과 동일 서명에 의한 데이터 유지 경로를 확인하고, 기존 데이터를 지우는 uninstall/clear는 사용하지 않는다. 재빌드 A는 당시 설치 APK 자체와 같다고 보장되지 않으므로 “소스 재현 A”로 표기한다. 이번 작업에서는 worktree 생성/빌드/설치를 하지 않았다.

### 8.5 회귀가 재현될 때의 최소 분리 실험

| 확인할 질문 | 후속 방법 | 결과 해석 |
|---|---|---|
| copy와 scan 중 무엇이 늘었나? | 기존 DetectorFrameDiagnostics의 outputCopyTimeMs/candidateScanTimeMs를 확인. 별도 실험 빌드에서 같은 FRAME에 두 값과 후보 수를 추가하는 최소 계측 고려 | 기존 FRAME outputParsing 합만으로 둘을 구분할 수 없음. 고용량 상세 후보 로그를 켜서 측정 조건을 바꾸지 않음 |
| P0 LATENCY 부하인가? | 별도 진단 브랜치에서 B 전체와 **FRAME 기록은 유지하고 LATENCY만 비활성화한 B** 비교. 필요하면 이벤트 생성만/큐 제출까지를 분리 | 기존 성능 기록 전체 OFF는 FRAME 관측도 사라져 동등 비교가 아님. 기본 설정/인식 로직은 유지 |
| 실제 연산인가, 선점/GC인가? | 짧은 copy/scan span의 경과 시간과 해당 Analyzer thread CPU time, GC pause를 대조 | 경과 시간만 증가하면 대기 가능성, CPU time도 증가하면 연산/코어 성능·실행 코드 차이 추가 확인 |
| 누가 CPU를 점유하는가? | Perfetto/System Trace에서 Analyzer·ML Kit·writer·ART/GC의 sched_switch/sched_waking, Running/Runnable/Blocked, cpu_frequency/코어 이동 수집 | “Runnable 대기/lock/GC/Running 저클럭”을 구분. policy9만 보고 YOLO 실행 코어를 가정하지 않음 |
| 어느 함수가 CPU를 쓰는가? | CPU sampling으로 YoloOutputReader.copy, parseOutput, 포맷/할당 hot stack 확인. allocation 수집은 별도 짧은 실험 | profiler 자체 부하를 가진 실행과 무프로파일 기준 실행을 구분 |

copy/scan·후보 수의 추가 FRAME 계측이 필요하면 A와 B 양쪽에 같은 최소 관측을 넣은 보조 비교로 수행하고, 원본 APK A/B/A 결과와 별도로 표기한다. 보존된 기존 DetectionTiming Logcat에 outputCopyMs/candidateScanMs가 이미 있다면 활용할 수 있다(M:698–710). 그러나 이 Logcat의 gate인 enableDetectorDiagnostics(M:1217–1218)를 새로 켜면 scan 안 boxing/후보 로그도 늘어나므로 기본 시험을 얻기 위해 켜지 않는다. LATENCY 비활성화 비교도 진단 variant임을 명시해 배포 APK 비교와 섞지 않는다.

Perfetto는 스레드 실행 코어와 deschedule/재실행 대기를 구분하는 근거를 제공한다([공식 CPU scheduling 문서](https://perfetto.dev/docs/data-sources/cpu-scheduling)). 기존 trace section만으로 copy/scan 구간을 구분하기 부족하면 다음 계측 작업에서 작은 범위의 marker를 추가한다. Perfetto timestamp와 elapsedRealtimeNanos는 trace의 clock snapshot/매핑을 확인한 뒤 정렬하며 임의 offset을 가정하지 않는다. **이 표의 실험은 모두 미실행이다.**

## 9. 우선순위별 개선 방안

| 순서 | 적용 조건 | 개선 방향 / 보존 조건 |
|---|---|---|
| 1. 증거 확보 | 지금 | 원본/설정/APK 식별 → 조건을 맞춘 무수정 3분 A/B/A → 필요 시 양쪽 동일 계측으로 copy/scan 분리. 불확실한 원인을 코드 수정으로 고정하지 않음 |
| 2. 계측 오류 수정 | 원본/타이머 불일치가 재현될 때 | 해당 timer/집계 필드만 수정. 현 소스에서는 확정 오류 미발견. 기본 분석기에 outputParsing/post/nms를 노출하는 개선은 관측 편의이며 앱 성능 수정과 구분 |
| 3. P0 기록 경량화 | LATENCY 분리 비교 또는 trace에서 유의한 부하가 확인될 때 | 이벤트 생성/closure 축소, enqueue/lock 범위 축소, writer batch/flush 정책 검토. 순서·session 고정·bounded queue/drop 집계·종료 drain·파일 tail 보존의 계약 검증 필요 |
| 4. 출력 파싱 최적화 | scan/copy의 실제 CPU 비용이 병목으로 확인될 때 | copy/행 접근 측정 후 FloatArray 접근/진단 집계·후보 객체/범위/정렬 할당 검토. 이미 있는 buffer 재사용을 새 최적화로 제안하지 않음 |
| 5. 실행 부하 조정 | CPU 제한/ML Kit 경합/큰 Bitmap 할당의 기여 확인 시 | 처리 구조·입력 사본/진단 작업 배치·동시 실행 부하 검토. 이번 분석만으로 Thread 수·백엔드·ML Kit 주기·카메라 해상도를 바꾸지 않음 |
| 6. 지속성 검증 | 짧은 시험에서 원인과 개선을 구분한 뒤 | 같은 시작 조건의 10분 반복으로 FPS/P95/온도/정확도/경고 출력 확인 |

파싱 최적화 시 9클래스 매핑, normalized xywh, ROI offset, Letterbox 역변환, confidence .2, 전역 maxCandidates 정렬/동점 처리, 클래스별 NMS .45, 경고 정책과 TTS/Beep/진동 동작을 유지해야 한다. confidence 이전에 임의 top-K를 적용하거나 좌표 진단을 제거하는 것은 의미 보존 검증 없이 적용할 수정이 아니다. 모델/labels/설정 기본값/추론 백엔드는 유지한다. **어느 개선안도 ms/FPS 효과를 실측 전에 보장하지 않는다.**

## 10. 권장 후속 작업 및 수정 대상 파일

권장 순서는 **원본·APK 확보 → 기존 수치 재검증 → 냉각 조건을 맞춘 무수정 3분 A/B/A → 필요 시 양쪽 동일 계측으로 copy/scan 분리 → 재현 양상에 따라 LATENCY 분리 또는 trace → 근거가 있는 최소 수정 → 10분 검증**이다.

| 후속 대상 | 필요한 경우 / 작업 |
|---|---|
| tools/analyze_performance_logs.py 및 tools/test_analyze_performance_logs.py, tools/test_latency_analysis.py | outputParsing/post/nms/resize 및 개별 copy/scan·후보 필드가 있는 경우의 통계를 노출하고, 구/신 로그·성공 수/시간 창 의미 유지 |
| detector/DetectionDiagnostics.kt, metrics/PerformanceLogFormatter.kt, M:processBitmap | 기존 copy/scan 시간·confidencePassedCount/nmsInputCount를 최소 필드로 전달하는 진단용 후속 작업 |
| D:parseOutput / Y:YoloOutputReader.copy | 파서 연산/복사가 원인으로 입증됐을 때만 최적화. golden/좌표/NMS 결과 동등성 검증 |
| L, S, W, metrics/PerformanceLogRecorder.kt | LATENCY 생성/동기화/기록 부하가 입증됐을 때 경량화. writer 줄별 flush가 기존 코드였음을 반영 |
| C / M:maybeRunMlKitDetection / mlkit/MlKitObjectDetector.kt | callback 점유 또는 ML Kit 경합이 확인된 경우 실행 구조 검토 |
| metrics/AndroidSystemMetricSource.kt, SystemPerformanceDiagnostics.kt | 필요 시 CPU 표본/기기 정책 해석 보완. policy 값과 실제 스레드 클럭을 혼동하지 않음 |

**이번 검증:** Git/소스·기존 보고서 정적 대조, 지정 원본의 관련 경로 검색, 현재 모델/labels SHA 확인, 기존 Python 회귀 테스트 26개 통과(실행: .venv/Scripts/python.exe -B -m unittest discover -s tools -p 'test_*.py'). 테스트는 분석기 회귀 확인이며 새 APK의 무부하/실제 지연 원인 검증이 아니다. 앱 빌드/JVM·기기 테스트와 새 로그 재집계는 하지 않았다. 마지막 작업 트리 확인의 의도된 추가 파일은 이 문서 하나다.
