# P0 성능 회귀 검증용 3분 A/B/A 테스트 준비

작성일: **2026-10-22, Asia/Seoul**  
저장소: D:/develop/Projects/TestProjects/ObjectDetection  
참고: [outputParsing 회귀 분석](yolo-output-parsing-regression-analysis-20261022.md)

**준비 완료:** P0 이전 A와 이후 B를 별도 Git worktree에서 동일 환경의 debug APK로 빌드했다. APK 서명, 포함 모델/네이티브 라이브러리, 해석된 의존성까지 대조했다. **기기 명령·설치·데이터 초기화·실제 A/B/A 측정은 실행하지 않았다.** 아래 설치 명령은 사용자가 확인 후 실행할 절차다.

원본 checkout의 앱 소스·모델·labels·기본 설정·기존 보고서/로그는 변경하지 않았다. 작업 시작 시 존재하던 미추적 회귀 분석 문서도 보존했다. 커밋·푸시하지 않았다. 새 빌드 산출물은 원본 저장소의 별도 build 하위 폴더에 보관하며, 원본 app/build의 기존 APK는 덮어쓰지 않았다.

## 1. A/B 버전 및 산출물

| 구분 | A: P0 이전 | B: P0 이후 |
|---|---|---|
| Git commit | 53560e44e2b01c6d3bee88b44147e90a7dc2261d | 8fdc17549e1a1dc0bec16eae2b50521bce42f07b |
| 관계 | B의 직접 부모 | 현재 원본 checkout HEAD |
| worktree | C:/Users/SAMIN/.codex/worktrees/p0-aba-a-53560e4/ObjectDetection | C:/Users/SAMIN/.codex/worktrees/p0-aba-b-8fdc175/ObjectDetection |
| Git 상태 | detached HEAD, 앱/추적 파일 변경 없음 | detached HEAD, 앱/추적 파일 변경 없음 |
| 빌드 결과 | BUILD SUCCESSFUL / 2분 9초 | BUILD SUCCESSFUL / 1분 32초 |
| 실행 태스크 | 38개 모두 실행 | 38개 모두 실행 |
| APK 크기 | 220,529,865 bytes | 220,595,401 bytes |

빌드 시간은 호스트 캐시/컴파일 등의 영향을 받으며 앱의 성능 비교 결과가 아니다. 이번 두 빌드 시간 차이의 개별 원인은 측정하지 않았다. A는 당시 배포 APK 자체가 아니라 지정 커밋으로 **이번에 다시 빌드한 소스 재현 APK**다.

준비 폴더: **D:/develop/Projects/TestProjects/ObjectDetection/build/aba-p0-20261022/**

| 파일 | 용도 |
|---|---|
| [ObjectDetectionTestApp-P0-A-53560e4-debug.apk](../build/aba-p0-20261022/ObjectDetectionTestApp-P0-A-53560e4-debug.apk) | A1 및 A2에서 **똑같은 파일** 사용 |
| [ObjectDetectionTestApp-P0-B-8fdc175-debug.apk](../build/aba-p0-20261022/ObjectDetectionTestApp-P0-B-8fdc175-debug.apk) | B에서 사용 |
| [provenance.json](../build/aba-p0-20261022/provenance.json) | 커밋·APK/모델 해시·환경·서명·검증 상태 |
| [A-environment.json](../build/aba-p0-20261022/A-environment.json), [B-environment.json](../build/aba-p0-20261022/B-environment.json) | 실제 Gradle JVM/SDK/도구 및 의존성 144개 좌표·파일 SHA |
| [A-build.log](../build/aba-p0-20261022/A-build.log), [B-build.log](../build/aba-p0-20261022/B-build.log) | --info 빌드 원문 |
| A/B-gradle-version.txt, A/B-output-metadata.json | Gradle 버전 및 variant/package 산출물 정보 |
| A/B-signature.txt, A/B-manifest.txt, A/B-badging.txt | 서명 검증과 실제 APK manifest 검사 |
| [apk-contents.json](../build/aba-p0-20261022/apk-contents.json) | 포함 모델/labels 및 네이티브 라이브러리 16개 SHA |
| [SHA256SUMS.txt](../build/aba-p0-20261022/SHA256SUMS.txt) | 준비 산출물 무결성 확인 |
| [summarize-frame-stages.py](../build/aba-p0-20261022/summarize-frame-stages.py) | 기존 분석기에 없는 outputParsing/post 등의 보조 집계 |

이 폴더는 기존 /build ignore 규칙의 로컬 산출물이다. 장기 보존할 때는 폴더 전체를 별도 위치로 복사한다. 원본 저장소에서 clean을 실행하면 지워질 수 있으므로 시험 전에 보관한다. worktree는 재빌드에 사용할 수 있게 유지했다.

### APK 및 모델 SHA-256

~~~text
A APK
24ddb0a2d745a4249a3b52447edbd275be9121e8061f4c19dd83cf67616341c2

B APK
eef684911ee4fca6d8a1a97c75093dc8f16b63b19e39879dad2b6e5059f5931d

best_512.tflite (A/B APK 내부 모두 동일, 37,926,343 bytes)
f312b541c86426c53c02ef1674ee539c6f87fcf97102061fade628317b3c10b9

A/B 공통 signer certificate SHA-256
97b8789396a208af7b2abd99c6480241f37e58304c13c5b62c95d28630c384a8
~~~

APK ZIP CRC 검사와 APK Signature Scheme v2 검증에 둘 다 통과했다. 두 APK의 모든 TFLite/labels asset 및 네이티브 라이브러리 16개는 서로 동일하다.

**labels 바이트 해시의 구분:** Git blob/원본 checkout labels.txt는 LF·64 bytes·SHA ef1d20e200c6ec8a7a6e888c823abfb86b5b0fb92f67dab022aaf3b35309edc4다. 새 worktree는 기존 Git core.autocrlf=true 체크아웃으로 CRLF가 되어 APK 내부 labels는 73 bytes·SHA cc09df655a7a364c7a2f047cb92db795a90dbcd9f4e3b1819b410585d245b909다. A/B의 labels 바이트는 서로 같고, CRLF를 LF로 정규화한 바이트는 원본/Git blob과 같다. 수동으로 labels를 수정하지 않았다. 9클래스 순서/문자열은 동일하며 detector/ModelAssets.kt:56–59는 줄별 trim 후 클래스 계약을 확인한다.

## 2. 동일 빌드 조건 및 재현 명령

| 항목 | A/B 공통 실측/확인값 |
|---|---|
| 호스트 | Windows 11 10.0 amd64 |
| JDK | Eclipse Adoptium Temurin 21.0.7+6-LTS |
| JAVA_HOME / 실제 Gradle java.home | C:/Users/SAMIN/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2 |
| Kotlin compile jdkHome | 위와 동일 (--info 원문 확인) |
| Android SDK | C:/Users/SAMIN/AppData/Local/Android/Sdk |
| compileSdk / Build Tools | android-36.1 / 36.0.0 |
| NDK / AAPT2 | 28.2.13676358 / AGP aapt2-9.2.1-15009934-windows |
| Gradle / AGP / Kotlin compiler | 9.4.1 / 9.2.1 / 2.2.10 |
| Wrapper 배포 ZIP 검증 SHA | 2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb |
| variant / 옵션 | debug / --offline --no-daemon --no-build-cache --console=plain --info |
| Gradle JVM 메모리 | 기존 org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8 |
| 의존성 | debugRuntimeClasspath 아티팩트 **144개 좌표·파일 해시 일치** |
| signing | 기존 C:/Users/SAMIN/.android/debug.keystore 사용, 키 생성/변경 안 함 |
| 실제 APK applicationId | com.samin.objectdetection |
| versionCode / versionName | 1 / 1.0 |
| minSdk / targetSdk | 33 / 36 |
| debuggable / testOnly | true / testOnly 속성 없음(기본 false) |

Gradle --version의 “Kotlin 2.3.0”은 Gradle 내장 Kotlin이다. 앱 컴파일러 2.2.10과 구분한다. gradle/gradle-daemon-jvm.properties의 Java 21 조건 때문에 JAVA_HOME만 추정하지 않고, build 로그의 실제 Gradle daemon/Kotlin jdkHome 및 후속 검사 태스크의 java.home까지 대조했다.

실행한 빌드의 환경/명령은 다음과 같다. 재빌드 시에는 각 명령의 성공을 확인한 뒤 다음으로 진행한다. **A2를 위해 A를 다시 빌드하지 않는다.**

~~~powershell
$env:JAVA_HOME = 'C:/Users/SAMIN/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2'
$env:ANDROID_HOME = 'C:/Users/SAMIN/AppData/Local/Android/Sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$treeA = 'C:/Users/SAMIN/.codex/worktrees/p0-aba-a-53560e4/ObjectDetection'
$treeB = 'C:/Users/SAMIN/.codex/worktrees/p0-aba-b-8fdc175/ObjectDetection'
& "$treeA/gradlew.bat" -p $treeA :app:assembleDebug --offline --no-daemon --no-build-cache --console=plain --info
& "$treeB/gradlew.bat" -p $treeB :app:assembleDebug --offline --no-daemon --no-build-cache --console=plain --info
~~~

환경/의존성 검사는 준비 폴더의 inspect-environment.init.gradle를 외부 init script로 사용한 별도 조회 태스크다. 앱 소스나 저장소 Gradle 설정을 수정하지 않았고 APK에 새로운 계측을 삽입하지 않았다. 두 environment JSON 전체가 일치했다.

## 3. 데이터 보존 설치를 위한 사전 확인

**A/B끼리 서명이 같음은 확인했다. 현재 기기에 설치된 앱과의 서명/버전/데이터 호환성은 아직 확인하지 않았다.** 같은 “debug APK”라도 다른 PC/debug keystore로 서명되었으면 교체할 수 없다. Android는 업데이트 시 certificate를 비교한다([공식 앱 서명 설명](https://developer.android.com/studio/publish/app-signing#considerations)).

설정 코드 settings/AppSettings.kt 및 AppSettingsStore.kt는 A/B 동일하고 schema=6이다. 그러나 첫 실행 시 기존 pending/attempting rollback, 잘못된 설정 decode 시 기본값 복구 등의 기존 로직은 동작한다(AppSettingsStore.kt:17–40). 설치 전 정상 초기화/적용이 완료된 현재 상태를 저장하고, 매 교체 후 적용 설정을 다시 확인한다.

### 3.1 설치 전 원본 확보

1. 현재 앱의 진행 중인 기록을 정상 종료하고 저장 완료를 확인한다. 중요한 기존 성능 로그·평가/캡처 자료를 외부로 보관한다.
2. 현재 적용 설정을 화면/공유 snapshot으로 보존한다. 앱 사용자 프리셋도 필요하면 별도로 이름을 붙여 저장한다. **프리셋은 앱 내부 사본이고, 공유 ZIP의 settings.properties는 공유 시점 설정이며 전체 앱 데이터 백업/설정 import 파일이 아니다.** 외부 import/전체 복원 UI는 없다.
3. 아래 read-only preflight로 기기 serial/OS/user와 설치 package/version/APK를 확인한다. 출력의 실제 APK 경로를 사용해 현재 base 및 split APK를 모두 PC로 pull하고 서명을 확인한다. 아래 YOUR_DEVICE_SERIAL은 실제 값을 넣는다.

~~~powershell
# 저장소 root PowerShell에서 사용자가 실행
$abaOut = 'D:/develop/Projects/TestProjects/ObjectDetection/build/aba-p0-20261022'
$adb = 'C:/Users/SAMIN/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$apksigner = 'C:/Users/SAMIN/AppData/Local/Android/Sdk/build-tools/36.0.0/apksigner.bat'
$env:JAVA_HOME = 'C:/Users/SAMIN/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2'
New-Item -ItemType Directory -Path "$abaOut/preflight" -ErrorAction Stop
& $adb devices -l
$serial = 'YOUR_DEVICE_SERIAL'
& $adb -s $serial shell getprop ro.build.version.sdk
$deviceUser = (& $adb -s $serial shell am get-current-user).Trim()
if ($LASTEXITCODE -ne 0 -or $deviceUser -ne '0') {
    throw '이 명령 예시는 owner user 0 전용입니다. 사용자별 별도 절차로 진행하세요.'
}
& $adb -s $serial shell dumpsys package com.samin.objectdetection
& $adb -s $serial shell pm path com.samin.objectdetection
# pm path가 반환한 실제 base/split 경로별로 실행하고 파일을 보존
# & $adb -s $serial pull '실제/base.apk/경로' "$abaOut/preflight/installed-base.apk"
# & $apksigner verify --verbose --print-certs "$abaOut/preflight/installed-base.apk"
~~~

preflight 폴더는 먼저 새로 만들고 기존 파일을 덮어쓰지 않는다. 기존 앱 certificate SHA-256이 §1의 값과 같은지, versionCode가 1인지 확인한다. Android API는 minSdk 33 이상이어야 한다. **아래 pm path/run-as/install 예시는 owner user 0 전용이다.** current user가 0이 아니거나 업무 프로필/다중 사용자 앱을 시험한다면 이 예시를 진행하지 않고 사용자별 APK·데이터·설치 범위를 명시한 별도 절차를 사용한다.

4. 기존 앱이 debuggable이고 run-as가 허용되면 private 데이터도 앱을 정상 저장 후 종료한 상태에서 archive로 추출할 수 있다. 다음은 **사용자가 실행할 선택적 백업 예시**다. Python이 바이너리 stdout을 직접 파일로 받으므로 PowerShell 버전별 바이너리 리다이렉션 변환을 피한다.

~~~powershell
& $adb -s $serial shell am force-stop com.samin.objectdetection
@'
from pathlib import Path
import subprocess, sys, tarfile, hashlib
import xml.etree.ElementTree as ET
adb = r"C:/Users/SAMIN/AppData/Local/Android/Sdk/platform-tools/adb.exe"
target = Path("build/aba-p0-20261022/preflight/private-data-before.tar")
target.parent.mkdir(parents=True, exist_ok=True)
with target.open("xb") as output:
    subprocess.run([adb, "-s", sys.argv[1], "shell", "-T", "run-as",
                    "com.samin.objectdetection", "tar", "-cf", "-", "."],
                   stdout=output, check=True)
with tarfile.open(target) as archive:
    prefs = [m for m in archive.getmembers()
             if m.name.endswith("shared_prefs/object_detection_app_settings.xml")]
    if len(prefs) != 1:
        raise SystemExit("설정 XML 확인 실패: 백업 완료로 취급하지 마세요.")
    data = archive.extractfile(prefs[0]).read()
    if any(node.get("name") == "apply_state" for node in ET.fromstring(data)):
        raise SystemExit("설정 적용 marker가 남았습니다. 정상 초기화/적용 상태를 확인하세요.")
    print("settings XML bytes:", len(data))
    print("settings XML SHA256:", hashlib.sha256(data).hexdigest())
print("Archive SHA256:", hashlib.sha256(target.read_bytes()).hexdigest())
'@ | & './.venv/Scripts/python.exe' -B - $serial
~~~

명령 성공과 archive 목록/설정 XML을 실제로 확인한 뒤에만 백업 완료로 표시한다. shell -T는 PTY를 사용하지 않고 shell v2의 원격 종료 코드를 전달하는 경로다. exec-out이나 shell -x로 바꾸면 원격 실패 확인을 잃을 수 있다([AOSP adb 옵션](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/docs/user/adb.1.md)). run-as 실패/미지원이면 빈 파일을 백업으로 취급하지 않는다. 이 archive는 app private 파일 사본이며 공유 저장소·Android Keystore·기기 전체 상태까지 복원 가능하다는 보장은 아니다. 복원 명령을 자동 실행하지 않는다. allowBackup=true 또는 adb backup만으로 실제 최신 백업이 확보되었다고 가정하지 않는다.

### 3.2 교체 설치 원칙과 중단 조건

- **사용할 옵션은 install -r.** 기존 데이터를 유지하는 재설치다([공식 adb 옵션](https://developer.android.com/tools/adb#pm)).
- 이번 A/B는 testOnly 속성이 없으므로 기본 명령에 -t가 필요 없다. 나중에 다른 APK를 쓰면 manifest를 다시 확인한다. -t는 test APK 허용 옵션이며 데이터 보존 옵션이 아니다.
- A/B 모두 versionCode=1이므로 A→B→A에서 -d가 필요 없다. 현재 설치 앱 버전이 1보다 높으면 아래 명령으로 진행하지 말고 그 상태를 먼저 확인한다.
- 서명 불일치/다운그레이드/저장공간/호환성 오류가 나면 **그 단계에서 중지**한다. 자동 uninstall, pm clear, uninstall -k, 다른 keystore 생성 또는 -d/-g 추가로 우회하지 않는다. -g는 권한 상태를 바꾸므로 기본 시험에 넣지 않는다.
- 매 교체 후 기존 설정/자료 존재와 정상 초기화 완료를 확인한다. 새 default가 보이면 시험을 시작하지 말고 원래 snapshot과 상태를 대조한다. 정상 모델 초기화 뒤 markAppliedHealthy가 rollback marker를 정리한다(MainActivity.kt:274).

## 4. 공통 설정 체크리스트

카메라 화면 **⚙ → 상세 설정**에서 확인한다. 기존 저장 설정을 자동 변경하지 않았다. 시험을 위해 사용자가 선택한 값은 **적용 및 저장**한 후 실제 적용값/로그 헤더로 확인한다. 편집 중 카테고리 요약은 현재 적용값이므로 저장 전 요약만 보고 완료로 판단하지 않는다.

| 확인 | 항목 / 실제 UI | A1 / B / A2 공통값 |
|---|---|---|
| [ ] | 모델 선택 | YOLO 512×512 (best_512.tflite), FLOAT32, §1 SHA |
| [ ] | Interpreter Thread Count / 백엔드 | 2 / CPU |
| [ ] | XNNPACK 사용 | ON (요청값이며 실제 위임 coverage의 증거는 아님) |
| [ ] | 카메라 화면 표시 (Preview) | OFF. 디스플레이 전원 OFF가 아님 |
| [ ] | 인식 영역 크롭 사용 | ON |
| [ ] | 상단/하단/왼쪽/오른쪽 크롭 (%) | 20 / 0 / 10 / 10 |
| [ ] | Resize Mode / Center Square ROI | LETTERBOX / OFF |
| [ ] | Confidence / NMS / Max Candidates | 0.2 / 0.45 / 100 |
| [ ] | Detect Interval | 0ms (기존 비교 조건과 동일) |
| [ ] | ML Kit Detection / Detection Interval (ms) | ON / 1500 |
| [ ] | 테스트 시간 (분) | **3** |
| [ ] | System Performance Diagnostics | ON |
| [ ] | 배터리·발열 진단 | ON |
| [ ] | Sample Interval (ms) | 5000 |
| [ ] | 상세 detector/debug/input 이미지 진단 | 기준 조건 OFF를 A/B/A 모두 유지 |
| [ ] | Size Filter / Geometry / Temporal | 과거 10/20 기준은 DISABLED / ON / OFF. 다른 값이면 세 회 동일하게 고정하고 별도 조건으로 기록 |
| [ ] | 실제 Beep / TTS / 진동 | 과거 10/20 기준은 모두 ON. 다른 값이면 세 회 동일하게 고정하고 별도 조건으로 기록 |
| [ ] | 필터·경고·Beep/TTS/진동·위치 등 다른 기능 | 현재 비교할 동일 설정 유지, snapshot에 기록 |
| [ ] | 장면·조도·카메라 방향/회전·실제 해상도 | 동일 고정 장면, 로그 실제 frame/ROI 대조 |
| [ ] | 충전/절전·밝기·케이스·주변 온도 | 동일. 가급적 USB를 분리한 비충전 상태로 측정 |

“크롭 프리셋” 버튼은 상단 10%/좌우 5%이므로 이번 20/10 조건에 쓰지 않는다. 시스템/배터리 진단과 Enable Detector Diagnostics·Debug Detection Logging·Debug Save Inference Input·Debug Save On Detection·기존 호환 Debug Image는 별개다. 후자를 새로 켜면 후보 로그/boxing/이미지 기록이 늘어나 원래 비교를 교란한다.

과거 필터/출력 기준은 [10/20 분석 문서](yolo-inference-optimization-analysis-20261020.md) §2.2의 2차 근거다. 현재 저장값을 유지해 새 A/B/A를 할 수도 있지만, 이때는 과거 10/20 시험 조건의 재현과 구분한다. 특히 면적 필터 모드는 scan 안 연산/할당을 달리할 수 있으므로 반드시 기록한다. 이번 준비에서 기기의 필터/출력 설정을 변경하지 않았다.

UI 근거: settings/SettingsActivity.kt:149, 289–313, 419–448 및 ui/MainScreenView.kt:120. 측정 당시 설정은 성능 로그의 **Applied AppSettings Snapshot**을 우선한다. 공유 ZIP settings.properties와 불일치하면 측정 뒤 설정 변경 여부부터 확인한다.

## 5. 사용자가 실행할 A1 → B → A2 순서

다음 설치 명령은 **한 번에 연속 실행하는 자동 시험 스크립트가 아니다.** 각 단계의 측정·저장·외부 보관·냉각을 마친 다음 단계로 진행한다.

### A1: P0 이전

1. §3의 데이터/서명/버전 확인과 백업을 마친다. APK 해시를 확인한다.

~~~powershell
$apkA = "$abaOut/ObjectDetectionTestApp-P0-A-53560e4-debug.apk"
$apkB = "$abaOut/ObjectDetectionTestApp-P0-B-8fdc175-debug.apk"
Get-FileHash -LiteralPath $apkA,$apkB -Algorithm SHA256
& $adb -s $serial install -r $apkA
if ($LASTEXITCODE -ne 0) { throw 'A 설치 실패: 제거/초기화하지 말고 중지' }
~~~

2. 앱을 직접 열고 정상 초기화·기존 설정/자료를 확인한다. §4를 적용·확인한다. 이후 세 회 모두 **설정 확인 → 앱 추론 중지 → 충분히 냉각 → 앱 재시작 → 고정 30초 예열 → 시작 조건 확인 → 측정** 순서를 따른다. debugger/Profiler/화면 녹화는 연결하지 않는다.
3. **예열 후 측정 시작** 배터리 온도를 가급적 ±0.5~1°C 이내로 맞춘다. 조건이 맞지 않으면 중지하고 냉각부터 같은 순서로 반복한다. 이는 시험 설계 목표이며 온도만 같다고 SoC 상태가 같아지는 것은 아니다. CPU policy 상한·Thermal/headroom도 가능하면 확인한다. live 표시 UI가 없으므로 별도 read-only 관측 또는 최초 SYSTEM/BATTERY_SAMPLE로 사후 대조한다. USB/충전 조건은 세 회 같게 한다.
4. 카메라 **성능 로그 시작**을 누르고 같은 장면에서 3분 기록한다. PERF mm:ss → PERF 저장 중… → **시간 종료 · 자동 저장 완료: perf_….txt**와 버튼 복귀를 확인한다.
5. **상세 설정 → 성능 측정 및 로그 → 최근 로그 및 입력 PNG를 ZIP으로 공유**로 즉시 외부 보관한다. A1_logs.zip으로 구분하고 내부 원본 perf 파일명을 기록한다.
6. 로그 회수 후 앱 종료/동일한 전경 이탈 절차로 추론을 멈추고 다음 실행까지 냉각한다. 자동 3분 저장은 추론 자체를 멈추지 않는다.

### B: P0 이후

7. 기록/저장 완료를 확인한 뒤 B로 교체한다.

~~~powershell
& $adb -s $serial install -r $apkB
if ($LASTEXITCODE -ne 0) { throw 'B 설치 실패: 제거/초기화하지 말고 중지' }
~~~

8. A1과 동일하게 정상 초기화·적용 설정·예열·냉각·시작 조건을 확인한다. 3분 기록 → 저장 완료 → **B_logs.zip** 외부 보관 → 앱 종료/냉각을 수행한다.

### A2: 같은 A APK 재측정

9. A1에서 사용한 **동일 SHA의 A 파일**로 교체한다.

~~~powershell
& $adb -s $serial install -r $apkA
if ($LASTEXITCODE -ne 0) { throw 'A2 설치 실패: 제거/초기화하지 말고 중지' }
~~~

10. 동일 절차로 3분 기록하고 **A2_logs.zip**으로 보관한다. 시험 후 현재 설치본은 A다. 원래 사용 중이던 다른 APK로 복귀하려면 미리 보관한 원본의 서명/버전·split 구성을 확인한 별도 교체 절차를 사용하며, 이번 준비에서는 복귀 설치도 자동 수행하지 않는다.

공유 ZIP은 앱 cache의 object_detection_latest_logs.zip이라는 같은 이름으로 만들어지고 **최신 성능 로그 1개만** 담는다(settings/LogShareManager.kt:35–55). 매 실행 직후 별도 이름으로 외부 저장한다. 원본 로그는 Documents/GOTORO/performance/perf_yyyyMMdd_HHmmss.txt다(metrics/PerformanceLogRecorder.kt:41–46,155). 최신 debug/evaluation 파일은 과거 실행의 자료일 수 있으므로 현재 세션과 자동으로 묶지 않는다.

## 6. 기록표와 비교 지표

아래 칸은 실제 기기 시험 뒤 채운다. 현재 결과값은 없다.

| 기록 항목 | A1 | B | A2 |
|---|---|---|---|
| APK SHA / 원본 perf 파일 / sessionId | 미실행 | 미실행 | 미실행 |
| serial·OS·user / 설치·재시작·예열 방식 | — | — | — |
| 시작 시각·실제 측정 초·성공 FRAME 수 | — | — | — |
| 시작/종료 온도·잔량·충전/절전 상태 | — | — | — |
| 시작 policy별 scalingMaxKHz / Thermal / headroom | — | — | — |
| 냉각 시간·주변 온도·장면·조도 | — | — | — |
| 오류/누락·설정/해상도 불일치 | — | — | — |

| 비교 지표 | 산출/범위 | 비교 시 주의 |
|---|---|---|
| FPS | 성공 FRAME 수 / 실제 단조 측정 시간 | 최종 legacy 반올림 Average FPS 대신 exact summary 대조. LATENCY를 프레임 수에 합산하지 않음 |
| inference 평균/P95 | Java Interpreter 호출 경과 시간 | 순수 kernel CPU time 아님. nativeInvoke도 별도 보조 비교하며 더하지 않음 |
| outputParsing 평균/P95 | output copy + candidate scan의 정수 ms 합 | parseOutput 전체 아님. 개별 copy/scan은 기존 FRAME에서 분리 불가 |
| post 평균/P95 | detector parse/정렬/NMS + pipeline 정책 후처리 | outputParsing/nms를 포함하므로 합산하지 않음 |
| totalProcessing 평균/P95 | Analyzer 수신부터 pipeline 복귀 직후 | 이후 경고/기록 제출·close는 제외. 1/평균 시간과 실제 FPS를 동일시하지 않음 |
| CPU 주파수 | SYSTEM의 policy별 currentKHz/scalingMaxKHz/cpuinfoMaxKHz | 기기 policy 표본이며 YOLO 스레드 실행 코어/순간 유효 클럭 아님. 동일 policy·동일 시간 구간 비교 |
| 온도·Thermal/headroom | 최초/마지막 BATTERY_SAMPLE, SYSTEM/상태 이벤트 | 배터리 온도는 SoC 온도가 아님. NONE만으로 CPU 제한 부재를 보장하지 않음 |
| 보조 지표 | preprocess/bitmap/pipeline, processCPU%, detect, mlKitInFlight | 최종 detect는 confidence 통과 후보 수가 아님. ML Kit Boolean을 실제 연산 겹침 시간으로 대체하지 않음 |

전체 및 30초 구간 평균/표본 수, 첫/마지막 30초를 비교하고 P95는 원 프레임의 nearest-rank로 계산한다. 구간 P95를 평균내지 않는다. CPU/온도 표본은 프레임보다 성기므로 시간 구간을 맞추고 결측을 0으로 채우지 않는다.

B의 LATENCY callback·ImageProxy close·ML Kit/출력 span은 B 내부의 추가 진단이다. A에는 해당 필드가 없으므로 A의 span을 복원하거나 0으로 간주하지 않는다. A/B 공통 FRAME 지표와 구분한다.

### 로그 분석 명령

각 ZIP에서 원본 perf를 꺼내 새 runs 폴더에 A1_perf.txt / B_perf.txt / A2_perf.txt로 복사하고 원래 파일명과 SHA를 기록한다. 기존 원본은 보존한다. 아래는 저장소 root 기준이며 준비 작업에서 실제 A/B 로그를 만들어 넣지 않았다.

~~~powershell
$runs = "$abaOut/runs"
$logs = @("$runs/A1_perf.txt", "$runs/B_perf.txt", "$runs/A2_perf.txt")
& './.venv/Scripts/python.exe' -B tools/analyze_performance_logs.py @logs --interval-seconds 30 |
    Out-File -LiteralPath "$runs/base-analysis.json" -Encoding utf8
& './.venv/Scripts/python.exe' -B "$abaOut/summarize-frame-stages.py" @logs |
    Out-File -LiteralPath "$runs/stage-analysis.json" -Encoding utf8
~~~

기본 분석기는 FPS/inference/pipeline/totalProcessing/nativeInvoke 및 시스템·배터리/30초 정보를 제공한다. 기본 JSON에 빠진 outputParsing/post/nms/resize는 보조 스크립트가 기존 FRAME를 읽어 집계한다. 앱·기존 tools 소스를 바꾸거나 추가 detector 진단을 켜지 않는다. 로그 완전성/표본 수/오류·drop을 먼저 확인하고, 불완전 로그는 정상 비교로 취급하지 않는다. 보조 도구는 보존된 validation 로그로만 동작을 확인했으며 실제 A/B/A 로그를 수집/검증한 것은 아니다.

## 7. 결과 판정과 후속 순서

1. **유효성부터 확인:** 모델/적용 설정/카메라 실제 크기·회전·장면 및 시작 온도/CPU 상한이 다르면 원인을 기록하고 재측정한다. A1/B/A2의 오류·drop·실제 측정 시간/성공 수도 대조한다.
2. **A1과 A2가 비슷하고 B만 반복해서 느리면** P0 차이와의 관련성이 강화된다. 이 한 번의 비교로 이벤트/GC/lock 중 원인을 확정하지 않는다. 차이가 작으면 교차 반복을 늘려 분산부터 확인한다.
3. **A2도 B처럼 느리면** 누적 열·전력 정책·ART/실행 상태·장면 차이 같은 순서 효과를 먼저 분리한다. 온도 숫자 하나로 정규화했다고 주장하지 않는다.
4. outputParsing 증가가 재현되면 양쪽 동일 최소 계측의 별도 보조 빌드로 copy/scan/후보 수를 분리한다. 필요 시 LATENCY만 분리한 B 및 짧은 Perfetto/CPU sampling으로 실행/대기·GC를 구분한다. 기존 무수정 A/B/A와 다른 실험으로 표기한다.
5. 원인과 수정 방향을 구분한 뒤에만 최소 수정 및 10분 지속 검증을 진행한다. 이번 단계에서는 성능 개선/회귀의 크기를 실측 완료로 보고하지 않는다.

**완료 범위:** 두 지정 커밋/worktree·동일 환경 APK 빌드, 패키지/서명/모델/의존성 대조, 무결성 자료 및 사용자 절차 준비. **미실행:** 기존 설치 앱 preflight/백업·교체·설정 적용·3분 성능 측정·프로파일링. 기기 설치 적합성과 실제 데이터 보존 여부는 사용자의 §3 절차 후 확인할 항목이다.
