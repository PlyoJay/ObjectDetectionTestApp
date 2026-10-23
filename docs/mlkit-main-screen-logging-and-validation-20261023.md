# 메인 화면 ML Kit 기록 및 실제 SDK 검증

작성일: 2026-10-23 (Asia/Seoul). 기준 HEAD: `2f9517970c92d285a8b260a3c2149ea7371cbbdd`. 시작 시 `LogShareManager.kt`에 있던 미커밋 공유 함수 변경은 보존하고 재사용했다. 커밋/푸시는 하지 않았다.

사용자 보고 기준: 1500ms에서 SDK 76회/검출 0, 300ms에서 209회/검출 0. 두 조건 모두 SUCCESS이며 lease 반환 후 빈 결과가 Overlay에 전달되었다. 이 보고를 실제 양성 검출이나 입력 영상 정상의 증거로 해석하지 않는다.

## 1. 변경 파일 및 주요 수정

소스 경로는 `app/src/` 기준이다.

| 파일 | 내용 |
|---|---|
| `main/.../MainActivity.kt` | 메인 기록 토글/공유/경과 시간/결과 안내, 임시 진단 활성화, P0와 파일 기록 상호 배제 |
| `main/.../ui/MainScreenView.kt` | ML Kit 버튼 2개와 상태, 균등 폭 하단 버튼, 별도 footer/카메라 영역, 짧은 화면 footer 스크롤 |
| `main/.../mlkit/MlKitDiagnosticLogRecorder.kt` | 기존 `start`에 명시적 `temporaryEnable` 옵션 추가; source별 메모리 gate와 헤더 메타데이터; 새 파일 시스템 없음 |
| `main/.../mlkit/MlKitDiagnostics.kt` | 참조를 보관한 SDK callback까지 적용하는 runtime gate; OFF이면 지연 계산/이미지 샘플링 생략 |
| `main/.../settings/MlKitLogControls.kt` | 적용 OFF에서 메인 임시 기록이 가능한 점 안내; 기존 설정 기록/보기/삭제/공유 유지 |
| `test/.../mlkit/MlKitDiagnosticsTest.kt`, `MlKitDiagnosticLogRecorderTest.kt` | 임시 opt-in, source별 gate/재시작/설정 불변, 중지 후 callback 억제 |
| `androidTest/.../mlkit/MlKitMainScreenLoggingTest.kt` | 실제 Main/카메라 경로의 Preview ON/OFF, 설정 OFF, 반복/중복 요청, P0, 설정 동기화, 재생성/종료/재실행 |
| `androidTest/.../ui/MainScreenLoggingLayoutTest.kt` | 작은 320×480dp에서 버튼 폭/경고 영역/Preview ON/OFF 검증 |
| `androidTest/.../mlkit/MlKitModeComparisonTest.kt` | 실제 ML Kit A/B/C/D 비교와 선택적 입력 PNG 저장 |
| `androidTest/assets/mlkit_validation/` | Google 공식 신발 검출 예제 및 출처/라이선스/SHA-256 |

`MlKitObjectDetector.kt`, `MlKitInputBuffer.kt`, CameraX 변환, DetectionPipeline, YOLO/모델/클래스/NMS/ROI 설정값/경고 정책 및 P0 FRAME/LATENCY 구현은 수정하지 않았다. STREAM_MODE/multiple objects/1500ms/640×360 기본 동작을 유지했다.

## 2. UI 전후 및 측정 정책

변경 전에는 Preview 전체 위에 하단 2개 버튼 행이 떠 있었고, ML Kit 기록은 설정 화면에서 Diagnostics ON을 적용한 뒤 시작해야 했다.

변경 후 메인 하단에 **ML Kit 로그 시작/종료**, **ML Kit 로그 공유**, **ML KIT REC mm:ss/누락/저장 상태**가 표시된다. 기존 녹화·성능 로그·빠른 설정 버튼과 같은 Button 스타일을 사용하고, 각 행의 버튼 폭을 균등 배분한다. 카메라/Overlay/경고 영역과 footer를 분리하여 경고 아래에 버튼을 덮지 않는다. 짧은 창이나 큰 글꼴에서는 footer 높이를 창의 절반 이하로 제한하고 세로 스크롤로 모든 버튼에 접근할 수 있게 한다. Preview OFF에도 같은 footer를 유지한다. 카메라 영역의 FIT_CENTER와 Overlay 좌표 계산 방식은 그대로다.

파일 작업 중에는 토글/공유 버튼을 비활성화하고 Job guard로 재진입을 막는다. writer의 flush/close가 끝난 후 저장 완료를 표시한다. 크기/시간 한도 또는 오류로 종료되는 경우도 1초 상태 갱신에서 반영한다. 상태 확인 자체는 파일 I/O를 하지 않는다.

**메인 UI에서 P0 성능 로그와 ML Kit 파일 기록의 동시 시작을 막는다.** P0 측정 중 진단 비용을 바꾸면 비교 조건이 달라지므로 양쪽 시작 경로에 guard를 둔다. ML Kit 기록 중에는 성능 로그 시작을, 성능 로그 중에는 ML Kit 토글/공유를 비활성화한다. 안내 문구도 표시한다. writer 자체는 독립이지만 UI 정책은 측정 공정성을 우선한다. 명시적으로 적용한 Diagnostics ON의 기존 Logcat 동작은 유지되므로, 성능 비교에서는 그 적용 설정도 동일해야 한다. 기존 P0 출력 형식과 기본값은 변경하지 않는다.

## 3. 기록 방법 및 생명주기

1. 앱 실행 후 메인 하단의 **ML Kit 로그 시작**을 누른다. 설정 화면 진입이나 Diagnostics ON 저장은 필요 없다.
2. `ML KIT REC mm:ss`를 확인하며 객체를 비춘다.
3. **ML Kit 로그 종료**를 누르고 저장 완료를 기다린다.
4. **ML Kit 로그 공유**를 눌러 Android 공유 메뉴에서 받을 앱을 선택한다. 기록 중 공유를 누르면 중지/flush/close 후 공유한다.
5. 필요하면 다시 시작한다. 이전 파일 보기/삭제는 기존 설정 영역에서도 가능하다.

### 설정을 바꾸지 않는 임시 활성화

- 적용 설정 snapshot은 기존 `AppSettingsCodec.snapshotLines(appSettings)` 그대로 저장한다. 적용 OFF이면 헤더에도 `debug.enableMlKitDiagnostics=false`가 남으며 별도로 `recording.temporaryDiagnostics=true`, `recording.effectiveDiagnostics=true`를 기록한다.
- MainActivity당 하나의 진단 객체를 두고, 유효 상태는 **명시적 적용 ON OR 해당 source의 현재 파일 기록 활성**으로 계산한다. Analyzer가 읽는 nullable 참조는 volatile이고, 이미 SDK가 캡처한 객체도 event 시점에 이 gate를 확인한다. Overlay에는 메인 스레드에서 참조를 전달한다.
- 파일 중지 admission을 닫는 즉시 임시 gate도 꺼진다. 파일 writer는 수락한 행을 순서대로 drain한 뒤 END/flush/close한다. 이미 수락한 이벤트와 종료가 파일에 경합해 END 뒤에 쓰는 상황이 없다. SDK Task 자체를 취소하지 않으며 lease는 기존 SDK 완료/Job 종료 규칙을 따른다.
- 기록 경계에 걸친 SDK 요청은 PROCESS_REQUEST 또는 TASK_COMPLETE 한쪽만 파일 구간에 포함될 수 있다. 이것은 기록 구간 밖 이벤트이며 큐 손실과 구분한다. frame/session/requestIndex는 기존 값을 유지한다.
- 중지 후 적용 OFF이면 임시 진단을 해제하고, 적용 ON이면 기존 Logcat 진단을 계속한다. SharedPreferences 저장, 앱 재시작 또는 탐지 설정 변경을 하지 않는다.
- MainActivity 재생성은 이전 파일을 종료하며 임시 활성화를 자동 복구하지 않는다. 설정 Activity 전환/재생성은 기존 process recorder의 상태를 공유한다. 정상 앱 종료는 기존 Analyzer/SDK drain 후 source 종료로 파일을 닫는다. 강제 종료는 기존 `.open` 복구와 UNKNOWN 손실 표기를 유지한다.
- 기존 TXT/END/droppedLines/FileProvider/UTF-8/보관 정책, 단일 파일 5MiB/30분, 각 저장소 20개/50MiB/7일, bounded queue 256개를 유지한다. Analyzer·SDK callback·Overlay onDraw에서 파일 I/O를 하지 않는다.

## 4. Detector 및 실제 입력 코드 검토

| 단계 | 확인한 구현과 해석 |
|---|---|
| CameraX | RGBA_8888, KEEP_ONLY_LATEST. `toBitmapSafe()`에서 CameraX 1.3.4 `toBitmap()`을 사용하여 row stride/색 변환을 처리한다. 직접 YUV를 ARGB로 오인해 복사하는 경로가 아니다. |
| 방향 | `imageInfo.rotationDegrees`를 Matrix로 픽셀에 적용한 뒤 원본 변환 Bitmap을 recycle한다. Main에 전달된 width/height는 upright 기준이다. |
| ROI | ML Kit 준비를 YOLO pipeline 실행 전에 호출한다. YOLO에서 만든 ROI를 다시 자르는 것이 아니라 upright 전체 프레임에 `FrameRoi.calculate()`를 한 번 적용한다. 비율과 좌표 기준은 YOLO와 공유한다. |
| 640×360 | 재사용 ARGB_8888 target을 매번 지우고 crop 내용을 필터링하여 전체 덮어쓴다. 비율 유지나 letterbox가 아니라 독립 x/y scale의 stretch다. ROI crop 중간 Bitmap은 그리기 후 recycle한다. |
| 소유권 | 640×360 target은 카메라/YOLO의 source와 독립이다. 한 lease만 허용한다. SDK await는 NonCancellable로 terminal 상태까지 기다리고, Job completion에서 lease를 반환한다. `close()`도 lease 중이면 recycle을 미룬다. |
| InputImage | `InputImage.fromBitmap(bitmap, 0)`. 이미 upright이므로 0은 코드상 일관된다. 픽셀 정규화나 채널 교환을 wrapper가 추가하지 않는다. |
| Detector | `object-detection:17.0.2`, STREAM_MODE, enableMultipleObjects, classification 기본 OFF. SDK를 한 번 만들고 여러 요청에서 유지한다. 앱 변경으로 SINGLE_IMAGE_MODE나 다른 크기를 적용하지 않았다. |
| SDK 결과 | 성공 Task의 목록 크기 `sdkCount`는 wrapper 매핑/Overlay 이전 값이다. wrapper는 Rect/trackingId만 복사하며 confidence/NMS/클래스 필터로 객체를 제거하지 않는다. |
| 0 vs 오류 | TASK_COMPLETE SUCCESS/sdkCount=0은 정상 완료한 빈 목록이다. ERROR/CANCELLED는 별도 outcome과 sdkCount=-1이며 DETECT_ERROR도 기록된다. wrapper의 emptyList만 보면 둘이 섞이므로 Task 진단을 기준으로 구분한다. |

Google 문서는 STREAM_MODE의 초기 호출에서 불완전한 결과가 가능하고 SINGLE_IMAGE_MODE에서는 BBox 확정 후 결과를 반환한다고 설명한다. multiple objects와 classification은 별도 옵션이다. 또한 이 SDK는 salient object를 찾는 모델이며, YOLO의 9개 도로 클래스 검출기와 같은 의미로 볼 수 없다. [Android 공식 가이드](https://developers.google.com/ml-kit/vision/object-detection/android), [기능 및 입력 전처리](https://developers.google.com/ml-kit/vision/object-detection).

단, **코드 일관성만으로 실카메라의 색상·내용이나 0개 원인을 확정할 수 없다.** 76회/209회 연속 0을 단순한 초기 몇 프레임 현상이라고 단정하지 않는다. 예를 들어 upright 1080×1440에 top=.2/left=.1/right=.1이면 ROI는 864×1152이고, 640×360 stretch의 x/y 배율은 약 .741/.313으로 서로 다르다. 형상 변형은 비교 대상이지만 원인으로 입증되기 전에는 고치지 않는다.

## 5. 실제 SDK 비교

실행기: `MlKitModeComparisonTest.officialPositiveImageAcrossOriginalRoiStretchAndStream`.

Google 공식 예제는 신발 이미지에서 4개 객체 결과를 제시한다. fixture는 원본 JPG를 변경하지 않고 androidTest assets에만 넣었고 SHA-256과 출처를 함께 기록했다. 현재 기기/SDK에서 같은 개수를 보장하는 oracle로 사용하지 않는다. 이 테스트는 **실제 ObjectDetection client**를 호출하며 FakeDetector를 사용하지 않는다.

| 조건 | 입력 | 실행 |
|---|---|---|
| A | SINGLE_IMAGE_MODE + 원본 640×427 | 같은 client로 3회 |
| B | SINGLE_IMAGE_MODE + crop ROI 512×342 (원본 종횡비 유지, 별도 resize 없음) | 3회 |
| C | SINGLE_IMAGE_MODE + 기존 MlKitInputBuffer 640×360 ROI | 3회 |
| D | STREAM_MODE + C와 동일한 640×360 ROI | 8회, 완료 후 다음 요청 전 1500ms 대기 |

비교용 ROI는 사용자 기기의 보고값 top=.20/left=.10/right=.10을 테스트에만 명시했다. 앱 ROI 기본값을 바꾸지 않았다. 매 요청마다 outcome, sdkCount, 입력 크기, SDK 대기 소요 시간(ms), 모든 BBox/trackingId 또는 오류를 TSV에 기록한다. SDK 오류를 빈 성공으로 바꾸지 않고 테스트 실패로 표시한다. A에서 실제 양성이 한 번도 없으면 양성 검증도 실패로 판정한다.

`-e mlkitSaveInputs true`를 **명시한 테스트 실행에서만** A/B/C/D PNG를 각각 한 장 저장한다. 기본 앱과 기본 테스트 실행은 이미지를 자동 저장하지 않는다. 테스트 출력은 앱 내부 `files/mlkit_mode_comparison/results.tsv`와 선택적 PNG이며, PC의 `build/mlkit-main/`으로 추출하여 보존한다.

### 실제 실행 결과 (Pixel_7 AVD / Android API 37 / x86_64 / 16KiB)

| 조건 | sdkCount (요청 순) | SDK 대기 시간 ms (요청 순) | 오류 |
|---|---|---|---|
| A | 4, 4, 4 | 5486.41, 250.41, 545.87 | 없음, 모두 SUCCESS |
| B | 4, 4, 4 | 1117.80, 333.58, 489.43 | 없음, 모두 SUCCESS |
| C | 4, 4, 4 | 1565.06, 56.09, 297.01 | 없음, 모두 SUCCESS |
| D | 0, 0, 0, 0, 0, 0, 0, 0 | 3428.62, 338.23, 132.13, 317.87, 48.85, 1651.43, 169.90, 196.80 | 없음, 모두 SUCCESS |

좌표는 각 **입력 Bitmap** 기준 `left,top,right,bottom`이다. A/B/C는 각 조건의 세 요청에서 동일한 네 BBox를 반환했다. SINGLE_IMAGE_MODE의 trackingId는 모두 null이고, D는 BBox가 없다.

| 조건 | BBox 전체 목록 |
|---|---|
| A | `[302,93,468,388]`, `[444,80,616,297]`, `[0,114,318,323]`, `[189,78,335,225]` |
| B | `[238,10,402,309]`, `[0,28,262,237]`, `[381,1,511,202]`, `[130,0,283,138]` |
| C | `[300,9,504,323]`, `[0,30,331,250]`, `[475,3,639,214]`, `[163,1,352,145]` |
| D | 없음 |

원본은 방향과 RGB 색상이 자연스러운 신발 사진이다. B는 가장자리가 잘린 정상 crop이고 C는 수평으로 더 늘어난 stretch임을 PNG로 직접 확인했다. **C/D PNG의 SHA-256은 둘 다 `7b1fdd0d09a87d9d35a8ffbe7d9c14083f876580cdd6056862d86d494b0acd83`**이다. SDK 요청이 끝날 때까지 같은 lease를 유지했으며 source/crop/target을 recycle하거나 바꾸지 않았다.

이 환경의 이 이미지에서는 **실제 재사용 Bitmap + SINGLE_IMAGE_MODE에서 양성을 확인했고, 같은 픽셀의 STREAM_MODE에서 0개를 재현했다.** 따라서 빈 Overlay만의 문제나 이 fixture의 Bitmap이 무조건 검출 불가능하다는 설명은 맞지 않는다. 다만 정적 이미지 반복/SDK 버전/실행 순서/기기별 STREAM 처리 중 무엇이 원인인지는 아직 구분하지 못했다. 이 결과로 사용자 실기기의 모든 장면, 특히 세로 영상의 더 큰 stretch 영향까지 배제하지 않는다. 운영 모드를 바꾸거나 SDK 인식 문제가 해결됐다고 판정하지 않는다.

시간은 초기화/SDK 스케줄링/에뮬레이터 부하를 포함한 단일 요청 wall latency이며 모델 kernel 성능이나 실기기 평균/P95 비교 자료가 아니다. D는 **완료 후** 1500ms를 대기하므로 실제 요청 시작 간격은 처리 시간만큼 더 길다. 운영 앱의 시작 시각 기준 throttle과 동일한 카메라 스트림 재현이라고 주장하지 않는다.

원시 결과: 로컬 `build/mlkit-main/sdk-inputs/results.tsv`, `sdk-comparison.txt`. 네 입력 PNG도 `build/mlkit-main/sdk-inputs/`에 보존했다. 검출 비교 테스트 1개는 통과했다(오류 없음/A 양성 확인). **D가 0이라는 사실은 통과 여부와 별개로 남은 문제다.**

## 6. 테스트 결과

- 전체 JVM: 184개 통과. 기존 182개 + 임시 opt-in/gate 회귀 2개.
- Python 성능 로그 분석: 26개 통과.
- Android 통합: **서로 다른 35개 테스트 최종 통과**. SDK 비교 1개, 메인 UI 2개, 작은 화면 layout 1개, 기존 설정/파일 공유/ML Kit wrapper·Bitmap/Overlay·YOLO 31개.
- 최초 묶음 34개 중 메인 UI 2개가 에뮬레이터 System UI/런처 ANR 및 기존 16KiB 호환성 안내가 가린 상태에서 `RootViewWithoutFocusException`으로 실패했다. 나머지 32개는 통과했다. 해당 시스템 창을 정리하고 **코드를 변경하지 않은 채 메인 2개를 재실행하여 모두 통과**했다. SDK 비교 1개도 별도 통과했다. 기존 네이티브 라이브러리를 변경하거나 호환성 안내를 영구 억제하지 않았다.
- 일반 applicationId `com.samin.objectdetection`의 debug APK/AndroidTest APK 빌드 및 전체 JVM 184개 재검증도 성공했다. 최종 산출물은 `app/build/outputs/apk/debug/app-debug.apk`이다. 기존 trace API deprecation 경고는 남아 있다.

| 항목 | 결과 및 범위 |
|---|---|
| 설정 OFF 원터치 / snapshot 불변 / 임시 해제 | 통과. 헤더의 적용 OFF와 임시 true 구분, SharedPreferences 전체 적용 설정 동일 |
| Preview ON/OFF 기록 / SDK 이벤트 / 시간 표시 | 통과. 실제 카메라 경로의 PROCESS_REQUEST/TASK_COMPLETE/sdkCount, REC 시간 갱신 확인 |
| 종료 / flush / END / droppedLines / 공유 | 통과. 완료 TXT를 FileProvider content URI로 읽고 동일 내용 확인. chooser Intent는 monitor로 검증하고 외부 전송은 차단 |
| 반복 시작 / 중복 요청 / 저장 중 비활성화 | 통과. 준비 중 연속 호출 시 한 기록, 다음 시작은 고유 파일명 |
| 설정 화면 동기화 | 통과. 적용 OFF를 유지한 채 메인 기록을 설정에서 확인·중지하고 메인 상태 반영 |
| Main 재생성 / 정상 종료 / 재실행 | 통과. 이전 기록 종료, 새 실행의 임시 opt-in 미복구 |
| P0 성능 로그 | 통과. 양쪽 시작 상호 배제, 파일 기록 종료 후 P0 FRAME/LATENCY 생성 유지 |
| YOLO / Bitmap / Overlay | 통과. 두 모델, 실제 parser/NMS/ROI 복원, 입력 재사용 픽셀, SDK callback/취소, BBox transform/stale/clear 회귀 |
| 작은 화면 | 320×480dp 측정에서 버튼 너비/위치, 경고가 footer 위에 있는지 Preview ON/OFF 모두 통과 |
| 실제 SDK 양성 및 A/B/C/D | A/B/C 양성 확인. D는 SUCCESS/0 상태로 남아 있음 |
| 실기기 / 외부 앱 실제 수신 / OS kill·저장소 오류 전체통합 / 큰 글꼴·다른 창 크기 | 미검증. 기존 오류 주입 JVM 테스트와 구분 |

Android 비교/통합은 원본 앱 보호를 위해 ignored init script로 applicationIdSuffix `.mlkitmain`을 주입하고, 읽기 전용 Pixel_7 AVD 세션에서 수행한다. 운영 applicationId/빌드 설정은 변경하지 않는다. 연결된 실기기는 없다.

로컬 실행 출력: `build/mlkit-main/sdk-comparison.txt`, `integration.txt`(최초 실패 포함), `main-ui-rerun.txt`. 테스트 전용 패키지/임시 에뮬레이터는 검증 후 정리했다. 기본 package APK를 설치해 사용자 원본 앱을 덮어쓰는 작업은 하지 않았다.

재실행 예시:

```powershell
./gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --offline
.venv/Scripts/python.exe -m unittest discover -s tools -p 'test_*.py'
# 검증 전용 suffix APK 설치 후:
adb shell am instrument -w -r -e class com.samin.objectdetection.mlkit.MlKitModeComparisonTest -e mlkitSaveInputs true com.samin.objectdetection.mlkitmain.test/androidx.test.runner.AndroidJUnitRunner
```

## 7. 남은 문제와 다음 테스트

- SDK 양성은 실제 SDK 테스트 결과로만 판단한다. Bitmap/콜백/좌표 Fake 테스트는 처리 경로 검증으로만 사용한다.
- 공식 fixture의 양성이 확인되어도 사용자 실카메라 0개 증상의 원인이 해결됐다는 의미는 아니다. 같은 장면의 원본과 실제 ML Kit 입력을 명시적으로 한 장 확보해 네 조건을 실기기에서 비교해야 한다.
- 다음 비교는 같은 사진으로 D→C→D 순서를 바꾸고, STREAM 요청 간격/반복 수를 테스트에서만 달리하여 초기화·tracking 지속성·호출 순서 영향을 분리하는 것이다. 실제 세로 프레임의 원본/ROI/stretch도 비교한다. SDK 버전/기기별 차이를 확인한 뒤에만 기본 모드나 입력 형식 변경을 별도 결정한다.
- 실기기 Preview ON/OFF, 기종별 화면 크기/큰 글꼴, Android 공유 대상 앱의 실제 수신, OS 강제 종료/메모리 회수/저장 공간 부족은 별도 확인 대상이다. 구현된 ERROR/복구 단위 테스트를 이 전체 실기기 검증으로 대체하지 않는다.
- P0와 ML Kit 파일 기록의 동시 실행은 메인 UI에서 지원하지 않는다. 비교 측정은 두 기록을 순서대로 수행하고 Diagnostics 적용값/Preview/ROI/모델/스레드/장면도 동일하게 맞춘다. 실기기 성능 개선 효과는 측정하지 않았다.
