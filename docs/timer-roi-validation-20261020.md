# 테스트 시간 통일 및 인식 영역 크롭 — 변경·검증 결과

검증일: 2026-10-20 (Asia/Seoul)

## 변경 요약

| 작업 | 구현 |
|---|---|
| 테스트 타이머 | 3·5·10분, 기본 5분, 로그와 동시 시작, 남은 시간, 자동·수동 종료 및 저장 |
| 실행 독립성 | 단조 시계와 별도 IO 코루틴, Preview·Threads·진단 샘플 주기와 분리 |
| 측정 설정 고정 | 시작·측정·저장 중 상세/빠른 설정과 Overlay 전환 잠금 |
| 시간 로그 | 설정 시간, 실제 경과 시간, 종료 사유, 마감 밖 프레임 제외 |
| ROI | 방향별 비율, 기본 OFF, 상단 10%·좌우 5%·하단 0% 프리셋, 비율 검사 |
| 중복 크롭 방지 | 방향별 크롭과 중앙 ROI 상호 배제, 공통 ROI 결정 함수 |
| YOLO | 크롭 후 기존 640×640 Letterbox 및 기존 후처리 유지 |
| 좌표계 | 회전 후 전체 프레임 기준, YOLO/ML Kit 동일 ROI 및 BBox 복원 |
| Preview | BBox와 같은 FIT_CENTER 기하를 사용한 인식 영역 경계선 |
| 비교 로그 | 설정 스냅샷 및 매 프레임 크기·회전·실제 ROI 기록, Python 분석 지원 |

기존 작업 트리의 미커밋 변경을 유지하면서 확장했다. 모델 파일, `VisionStyleYoloDetector`의 모델 실행·Confidence·NMS 등 탐지 알고리즘은 수정하지 않았다. 기존 성능·배터리·발열 진단과 30초 구간 요약을 유지했다. 설정은 기존 카테고리 구조에 맞춰 카메라/입력과 성능 측정/로그에 나눠 배치했다.

- [타이머 설계·사용법·로그](test-timer-20261020.md)
- [ROI 설계·회전·좌표 변환·로그](recognition-roi-20261020.md)

## 단위 테스트와 빌드

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest --console=plain
python -m unittest discover -s tools -p test_analyze_performance_logs.py
git diff --check
```

| 검증 | 결과 |
|---|---|
| JVM 단위 테스트 | 142개 통과, 실패·오류·스킵 0 |
| Python 로그 분석 | 12개 통과 |
| Debug APK | `assembleDebug` 성공 |
| Android 계측 APK | `assembleDebugAndroidTest` 성공 |
| 공백 오류 검사 | 통과 |

시간 테스트는 3·5·10분 각각 Preview ON/OFF·Threads 1/4 조합, 마감 경계, 시작 이전/종료 이후 프레임·오류 제외, 수동 종료 실제 시간과 중복 종료를 검증한다. ROI 테스트는 OFF/중앙/방향별 크롭, 유한값·합계 검사, 최소 영역, 홀수 크기의 Letterbox와 ML Kit 역변환을 검증한다.

- [최종 빌드 로그](validation/timer-roi-final-build-20261020.txt)
- [Python 테스트 로그](validation/timer-roi-python-20261020.txt)
- APK: `app/build/outputs/apk/debug/app-debug.apk`

## Android 실기기

SM-S921N, Android 16에서 검증했다. 초기 잠금 화면으로 두 UI 테스트가 일시 중지됐고, 사용자 잠금 해제 후 다시 진행했다. 직접 `am instrument` 실행 시에는 Gradle의 `animationsDisabled`가 적용되지 않아 클릭/레이아웃 준비 문제가 나타났다. 레이아웃 완료 대기를 추가하고 애니메이션을 임시로 비활성화한 조건에서 UI 테스트 4개가 통과했다. 앱 런타임 설정을 우회하거나 모델을 변경하지 않았다.

실기기 테스트 항목:

1. 실제 비트맵 0/90/180/270도 회전, 크롭 픽셀 위치, 단일 탐지기 호출, 전체 프레임 BBox 복원.
2. BBox 표시 OFF에서도 FIT_CENTER 위치에 ROI 경계선 표시, 영역 표시 OFF 시 제거.
3. 카테고리별 ROI 프리셋·비율 오류·중앙 ROI 상호 배제·Letterbox 고정·측정 기간 저장.
4. Preview ON/OFF에서 실제 추론·진단을 유지하며 수동 저장, 설정 진입 잠금 및 해제.
5. 실제 3분 자동 저장: Preview ON·1스레드·시스템 진단 OFF, Preview OFF·4스레드·시스템/배터리 진단 ON. 최초 두 모드 실행을 모두 통과했고, 최종 APK의 OFF 실행도 별도로 통과하여 로그를 회수했다. 최종 UI/좌표 4개 및 자동 종료 1개, 총 5개 계측 테스트가 통과했다.

| 실행 | 설정 시간 | 실제 측정 시간 | 종료 사유 | 확인 |
|---|---:|---:|---|---|
| Preview ON, Threads 1, ROI 프리셋 | 180초 | 180.016초 | AUTO_DURATION | 성공 프레임 374, 로그 누락 0 |
| Preview OFF, Threads 4, ROI 프리셋 | 180초 | 180.037초 | AUTO_DURATION | 성공 프레임 237, 로그 누락 0, 배터리 샘플 37, 시스템 요약 유지 |
| Preview OFF, 기본 전체 영역 | 300초 | 7.988초 | MANUAL | 진단 유지, 설정 잠금·해제 |
| Preview ON, 기본 전체 영역 | 300초 | 7.286초 | MANUAL | 진단 유지, 설정 잠금·해제 |

카메라 진단 로그에서 Preview ON의 **Preview와 분석 영상이 모두 1440×1080, cropRect도 `(0,0)-(1440,1080)`**임을 확인했다. 카메라 회전 90도 후 실제 처리 크기는 1080×1440, 프리셋 ROI는 `[54,144,1026,1440]`이었다. Preview OFF에서도 분석 해상도가 같았다. 요청 해상도 1280×720와 실제 협상 해상도를 구분해 기록한다.

- [실기기 UI·좌표 테스트 통과 로그](validation/timer-roi-device-ui-20261020.txt)
- [최종 APK 자동 종료 테스트 통과 로그](validation/timer-roi-device-auto-final-20261020.txt)
- [최초 자동 종료 테스트 포함 JUnit 결과](validation/timer-roi-device-initial-20261020.xml) — 잠금 상태에서 발생한 두 UI 실패도 보존됨
- [Preview/분석 좌표계 진단](validation/timer-roi-camera-coordinates-20261020.txt)
- [자동 종료 ON 원본 로그](validation/timer-roi-auto-on-1thread-20261020.txt)
- [자동 종료 OFF 원본 로그](validation/timer-roi-auto-off-4threads-20261020.txt)
- [자동·수동 측정 로그 분석 JSON](validation/timer-roi-auto-analysis-20261020.json)
- [수동 종료 OFF 원본 로그](validation/timer-roi-manual-off-20261020.txt)
- [수동 종료 ON 원본 로그](validation/timer-roi-manual-on-20261020.txt)

## 검증 범위와 재현

5분·10분은 단위 테스트의 가상 단조 시계로 기간과 마감을 검증했으며, 실제 5분·10분 대기 실험은 수행하지 않았다. 회전 네 방향은 실제 Bitmap 회전으로 검증했다. 앱의 기존 세로 화면 고정은 유지한다. YOLO와 ML Kit의 **좌표 변환 일치**를 검증했으며, 서로 다른 모델이 동일 객체·BBox를 출력하는지 또는 탐지 정확도가 향상되는지는 별도의 정답 데이터가 필요하다.

성능 숫자는 기능 검증용이며 모델/스레드의 우열을 판단하는 비교 실험으로 사용하지 않는다. 화면 상태·진단 조건·배터리 전원·실행 순서가 다르다. 비교할 때 분석 JSON의 `measurement`와 로그 스냅샷으로 동일 조건을 먼저 확인한다.

최종 계측 실행은 APK와 캐시 로그를 유지하는 `adb shell am instrument`를 사용했다. 각 테스트가 설정을 백업·복원하며, 애니메이션 세 값도 원래 값 `1.0`으로 복원하고 확인했다. 최초 Gradle 계측 러너의 APK 정리 후 최종 Debug APK를 기기에 다시 설치했다.
