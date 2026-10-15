# YOLO 장시간 성능 저하 분석 — 2026-10-15

## 측정과 결론

사용자 제공 `perf_20261015_112344.txt` 원본을 재집계했다. 원본 SHA-256은 `f32e700496619881621bb14c9ac4bc678816fa96c279bd3d129098428d963979`이며 [전체 분석 JSON](validation/yolo-long-run-analysis-20261015.json)에 24개 구간·CPU 정책·상관계수·배터리 검증을 저장했다. 원본 파일은 수정하지 않았다.

**지속 지연의 가장 강한 근거는 CPU 정책 주파수 상한 하락과 native 추론 지연의 동행이다.** 정책 제한을 유발한 열/전력 관리 주체와 앱의 실제 실행 코어는 미확인이다. Thermal Status NONE만으로 이 제한을 배제할 수 없다. 작은 Java 최적화로 초기 FPS가 회복됐다고 주장하지 않는다.

| 전체 측정 | 원본 재집계 |
|---|---:|
| 모델 / 실행 | YOLO11s `best.tflite`, FLOAT32 640×640, CPU, Threads 4 요청, XNNPACK 요청 ON |
| 시간 / 프레임 / FPS | 710.657초 / 1,439 / 2.0249 |
| 추론 평균 / P95 | 452.261 / 542ms |
| nativeInvoke 평균 | 452.384ms |
| 전처리 / bitmap / pipeline / totalProcessing 평균 | 4.184 / 15.710 / 464.700 / 481.524ms |
| 오류 / 누락 로그 | 0 / 0; 원본 FRAME 개수와 확정 누적 프레임 수 일치 |
| 배터리 / 배터리 온도 | 94→84% / 31.2→35.4°C |

`inference`는 정수 ms 내림, `nativeInvoke`는 소수 ms이므로 native 평균이 소폭 큰 것은 계측 해상도 차이다. 두 값을 빼서 음수 Java 비용이나 100% 초과 native 비율을 계산하지 않는다. pipeline/totalProcessing 범위도 서로 다르다.

## 확인된 원인 근거와 미확인 가설

| 구간(초) | 추론 평균 ms | FPS | policy9 현재/상한 구간 평균 GHz |
|---|---:|---:|---:|
| 0–30 | 362.413 | 2.500 | 2.496 |
| 30–60 | 367.720 | 2.500 | 2.496 |
| 60–90 | 455.233 | 2.000 | 1.952 |
| 300–330 | 409.836 | 2.233 | 2.176 |
| 540–570 | 530.769 | 1.733 | 1.664 |
| 600–630 | 541.784 | 1.700 | 1.632 |

- **확인:** 60.326초 SYSTEM에서 policy9 상한 2.400→1.920GHz, 531.965초에 1.920→1.632GHz. policy4/7 등 다른 정책 상한도 변한다. 30초 구간 평균 24쌍의 policy9 주파수–추론 Pearson r=-0.98467(마지막 부분 구간 포함). 초기 0–60초 native 평균 365.198ms, 마지막 570–710.657초 541.086ms로 네이티브 구간에서도 증가한다. 후반 추론은 초기보다 약 48% 느리다. 중간 회복도 있어 단순한 누적 메모리 증가만으로 설명하기 어렵다.
- **해석 한계:** governor 이름은 `energy_aware`로 유지되고 상한이 변한다. 제한을 설정한 thermal/power HAL, 제조사 정책, 배터리 절전 기능 등의 주체는 기록되지 않았다. [CPUFreq 문서](https://www.kernel.org/doc/html/latest/admin-guide/pm/cpufreq.html)의 scalingMaxKHz는 정책에 허용된 상한이며 앱 실행 코어의 실측 클럭이 아니다. 상관관계에는 시간·온도 등의 공통 원인이 포함될 수 있다.
- **열 가설:** 모든 Thermal Status는 NONE, Headroom은 0.663–0.780이며 배터리 온도는 상승했다. 배터리 온도는 SoC 온도가 아니다. 제조사 제한이 공개 Thermal Status에 그대로 드러난다고 가정할 수 없다([공식 Thermal API 안내](https://developer.android.com/games/optimize/adpf/thermal)). 열 제한과 다른 전력 정책의 구분은 미확인이다.
- **Interpreter/Threads:** Activity 초기화에서 한 번 만들고 입력·출력 buffer/호출 컨테이너를 재사용한다. 프레임별 재생성 경로가 없다. 설정 재시작 때 analyzer drain 후 close한다. `setNumThreads(4)` 전달과 로그의 옵션값은 확인되지만 실제 worker 4개라는 뜻은 아니다([옵션 API](https://developers.google.com/edge/api/litert/kotlin/java/org/tensorflow/lite/InterpreterApi.Options)). 프로세스 전체 CPU 평균은 약 124%(한 코어=100%)로, YOLO 단독 worker 수를 역산할 수 없다. 현재 로그에는 tid/sched/core 이동 정보가 없다.
- **XNNPACK:** 현재 로그는 요청 ON만 보장한다. 같은 모델·런타임의 [선행 단독 벤치마크](yolo-inference-next-optimization-20261015.md)에서는 native partition 위임과 ON 시 요청 1/2/4 모두 약 1코어 CPU 사용을 관측했다. 이 사실을 이번 카메라 세션의 worker/partition 증명으로 대체하지 않는다.
- **ML Kit/GC:** ML Kit는 별도 비동기 Task이며 analyzer에서 scaled bitmap 준비 후 YOLO와 겹칠 수 있다. 시작 시 ML Kit 진행 힌트 true 408개는 평균 459.809ms, false 1,031개는 449.274ms다. 시간·주파수 조건이 섞인 비교이므로 10.534ms를 ML Kit의 인과적 비용으로 해석하지 않는다. GC/스레드 스케줄링 원인은 Perfetto/GC 로그가 없어 미확인이다. 프레임별 bitmap·결과 객체 할당은 있으나 누수 증거는 없다.

## 배터리 측정 신뢰성

코드의 CURRENT_NOW/AVERAGE µA÷1000→mA, 전압 mV, `mA×mV/1000→mW`, signed 전류 보존은 [BatteryManager 규약](https://developer.android.com/reference/android/os/BatteryManager)에 맞다. 방전 음수를 양수로 뒤집지 않는다. 평균 전력은 유효 샘플의 signed 추정 전력 산술 평균이며 앱 전용 전력이나 시간 가중 에너지가 아니다.

**이번 전류·전력 수치는 효율 비교에 사용하면 안 된다.** 전 샘플은 Discharging/None이며 charge counter는 3,658,840→3,278,600µAh(**380.24mAh 감소**). 동일 710.517초에서 전류 사다리꼴 적분은 **0.311557mAh**, 비율은 **0.00081937(약 1/1,220)**다. counter 차분으로 환산한 구간 평균 방전 전류는 약 1,926.6mA이나 이것 역시 counter를 신뢰한다는 조건부 값이다. 로그 평균 -1.579mA/-6.178mW와 큰 불일치가 있다.

제조사 전류 보고 단위 위반, fuel gauge/counter 오차 등을 의심하되 어느 API가 잘못됐는지는 확정하지 않았다. 전류에 임의 ×1000 보정을 적용하지 않았다. 분석기에 `SUSPECT_CURRENT_COUNTER_MISMATCH`를 추가하여 이 로그를 표시한다. 기준은 동일한 비충전 방전 구간에서 60초 이상·counter 감소 1mAh 이상·10배 초과 차이라는 **진단 휴리스틱**이며 교정/정확도 검증이 아니다. 누락·충전 전환·증가 counter·부호 불일치·120초 초과 간격은 적분에서 제외한다. ENERGY_COUNTER는 미지원이며 N/A를 유지한다.

화면·카메라·ISP·ML Kit·YOLO·다른 프로세스를 포함한 단말 배터리 측정이므로 잔량 감소나 counter 차이를 앱 단독 소비량으로 표현하지 않는다. 잔량 10 percentage points를 배터리 용량 없이 Wh로 환산하지 않는다.

진단은 기존 worker에서 5초마다 수집한다. 원본 시스템 조회 평균 10.682ms×142회, 배터리 조회 평균 5.814ms×143회로 조회 elapsed 합 약 2.35초(전체 wall 시간의 약 0.33%)다. 이는 CPU 사용률이나 FPS 손실률이 아니며 writer 비용도 제외한다. 수집을 원인으로 단정할 증거는 없고, 동일 조건 ON/OFF/ON 비교가 필요하다. tensor 진단 시간은 전 프레임 0ms이며 상세 Debug/PNG 설정도 OFF였다.

## 수정한 코드와 예상 효과

| 파일 | 수정 / 이유 | 효과와 한계 |
|---|---|---|
| `app/src/main/java/com/samin/objectdetection/MainActivity.kt` | verbose/timing 로그 6개 경로를 inline lambda로 변경, 해당 로그에만 쓰는 시각 계산도 내부로 이동 | OFF일 때 문자열·라벨 join·empty-reason 계산을 건너뛴다. ON의 메시지와 기록 위치는 유지한다. 불필요한 할당/작업 제거이며 native 클럭 제한 해소나 실측 FPS 향상은 미확인 |
| `tools/analyze_performance_logs.py` | relatedCpus 공백에서 cpuFrequency가 잘려 policy4/7/9를 잃던 parser 수정; 선택적 30초 분석·주파수 상관·ML Kit 힌트 그룹·전류/counter 일관성 추가 | 이번 로그의 실제 분석 오류를 수정했다. 전부 PC에서 실행되어 앱 부하는 없다. 상관/일관성 휴리스틱은 원인 확정/단위 교정 기능이 아님 |
| `tools/test_analyze_performance_logs.py` | 다중 정책 파싱, 구간/부분 구간/필터·상관, 미지원/충전/리셋/전류 단위 검증 추가 | 기존 로그 호환과 잘못된 숫자 대입 방지 |

`best.tflite`/클래스/Letterbox/NMS/필터/ML Kit 실행/Overlay/TTS/설정 기본값은 유지했다. 인터프리터·buffer·bitmap 수명과 flush 정책도 바꾸지 않았다. 특히 ML Kit가 사용하는 camera bitmap을 조기 recycle하거나 writer flush 간격을 늘리지 않았다. 정확도에 관련된 수치 연산 경로는 변경하지 않았다.

## 개선 우선순위

| 순위 / 후보 | 기대 효과 | 정확도·응답성 영향 | 배터리 영향 / 난이도 |
|---|---|---|---|
| P0: 정책 제한/worker/전류 검증 | 원인과 평가 지표 확정 | 결과 변경 없음 | 측정 부하 통제 필요 / 중간 |
| P1: Threads 2/3/4 비교 | 지속 속도·자원 사용 균형 가능. 현재 ON의 병렬 효과는 미확인 | 클래스/입력/후처리 동일, raw 수치·탐지 회귀 확인 필요 | 감소 가능하나 보장 없음 / 낮음(기존 설정) |
| P1: ML Kit 경쟁 A/B | native 동시 실행 영향 분리 | OFF는 ML Kit overlay/tracking 기능 변화; 상시 적용 보류 | 절감 가능 / 실험 낮음, 실행 시점 조정 중간 |
| 적용: 미사용 로그 계산 제거 | 작은 Java/할당 비용 감소 | 탐지 계산 변경 없음 | 작고 미측정 / 낮음 |
| P2: 기타 메모리·연산 제거 | profiling으로 실제 hotspot 확인 후 제한 적용 | bitmap ownership/후처리 변경은 위험 | 미확인 / 중간 |
| P2: 추론 시작 간격 600ms 실험 | 연속 부하를 줄일 여지 | 새 결과 갱신 간격 증가; 장애물 대응 예산 확인 필요 | 추론 부하는 줄어도 카메라/화면 비용은 유지 / 낮음 |
| P3: YOLO11s 512 / YOLO11n 등 | 연산량 절감 후보, 실측 이득 미확인 | 작은/먼 장애물 recall·box 위치 영향 가능; 재export·평가 필요 | 절감 가능 / 높음, **이번 적용 제외** |

별도 executor는 ML Kit native worker와 YOLO를 다른 CPU 코어로 격리하지 않는다. 직렬 실행은 YOLO 대기를 늘릴 수 있다. governor/클럭 강제 고정이나 성능 모드는 발열·소모를 늘릴 수 있어 적용하지 않았다. YOLO11s 640 유지 상태에서는 이 측정과 경쟁/스케줄링 검증을 먼저 한다.

## 검증 및 후속 실기기 테스트

- debug 앱 + AndroidTest APK 빌드 성공, 전체 JVM **110개 통과**([실행 로그](validation/yolo-long-run-build-tests-20261015.txt)).
- Python **9개 통과**, 원본 1,439 FRAME 재집계와 정책 4개 복원 확인([분석기 테스트](validation/yolo-long-run-analyzer-tests-20261015.txt)). `git diff --check` 통과.
- 이번 작업 시 `adb devices`에 연결 기기가 없어 instrumentation 실행·변경 후 FPS/전력·실제 탐지 동일성은 **미검증**이다. 이전 기기 테스트 통과를 이번 수정의 실행 검증으로 간주하지 않는다.

다음 측정은 같은 모델/장면/밝기/주사율/카메라/필터/Overlay/TTS, 충전 해제, 비슷한 시작 잔량·온도, 화면 녹화 OFF로 한다. 시스템+배터리 진단 ON, 5000ms, 상세 Debug/PNG OFF, CPU4/XNNPACK ON/ML Kit ON/Detect Interval=0을 변경 후 기준으로 **12분** 먼저 측정한다. 전류 교정 전에는 mW로 승자를 정하지 않고 잔량·counter·온도·응답 지연을 함께 본다.

1. 충분히 냉각한 뒤 Threads **4→3→2** 각 12분, 나머지 고정. 지속 FPS·P95·570초 이후 성능·counter 변화·최고 온도 비교 후 유망 후보를 역순 반복한다. 기본값은 측정 전 바꾸지 않는다.
2. 동일 Threads에서 ML Kit ON/OFF/ON 각 12분으로 경쟁 영향 확인. OFF는 원인 분리용이며 제품 기능 변경으로 자동 채택하지 않는다. native worker/ML Kit tid의 sched·cpu_frequency·idle과 `YOLO.Interpreter.run`, GC pause를 Perfetto로 0–90초 및 500–600초 부근에 수집한다. profiler 부하를 기록하고 무프로파일 로그와 비교한다.
3. 진단 비용은 조건을 고정해 배터리 ON/OFF/ON, 별도 시스템 ON/OFF/ON으로 분리한다. OFF 구간에 없는 지표를 0으로 채우지 않는다.
4. 응답성 예산이 허용될 때만 Detect Interval=600ms를 0과 비교한다. 이 설정은 **추론 시작 간격 하한**이지 완료 후 휴지 시간이 아니다. 100/200ms는 현재 약 400–575ms 처리보다 짧아 실질적인 휴식을 만들기 어렵다. 평균 FPS 외에 실제 경고 지연·누락을 확인한다.
5. 기기에서 BatteryManager raw CURRENT_NOW/AVERAGE 및 charge counter를 동일 시간에 다시 읽고, 제조사 단위 명세/허용된 독립 fuel gauge 또는 전력 측정과 대조한다. 단위를 확인하기 전 자동 ×1000 보정이나 앱 단독 전력 계산은 금지한다.

재현 명령:

```powershell
python tools/analyze_performance_logs.py "C:\Users\SAMIN\OneDrive\Documents\카카오톡 받은 파일\perf_20261015_112344.txt" --interval-seconds 30
```
