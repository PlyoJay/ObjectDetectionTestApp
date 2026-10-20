# 성능 테스트 타이머 (2026-10-20)

## 저장소 분석과 구현 경계

앱은 CameraX 분석 스레드에서 동기 YOLO 추론을 수행한다. 성능 로그는 `PerformanceLogRecorder` → `PerformanceLogSession` → `OrderedLogWriter`로 저장하며, 시스템·배터리 진단은 별도의 수집기를 사용한다. 기존 세션은 수동 종료만 지원했다.

타이머는 ROI 전처리와 독립적인 `metrics/PerformanceTestWindow.kt`에 정의했다. 측정 기간은 3·5·10분, 기본 5분이다. 설정 스키마는 5로 올렸고, 이전 설정에는 5분을 적용한다. 기존 진단·프리셋 값은 유지한다.

## 동작

- **성능 측정 및 로그 → 테스트 시간 (분)** 에서 기간을 선택한다.
- MediaStore 출력 준비가 끝난 시점에 로그 시작 시각을 확정하고, 동일한 단조 시각으로 타이머와 성능 수집 기준을 시작한다. 파일 생성 대기 시간은 측정에 포함하지 않는다.
- 남은 시간을 `PERF mm:ss`로 표시한다. Preview OFF에서도 표시한다.
- 자동 종료는 Recorder의 IO 코루틴에서 실행한다. CameraX 프레임 도착, YOLO 추론, Preview, Threads, 진단 샘플 간격과 무관하다.
- 종료 버튼으로 수동 저장한다. 자동·수동 종료가 경합해도 한 세션의 종료·최종 쓰기·drain은 한 번만 실행한다.
- 시작·측정·저장 중 상세 설정, 빠른 설정, Overlay 전환을 잠근다. 기존 설정 진입 가드도 유지한다. 파일 drain까지 완료한 후 잠금을 해제한다.
- 자동 종료 시 진행 중인 탐지 모델은 계속 동작하고 성능 로그만 종료한다. OS 스케줄링으로 실제 종료 시각에는 작은 지연이 있을 수 있으며 로그에 실제 시간을 남긴다.
- 성공 프레임은 완료 시각이 `[로그 시작, 설정 마감)`에 속할 때만 세션에 포함한다. 이전 실행 프레임이나 마감 이후 완료 프레임이 정해진 측정 구간에 섞이지 않는다.

## 로그 형식

헤더의 `configuredDurationMs`와 설정 스냅샷의 `performanceLog.durationMinutes`에 선택 시간을 기록한다. 최종 요약에는 아래 정보를 추가했다.

```text
TEST_SESSION | configuredDurationMs=180000 actualDurationMs=180002 stopReason=AUTO_DURATION stoppedElapsedNs=...
```

`actualDurationMs`는 로그 시작부터 수집 종료 요청까지의 단조 경과 시간이며, 파일 drain 시간을 제외한다. 종료 사유는 `AUTO_DURATION`, `MANUAL`, `ACTIVITY_DESTROYED`, `ERROR`다. 수동 종료 요청이 이미 마감 시각을 지난 경우 `AUTO_DURATION`로 기록한다. 기존 최종 성능·30초 구간·배터리 요약은 유지한다.

`tools/analyze_performance_logs.py`의 `measurement.session`에서도 해당 정보를 확인할 수 있다. 새 필드가 없는 과거 로그는 `N/A`로 표시한다.

## 검증

검증 명령, 실기기 결과와 전체 변경 요약은 [통합 검증 문서](timer-roi-validation-20261020.md)에 기록한다. 단위 테스트는 모든 기간과 Preview ON/OFF·Threads 1/4 조합, 시간 경계, 수동 종료 실제 시간, 중복 종료 및 최종 기록 횟수를 검증한다.
