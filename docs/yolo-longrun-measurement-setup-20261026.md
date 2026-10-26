# YOLO 11클래스 5분 기준선 및 짧은 Perfetto 수집 준비

작성: 2026-10-26 KST. **실제 5분 측정은 아직 실행하지 않았다. 아래 절차를 읽은 뒤 사용자가 앱에서 시작한다.**
앱 코드·모델·labels·설정·인식/경고 정책 변경, 앱 설치/실행, governor/클럭/열 보호 변경, 커밋·푸시는 하지 않았다.

## 1. 비교 대상과 기존 근거

참고: [기존 장시간 성능 보고서](yolo-longrun-performance-degradation-20261026.md),
[11클래스 이관 및 검증](yolo-eleven-class-migration-20261026.md).

| 항목 | 기존 9클래스 로그 | 이번 측정 대상 |
| --- | --- | --- |
| 모델 SHA-256 | `f312b541c86426c53c02ef1674ee539c6f87fcf97102061fade628317b3c10b9` | `cc189262fd705e7d0b48246ebe42f868ff9b7370d97a0a6d7a7f756f16d4bad1` |
| FLOAT32 입력 | `[1,3,512,512]` | `[1,3,512,512]` |
| FLOAT32 출력 | `[1,13,5376]` | `[1,15,5376]` |
| 클래스 | 9 | 기존 9 + cat(9), dog(10) |
| 모델 크기 | 이전 문서 참조 | 37,929,451 bytes |
| 분석 소스 | `7030c3f8aaec0e676f1366c39a221b89d7b26c18`; 당시 APK 소스 일치 증거 없음 | 준비 시작 HEAD `3a71eb2e2077ed6a8ff75e555f28a5339e9a3d3c`; 새 빌드 manifest로 APK까지 결합 |

**기존 로그는 저하 양상에 관한 역사적 근거다. 새 모델 결과와 동일 모델 A/B, 최적화 효과, 클래스 추가 비용으로 비교하지 않는다.**
앞으로 trace 영향 비교는 동일한 새 11클래스 모델·APK·설정의 Baseline/Perfetto 회차 사이에서만 수행한다.

기존 5분 ML Kit OFF/ON 세션에서 0–30초 → 180–210초 추론 평균은 각각
222.47→491.91ms / 222.77→487.50ms, FPS는 3.200→1.500 / 3.167→1.533이었다.
전체 처리 증가분에서 추론 증가분의 비중은 약 80%였다. CPU policy `scaling_max_freq` 하락도 함께 관측됐다.
이는 상관 증거이며 thermal 제한 주체, 실제 실행 코어, running/runnable 대기, GC 원인을 확정하지 못한다.
ML Kit OFF에서도 저하가 발생했으므로 ML Kit가 필수 원인이라는 근거는 없다.

기존 로그 해석은 그대로 유지한다.

- `FRAME`: 성공 pipeline 완료 시각으로 집계. FPS는 구간 성공 수/실제 구간 초, P95는 실제 FRAME 표본의 nearest-rank.
- `LATENCY`: `(sessionId,frameId)`로 결합하며 FPS에 더하지 않는다. 파일 행 순서 대신 각 elapsed 시각 사용.
- `INTERVAL_SUMMARY`/`CUMULATIVE_SUMMARY`: 확정값. `INTERVAL_PREVIEW`는 중간값이다.
- `SYSTEM`: 5초 관측이며 CPU %는 모든 앱 스레드 합계, 100%=한 코어. policy 주파수는 앱 실행 CPU를 뜻하지 않는다.
- `BATTERY_SAMPLE`: 배터리 온도는 SoC 온도가 아니다. 기존 전류/counter 신뢰성 경고를 유지하고 앱 전력으로 사용하지 않는다.
- `totalProcessing`은 analyzer 수신→pipeline 완료, `pipeline`은 DetectionPipeline 범위다. 세부 시간과 상위 시간을 합산하지 않는다.
- `droppedFrames`는 명시적인 앱 skip이다. 카메라 손실 0을 증명하지 않는다. `Dropped Log Lines`와 trace 손실은 서로 다른 항목이다.

## 2. 준비 파일과 추적 범위

| 파일 | 역할 |
| --- | --- |
| `tools/perfetto/Prepare-YoloMeasurement.ps1` | 실제 모델 검사, 선택적 새 debug 빌드, 소스/APK/model 해시 manifest, 선택적 기기 읽기 확인 |
| `tools/perfetto/Start-YoloMeasurement.ps1` | 사용자가 시작하는 새 앱 로그를 기다려 Baseline 또는 짧은 trace 회차 수집 |
| `tools/perfetto/Common.ps1` | ADB·헤더·기기 BOOTTIME·boot ID·설치 APK 확인 |
| `tools/perfetto/yolo-short.pbtxt` | 회당 20초, 32MiB ring buffer, ftrace CPU별 2MiB/250ms drain |
| `tools/perfetto/yolo_measurement.py` | 빌드 provenance와 오프라인 성능/trace/기준선 비교 분석 |
| `tools/perfetto/inspect-yolo.sql` | Perfetto UI에서 시계, 손실, CPU별 실행, GC 확인 |
| `tools/perfetto/conditions-template.txt` | 회차 조건 기록 양식 |
| `tools/perfetto/Test-YoloMeasurement.ps1`, `test_yolo_measurement.py` | 실제 기기/장시간 측정 없이 수집·분석 계약 검증 |

소스의 `androidx.core.os.trace`가 내부에서 `android.os.Trace.beginSection/endSection`을 호출함을
로컬 core-ktx 1.18.0 sources JAR로 확인했다. 아래 구간은 상세 진단 ON 여부와 무관하게 기존 경로에서 호출된다.

| 기존 구간 | 위치/조건 |
| --- | --- |
| `CameraX.toBitmap`, `CameraX.rotate` | `camera/ImageProxyExt.kt`; rotate는 실제 회전이 있을 때 |
| `YOLO.resize`, `YOLO.getPixels`, `YOLO.rgbWrite` | `detector/VisionStyleYoloDetector.kt` |
| `YOLO.Interpreter.run` | 같은 detector의 기존 Java Interpreter 호출 범위 |
| `MLKit.InputImage` | `mlkit/MlKitObjectDetector.kt`; ML Kit 실행이 있을 때 |

debug 빌드는 debuggable이며 `atrace_apps: "com.samin.objectdetection"`로 앱 구간을 요청한다.
`linux.ftrace`의 네 이벤트와 `dalvik`을 우선 사용한다. `linux.process_stats`는 시작 시 이름 매핑,
`linux.sys_stats`의 500ms cpufreq polling은 짧은 trace 안에서만 주파수 초기값/추이를 보완한다.
polling 주파수 track만으로 커널 `cpu_frequency` 이벤트 지원을 증명하지 않는다.

tracefs 목록을 읽을 수 있으면 없는 이벤트만 설정에서 제외하고 `support.json`에 N/A를 쓴다.
읽기 권한이 없으면 요청을 유지하고 지원 여부를 미확인으로 기록한다.
`dalvik` 범주가 없으면 제외/N/A다. 최종 지원 판단은 실제 이벤트·setup 오류·트랙을 함께 확인한다.
관측되지 않은 이벤트, 비활성 경로, GC 미발생은 0으로 채우지 않는다.

## 3. 시작 전 조건 고정

Windows의 PowerShell 7(`pwsh`), Android SDK `adb`, 저장소 `.venv/Scripts/python.exe`를 사용한다.
모델 검사는 기존 `tools/requirements-golden.txt` 환경(ai-edge-litert/numpy/tflite)이 필요하다.
trace 분석은 별도로 공식 native `trace_processor_shell.exe`가 필요하며 수집에는 필요 없다.
[공식 Trace Processor 준비 방법](https://perfetto.dev/docs/analysis/trace-processor)을 참조한다.

1. 동일 기기·OS·장면·조명·자세·밝기·주사율·앱 foreground 상태를 유지한다. 화면 녹화/다른 profiler를 끈다.
2. 시작 배터리 온도/잔량 범위, 충분한 냉각 시간, 세션 전 카메라 실행 시간을 맞춘다. 앱 재실행/설정 변경 이력도 기록한다.
3. 기존 로그의 무충전 조건을 가능하면 유지한다. USB 연결이 충전을 유발하면 연결/전원 상태를 기록하고 모든 회차에서 동일하게 맞춘다.
   스크립트가 충전이나 전원 정책을 변경하지 않는다.
4. 앱 UI에서 **512 모델, CPU 2 threads, XNNPACK 요청 ON, LETTERBOX, Preview OFF, 카메라 요청 1280×720,
   detect interval 0ms, ROI 좌/우 10%·상단 20%·하단 0%**를 확인한다. 기존 예상 실제 영상은 회전 후
   1080×1440, 90°, ROI `[108,288,972,1440]`다. 실제 값이 다르면 조건 차이를 기록하고 동일 조건이라고 주장하지 않는다.
5. 성능 시간 5분, system/battery 진단 ON·5000ms, 상세 detection/ML Kit 진단·tensor/PNG 저장 OFF를 확인한다.
   confidence/NMS/후보 수/필터/경고 출력 정책은 현재 값을 그대로 유지한다. 양식의 기존 값과 다르면 실제 값을 적는다.
6. 우선 ML Kit OFF 조건으로 시작한다. ON 조건은 SINGLE_IMAGE_MODE/1500ms를 유지한 별도 기준선/재현 묶음으로 만든다.
7. 아래 조건 양식을 복사해 실제 조건을 채운다. 자동 수집 전후 battery 스냅샷과 앱 settings snapshot도 함께 보관된다.

아래 명령은 저장소 루트 `D:\develop\Projects\TestProjects\ObjectDetection`에서 실행한다.

```powershell
# 소스/실제 모델/라벨을 검사하고 새 APK까지 결합한다. 앱 설치나 측정 시작은 하지 않는다.
pwsh -File tools/perfetto/Prepare-YoloMeasurement.ps1 `
  -BuildApk -DeviceCheck -Serial R3CX506B3RH `
  -OutputDirectory build/yolo-measurements/prepared-20261026

Copy-Item tools/perfetto/conditions-template.txt build/yolo-measurements/conditions-off.txt
# conditions-off.txt를 실제 조건으로 작성한다.
```

매번 새 디렉터리를 사용한다. `-BuildApk` 없이 기존 APK를 조사할 수도 있지만 소스 리비전은 UNPROVEN으로 기록되고
측정 runner는 이를 거부한다. `build-manifest.json`에는 HEAD, dirty 상태, tracked/untracked 비무시 파일 해시,
tree fingerprint, 빌드 명령, APK SHA-256, APK 안 모델/labels 해시, 실제 Interpreter shape/type가 기록된다.
소스 파일을 APK에 새로 계측하지 않고 **빌드 산출물과 외부 manifest**를 결합하는 방식이다.
기존 설치 APK와 새 APK 해시가 같으면 설치할 필요가 없다. 다르면 사용자가 아래처럼 설치 후 앱 상태/설정을 확인한다.

이번 준비에서는 위 `prepared-20261026` 디렉터리를 이미 만들었다. 빌드는 성공했으며 설치 APK SHA-256도
`5595b51a4949cf6d969a6185c1283856b18b7a448a59c920eab645172260561b`로 일치했다.
현재 소스를 그대로 사용할 때는 재설치/준비 재실행 없이 조건 메모 작성 후 회차 A부터 시작할 수 있다.
준비를 다시 수행하려면 새 디렉터리 이름을 지정하고 이후 명령의 manifest 경로도 바꾼다.

```powershell
# 필요한 경우에만 사용자가 실행한다. 앱 데이터 삭제 명령은 사용하지 않는다.
adb -s R3CX506B3RH install -r -t app/build/outputs/apk/debug/app-debug.apk
```

## 4. 회차 A: Perfetto 없는 기본 5분 기준선

```powershell
pwsh -File tools/perfetto/Start-YoloMeasurement.ps1 `
  -Mode Baseline -Serial R3CX506B3RH `
  -BuildManifest build/yolo-measurements/prepared-20261026/build-manifest.json `
  -ConditionsFile build/yolo-measurements/conditions-off.txt `
  -OutputDirectory build/yolo-measurements/baseline-off-01
```

`ARMED (Baseline)` 출력 후 **사용자가 앱에서 새 성능 로그 시작을 누른다**. 이미 진행 중인 로그는 선택하지 않는다.
대기 제한은 기본 180초다. 시작 전 파일 목록을 기억하고 새 파일의 flush된 헤더를 1초 간격으로 찾는다.
새 헤더의 모델 SHA·실제 I/O shape·5분 설정과 설치 APK를 검증한 뒤 세션을 고정한다.
Baseline 분기에서는 Perfetto 명령을 한 번도 실행하지 않는다. 중간 주기 ADB 수집도 없다.
기존 앱 FRAME/SYSTEM/배터리 로그만 5분간 기록한다. 시작 전/종료 후 ADB 작업은 별도로 남는다.

앱의 5분 AUTO_DURATION 종료와 writer drain 후 파일 크기 안정성을 확인해
`/sdcard/Documents/GOTORO/performance/perf_*.txt`를 `performance.txt`로 가져온다.
화면 유지/앱 foreground는 사용자가 유지하며 시스템 설정을 스크립트가 바꾸지 않는다.

## 5. 회차 B: 별도 재현에서 짧은 Perfetto 수집

냉각·초기 상태·카메라 예열 시간·장면을 다시 맞추고 새로운 5분 로그를 시작한다.

```powershell
pwsh -File tools/perfetto/Start-YoloMeasurement.ps1 `
  -Mode Perfetto -Window Both -Serial R3CX506B3RH `
  -BuildManifest build/yolo-measurements/prepared-20261026/build-manifest.json `
  -ConditionsFile build/yolo-measurements/conditions-off.txt `
  -OutputDirectory build/yolo-measurements/perfetto-off-01
```

`ARMED` 후 사용자가 앱에서 시작한다. 세션 +5초→약25초, +185초→약205초에 각각 20초 설정으로 수집한다.
따라서 요청한 0–30초/180–210초 안쪽에 준비·종료 지연을 위한 여유를 둔다.
현재 시간이 너무 늦으면 다른 시간대 trace로 대체하지 않고 실패한다.
최종 포함 여부는 trace ClockSnapshot/bounds로 다시 검사한다.

5분 연속 trace, 매프레임 ADB 조회, heap dump, allocation stack 수집은 하지 않는다.
긴 대기는 호스트에서 수행하고 창 근처에서 기기 BOOTTIME으로 다시 확인한다.
설정은 `/data/local/tmp/yolo-<sessionId>.pbtxt`에 놓고 shell의 `cat ... | perfetto --txt -c -`로 전달한다.
실기기에서 Perfetto가 이 경로의 설정을 직접 열면 SELinux 거부가 발생했으므로 stdin을 사용한다.
출력은 `/data/misc/perfetto-traces/yolo-<sessionId>-early|late.perfetto-trace`다.
foreground Perfetto 정상 종료 뒤 pull하므로 기록 중 파일을 정상 결과로 가져오지 않는다.

초기 trace와 pull 자체가 이후 열 이력에 영향을 줄 수 있다. 필요하면 **별도 회차**에서 `-Window Early`와
`-Window Late`를 각각 수행해 late 회차에 early trace가 없도록 한다. 5분 기준선/재현 순서를 교대하고
조건별 3회 이상 반복해 자연 변동과 trace 영향 범위를 본다. 냉각 없이 연속 실행하지 않는다.

`Ctrl+C`, ADB 단절, 재부팅, 잘못된 모델/설정, 앱 조기 종료는 유효한 5분 결과로 취급하지 않는다.
오류 시 `run.json`과 남아 있는 원본 파일을 보존한다. 취소 당시 실행 중이던 trace는 최대 설정 시간 후 끝나므로,
나중에 해당 remote 경로에서 회수할 수 있으나 완결성 검사를 통과하기 전에는 분석에 사용하지 않는다.
자동 삭제는 하지 않는다.

## 6. 시계 대응 및 결과 파일

- 앱 `Start Elapsed Ns`, FRAME `inferenceStartElapsedNs`, `inferenceEndElapsedNs`, `completedElapsedNs`는
  Android `elapsedRealtimeNanos`, 즉 suspend를 포함하는 BOOTTIME 기준이다.
- `Start Unix Ms`는 기기 Unix wall milliseconds다. `Start Time` 표시 문자열/파일명/PC 시각은 정확한 조인 키가 아니다.
- `run.json`은 boot ID, 호스트 UTC 전후 bracket, 기기 `/proc/uptime`(BOOTTIME 근사, 약 10ms 해상도),
  wall seconds, 각 trace 명령 직전/직후·pull 후 시각을 기록한다. 원격 명령 경계는 trace 실제 경계와 구분한다.
- 분석기는 `clock_snapshot`의 `clock_id=6`에서 `offset = ts - clock_value`를 확인한다.
  `sessionSeconds = (traceTimestampNs - offset - StartElapsedNs) / 1e9`로 맞춘다.
  ClockSnapshot이 없거나 offset이 일관되지 않으면 자동 시각 대응은 N/A로 남긴다.
  Perfetto UI에서 0부터 보이는 표시 시각이나 임의의 host/phone wall offset을 직접 빼지 않는다.
- FRAME과 기존 일반 이름 trace에는 공통 frame ID가 없다. slice를 유일하게 감싸는 FRAME 추론 시작/끝 구간이
  있을 때만 `inferenceFrameJoins`에 frameId를 대응시킨다. 경계/중복/미관측은 N/A다.

| 디렉터리 | 예상 산출물 |
| --- | --- |
| prepared | `build-manifest.json`, `model-inspection.json`, `build.txt`, `yolo-short.pbtxt`; DeviceCheck 시 기기/범주/서비스/패키지/이벤트 목록과 `device-check.json` |
| baseline | `run.json`, `build-manifest.json`, `conditions.txt`, `session-header.txt`, `battery-before.txt`, `battery-after.txt`, `performance.txt` |
| perfetto | baseline 파일 + `support.json`, `available-events.txt`, `effective.pbtxt`, 선택 창의 `early/late.perfetto-trace`, `early/late-perfetto.txt` |
| 분석 후 | `analysis.json`; processor 사용 시 trace별 bounds/clocks/stats/metadata/events/cpu-tracks/slices/inference-states `.sql`, `.csv`, processor stderr |

준비 과정에 사용한 1초 검증 파일/분석 도구는 `build/yolo-measurements/validation/`에 있다.
모든 측정 산출물은 기존 ignore 대상인 루트 `build/` 아래에 둔다. 원본 파일은 별도 백업한다.

## 7. 오프라인 분석과 확인할 지표

```powershell
.venv/Scripts/python.exe tools/perfetto/yolo_measurement.py report `
  --run build/yolo-measurements/baseline-off-01

.venv/Scripts/python.exe tools/perfetto/yolo_measurement.py report `
  --run build/yolo-measurements/perfetto-off-01 `
  --baseline build/yolo-measurements/baseline-off-01 `
  --trace-processor build/yolo-measurements/validation/trace_processor_shell.exe
```

native processor가 없으면 해당 인수를 생략하고 성능 로그만 분석한다. trace 검사는 N/A로 남는다.
또는 [Perfetto UI](https://ui.perfetto.dev)에 trace를 열고 `inspect-yolo.sql`의 SELECT들을 각각 실행한다.

1. **완결성/동일성**: AUTO_DURATION 300000ms, 성공 FRAME 수와 CUMULATIVE 일치,
   `Dropped Log Lines=0`, 모델/shape/APK/settings 일치, boot ID 유지, 조건 메모와 실제 영상/ROI 확인.
2. **trace 유효성**: 실제 bounds가 초반/후반 요청 창 안이며 약20초인지, 기존 앱 구간이 보이는지,
   네 이벤트/GC 범주의 지원 여부, `ftrace_setup_errors`와 parser 오류 확인.
3. **trace 손실**: `stats`의 data_loss/error, CPU별 `overrun_end-begin`/dropped events,
   central buffer bytes/chunks overwritten, packet loss를 확인한다. 링 버퍼 앞부분 유실도 손실이다.
   분석기는 `INVALID`/`NO_RECORDED_LOSS`/`N/A`를 구분한다. setup 오류는 별도 확인한다.
   손실이면 버퍼·drain을 검토하고 짧은 창을 다시 수집한다. 설정을 바꾼 회차는 별도로 표기한다.
4. **기준선**: 30초별 성공 FPS, inference/nativeInvoke/totalProcessing 평균·P95, Bitmap/전처리/parsing,
   오류·skip, 5초 CPU 정책/열/배터리 추이를 확인한다.
5. **trace 영향**: trace 실제 시작/끝에 해당하는 동일 세션 초 구간을 기준선과 재현 로그에서 각각 집계한다.
   `traceImpact`에 FPS 변화 %와 두 회차의 평균/P95가 들어간다. 부족/누락 FRAME이면 정확한 비교를 거부한다.
   단일 회차 차이를 순수 tracing 인과 비용으로 단정하지 않는다. 준비/pull 구간도 `run.json`으로 확인한다.
   trace 손실이 있으면 CPU/GC 원인 해석은 보류한다. 로그 기반 FPS 차이는 별도 관측값으로만 보관한다.
6. **CPU와 지연**: Interpreter slice의 caller tid, CPU별 실행/migration, running/runnable/그 외 대기 시간을 본다.
   worker는 sched에서 확인한다. caller sleeping이 worker 계산 대기일 수도 있어 caller만으로 계산량을 해석하지 않는다.
   `inference-states.csv`는 caller 상태이고 `inspect-yolo.sql`은 invocation 안의 전체 앱 스레드 CPU 실행을 보여준다.
   ML Kit/UI도 포함되므로 모든 앱 스레드를 YOLO worker로 부르지 않는다.
7. **주파수/idle**: 실제 caller/worker가 실행된 CPU의 cpufreq와 idle을 교차 확인한다.
   idle 상태의 주파수 수치나 policy 5초 스냅샷을 실제 추론 클럭으로 대체하지 않는다.
8. **GC**: 같은 process의 ART GC/suspend/stop-the-world 구간과 inference/runnable 대기를 교차 확인한다.
   concurrent GC 전체 duration은 pause duration이 아니다. GC 구간 미관측은 GC 비용 0을 증명하지 않는다.

Perfetto만으로 parsing copy/scan 분리, confidence 통과 후보 수, 전체 메모리/누수 추이, 실제 delegate partition,
thermal 제한 주체가 해결되지 않으면 미확인 항목으로 남긴다. 그 결과를 본 뒤 필요한 앱 계측을 별도 결정한다.
이번에는 `YOLO.outputCopy`/`YOLO.candidateScan` 등 새 앱 Trace/로그를 추가하지 않았다.

관련 공식 근거: [앱 ATrace](https://perfetto.dev/docs/data-sources/atrace),
[시계 동기화](https://perfetto.dev/docs/concepts/clock-sync),
[buffer와 손실](https://perfetto.dev/docs/concepts/buffers),
[trace stats](https://perfetto.dev/docs/analysis/sql-stats).

## 8. 준비 단계 검증과 남은 항목

- 실제 파일 SHA-256 및 기존 Interpreter 검사 도구로 11클래스 모델 계약을 재검사한다. 준비 manifest에 실제 결과를 저장한다.
- 오프라인 Python 분석 테스트 8개, PowerShell 헤더/잘못된 모델·shape/재부팅 검사 4개,
  가상 기기 시간을 사용하는 전체 Baseline/Perfetto runner 검사 2개가 통과했다.
  Baseline에서 Perfetto 명령 0회, 재현에서 trace 2회인 점도 검사했다.
- 기존 성능 로그 분석기 회귀 테스트 12개, 모든 새 PowerShell 스크립트의 구문 검사도 통과했다.
- `assembleDebug --offline` 성공. 빌드 APK와 설치 APK 해시 일치, APK 내부 모델/labels 해시 일치,
  앱 소스/모델/라벨의 Git diff 없음까지 확인했다.
- SM-S921N(API 36), Perfetto v51.2, dalvik 범주와 세 데이터 소스를 읽기 확인했다.
  앱을 실행하지 않은 **1초 시스템 설정 검증**에서 네 ftrace 이벤트가 모두 관측됐고 setup 오류와 기록된 손실이 없었다.
  BOOTTIME ClockSnapshot 변환 offset은 이 검증 trace에서 0이었다. 이는 향후 모든 trace에서 offset=0이라는 보장이 아니다.
- 공식 manifest SHA-256을 검증한 Trace Processor v58.2로 쿼리와 CSV 출력을 확인했다.
  장시간 부하에서는 이벤트 발생량과 손실 여부가 달라질 수 있어 20초 실측 파일마다 다시 검사한다.
- **미실행/미확인**: 새 11클래스 모델의 실제 5분 기준선, 재현 trace의 YOLO/Bitmap slice 실관측,
  trace FPS 영향, 추론 지연과 CPU/주파수/GC의 관계, 저하 원인.
  1초 시스템 trace에는 앱 process가 실행 중이지 않아 YOLO/GC 결과는 N/A다.

검증 재실행:

```powershell
pwsh -File tools/perfetto/Test-YoloMeasurement.ps1
.venv/Scripts/python.exe -m unittest discover -s tools/perfetto -p test_yolo_measurement.py -v
```
