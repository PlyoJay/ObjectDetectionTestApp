# ARCore 측정 로그 목록·다중 공유·다운로드 내보내기

## 사용 방법

1. 메인 화면에서 **ARCore 거리 측정**을 연다.
2. **측정 시작 → 측정 종료** 또는 **측정 로그 저장**을 누른다. 내부 CSV의 flush/close/drain이 성공한 뒤 공용 다운로드로 자동 내보낸다. 내부 원본은 보존된다.
3. **측정 로그 목록**에서 기존 `files/arcore_depth/`의 모든 CSV를 확인한다. 파일명, 생성 시각, 수정 시각, 크기를 표시하며 수정 시각 최신순이다. 긴 이름과 날짜가 잘리지 않도록 행 높이는 내용에 맞춘다.
4. 개별 항목을 누르거나 **전체 선택 / 선택 해제**를 사용한다. 기록 중·저장 미완료 파일은 목록에는 표시하되 선택할 수 없다.
5. **선택 CSV 공유**는 선택된 내부 원본들을 `ACTION_SEND_MULTIPLE`로 한 번에 공유한다. 기존 **측정 로그 공유** 버튼은 최신 CSV 하나를 `ACTION_SEND`로 공유한다.
6. **선택 내보내기 / 전체 내보내기**로 예전 CSV도 공용 다운로드에 저장할 수 있다. 결과 창은 새 파일·기존 동일 파일·실패 수와 실제 파일명을 표시한다. **내보낸 CSV 공유**로 성공한 공용 파일들을 다시 공유할 수 있다.

삼성 내 파일 앱에서는 **내장 저장공간 → Download → GOTORO → ARCore**에서 확인한다. 앱 표시 경로는 `Download/GOTORO/ARCore/`다. 실제 기기의 파일 관리자 표시와 수신 앱의 공유 동작은 별도 확인 항목이다.

목록은 별도 Activity다. 진입 시 기존 ARCore Activity의 일시정지 동작으로 현재 기록이 종료·저장된다. 새 화면이 열리는 시점까지 저장 중인 파일은 미완료 상태로 보일 수 있으므로 **새로고침**으로 완료 상태를 다시 확인할 수 있다. 목록 화면은 카메라나 Depth 데이터를 취득하지 않는다.

## 변경 파일

| 파일 | 변경 내용 |
| --- | --- |
| `app/src/main/java/com/samin/objectdetection/arcore/DepthLogListActivity.kt` | 목록, 파일 정보, 다중 선택, 전체 선택/해제, 일괄 공유·내보내기와 결과 표시 |
| `app/src/main/java/com/samin/objectdetection/arcore/DepthLogFiles.kt` | 내부 CSV 목록/경로/완료 상태 검증, 파일명 기준 선택 상태 |
| `app/src/main/java/com/samin/objectdetection/arcore/DepthLogSharing.kt` | 단일·다중 공유, FileProvider/MediaStore content URI, ClipData 및 읽기 권한 |
| `app/src/main/java/com/samin/objectdetection/arcore/DepthLogExporter.kt` | drain 이후 내보내기, SHA-256 중복 검사, 동명 충돌 처리, 실패 시 신규 행 정리 |
| `app/src/main/java/com/samin/objectdetection/arcore/MediaStoreDepthDownloads.kt` | MediaStore.Downloads insert/query/copy/publish/delete 어댑터 |
| `app/src/main/java/com/samin/objectdetection/arcore/DepthCsvRecorder.kt` | CSV 형식을 바꾸지 않는 `.incomplete` 상태 파일, close 성공 후 완료 처리 |
| `app/src/main/java/com/samin/objectdetection/arcore/ArCoreDepthActivity.kt` | 목록 진입 버튼, 자동 내보내기와 상태/오류 표시, 기존 단일 공유 유지 |
| `app/src/main/AndroidManifest.xml` | 앱 내부 로그 목록 Activity 등록. 저장소 권한 추가 없음 |
| `app/src/test/java/com/samin/objectdetection/arcore/DepthLogFilesTest.kt` | 목록·날짜/크기·정렬·선택·경로 경계·기록 종료/실패 검증 |
| `app/src/test/java/com/samin/objectdetection/arcore/DepthLogExporterTest.kt` | 종료 대기·중복·충돌·동시 요청·쓰기/close/publish/조회/정리 실패 검증 |
| `docs/arcore-depth-test.md` | 로그 관리 확장 문서 연결 |
| `docs/arcore-depth-log-management.md` | 이 문서 |

YOLO, ML Kit, CameraX, 지면 기반 거리 추정, ARCore Depth 취득·계산·표시 필터 코드는 수정하지 않았다. 기존 CSV의 헤더·열·행 포맷도 그대로다. 내부 CSV 삭제나 기존 공용 파일 덮어쓰기 기능은 추가하지 않았다. 저장소 권한, Gradle 의존성, SDK 버전도 추가/변경하지 않았다.

## 기록 완료와 내부 원본 보존

새 기록을 시작하기 전에 `<CSV 파일명>.incomplete` 파일을 같은 내부 폴더에 만든다. CSV writer가 수용한 모든 행의 flush, close, drain이 성공한 뒤에만 이 상태 파일을 제거하고 완료 Future를 성공시킨다. 쓰기·닫기·완료 상태 저장에 실패하면 상태 파일과 내부 CSV를 남긴다. 앱이 강제 종료되어도 미완료 표식이 남으므로 다음 실행에서 공유/내보내기를 차단한다.

상태 파일은 CSV 내용 밖에 있으므로 기존 로그 파서는 변경할 필요가 없다. 이전 버전에서 작성되어 상태 파일이 없는 CSV는 기존 완료 파일로 취급해 목록·공유·내보내기를 지원한다. 예전 버전에서 강제 종료되어 생긴 불완전 파일인지는 과거 완료 메타데이터가 없어서 소급 판별할 수 없다.

목록에서 선택했더라도 공유/내보내기 직전에 파일 존재, 내부 폴더 경계, CSV 확장자, 읽기 가능 여부와 완료 표식을 다시 확인한다. 선택 상태는 행 번호 대신 파일명으로 유지하므로 새 파일 추가나 정렬 변경으로 다른 로그가 선택되지 않는다.

공유는 원본 파일을 읽기 전용 content URI로 제공한다. FileProvider에 기존 등록된 `files/arcore_depth/` 범위를 재사용하며 `ClipData`에 모든 URI를 넣고 `FLAG_GRANT_READ_URI_PERMISSION`을 부여한다. 실제 공유 대상은 Android 공유 화면에서 사용자가 선택한다.

## 공용 다운로드 저장

`MediaStore.Downloads`의 기본 외부 볼륨을 사용한다. 앱의 minSdk는 33이므로 Android 10 이상 Downloads API 범위 안에 있으며 `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`, `MANAGE_EXTERNAL_STORAGE`를 추가하지 않는다.

내보내기 순서:

1. 내부 close/drain Future의 성공을 기다린다. 실패하면 MediaStore에 접근하지 않고 저장 오류를 반환한다.
2. 내부 완료 상태와 크기/수정 시각을 확인하고 SHA-256을 계산한다.
3. 대상 폴더에 이미 공개된 같은 파일명 계열의 내용을 비교해 중복 여부를 확인한다.
4. 신규 URI를 insert하면서 `MIME_TYPE=text/csv`, `RELATIVE_PATH=Download/GOTORO/ARCore/`, `IS_PENDING=1`을 지정한다.
5. 내부 원본을 바이트 그대로 복사하고 출력 스트림을 닫는다. 복사 바이트 수/해시와 내부 크기·수정 시각·완료 상태를 다시 확인한다.
6. 성공한 파일만 `IS_PENDING=0`으로 공개하고, MediaStore가 최종 확정한 파일명을 조회해 표시한다.
7. 실패하면 이번 작업이 새로 만든 MediaStore URI만 정리한다. 내부 CSV와 원래 있던 다운로드 파일은 건드리지 않는다. 정리까지 실패한 경우 그 오류도 표시한다.

자동 내보내기는 Activity 종료와 독립된 작업으로 수행한다. 카메라 종료는 CSV drain까지만 기다리고 다운로드 복사 완료를 기다리며 카메라 자원을 붙잡지 않는다. 명시적인 저장은 화면 상태와 Toast로 파일명/위치를 보여 주고, 일시정지/종료 중 자동 저장 결과도 완료 후 알린다. 목록의 일괄 내보내기는 파일별 결과를 표시하고 실패한 파일을 남겨 재시도할 수 있다.

Scoped Storage 및 공유 방식의 근거: [Android MediaStore/공유 저장소 문서](https://developer.android.com/training/data-storage/shared/media), [MediaStore.Downloads](https://developer.android.com/reference/android/provider/MediaStore.Downloads), [Android 다중 콘텐츠 공유 문서](https://developer.android.com/develop/ui/compose/sharing/send#share-multiple-pieces-of-content).

## 중복·이름 충돌 정책

- **같은 파일명 계열 + SHA-256 동일**: 기존 공용 URI를 재사용하고 “이미 내보낸 동일 파일”로 표시한다. 내보내기 객체나 Activity를 새로 만들어도 MediaStore에서 확인하므로 앱 재실행 후에도 중복 생성을 피한다.
- **이름은 같지만 내용이 다름**: 기존 파일을 덮어쓰지 않고 `원래이름__<내용 해시 앞 12자리>.csv`를 요청한다. 해시는 전체 SHA-256으로 내용 비교하며 짧은 접미사만으로 동일 파일이라고 판단하지 않는다.
- MediaStore가 충돌을 해결해 `원래이름 (1).csv`처럼 바꾼 파일도 다시 검색·비교한다. 읽을 권한이 없는 타 앱 파일은 덮어쓰거나 삭제하지 않고 새 이름으로 보존한다.
- 기존 공용 파일 조회/읽기 중 일반 I/O 오류가 발생하면 비교하지 못한 파일을 무조건 중복 저장하지 않고 오류를 보고한다. 나중에 다시 시도할 수 있다.
- 자동·수동·다른 Activity의 내보내기는 앱 프로세스 내에서 직렬화해 동시에 같은 파일을 새로 insert하는 경합을 막는다.
- 별개의 이름으로 보관한 CSV는 내용이 우연히 같아도 각각의 로그 파일로 유지한다.

프로세스 강제 종료나 저장소 제공자 장애로 정리하지 못한 pending 항목은 운영체제의 관리 대상이며 일반 앱에 완성된 파일로 공개하지 않는다. 강제 종료·앱 재설치·권한 변경까지 포함한 MediaStore 동작은 실제 기기 확인 대상이다.

## 자동 검증과 실제 기기 확인

실행 명령:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
```

신규 단위 테스트는 다음을 확인한다.

- 기존 CSV 포함 목록, 생성/수정 날짜·크기, 최신순 정렬, 폴더 밖 파일 배제.
- 개별/전체 선택·해제, 목록 정렬 변경 후 선택 유지, 미완료 파일 제외.
- writer가 실제 flush/close를 마칠 때까지 공유/내보내기 차단, 디스크 쓰기 실패 후 미완료 상태와 원본 보존.
- 종료 Future 성공 전 MediaStore 접근 없음, 종료 실패 시 내보내기 없음.
- 복사 내용 동일성, 출력 close 후에만 공개, 반복 요청·새 exporter·동시 자동/수동 요청의 중복 방지.
- 동명 다른 내용, 제공자의 파일명 변경, 읽기 권한이 없는 타 앱 파일 충돌 처리.
- 파일 누락/변경, 조회·읽기·쓰기·닫기·공개·정리 오류에서 내부 원본과 기존 공용 파일 보존.

MediaStore 동작은 단위 테스트의 가짜 저장소로 절차·실패 정책을 검증한다. 실제 MediaStore 제공자, 삼성 내 파일 앱, 수신 앱의 URI 권한 처리에 대한 계측/실측 검증은 수행하지 않는다. 단말에서 아래 항목을 확인해야 한다.

2026-10-27 최종 자동 검증 결과:

- **단위 테스트 235개 통과**: 43개 suite, 실패/오류 0. 기존 220개와 새 로그 관리 테스트 15개(목록/완료 상태 5개, 내보내기 10개)를 포함한다.
- **Debug 빌드 성공**: `:app:assembleDebug`, `:app:assembleDebugAndroidTest` 모두 성공. 기기에 설치하거나 실행하지는 않았다.
- **Lint 성공**: 오류 0개, 경고 239개. 기존 경고와 프로그램 방식 UI의 문자열/locale 등 경고를 포함하며 전체 경고 정리는 작업 범위에 넣지 않았다.
- 최종 명령은 `BUILD SUCCESSFUL`(1분 29초). `git diff --check` 통과.
- 저장소 권한 추가 없음. YOLO·ML Kit·CameraX·ARCore Depth 계산 소스 변경 없음. 내부 CSV 원본 및 기존 공용 파일 삭제/덮어쓰기 없음.

단말 확인 항목:

1. 여러 측정 CSV의 파일명·날짜·크기와 다중 선택/전체 선택/해제가 올바른지.
2. 이메일·메신저·파일 전송 앱에서 여러 CSV 첨부와 실제 내용 읽기가 가능한지.
3. 삼성 내 파일의 `Download/GOTORO/ARCore/`에 완료된 CSV만 나타나는지.
4. 같은 CSV를 반복 내보내도 파일 수가 늘지 않는지, 다른 내용의 동명 파일은 모두 남는지.
5. 저장소 부족·권한/공급자 오류·앱 강제 종료 때 오류 표시와 내부 원본 보존이 되는지.
6. 로그 목록에서 돌아온 후 기존 ARCore 화면과 객체 인식 기능이 정상 동작하는지.

커밋과 푸시는 수행하지 않는다.
