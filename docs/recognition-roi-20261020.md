# 인식 영역 크롭 (2026-10-20)

## 저장소 분석과 적용 위치

기존 경로는 `ImageProxy.toBitmapSafe()`에서 전체 버퍼를 비트맵으로 바꾼 뒤 `rotationDegrees`만큼 회전하고, `DetectionPipeline`에서 선택적인 중앙 정방형 ROI를 잘라 YOLO에 전달했다. YOLO는 입력을 resize/letterbox한 뒤 BBox를 입력 비트맵 픽셀 좌표로 복원한다. Pipeline은 ROI offset을 더해 회전된 전체 프레임 좌표로 복원한다. ML Kit는 기존에 별도의 전체 프레임 640×360 축소본을 사용했다.

공통 `camera/RecognitionRoi.kt`에 설정과 `FrameRoi` 계산을 분리했다. 모델 파일, YOLO의 텐서 처리·Confidence·후처리·NMS·기존 필터 알고리즘은 변경하지 않았다.

## 설정과 유효성

**카메라 및 입력 → 인식 영역 크롭**에 크롭 스위치, 네 방향 백분율, 영역 표시 스위치와 프리셋 버튼을 추가했다. 성능 측정 기간은 별도의 **성능 측정 및 로그** 카테고리에 둔다.

| 항목 | 기본값 / 규칙 |
|---|---|
| 크롭 | OFF, 전체 프레임 유지 |
| 방향별 비율 | 기본 0%, 각각 독립 설정 |
| 제공 프리셋 | 상단 10%, 하단 0%, 왼쪽 5%, 오른쪽 5%, 크롭 ON |
| 유효성 | 유한한 0~100%; 상하 합계 및 좌우 합계 각각 100% 미만 |
| 중앙 ROI | 크롭 ON 시 해제·비활성화, 동시 저장 불가 |
| YOLO Resize | 크롭 ON 시 640×640 Letterbox 고정; STRETCH 동시 저장 불가 |
| 영역 표시 | Preview ON에서 초록색 인식 영역 경계선, 별도 ON/OFF 가능 |

크롭 OFF는 저장된 비율을 적용하지 않는다. 기존 중앙 ROI를 사용하던 설정은 새 기능이 OFF일 때 그대로 유지한다. 공통 기하 계산도 방향별 크롭에 우선권을 주므로 잘못된 직접 호출로 두 모드가 켜져도 이중 크롭하지 않는다. 스키마 1~4의 설정에는 크롭 OFF와 0%를 적용한다.

## 좌표 변환

비율의 상하좌우는 **회전 완료 후 화면을 바르게 세운 전체 영상** 기준이다. 90/270도 회전에서는 프레임 폭·높이가 먼저 교환된다. 그 후 다음 정수 픽셀 영역을 계산한다 (`int`는 양수 소수점 버림).

```text
left   = int(frameWidth  × leftRatio)
top    = int(frameHeight × topRatio)
right  = frameWidth  - int(frameWidth  × rightRatio)
bottom = frameHeight - int(frameHeight × bottomRatio)
```

YOLO 경로는 `전체 버퍼 → 회전 → ROI 1회 → 기존 640×640 Letterbox → 기존 Letterbox 역변환 → ROI offset 복원`이다. CameraX `ImageProxy.cropRect`는 추가 적용하지 않는다. 사용되지 않는 기존 `DetectionResult.mapToOriginalFrame` 확장 함수를 새 경로에서 호출하지 않아 모델 입력 좌표 변환을 이중 적용하지 않는다.

ML Kit도 동일한 `FrameRoi`를 잘라 기존 640×360 입력으로 축소한다. BBox는 아래 식으로 전체 프레임에 복원한다. YOLO·ML Kit가 입력을 함께 사용하는 경우에도 각 비트맵 소유권을 분리하여 analyzer가 재활용한 비트맵을 비동기 ML Kit에서 참조하지 않게 한다.

```text
xFull = roi.left + xMl × roi.width / 640
yFull = roi.top  + yMl × roi.height / 360
```

YOLO와 ML Kit의 출력 좌표계는 같지만 모델이 달라 탐지 객체·BBox 자체가 같다는 의미는 아니다. 중앙 ROI 사용 시에도 ML Kit를 같은 ROI로 맞췄다. Preview 경계선은 BBox와 같은 FIT_CENTER 변환을 사용하고, 탐지 결과 만료나 BBox Overlay OFF에 영향을 받지 않는다. Preview OFF에서는 경계선도 그리지 않는다.

## 비교 가능한 로그

- 헤더 `Recognition ROI`에 적용 모드, 활성화 여부, 네 방향 비율, 좌표계를 기록한다.
- 전체 설정 스냅샷에 `camera.roi.*`, `camera.useCenterSquareCrop`, `yolo.resizeMode`를 기록한다.
- 각 `FRAME`에 실제 회전 후 크기 `frame=WxH`, `rotation`, 정수 픽셀 `roi=[left,top,right,bottom]`를 기록한다. 카메라가 협상한 해상도와 비율 반올림을 추적할 수 있다.
- 분석 도구의 `measurement.roiSettings`, `measurement.observedFrameRois`에 비교 정보를 제공한다. ROI·해상도·회전·Resize·ML Kit·Threads·Preview·진단 설정이 같은 실행끼리 성능을 비교해야 한다.

## 검증 범위

단위 테스트로 OFF·프리셋·중앙 ROI 우선순위·비율 오류·최소 픽셀 영역·홀수 해상도·Letterbox padding을 확인한다. 0/90/180/270도에 해당하는 가로·세로 크기에서 YOLO Letterbox 역변환과 ML Kit 축소 역변환의 전체 프레임 좌표가 일치하는지 검증한다.

Android 계측 테스트는 실제 컬러 비트맵을 각도로 회전하고, Pipeline에 가짜 탐지기를 넣어 픽셀 크롭 위치·한 번의 detector 호출·BBox 전체 프레임 복원·원본 비트맵 생존을 검증한다. 앱은 기존처럼 세로 화면 고정이다. 합성 비트맵의 네 방향 회전 검증을 모든 기기에서의 가로 UI 지원이나 카메라 FOV 일치 검증으로 확대 해석하지 않는다.

실행 결과는 [통합 검증 문서](timer-roi-validation-20261020.md)를 참조한다.
