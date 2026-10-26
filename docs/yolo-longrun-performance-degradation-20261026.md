# YOLO 장시간 실행 성능 저하 조사 — 2026-10-26

## 1. 결론과 조사 범위

**가장 가능성이 높은 원인 후보는 장시간 부하에 동반된 CPU 성능 정책 제한과 스케줄링 변화다.** 두 세션에서 CPU policy의 `scaling_max_freq` 하락과 Java/native 추론 지연 증가가 함께 관측된다. 다만 원본에는 실행 tid/CPU, runnable 대기, SoC 온도, 제한을 결정한 주체가 없다. 따라서 **thermal throttling 확정**, **특정 코어의 클럭 하락이 지연을 유발했다는 확정**, **Interpreter 내부 알고리즘의 누적 성능 저하 확정**은 모두 불가능하다.

ML Kit OFF에서도 같은 약 2.2배 추론 지연과 약 50% FPS 저하가 발생한다. ML Kit가 이 현상의 필수 조건이라는 가설은 지지되지 않는다. 작은 추가 경쟁은 배제하지 않는다. 후처리 parsing과 Bitmap 처리도 느려지지만, 0–30초 대비 180–210초 전체 처리 증가분 중 추론 구간 증가분의 비중은 OFF 80.90%, ON 79.62%다. 이는 시간 범위의 산술 분해이며 원인의 기여율은 아니다.

이번 변경은 이 보고서만이다. 앱 코드·진단 기능·로그 스키마·설정은 변경하지 않았다. 최적화 적용 전후의 기기 성능 개선을 주장하지 않는다. 커밋·푸시는 하지 않았다.

### 소스와 측정 모델의 동일성

- 저장소: `PlyoJay/ObjectDetectionTestApp`, 로컬 경로 `D:\develop\Projects\TestProjects\ObjectDetection`.
- 분석한 HEAD: `7030c3f8aaec0e676f1366c39a221b89d7b26c18` (2026-10-23, KST). 2026-10-26 GitHub connector로 이 SHA와 원격 `main`을 비교하여 `identical`, ahead/behind 모두 0임을 확인했다. [분석 기준 커밋](https://github.com/PlyoJay/ObjectDetectionTestApp/commit/7030c3f8aaec0e676f1366c39a221b89d7b26c18).
- Kotlin/설정 소스에는 조사 시작 시 미커밋 변경이 없었다. **기존 사용자 변경인 `app/src/main/assets/best_512.tflite`는 그대로 보존했다.** 현재 작업 폴더 모델 SHA-256은 `cc189262fd705e7d0b48246ebe42f868ff9b7370d97a0a6d7a7f756f16d4bad1`이다.
- 두 측정 로그의 모델 SHA-256과 `git show HEAD:app/src/main/assets/best_512.tflite`의 SHA-256은 모두 `f312b541c86426c53c02ef1674ee539c6f87fcf97102061fade628317b3c10b9`로 일치한다. **다음 재현 실험에서 현재 작업 폴더 모델을 그대로 빌드하면 이번 로그와 모델이 달라진다.** 사용자 변경을 되돌리지 말고, 측정 모델을 별도로 확보해 해시를 확인해야 한다.
- 로그에 APK의 Git SHA는 없다. 모델 해시와 설정은 대조했지만 측정 APK의 모든 소스가 이 HEAD와 같다는 증명은 없다. 소스 분석은 확인한 최신 HEAD의 동작을 설명한다.

## 2. 기존 로그만으로 판단할 수 없는 항목

추가 계측 필요 여부를 정하기 전에 다음 한계를 명시한다.

| 미확인 항목 | 현재 로그의 한계 | 필요한 최소 증거 |
| --- | --- | --- |
| 제한 주체: thermal / power / 제조사 정책 | 상한 변화는 보이지만 원인 코드·전력 모드·SoC/skin 온도 없음 | 동일 조건 재현, 읽기 가능한 thermal/power 상태; 비공개 값은 N/A |
| 추론 실행 CPU·worker 수·migration | policy는 장치 범위, process CPU는 모든 앱 스레드 합계 | Perfetto sched + frequency + 기존 YOLO trace |
| native 계산량 증가 vs runnable 대기·락·GC | `nativeInvoke`도 elapsed duration이며 thread CPU time이 아님 | trace 구간과 스레드 running/runnable/sleeping, GC pause의 교차 분석 |
| 실제 XNNPACK partition 위임·병렬화 | `xnnpackRequested=true`, `delegateVerification=UNAVAILABLE_JAVA_API` | 같은 모델/런타임의 native delegate 진단 또는 독립 benchmark; 요청값만으로 대체 불가 |
| output copy와 candidate scan의 비용 분리 | FRAME의 `outputParsing`은 두 시간을 합친 값 | 기존 코드 타이머에 대응하는 세부 trace, 필요하면 저빈도 요약 |
| confidence 통과 수·NMS 입력 수의 변화 | FRAME `detect`는 NMS 이후 결과 수; raw 후보/통과 수는 미기록 | 이미 계산하는 `DetectorFrameDiagnostics` 수치만 5초 요약 |
| 반복 할당·GC·누수의 실측 | heap/PSS/RSS/native allocated/GC count/time 없음 | 5초 메모리/GC 추이 + 짧은 ART GC trace |
| 계측의 FPS 영향 | 조회 elapsed는 있으나 writer CPU/I/O 대기·queue depth·비계측 대조 없음 | 동일 조건에서 기록 수준을 나눈 대조 실험 |
| 정확한 센서 프레임 폐기 수 | `droppedFrames`는 앱의 명시적 skip 수만 기록 | CameraX/카메라 측 추적; 앱의 skip 0을 카메라 손실 0으로 읽지 않음 |

배터리 전류·전력은 기존 신뢰성 경고로 인해 유효한 앱 소비량이 아니다. 이하 효율 비교에서 사용하지 않는다.

## 3. 로그 재집계 방법과 무결성

원본은 읽기만 했다. 파일명·세션·SHA-256을 다음과 같이 고정한다.

| 항목 | ML Kit OFF | ML Kit ON |
| --- | --- | --- |
| 파일 (`C:\Users\SAMIN\Downloads\`) | `perf_20261026_093315.txt` | `perf_20261026_095053.txt` |
| 크기 bytes | 1,647,307 | 1,676,930 |
| 원본 SHA-256 | `5f01c455c2924fc13ecceefaba98b4989ce98daf64f7db10e03ba029c0c9da9c` | `af28eb6ca150d5d6e70cec66f67a5a65fcd1d4dcbb57fe04df73d2d47b788f0e` |
| sessionId | `b63df99c-ed2a-4f1e-841e-e3d8ab5f113f` | `2cb98cc2-029c-4f17-9904-bae1e19a6cbf` |
| 실제 기록 길이 / 종료 | 300.025초 / AUTO_DURATION | 300.024초 / AUTO_DURATION |
| 성공 FRAME / 확정 summary 성공 수 | 628 / 628 | 618 / 618 |
| 추론 오류 / 명시적 skip / dropped log lines | 0 / 0 / 0 | 0 / 0 / 0 |
| SYSTEM / BATTERY_SAMPLE | 60 / 61 | 60 / 61 |

공통 조건은 로그 헤더와 settings snapshot에서 확인했다. FLOAT32 input `[1,3,512,512]`, output `[1,13,5376]`, CPU threads 요청 2, XNNPACK 요청 ON, LETTERBOX, Preview OFF, 카메라 요청 1280×720, 회전 후 실제 프레임 1080×1440, 회전 90°, ROI `[108,288,972,1440]` = 864×1152다. 좌우 10%, 상단 20%, 하단 0%, detect interval 0ms다. ML Kit 모드는 두 snapshot 모두 SINGLE_IMAGE_MODE/1500ms이며 enabled만 다르다. 시스템·배터리 진단 5000ms, 상세 detection/ML Kit 진단·PNG 저장은 모두 OFF다. 실제 beep/TTS/vibration 설정은 ON으로 유지되어 있다.

집계 규칙:

1. FRAME을 `completedElapsedNs - Start Elapsed Ns`로 정렬하고 `[0,30), …, [270,300)`에 배치했다. 성공 완료 수/30으로 FPS를 계산했다. 정수 `fps`, 누적 `inferenceFps`, `Average FPS`를 구간 FPS로 평균하지 않았다.
2. 각 구간 실제 유효 FRAME에서 평균과 nearest-rank P95 (`ceil(0.95*n)`번째 값)를 계산했다. 최종 `INTERVAL_SUMMARY`의 프레임 수·추론/pipeline/전체 평균·P95와 **20구간 모두 일치**했다. 평균 비교 허용치는 출력 소수 3자리 반올림 오차 0.00051ms다. `INTERVAL_PREVIEW`는 확정값으로 사용하지 않았다.
3. SYSTEM은 `sampleElapsedNs`, BATTERY_SAMPLE은 `elapsedSeconds`, LATENCY는 `eventElapsedNs`로 따로 정렬했다. LATENCY join은 `(sessionId, frameId)`이며 행 수를 성공 FPS에 더하지 않았다. 이번 두 파일의 LATENCY 관측 시각 역전은 없지만 파일 순서를 일반적인 이벤트 시각 보장으로 가정하지 않았다.
4. SYSTEM 구간값은 **실제 SYSTEM 6개/30초의 산술 평균**이다. CPU 사용률은 첫 샘플 N/A여서 첫 구간 5개, 나머지는 6개다. `INTERVAL_SUMMARY`에 붙은 시스템 값은 마지막 1개이므로 구간 평균으로 재사용하지 않았다. CPU 사용률은 약 5초 길이의 이전 구간 측정이라 경계에서 일부 시간이 걸친다.
5. Headroom은 10초마다 갱신되고 중간 SYSTEM은 약 5초 된 캐시다. 표의 평균은 관측값 평균이며 독립적인 온도 샘플 6개가 아니다. 배터리는 구간당 6개, 300초 직후 최종 endpoint 1개를 별도로 해석했다. 300–300.025/300.024초의 빈 최종 summary는 FPS/상관 분석에서 제외했다.
6. `totalProcessing`은 analyzer 수신부터 pipeline 완료까지이며 Bitmap·ML Kit 준비를 포함한다. `pipeline`은 `DetectionPipeline.process` 범위다. UI draw·경고 출력·일부 기록/cleanup은 전체 처리 이후다. 상세 시간은 상위 시간과 중첩되므로 합산하지 않았다. 정수 ms 내림 때문에 0ms는 작업이 없다는 뜻이 아니다.

측정 시작 전에 analyzer가 이미 동작했다. 첫 FRAME은 OFF id=54, ON id=13으로 수신/추론 시작이 세션 앞에 걸치지만 완료가 세션 안이라 정상 포함된다. 해당 프레임에는 세션 LATENCY recorder가 없고, 반대로 마지막 CAMERA_RECEIVED(OFF id=682 / ON id=631)는 완료가 기록창 밖이어서 대응 FRAME이 없다. 따라서 각 세션 CAMERA_RECEIVED 수는 성공 수와 우연히 같지만 ANALYZER/WARNING은 1개 적다. 이것은 dropped log lines나 추론 오류가 아니다. 초기값은 앱 cold-start benchmark가 아니다.

### 3.1 전체 측정과 30초 구간별 FPS·지연

| 전체 기록 | OFF | ON |
| --- | ---: | ---: |
| 성공 FPS | 2.0932 | 2.0598 |
| 추론 평균 / P95 ms | 350.139 / 649 | 355.015 / 648 |
| nativeInvoke 평균 ms | 350.073 | 354.981 |
| 전체 처리 평균 / P95 ms | 459.752 / 807 | 467.523 / 808 |
| pipeline 평균 / P95 ms | 435.175 / 768 | 441.146 / 769 |
| process CPU 유효 샘플 평균 % | 118.713 | 122.047 |

아래 avg/P95 단위는 ms다.

| 초 | OFF n | OFF FPS | OFF 추론 avg/P95 | OFF 전체 avg/P95 | OFF pipeline avg/P95 | ON n | ON FPS | ON 추론 avg/P95 | ON 전체 avg/P95 | ON pipeline avg/P95 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 0–30 | 96 | 3.200 | 222.47/244 | 297.49/320 | 280.72/303 | 95 | 3.167 | 222.77/247 | 298.88/323 | 281.85/305 |
| 30–60 | 92 | 3.067 | 229.88/250 | 307.73/327 | 290.88/313 | 93 | 3.100 | 229.73/256 | 308.02/343 | 289.96/324 |
| 60–90 | 82 | 2.733 | 255.43/364 | 348.40/490 | 326.10/446 | 72 | 2.400 | 292.04/548 | 390.89/685 | 365.29/650 |
| 90–120 | 66 | 2.200 | 321.12/510 | 430.61/627 | 404.08/589 | 69 | 2.300 | 310.14/557 | 420.28/699 | 393.77/665 |
| 120–150 | 56 | 1.867 | 401.21/616 | 527.88/767 | 500.20/729 | 48 | 1.600 | 466.73/650 | 604.67/808 | 572.00/770 |
| 150–180 | 49 | 1.633 | 457.69/649 | 594.08/806 | 565.41/769 | 49 | 1.633 | 461.39/687 | 603.10/853 | 569.57/813 |
| 180–210 | 45 | 1.500 | 491.91/649 | 630.56/807 | 599.69/769 | 46 | 1.533 | 487.50/649 | 631.39/811 | 598.00/769 |
| 210–240 | 47 | 1.567 | 485.79/649 | 625.04/808 | 594.09/770 | 52 | 1.733 | 420.73/650 | 553.96/807 | 525.42/775 |
| 240–270 | 47 | 1.567 | 487.60/650 | 626.83/809 | 596.40/771 | 47 | 1.567 | 482.13/687 | 623.57/856 | 590.36/815 |
| 270–300 | 48 | 1.600 | 467.96/650 | 603.77/809 | 573.25/771 | 47 | 1.567 | 478.09/649 | 617.28/810 | 584.53/769 |

180–210초/0–30초 추론 평균 비율은 OFF 2.211배, ON 2.188배다. 동일 구간 FPS 감소는 각각 53.1%, 51.6%다. 전체 5분 ON은 OFF보다 FPS 1.59% 낮고 추론 평균 1.39% 높다. 그러나 ON은 60–90초·120–150초에 더 느리고 210–240초에는 오히려 빠르다. 한 번씩의 순차 실험, 시작 배터리 온도/잔량 차이, 장면·다른 프로세스·정책 변동을 통제하지 못했으므로 ON의 순수 인과적 비용을 1.59%라고 결론내리지 않는다.

### 3.2 Bitmap·전처리·후처리 비용

아래는 평균 ms이며 post만 avg/P95다. `parsing=outputCopy+candidateScan`, `post=detector post+pipeline post`다. ROI는 pipeline의 crop 시간이다. NMS는 **모든 1,246 FRAME에서 정수 기록 0ms**여서 별도 열을 생략했다. sub-ms NMS가 존재할 수 있다.

| 초 | OFF bitmap | OFF 전처리 | OFF ROI | OFF parsing | OFF post avg/P95 | OFF ML준비 | ON bitmap | ON 전처리 | ON ROI | ON parsing | ON post avg/P95 | ON ML준비 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 0–30 | 15.75 | 1.16 | 2.26 | 51.62 | 51.85/59 | 0.013 | 15.16 | 1.13 | 2.21 | 52.29 | 52.66/60 | 0.799 |
| 30–60 | 15.65 | 1.18 | 2.47 | 54.28 | 54.51/64 | 0.013 | 16.04 | 1.13 | 2.46 | 53.24 | 53.70/61 | 1.004 |
| 60–90 | 21.18 | 1.54 | 3.23 | 62.73 | 62.96/71 | 0.014 | 23.03 | 2.07 | 3.78 | 64.29 | 64.44/90 | 1.352 |
| 90–120 | 25.29 | 2.62 | 3.73 | 73.48 | 73.59/77 | 0.017 | 23.75 | 2.64 | 3.61 | 74.29 | 74.52/91 | 1.486 |
| 120–150 | 26.21 | 4.00 | 5.18 | 86.86 | 86.96/99 | 0.025 | 28.12 | 4.42 | 6.21 | 91.54 | 91.92/105 | 3.076 |
| 150–180 | 27.31 | 5.43 | 5.55 | 93.84 | 94.43/105 | 0.024 | 29.00 | 4.98 | 7.22 | 93.22 | 93.49/111 | 3.037 |
| 180–210 | 29.44 | 4.73 | 5.82 | 94.18 | 94.69/105 | 0.027 | 28.83 | 4.98 | 7.04 | 95.67 | 95.98/105 | 3.163 |
| 210–240 | 29.32 | 4.83 | 5.91 | 95.00 | 95.38/105 | 0.022 | 24.58 | 4.08 | 5.65 | 91.98 | 92.19/111 | 2.580 |
| 240–270 | 28.91 | 4.72 | 5.83 | 95.51 | 96.09/106 | 0.022 | 28.60 | 4.51 | 6.66 | 94.21 | 94.51/112 | 3.039 |
| 270–300 | 28.94 | 4.56 | 5.81 | 91.67 | 92.60/106 | 0.022 | 28.53 | 4.74 | 6.47 | 92.53 | 93.00/106 | 2.887 |

- OFF 0–30→180–210초: parsing 51.62→94.18ms, Bitmap 15.75→29.44ms, 전처리 1.16→4.73ms. ON도 각각 52.29→95.67ms, 15.16→28.83ms, 1.13→4.98ms다. 추론 외 경로도 함께 느려져 Interpreter 하나에만 누적 문제가 생겼다고 볼 근거가 부족하다.
- Bitmap 세부 평균은 OFF CameraX 변환 3.538→5.529ms / 회전 12.668→24.328ms, ON 2.959→5.614ms / 회전 12.703→23.709ms다. 회전 비용이 Bitmap 시간의 큰 부분이다. aggregate/세부 각각의 정수 내림·소수 해상도가 달라 정확한 덧셈 일치는 기대하지 않는다.
- 같은 구간 input RGB write는 OFF 1.266→2.564ms, ON 1.250→2.561ms다. 전처리도 증가하지만 약 265–269ms의 추론 증가를 설명하지 못한다.
- inputDiagnostics/outputDiagnostics는 전체 FRAME에서 0.000ms이고 관련 debug 설정도 OFF다. 이번 parsing 50–95ms를 PNG 저장·전체 tensor hash 진단 비용으로 설명할 근거가 없다.
- FRAME `detect`는 OFF 1–5개, ON 1–4개로 NMS 이후 결과다. parsing과 `detect`의 전체 샘플 Pearson r는 0.0731 / 0.0187로 약하다. **이는 confidence 통과 후보 수와 parsing 시간의 상관을 측정한 값이 아니다.** 그 후보 수는 원본에 없어 N/A다. 고정 raw 후보 5,376개 전부를 순회한다는 소스 근거와 NMS가 작은 시간이라는 근거는 확인되지만, copy/scan 중 어디가 95ms를 차지하는지는 분리해야 한다.

### 3.3 시스템·열·배터리·메모리

CPU %는 모든 앱 스레드 합계이며 100%=한 코어다. 두 코어 총용량 대비 백분율이나 YOLO 단독 사용률이 아니다.

| 초 | OFF CPU % | OFF Headroom 평균 (범위) | OFF 배터리 °C 평균 (범위) | ON CPU % | ON Headroom 평균 (범위) | ON 배터리 °C 평균 (범위) |
| --- | --- | --- | --- | --- | --- | --- |
| 0–30 | 118.46 | 0.651 (0.610–0.687) | 31.45 (30.9–32.0) | 122.11 | 0.619 (0.567–0.663) | 30.52 (29.1–30.8) |
| 30–60 | 118.38 | 0.731 (0.710–0.753) | 33.10 (32.0–34.2) | 121.34 | 0.719 (0.690–0.743) | 33.13 (30.8–33.6) |
| 60–90 | 119.16 | 0.780 (0.770–0.790) | 34.95 (34.2–35.7) | 122.22 | 0.772 (0.763–0.777) | 35.18 (33.6–35.5) |
| 90–120 | 117.92 | 0.781 (0.780–0.783) | 35.70 (35.7–35.7) | 122.51 | 0.780 (0.777–0.783) | 35.50 (35.5–35.5) |
| 120–150 | 118.32 | 0.779 (0.777–0.783) | 35.95 (35.7–36.2) | 123.40 | 0.782 (0.780–0.783) | 36.33 (35.5–36.5) |
| 150–180 | 115.73 | 0.776 (0.773–0.777) | 36.25 (36.2–36.3) | 122.37 | 0.778 (0.777–0.780) | 36.50 (36.5–36.5) |
| 180–210 | 119.53 | 0.776 (0.773–0.777) | 36.30 (36.3–36.3) | 122.64 | 0.779 (0.777–0.780) | 36.50 (36.5–36.5) |
| 210–240 | 120.26 | 0.773 (0.773–0.773) | 36.30 (36.3–36.3) | 119.01 | 0.791 (0.780–0.800) | 36.50 (36.5–36.5) |
| 240–270 | 119.80 | 0.773 (0.773–0.773) | 36.30 (36.3–36.3) | 122.21 | 0.800 (0.800–0.800) | 37.17 (36.5–37.3) |
| 270–300 | 119.51 | 0.776 (0.773–0.777) | 36.45 (36.3–36.6) | 122.67 | 0.803 (0.800–0.807) | 37.30 (37.3–37.3) |

모든 SYSTEM 120개와 두 세션의 초기 THERMAL_STATUS 관측은 NONE이며 관측된 상태 전환은 없다. 메모리(PSS/RSS/Java/native heap), 할당률, GC count/time/pause는 **모든 구간 N/A(미측정)**다. 감소하는 FPS를 메모리 증가나 GC 증가로 바꿔 해석하지 않는다.

Headroom은 OFF 0.610→0.777(샘플 최고 0.790), ON 0.567→0.807이다. 이 API의 1.0은 SEVERE 기준이며 값이 커질수록 열 envelope 사용이 큰 쪽이다. “남은 여유 77%”나 CPU 온도 77°C가 아니다. 현재 공식 설명도 느리게 변하는 skin 등의 센서를 대상으로 한다. NONE 보고와 배터리 온도 상승만으로 SoC 열 제한을 확정하거나 모든 주파수 제한을 배제할 수 없다. [PowerManager 공식 API](https://developer.android.com/reference/android/os/PowerManager#getThermalHeadroom(int)).

최종 배터리 endpoint는 OFF 300.026초/36.6°C, ON 300.025초/37.3°C다. 첫 값은 각각 30.9°C / 29.1°C이며 잔량은 84→80% / 80→76%다. 시작 온도·잔량부터 달라 엄밀한 동일 초기 열 상태의 A/B는 아니다. 배터리 온도는 CPU/GPU 온도와 구분한다.

두 BATTERY_SUMMARY 모두 `SUSPECT_CURRENT_COUNTER_MISMATCH`, `UNVERIFIED_FUEL_GAUGE_ESTIMATE`다. 동일 방전 구간 counter 감소는 각각 143.560mAh이지만 API 전류 적분은 0.112/0.114mAh, 비율은 0.000778/0.000796이다. 기존 경고를 유지하며 `batteryCurrentMa`/신뢰 가능한 전력은 N/A다. `currentValidSamples=61` 등은 API 산술값의 가용성이지 검증된 전류 샘플 61개를 뜻하지 않는다. API 전류에 임의 ×1000 보정을 하지 않았다. counter 기반 값도 장치 전체의 미검증 추정이며 앱 전력·ML Kit 전력 차이로 사용하지 않았다.

### 3.4 CPU policy별 current/scalingMax와 상관

표는 5초 스냅샷 6개의 구간 산술 평균이며 **GHz**로 환산했다. `current=scaling_cur_freq`, `max=scaling_max_freq`다. cpuinfoMax는 두 세션 내내 policy0/4/7/9 각각 1.959/2.592/2.900/3.207GHz, governor는 `energy_aware`, relatedCpus는 `0 1 2 3` / `4 5 6` / `7 8` / `9`다. policy 이름으로 앱 실행 코어를 지정하지 않았다.

ML Kit OFF:

| 초 | policy0 current/max GHz | policy4 current/max GHz | policy7 current/max GHz | policy9 current/max GHz |
| --- | --- | --- | --- | --- |
| 0–30 | 0.944 / 1.914 | 1.008 / 2.496 | 0.896 / 2.861 | 3.014 / 2.870 |
| 30–60 | 0.736 / 1.869 | 0.672 / 2.400 | 0.848 / 2.823 | 2.816 / 2.880 |
| 60–90 | 0.640 / 1.728 | 0.672 / 2.240 | 0.672 / 2.368 | 2.368 / 2.368 |
| 90–120 | 0.832 / 1.664 | 0.896 / 1.984 | 0.896 / 1.984 | 2.016 / 2.016 |
| 120–150 | 0.720 / 1.632 | 0.848 / 1.760 | 0.672 / 1.760 | 1.632 / 1.824 |
| 150–180 | 0.768 / 1.600 | 1.008 / 1.696 | 0.672 / 1.696 | 1.360 / 1.696 |
| 180–210 | 0.944 / 1.600 | 1.472 / 1.632 | 0.672 / 1.632 | 1.008 / 1.696 |
| 210–240 | 0.752 / 1.568 | 1.312 / 1.632 | 0.672 / 1.632 | 1.168 / 1.664 |
| 240–270 | 0.752 / 1.568 | 1.312 / 1.632 | 0.672 / 1.632 | 0.992 / 1.664 |
| 270–300 | 0.768 / 1.632 | 1.472 / 1.632 | 0.832 / 1.632 | 1.024 / 1.728 |

ML Kit ON:

| 초 | policy0 current/max GHz | policy4 current/max GHz | policy7 current/max GHz | policy9 current/max GHz |
| --- | --- | --- | --- | --- |
| 0–30 | 0.768 / 1.959 | 0.896 / 2.592 | 0.976 / 2.900 | 3.053 / 2.704 |
| 30–60 | 0.816 / 1.869 | 0.832 / 2.400 | 0.672 / 2.823 | 2.944 / 2.752 |
| 60–90 | 0.627 / 1.728 | 0.672 / 2.272 | 1.248 / 2.368 | 1.856 / 2.368 |
| 90–120 | 0.768 / 1.664 | 0.800 / 2.016 | 0.800 / 2.016 | 1.792 / 2.048 |
| 120–150 | 0.704 / 1.632 | 0.832 / 1.664 | 0.672 / 1.664 | 1.584 / 1.760 |
| 150–180 | 0.848 / 1.600 | 1.152 / 1.600 | 0.928 / 1.600 | 1.344 / 1.696 |
| 180–210 | 0.832 / 1.568 | 0.992 / 1.632 | 0.736 / 1.632 | 1.504 / 1.664 |
| 210–240 | 1.184 / 1.568 | 1.440 / 1.600 | 1.136 / 1.600 | 1.344 / 1.664 |
| 240–270 | 0.704 / 1.600 | 1.280 / 1.600 | 0.672 / 1.600 | 1.024 / 1.696 |
| 270–300 | 0.752 / 1.632 | 0.992 / 1.632 | 0.672 / 1.632 | 1.376 / 1.728 |

`scaling_max_freq`는 정책에 허용된 상한이다. `scaling_cur_freq`는 드라이버 요청/보고값일 수 있어 하드웨어 실효 클럭과 같다고 보장되지 않는다. 소스도 각 sysfs 파일을 순차 조회하므로 한 행이 원자적 스냅샷은 아니다. 실제로 policy9에서 current>max인 샘플이 OFF 4/60, ON 6/60개다. 수치를 임의 보정하거나 상한 위반·장치 이상으로 단정하지 않았다. [Linux CPUFreq 공식 설명](https://www.kernel.org/doc/html/latest/admin-guide/pm/cpufreq.html#policy-interface-in-sysfs).

예를 들어 OFF 80.415초에는 policy4/7/9 상한이 모두 2.112GHz, 120.582초에는 1.728/1.728/1.824GHz다. ON 80.362초에는 2.208/2.208/2.208GHz, 120.522초에는 1.728/1.728/1.824GHz다. 180–210초에 policy4 상한 평균은 양쪽 1.632GHz다. 점진적으로 낮아진 뒤 흔들리므로 단일 시각의 단일 단계 제한으로 표현하지 않는다.

정확히 10개의 완전한 30초 구간을 같은 가중치로 대응시킨 **추론 평균 vs 정책 주파수 평균 Pearson r**:

| 정책 | OFF current r | OFF max r | ON current r | ON max r |
| --- | --- | --- | --- | --- |
| policy0 | 0.0423 | -0.9144 | 0.1370 | -0.8874 |
| policy4 | 0.8054 | -0.9806 | 0.5470 | -0.9670 |
| policy7 | -0.5942 | -0.9508 | -0.3363 | -0.9546 |
| policy9 | -0.9845 | -0.9460 | -0.8928 | -0.9565 |

policy4 상한과 강한 음의 상관이 있고 policy9 current도 음의 상관이다. 반면 policy4 current는 양의 상관이다. 바쁜 순간의 core 이동·부하 분배·드라이버 보고/샘플 시점 등 여러 설명이 가능하다. 이 상반된 current 패턴도 장치 policy 평균을 추론 스레드 클럭으로 대입하면 안 되는 이유다. 표본 10개, 시간 추세·온도·다른 부하가 공통 교란이므로 유의확률이나 인과 효과량으로 확장하지 않았다.

### 3.5 LATENCY와 CPU 경쟁·analyzer 완료

아래는 **이벤트 관측 시각** 기준 구간이다. FRAME 완료 기준 표와 집계 기준이 달라 경계를 넘는 프레임/Task가 있을 수 있다. callback end는 ImageProxy close 직후, ANALYZER telemetry enqueue 전의 관측값이다.

| 이벤트 시각 구간(초) | OFF received/end 수 | OFF 완료→callback end ms | OFF MLKIT 수 | OFF ML Task avg/P95 ms | ON received/end 수 | ON 완료→callback end ms | ON MLKIT 수 | ON ML Task avg/P95 ms |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 0–30 | 96/95 | 1.272 | 0 | N/A/N/A | 95/94 | 1.260 | 18 | 47.53/56.79 |
| 30–60 | 92/92 | 1.071 | 0 | N/A/N/A | 93/93 | 1.319 | 19 | 54.84/66.99 |
| 60–90 | 82/82 | 1.248 | 0 | N/A/N/A | 72/72 | 1.273 | 17 | 52.48/68.91 |
| 90–120 | 66/66 | 1.393 | 0 | N/A/N/A | 69/69 | 1.534 | 18 | 54.41/66.25 |
| 120–150 | 56/56 | 2.194 | 0 | N/A/N/A | 48/48 | 2.299 | 16 | 62.28/78.62 |
| 150–180 | 49/49 | 2.055 | 0 | N/A/N/A | 49/49 | 2.368 | 15 | 59.68/76.08 |
| 180–210 | 45/45 | 2.394 | 0 | N/A/N/A | 46/46 | 2.320 | 16 | 60.27/87.40 |
| 210–240 | 47/47 | 2.210 | 0 | N/A/N/A | 52/52 | 2.174 | 16 | 57.96/90.63 |
| 240–270 | 47/47 | 2.428 | 0 | N/A/N/A | 47/47 | 2.418 | 16 | 57.81/74.14 |
| 270–300 | 48/48 | 2.417 | 0 | N/A/N/A | 47/47 | 2.384 | 16 | 58.27/69.44 |

전체 LATENCY:

| 항목 | OFF | ON |
| --- | ---: | ---: |
| CAMERA_RECEIVED / ANALYZER / WARNING / MLKIT / OUTPUT 수 | 628 / 627 / 627 / 0 / 13 | 618 / 617 / 617 / 167 / 29 |
| analyzer callback avg / P95 ms (관측 가능한 것만) | 462.218 / 809.843 | 470.123 / 811.534 |
| pipeline 완료→callback end avg / P95 ms | 1.721 / 2.853 | 1.800 / 2.977 |
| ImageProxy.close avg / P95 ms | 0.178 / 0.300 | 0.177 / 0.301 |
| warning submit API avg / P95 ms | 0.119 / 0.203 | 0.118 / 0.247 |
| ML Kit dispatch→request avg / P95 ms | N/A | 1.855 / 3.136 |
| ML Kit request→completion 관측 avg / P95 ms | N/A | 56.347 / 74.136 |

ANALYZER는 기록된 것 모두 DELIVERED, MLKIT 167개는 모두 SUCCESS다. ML Kit Task 관측 구간과 YOLO 추론 구간이 시간상 겹친 FRAME은 166개다. 이들의 추론 평균은 368.639ms, 관측 구간과 겹치지 않은 452개는 350.011ms다. 이는 CPU가 동시에 running이었다는 증거가 아니며 짧은 Task가 대기한 시간도 포함한다. Task completion은 관측 시각이므로 실제 SDK 작업 종료와도 구분한다.

dispatch 직후 `mlKitInFlight=true`인 167개 평균은 367.677ms, false 451개는 350.326ms다. 단순 전체 차이는 약 17.35ms이지만 시간대별 분포가 다르다. **180–210초에서는 true 16개=450.938ms, false 30개=507.000ms로 방향이 반대**다. hint 또는 overlap별 단순 평균 차이를 ML Kit CPU 경쟁의 인과적 비용으로 해석하지 않는다.

전체 후처리 이후 경고 출력은 각각 13/29개 OUTPUT 관측이 있으므로 세션의 부가 작업이 완전히 동일하지도 않다. TTS/beep의 실제 발생을 설정 ON만으로 추정하지 않고, 물리적 음향/진동 시작은 N/A다. 완료 이후 callback 관측까지 약 1–2ms로, 수백 ms 지연이 ImageProxy.close 또는 warning submit에서 생겼다는 근거는 없다. 다만 callback end 관측 뒤 telemetry enqueue와 다음 analyzer delivery까지의 대기는 이 값에 포함되지 않는다.

## 4. 실제 소스 처리 경로와 수명·동기화

분석 파일의 줄 번호는 위 HEAD 기준이다. `DetectionPipeline`은 Interpreter 뒤에만 있는 단계가 아니라 ROI·detector·후속 정책을 감싸는 호출이다.

```text
CameraX ImageAnalysis (RGBA, KEEP_ONLY_LATEST, single analyzer executor)
  -> ImageProxyExt.toBitmapSafe: CameraX.toBitmap -> 90도 회전
  -> MainActivity.processBitmap
       -> maybeRunMlKitDetection: eligibility + private ROI/640x360 준비
            -> 비동기 ML Kit process/await (YOLO와 시간상 겹칠 수 있음)
       -> DetectionPipeline.process
            -> ROI Bitmap crop
            -> VisionStyleYoloDetector.detect [synchronized]
                 -> cached LETTERBOX transform / reusable 512 Bitmap
                 -> getPixels / RGB NCHW write
                 -> 재사용 Interpreter.runForMultipleInputsOutputs
                 -> output copy / 5376개 scan / candidate sort / NMS
            -> 원본 좌표 매핑 / geometry·size·temporal / motion / warning policy
            -> ROI recycle (finally)
       -> pipeline completion 시각 고정
       -> 경고 submit / metrics / FRAME enqueue / UI 제출 조건 처리
  -> full Bitmap recycle -> ImageProxy.close -> ANALYZER telemetry enqueue
  -> OrderedLogWriter에서 format / write / 매 행 flush
```

### 4.1 Interpreter·tensor·Bitmap 재사용

| 소스 근거 | 확인한 동작 | 판단 |
| --- | --- | --- |
| [MainActivity.kt](../app/src/main/java/com/samin/objectdetection/MainActivity.kt) L304–317, L612–625 | Activity 초기화/설정 재시작 때 detector 생성, analyzer와 ML Kit drain 후 close | 프레임마다 Interpreter를 생성하는 경로 없음 |
| [VisionStyleYoloDetector.kt](../app/src/main/java/com/samin/objectdetection/detector/VisionStyleYoloDetector.kt) L92–156 | Interpreter 1개, direct input/output, 호출 Array/Map, FloatBuffer view, 입력 FloatArray, pixels IntArray, `Array<FloatArray>` output, 512 Bitmap/Canvas를 init에서 생성 | 주요 tensor/호출 컨테이너/모델 입력 Bitmap은 재사용 |
| 같은 파일 L182–353, L711–722 | detect/self-test/close synchronized, transform은 크기/모드 같으면 재사용, close 중복 방지 | 고정 shape의 per-frame resizeInput/allocateTensors/Interpreter 재초기화 없음 |
| [YoloTensorContract.kt](../app/src/main/java/com/samin/objectdetection/detector/YoloTensorContract.kt) L75–96, L127–142 | FLOAT32 bulk write와 channels-first bulk copy를 기존 배열로 수행 | 전체 입력 FloatArray/output rows를 프레임마다 새로 만들지 않음 |

로그 설정 2는 `setNumThreads(2)`로 전달되고 `setUseXNNPACK(true)` 호출 후 옵션 getter 값을 execution에 저장한다. 로그 전 구간 CPU/2/true, lastInference SUCCESS, inferenceErrors=0, fallbackCount=0이다. 의존성은 LiteRT artifact 2.1.1, 로그 runtimeVersion은 2.21.0이다. artifact와 런타임 문자열을 같은 버전 번호로 억지로 맞추지 않는다. `InferenceExecutionInfo`의 `appliedBackend=CPU`는 앱이 선택한 경로 기록이며 native partition 검증 결과가 아니다. **실제 worker 2개가 계속 running, 모든 op가 XNNPACK에 위임, GPU/CPU fallback을 native 수준에서 완전히 검증했다는 뜻은 아니다.** 기존 선행 문서의 다른 모델/세션 결과로 이번 세션을 대신 증명하지 않았다.

### 4.2 반복 할당과 자원 해제

- [ImageProxyExt.kt](../app/src/main/java/com/samin/objectdetection/camera/ImageProxyExt.kt) L27–39/L54–71: `toBitmap()` 결과와 회전 `Bitmap.createBitmap`이 프레임 경로에 있다. 회전 원본은 정상 경로에서 recycle한다. [CameraController.kt](../app/src/main/java/com/samin/objectdetection/camera/CameraController.kt) L157–174는 listener 반환 후 full Bitmap을 finally에서 recycle하고 ImageProxy도 finally에서 close한다.
- [DetectionPipeline.kt](../app/src/main/java/com/samin/objectdetection/pipeline/DetectionPipeline.kt) L58–67/L200–205: ROI 864×1152 Bitmap은 매 프레임 `Bitmap.createBitmap`로 만들고 finally에서 recycle한다. ARGB_8888 pixel payload만 약 3.80MiB이며 allocator/플랫폼의 실제 할당량은 미측정이다. 정상 수명은 제한되지만 반복 copy/할당 부담은 존재한다.
- [MlKitInputBuffer.kt](../app/src/main/java/com/samin/objectdetection/mlkit/MlKitInputBuffer.kt) L25–83: private 640×360 target/Canvas/Matrix/Rect/Paint는 재사용한다. 그러나 ML Kit를 dispatch하는 프레임에는 별도 ROI 임시 Bitmap을 만들고 recycle한다. 소스 주석대로 ROI 경계 필터 샘플링을 보존하기 위한 real crop이다. 단순 source-subrect draw로 바꾸면 결과가 달라질 수 있다.
- detector는 결과 `DetectionResult`, mutableList, `sortedByDescending/take`, NMS mutableList 등을 프레임마다 만든다. pipeline에서도 map/filter/좌표 변환 결과 객체가 생성된다. 작은 객체의 반복 할당 가능성과 누수는 구별해야 한다.
- metrics의 결과 records는 최대 500개, 최근 성능 표본은 100개, session 성능 표본은 100,000개 상한이고 세션 시작 때 clear한다([DetectionMetricsCollector.kt](../app/src/main/java/com/samin/objectdetection/metrics/DetectionMetricsCollector.kt) L196–252/L433–437). 이 두 세션에서는 628/618개 수준으로 자란다. [PerformanceIntervalCollector.kt](../app/src/main/java/com/samin/objectdetection/metrics/PerformanceIntervalCollector.kt)는 interval별 histogram을 보존하므로 기록 시간/값 종류에 따른 정상 증가도 있다. 메모리 증가를 전부 누수로 부를 수 없다.
- motion track은 stale timeout으로 제거하고 track별 기록 수를 제한한다([ObjectMotionTracker.kt](../app/src/main/java/com/samin/objectdetection/motion/ObjectMotionTracker.kt) L94–103/L247–249). writer queue도 512개로 제한한다. 확인한 경로에서 프레임 Bitmap을 무제한 보존하는 구조는 찾지 못했다. **메모리·GC 로그 부재로 native/SDK 누수까지 없다고 입증한 것은 아니다.**

### 4.3 락·스레드·CameraX 백프레셔

- [CameraController.kt](../app/src/main/java/com/samin/objectdetection/camera/CameraController.kt) L70/L105–109: 단일 analyzer executor가 동기 `listener.onFrame` 전체를 수행한다. `STRATEGY_KEEP_ONLY_LATEST`이므로 느려질 때 모든 카메라 프레임을 쌓아 오래된 입력을 순차 처리하는 구조가 아니다. 전달되지 않은 중간 카메라 프레임은 `droppedFrames`에 집계되지 않는다. detectInterval=0이므로 이번 명시적 interval skip 0은 자연스럽다.
- detector `@Synchronized` 락 획득 대기는 함수 진입 후 detectorStart 타이머 밖이며, Interpreter 호출 타이머는 전처리 뒤에 있다. 라이브 경로는 analyzer 하나이므로 detect끼리의 병렬 락 경쟁은 보이지 않는다. self-test/close는 같은 락을 쓰지만 장시간 로그에는 재시작·오류 근거가 없다. native 내부 락/worker barrier 대기는 소스 Java 코드만으로 배제할 수 없다.
- [MainActivity.kt](../app/src/main/java/com/samin/objectdetection/MainActivity.kt) L735–744/L1518–1627: ML Kit 입력 준비는 YOLO 전에 analyzer에서 실행한다. SDK 호출은 `Dispatchers.IO` coroutine으로 보내고 AtomicBoolean으로 한 번에 하나만 허용한다. 별도 executor/coroutine이 CPU 코어를 격리하지는 않는다.
- [MlKitObjectDetector.kt](../app/src/main/java/com/samin/objectdetection/mlkit/MlKitObjectDetector.kt) L39–67: 비동기 `detector.process`를 await하지만 이 await는 YOLO analyzer의 대기가 아니다. NonCancellable로 SDK Task terminal까지 입력 lease를 유지하고 job completion에서 반환하므로 조기 recycle/reuse 경쟁을 방지한다. private buffer acquire/release의 synchronized 구간은 있다. 실제 락 대기 시간은 미측정이다.
- [AnalyzerShutdown.kt](../app/src/main/java/com/samin/objectdetection/camera/AnalyzerShutdown.kt)와 MainActivity drain은 종료/설정 재시작 때 작동한다. 매 프레임 join/awaitTermination 하는 경로는 없다.

### 4.4 parsing·NMS와 개선 가능한 실제 경로

[VisionStyleYoloDetector.kt](../app/src/main/java/com/samin/objectdetection/detector/VisionStyleYoloDetector.kt) L369–505에서 13×5376 output을 reusable rows에 복사하고 5,376개 후보의 좌표 4개와 클래스 9개를 순회한다. confidence 통과 후 좌표 복원·필터·객체 할당을 수행한다. L528에서 **전체 통과 후보를 정렬한 뒤** 최대 100개를 NMS에 전달한다. L582의 NMS는 class별 IoU suppression이며 quadratic 작업은 최대 100개로 제한된다.

따라서 다음은 구분해야 한다.

1. parsing 50–95ms는 실제 관측 hotspot이다. copy와 scan의 분리가 먼저다. “매번 output 배열을 새로 만들어서 느리다”는 소스와 맞지 않는다.
2. confidence 통과 후보가 증가하면 객체 생성/정렬 비용이 늘 수 있으나 raw scan 수는 고정이다. 지금 로그로 그 통과 수의 증가를 입증할 수 없다. FRAME `detect`가 2개라고 raw scan도 2개인 것은 아니다.
3. NMS 시간 0ms 기록은 1ms 미만일 수 있다는 뜻이다. NMS 후보 cap 변경이나 threshold 변경을 지금 최우선 최적화로 선택할 근거는 없다.
4. rawCoordinate min/max를 debug OFF에서도 매 raw 후보마다 누적하고, 실행 후 첫 번 출력용 진단값을 만든다. 실제 hotspot이 이 부위인지 세부 trace로 확인한 뒤 진단 계산의 실행 주기를 줄일 수 있다. bbox/score/NMS·정책 결과와 진단 필드 의미를 보존해야 한다.

## 5. 원인 후보별 근거와 신뢰도

신뢰도는 해당 **관측/판단**의 강도이며 원인 확정 확률이 아니다.

| 후보 | 지지 근거 | 반증·확인 한계 | 현재 판단 / 신뢰도 |
| --- | --- | --- | --- |
| CPU 상한 제한 / thermal·power management | 양쪽 policy 상한 하락, policy4 max r≈-0.98/-0.97, native 및 Java 후처리·Bitmap 동반 증가 | 실행 CPU 미기록, 공개 Thermal Status NONE, 정책 주체/센서 없음 | 상한 하락 사실 **높음**. 주요 기여 가설 **중간**. thermal/power 중 구체 원인 **미확정** |
| Interpreter 내부 추론 성능 저하 | nativeInvoke도 OFF 222.704→491.588ms, ON 223.008→487.177ms | native elapsed는 running time이 아니며 scheduling·주파수·GC 대기를 분리하지 못함. 동일 interpreter/고정 tensor 재사용 | 느려진 위치가 native 구간까지 포함됨 **높음**. Interpreter 내부 고유 누적 결함 **근거 부족** |
| CPU scheduling / 스레드 경쟁 | 약 119–122% process CPU 유지 중 완료 수 감소, late 추론이 약 350/650ms 대역으로 섞임, policy4/9 current 변화 방향 다름; ML Kit 시간상 overlap 관측 | CPU 사용률은 전체 프로세스/약5초 평균. worker/tid/runqueue 데이터 없음. OFF에서도 재현 | 기여 가능성 **중간**, 실제 scheduler 원인 **미확정**. ML Kit 주원인 가설은 약함 |
| Bitmap·전/후처리 증가 | parsing 약 52→95ms, bitmap 약15→29ms; per-frame camera/rotation/ROI 할당 | 전체 처리 증가의 대부분은 추론 구간. 후보 증가/scan/copy/할당 분리 불가 | 부가 지연의 실재 **높음**, 독립 주원인이라는 판단 **낮음** |
| 반복 할당·GC·자원 누수 | 프레임 Bitmap·ROI·결과 객체, session histogram/표본 증가 경로 존재 | recycle/close/queue 제한 있음. heap·GC는 전부 N/A. ON 일부 회복 및 OFF 동일 저하 | 반복 할당 구조 **높음**, GC 기여·누수 원인 **미확정** |
| 측정 오차 / 계측 부하 | 매 행 flush, writer/diagnostics CPU 공유, ms 내림·경계 overlap 존재 | FRAME↔최종 summary 완전 일치, native/monotonic 타이밍도 증가, dropped=0, 무거운 tensor 진단 OFF | 단순 집계 오류가 2배 저하를 만든다는 근거 **낮음**. 계측의 실제 기여 **미확정** |

180초 이후 추론은 두 파일 모두 단조롭게 증가하지 않고 약 350ms와 650ms 부근 대역이 섞인다. ON 210–240초에는 추론 평균이 420.731ms로 회복되며 정책 상한은 계속 낮다. core 종류/배치·running/runnable 비율, 다른 부하 또는 캐시/메모리 효과를 확인해야 한다. 이 분포만 보고 big/little 이동이나 GC를 확정하지 않았다.

### 로그 작성의 비용과 범위

[PerformanceLogSession.kt](../app/src/main/java/com/samin/objectdetection/metrics/PerformanceLogSession.kt)의 synchronized 구간은 window 검사, histogram 갱신, record copy/queue 제출을 수행한다. 최종 통계는 writer 작업으로 format한다. [OrderedLogWriter.kt](../app/src/main/java/com/samin/objectdetection/logging/OrderedLogWriter.kt) L27–40는 별도 single executor와 semaphore `tryAcquire`를 사용하므로 포화 시 analyzer가 디스크 완료를 기다리기보다 로그를 버린다. 다만 매 행 `flush()`한다. 문자 formatting/파일 I/O가 다른 스레드라도 CPU·메모리·스토리지는 공유한다. dropped=0은 계측 부하 0의 증거가 아니다.

SYSTEM 수집 elapsed 합은 OFF 872.677ms / ON 904.998ms, 배터리 수집 합은 473.490ms / 462.642ms다. 합산 조회 wall span은 약 1.346초 / 1.368초로 각 5분의 약 0.449% / 0.456%다. 별도 diagnostics worker에서 5초마다 조회하며 **이 비율을 CPU 사용률·FPS 손실률로 해석하지 않는다.** writer/logcat·락·추가 스케줄링 비용은 포함되지 않는다. 소스 상 매100프레임 summary의 최근100개 정렬도 있지만, 선형으로 커지는 매프레임 전체 이력 정렬은 아니다.

FRAME의 `inferenceEndElapsedNs - inferenceStartElapsedNs`를 직접 계산한 전체 평균은 OFF 350.635ms / ON 355.521ms다. 정수 기록 평균 350.139/355.015ms와의 약 0.5ms 차이는 ms 내림과 일치한다. 상세 `nativeInvoke`는 Java 호출보다 좁은 런타임 계측 범위다. 정수 inference와 소수 native 값을 단순 빼서 정확한 Java 비용으로 해석하지 않았다. 수백 ms의 장기 증가를 정수 반올림이나 wall clock 변경만으로 설명할 근거는 없다.

## 6. 필요한 추가 검증과 최소 계측 계획

### 6.1 이번에 확인한 기기 접근 범위

연결된 SM-S921N(API 36)에서 `adb devices -l`, Perfetto v51.2, 등록 데이터 소스 `linux.ftrace`, `linux.process_stats`, `linux.sys_stats`를 조회했다. 설치 앱은 debuggable이며 `dumpsys meminfo`/`/proc/<pid>/status` 단일 스냅샷 조회도 가능했다. 최초 sandbox 내 ADB server 연결은 실패했고 읽기 전용 조회를 허용된 실행으로 다시 수행하여 연결을 확인했다.

이 현재 조회는 과거 OFF/ON 측정창의 데이터가 아니다. 원본 두 세션의 sched/frequency/GC trace는 **N/A(수집되지 않음)**, 과거 메모리 추이는 **N/A**로 유지한다. 현재 실행 모델·설정·열 이력이 동일하다고 확인되지 않은 무관한 live trace를 두 원본의 원인 증거로 추가하지 않았다. 앱 재설치/모델 교체/새 측정 시작/설정 변경은 하지 않았다. 다음의 통제된 재현에서 Perfetto를 사용할 수 있는 환경은 확인했지만 개별 ftrace event의 실제 제공 여부는 trace에서 다시 확인해야 한다.

### 6.2 우선순위 1: 기존 trace + Perfetto

기존 `CameraX.toBitmap`, `CameraX.rotate`, `YOLO.resize`, `YOLO.getPixels`, `YOLO.rgbWrite`, `YOLO.Interpreter.run`, `MLKit.InputImage` trace를 먼저 사용한다. 같은 설정/모델로 새 기준 세션을 만들고 **초기 0–30초와 저하 구간 180–210초에 각각 20–30초만** 기록한다. baseline 대비 trace가 FPS/P95에 미치는 영향도 함께 측정한다. 시스템 전체 sched는 고빈도이므로 계속 5분 수집하기보다 짧은 창을 사용한다.

다음은 20초 재현용 최소 구성 예시다. 설치 앱의 패키지를 지정하고 지원하지 않는 source/event는 N/A로 남긴다. 500ms frequency polling은 20초 trace 동안만 사용한다. 기존 앱 SYSTEM 5초 주기는 유지한다.

```protobuf
buffers { size_kb: 32768 fill_policy: RING_BUFFER }
duration_ms: 20000
data_sources {
  config {
    name: "linux.ftrace"
    ftrace_config {
      ftrace_events: "sched/sched_switch"
      ftrace_events: "sched/sched_waking"
      ftrace_events: "power/cpu_frequency"
      ftrace_events: "power/cpu_idle"
      atrace_categories: "dalvik"
      atrace_apps: "com.samin.objectdetection"
    }
  }
}
data_sources {
  config {
    name: "linux.process_stats"
    process_stats_config { proc_stats_poll_ms: 5000 }
  }
}
data_sources {
  config {
    name: "linux.sys_stats"
    sys_stats_config { cpufreq_period_ms: 500 }
  }
}
```

예시 실행(재현 실험 시에만):

```powershell
adb push yolo-longrun.pbtxt /data/local/tmp/yolo-longrun.pbtxt
adb shell perfetto --txt -c /data/local/tmp/yolo-longrun.pbtxt -o /data/local/tmp/yolo-longrun.perfetto-trace
adb pull /data/local/tmp/yolo-longrun.perfetto-trace .
```

`sched_switch/sched_waking`으로 analyzer와 확인 가능한 native worker의 running/runnable/blocked 시간을 구분하고, **실제 running CPU 시간 구간**에 frequency를 겹친다. frequency event는 변경 때만 발생하므로 초기값 polling을 함께 사용한다. CPU residency·migration·동시 worker 수와 native call wall duration의 변화를 비교한다. ML Kit worker를 이름/패키지만으로 확정할 수 없으면 attribution을 N/A로 남기고 동시 작업 관측으로만 표현한다. trace buffer loss/data loss도 확인한다. [Perfetto scheduling](https://perfetto.dev/docs/data-sources/cpu-scheduling), [frequency 수집](https://perfetto.dev/docs/data-sources/cpu-freq), [메모리 counters](https://perfetto.dev/docs/data-sources/memory-counters).

예상 판별 기준은 다음과 같다.

- running CPU의 주파수/코어 성능이 내려가고 같은 작업의 running duration이 늘면 정책/배치 영향의 근거가 강해진다. 제한 주체는 별도 상태가 없으면 계속 미확정이다.
- running 시간은 비슷한데 runnable 대기가 늘면 스케줄링 경쟁 쪽 근거가 강해진다. sleeping/blocked 증가는 native barrier·락·I/O 등의 추가 확인 대상이다.
- 비슷한 CPU residency/frequency에서도 반복 inference의 thread CPU 시간이 지속 증가하면 동일 고정 입력 standalone 반복 실험으로 Interpreter/런타임 내부 문제를 분리한다. 입력·모델·delegate·threads는 원본과 같아야 한다.
- ART GC pause와 지연 프레임의 겹침, heap의 GC 후 기준선 상승을 각각 확인한다. GC가 발생했다는 사실만으로 누수라고 하지 않는다.

### 6.3 필요한 경우에만 앱 진단 추가

Perfetto와 기존 SYSTEM으로 남는 공백이 있을 때 다음을 **기존 diagnostics executor에서 5초마다** 수집하는 별도 선택형 이벤트로 추가한다. CameraX thread에서 PSS 조회·heap dump·스레드 열거를 매프레임 수행하지 않는다.

1. Java used/committed/max heap, native allocated heap, 읽기 가능한 RSS/PSS, ART `Debug.getRuntimeStats()`의 GC count/time 및 할당 누적값을 수집한다. 미지원/권한 거부·속성 부재는 N/A, counter reset/감소는 delta N/A다. `Debug.getMemoryInfo` 등 비싼 조회는 첫/30초 간격 수준으로 더 낮추고 collectionDuration을 기록한다.
2. `YOLO.outputCopy`, `YOLO.candidateScan` trace를 기존 타이머 경계에 맞춰 추가한다. 이미 계산하는 confidencePassed/nmsInput/nmsOutput와 두 경과시간은 메모리에 모아 5초 요약한다. 매 raw 후보 로그나 tensor 저장은 활성화하지 않는다.
3. 필요하면 writer queue depth/drops, formatting/write/flush duration을 writer 내부에서 누적하고 5초 요약한다. analyzer에서 writer 완료를 기다리는 계측을 넣지 않는다.

기존 FRAME/LATENCY/SYSTEM/BATTERY_SAMPLE 열이나 의미를 바꾸지 않고 새 이벤트 이름/스키마로 추가하는 방식이 적절하다. 이 보고서 단계에서는 앱 계측을 구현하지 않았다. 구현 시 N/A/권한 실패/주기 제한/stop drain/queue 포화/기존 로그 parser 호환 테스트와 짧은 기기 ON/OFF 회귀 검증이 필요하다. 장시간 heap dump·할당 stack 전수 추적은 첫 검증 수단으로 선택하지 않는다.

## 7. 개선 우선순위: 영향도·작업량 순

아래 시간은 구현/분석의 대략적인 엔지니어 작업량이며 기기 반복 측정 시간은 별도다. 효과는 아직 측정되지 않았다.

| 순위 | 다음 작업 | 기대 영향과 선정 근거 | 작업량 / 적용 조건 |
| --- | --- | --- | --- |
| P0 | 원본 모델 해시를 맞춘 통제 재현 + 짧은 Perfetto | 지연 대부분이 native call에 있어 잘못된 CPU/GC/Interpreter 원인 선택을 막는 영향이 가장 큼 | 반나절~1일; 기존 trace 사용, 동작 변경 없음 |
| P1 | parsing copy/scan trace 및 5초 후보/메모리·GC 요약 추가 | 약95ms parsing hotspot과 누수 가설의 증거 공백을 동시에 메움 | 반나절~1일; Perfetto로 해소되지 않는 항목만, 기존 스키마 보존 |
| P1 | **확인된 scan hotspot의 불필요한 진단 연산·할당 제거** | 전체 raw scan의 좌표 range 등 진단 전용 반복 계산, 결과 list/sort의 실제 비용을 줄일 가능성 | 1~2일; trace로 hotspot 확인 후, score/box/동일 점수 순서/필터/NMS 결과와 진단 의미 회귀 |
| P2 | analyzer 소유 ROI Bitmap/Canvas 재사용 | 매프레임 864×1152 crop copy/할당 구조 개선 여지; 직접 ROI 비용은 약2→6ms라 native 저하의 단독 해결책은 아님 | 1~2일; ROI 픽셀·letterbox 결과 일치, 크기 변경·실패·close 수명 검증 |
| P2 | CameraX 변환·회전 buffer 재사용 경로 검토 | 약29ms Bitmap 중 회전 약24ms로 ROI보다 절감 가능한 관측 시간이 큼 | 2~4일; stride/회전/채널/밀도/ROI 경계 샘플링 회귀 부담이 커 후순위 |
| P3 | opt-in log batching/불필요 logcat 문자열 계산 절감 | 매 행 flush·무조건 문자열 생성 경로의 부하 가능성; 현재 원인 기여 미측정 | 반나절~1일; A/B에서 효과 확인 후, 순서/stop drain/저FPS tail 보존 회귀 |
| 조건부 | ML Kit 실행 겹침 조정 | 경쟁이 실제 확인될 때만 고려. OFF에서도 동일 저하라 기대 우선순위 낮음 | 1~2일; CPU 별도 executor만으로 해결된다고 가정하지 않음, 결과 갱신/경고 지연 검증 |

**다음 실제 코드 수정의 시작점은 원인 검증에 필요한 parsing 세부 trace와 저빈도 후보·메모리/GC 요약이다.** 기존 Perfetto만으로 필요한 증거가 확보되면 이 계측 중 불필요한 항목은 생략하고, 확인된 scan hotspot 개선부터 적용한다. ROI/회전 개선은 그다음이다. 코드가 원인 확인을 앞서가지 않도록 이번에는 분석 결과만 남겼다.

모델, CPU threads, XNNPACK, 카메라 해상도, ROI/LETTERBOX, ML Kit 기본 모드/주기, FPS 제한, 경고 정책 변경은 이번 개선안의 자동 적용 항목이 아니다. CPU 주파수 고정·governor 변경·열 보호 해제는 수행하지 않으며 검증 계획에도 포함하지 않는다. 정책 영향이 확인되어도 작은 Java 최적화가 초기 3.2FPS를 장시간 유지하게 만든다고 약속할 수 없다.

## 8. 최적화 전후 비교 테스트 계획과 수행한 검증

### 전후 비교 계획

1. 원본 모델 SHA-256, APK/source SHA, 입력/출력 tensor, CPU2/XNNPACK 요청 ON, 카메라 요청/실제 크기, ROI/LETTERBOX, Preview OFF, ML Kit OFF 또는 ON/SINGLE_IMAGE_MODE/1500ms를 각각 기록한다. 현재 사용자 변경 모델을 덮어쓰지 않는다.
2. 동일 고정 장면/조명/기기 자세·밝기·주사율·실내 온도·경고 설정·앱 foreground 상태를 유지한다. 무충전 기준이며 USB/무선 ADB 등 연결 방식도 전후 동일하게 기록한다. 화면 녹화는 끈다. 시작 잔량·배터리 온도 범위를 맞추고 충분히 냉각한다. 시작 전 카메라 실행 시간도 맞춰 세션 전 열 이력을 통제한다.
3. 변경 전 A / 변경 후 B를 단일 변경씩 비교한다. OFF, ON 조건별 5분 A/B를 각각 3회 이상, 순서를 교대하고 매회 초기 상태를 맞춘다. 기존 SYSTEM/배터리 진단을 같은 설정으로 켠다. 5분 결과가 유망하면 같은 설정의 15–30분 장기 검증을 추가한다.
4. 30초별 FPS, 추론/native 평균·P95, pipeline/totalProcessing 평균·P95, parsing/Bitmap/전처리, analyzer callback, 후보 수, CPU/policy/Headroom/배터리 온도, 메모리/GC와 unavailable 수를 비교한다. 0–30초뿐 아니라 180–300초 유지 성능을 주 지표로 보고 전체 평균으로 저하 구간을 숨기지 않는다. 계측이 추가된 버전은 그 자체 ON/OFF 영향도 비교한다.
5. 인과 분리용으로 먼저 시스템+배터리 진단을 같은 상태에 둔 채 ML Kit OFF/ON/OFF를 반복한다. writer 부담 검증은 별도 회차에서 상세 성능 기록과 저빈도 완료 카운터만 있는 조건을 비교한다. 기록하지 않은 상세 지표는 N/A다. 서로 다른 계측 수준의 수치를 그대로 최적화 효과로 섞지 않는다.
6. 시각 결과 회귀는 고정 이미지의 입력 tensor/box·class·confidence·NMS 순서·경고 후보와 ROI 경계 이미지를 비교한다. Bitmap 재사용은 해상도/회전 변경, 동시 ML Kit, 취소/Activity 종료, 오류 경로의 recycle/lease 수명을 검증한다. 처리량 증가와 함께 경고/새 결과 지연 P95가 악화되지 않는지 확인한다.
7. 성공 기준은 동일 조건의 반복 변동폭을 넘어 late FPS/처리 P95가 일관되게 개선되고, 추론 오류·로그 누락·검출/경고 회귀·지속적인 GC 후 heap 상승이 없는 것이다. GC/clock처럼 수집되지 않은 값으로 승자를 정하지 않는다. 배터리 전류의 신뢰성 경고가 남는 동안 앱 mW나 에너지 절감률을 산출하지 않는다.

### 이번에 수행한 검증

- 기존 Python 로그 분석기 테스트 **12개**, latency 분석 테스트 **14개**, 총 **26개 통과**.
- 두 원본 1,246개 FRAME의 session/frame ID 중복 없음, 원본 수와 확정 누적 수 일치, dropped log lines=0 확인.
- 독립 재집계한 20개 30초 구간에서 성공 수와 추론/pipeline/totalProcessing 평균·P95를 최종 INTERVAL_SUMMARY와 비교해 전부 일치.
- 모든 policy 문자열을 공백이 들어간 `relatedCpus`까지 보존하여 4개 정책을 복원. N/A를 상한이나 0으로 대체하지 않음.
- `git diff --check`, 문서 공백/코드 fence/표 열 수/로컬 소스 링크 검사 통과. 원본 로그와 기존 사용자 변경 모델 해시를 작업 후 재확인하여 그대로임을 확인.
- 문서의 수치 표는 이 독립 재집계에서 생성했다. 앱 기능/진단을 바꾸지 않았으므로 Android build/instrumentation 회귀는 이번 문서 변경의 검증으로 실행하지 않았다. Perfetto 전후 재현·메모리 추이·최적화 성능은 후속 검증 대상이다.

기존 도구로 전체 분석을 재현하는 명령:

```powershell
python tools/analyze_performance_logs.py "C:\Users\SAMIN\Downloads\perf_20261026_093315.txt" "C:\Users\SAMIN\Downloads\perf_20261026_095053.txt" --interval-seconds 30
python -m unittest discover -s tools -p test_analyze_performance_logs.py
python -m unittest discover -s tools -p test_latency_analysis.py
```

상세 비용의 임의 30초 구간은 `analyze(text, start_seconds=lo, exclude_tail_seconds=duration-hi, interval_seconds=30)`로 `[lo,hi)`를 선택한다. `duration`은 각 최종 CUMULATIVE_SUMMARY endSeconds(300.025/300.024)다. 구간별 SYSTEM 평균·상관에는 최종 INTERVAL_SUMMARY의 last sample이 아니라 원본 SYSTEM을 사용한다. 표 반올림 전 수치로 계산하고 P95끼리 평균하지 않는다.
