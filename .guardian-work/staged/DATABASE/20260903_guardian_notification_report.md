# 보호자 공통 알림 설정 변경

코드 변경과 테스트 완료. 실제 DB Migration은 미실행이며, 아래 SQL을 먼저 검토·적용한 뒤 배포해야 한다. 실제 이벤트 발송 구현은 제공된 프로젝트에서 발견되지 않아 발송 경로 변경 및 종단간 발송 검증은 미완료다.

## 1. 수정 파일

- `src/main/webapp/WEB-INF/jsp/setting/guardianNotificationSetting.jsp`
- `src/main/java/pas/settings/web/SettingsController.java`
- `src/main/java/pas/settings/service/SettingsSerivce.java`
- `src/main/java/pas/settings/service/impl/SettingsServiceImpl.java`
- `src/main/java/pas/settings/service/impl/SettingsMapper.java`
- `src/main/java/pas/settings/service/impl/SettingsMapper.xml`
- `src/main/java/pas/registUser/service/impl/RegistUserServiceImpl.java`
- `src/main/java/pas/registUser/service/impl/RegistUserMapper.java`
- `src/main/java/pas/registUser/service/impl/RegistUserMapper.xml`
- `src/test/java/pas/settings/web/GuardianNotificationSettingTest.java`
- `src/test/java/pas/settings/service/impl/GuardianNotificationServiceTest.java`
- `src/test/java/pas/settings/service/impl/GuardianNotificationMapperIntegrationTest.java`
- `DATABASE/20260903_guardian_notification.sql`
- 이 보고서

## 2–4. 실제 DB와 변경 구조

2026-09-03 Tomcat server.xml의 pasDS로 실제 DB를 읽기 조회했다. local globals.properties의 기본 접속은 실패했으나 실제 JNDI 설정을 통해 확인했다. 접속정보와 비밀번호는 보고서에 포함하지 않는다.

테이블: `tb_guardian_notification_setting`. 컬럼: `setting_id` BIGINT PK AUTO_INCREMENT, `guardian_id` VARCHAR(50) NOT NULL, `protege_id` VARCHAR(50) NOT NULL, `sos_enabled`, `route_deviation_enabled`, `low_battery_enabled`, `device_offline_enabled` TINYINT NOT NULL DEFAULT 1, `low_battery_threshold` INT NOT NULL DEFAULT 25, `created_at`, `updated_at` TIMESTAMP. 기존 UNIQUE: `uk_guardian_notification (guardian_id, protege_id)`. 데이터는 총 1건, 보호자 1명, 중복 보호자 0명이다.

변경 후 모든 설정 조회·저장은 `guardian_id` 단독 조건이다. 보호자 계정 행을 FOR UPDATE로 잠근 뒤 중복을 확인하고 UPSERT한다. Migration으로 guardian_id 단독 UNIQUE를 추가해 동시 저장도 DB에서 제한한다. protege_id와 기존 복합 인덱스는 보존하며, 신규 설정은 protege_id를 NULL로 저장한다. 기존 row의 protege_id는 감사 목적으로 그대로 남지만 조회 조건으로 사용하지 않는다. 기존 여러 행은 임의로 선택하거나 덮어쓰지 않고 명시적 오류로 차단한다.

## 5. UI/JS

피보호자 선택 영역, select 생성, 변경 이벤트, 연결 목록 조회, 연결 0명일 때 화면 숨김, protegeId 전송을 제거했다. 기존 GOTORO table/Kendo ON/OFF 토글 스타일을 사용한다. 설정을 읽기 전에는 저장·토글을 비활성화하여 읽기 실패가 기존 설정을 덮어쓰지 않도록 했다.

요청대로 배터리는 50%·25%·10% 독립 토글로 변경했다. 실제 기존 구현은 하나의 ON/OFF와 선택 임계값 방식이었다. 새 battery_50_enabled / battery_25_enabled / battery_10_enabled는 nullable이며 기존 값은 변경하지 않는다. 첫 저장 전에는 기존 ON/OFF와 선택된 임계값을 읽어 해당 토글만 ON으로 보여준다. 설정이 없는 경우 기존 선택 기준 25%를 유지하여 SOS/경로이탈/25%/오프라인은 ON, 50%/10%는 OFF다. 외부 발송기가 기존 임계값을 누적 방식으로 해석했다면 이 변환 정책을 발송 구현과 함께 재검토해야 한다.

## 6–8. Controller / Service / Mapper

기존 POST `/settings/selectGuardianNotification.json`, `/settings/saveGuardianNotification.json` URL을 유지했다. 조회 메서드는 request를 받지 않는다. Controller의 GUARDIAN 검사 및 기존 errorCode 응답 규약을 유지했다. Service도 세션 LoginVO로 GUARDIAN을 검증하고 guardianId를 생성한다. 저장 요청의 guardianId/protegeId는 사용하지 않는다. 잘못되거나 누락된 토글값은 거부한다. 연결 목록 API는 다른 기능을 위해 유지한다.

Mapper는 guardian_id 단독 SELECT/COUNT/UPSERT를 사용하며 알림 설정용 연결 검증 SQL을 제거했다. 관계 해제 시 설정 DELETE 호출과 그 전용 Mapper 메서드/SQL을 제거했다. 보호자·피보호자 어느 쪽에서 연결 해제해도 설정을 삭제하지 않는다.

## 9. 알림 발생·발송: 미완료

pas_spring 전체 src 및 관련 pas_boot 소스에서 테이블명, 설정 필드, SOS/배터리/오프라인/경로이탈/FCM/notification 등을 검색했지만 해당 설정을 읽어 이벤트 알림 수신자를 판단하는 발송기는 발견되지 않았다. pas_spring의 PopbillController는 전화번호를 받는 별도 알림톡 전송으로 이 이벤트 경로가 아니다. 이 코드를 임의로 수정하거나 연결되지 않은 발송기를 추가하지 않았다.

실제 발송 프로젝트 위치가 필요하다. 그 구현에서는 이벤트의 protected ID로 APPROVED 관계를 조회하고 각 guardian ID의 공통 설정을 읽어 유형별 수신 여부를 결정해야 한다. 이벤트 주체 정보는 메시지에 유지한다. 신규 배터리 토글 계약도 발송기와 함께 적용해야 한다. 현재 변경만으로 실제 알림 발송이 달라졌다고 볼 수 없다.

## 10–12. 검증

- Java 8로 변경 소스 및 관련 테스트 컴파일 성공.
- Service/UI 테스트 9개 통과: 연결 없이 기본값 조회, 요청 ID 위조 무시, 독립 배터리값 저장, 중복 데이터 읽기/쓰기 차단, GUARDIAN 외 계정·비로그인 거부, 누락/잘못된 값 거부.
- MariaDB/MyBatis 통합 테스트 2개 통과: JDBC 연결 전용 TEMPORARY TABLE에서 2명 연결, 3개 protegeId로 반복 저장해도 설정 1건, 연결 전체 해제 후 유지, 새 피보호자 연결 후 기존 값 유지, 다른 보호자 값 분리, 기존 설정의 배터리 변환, 독립 토글 저장/조회, 기존 protege_id 보존.
- 통합 테스트는 실제 테이블 데이터를 복사하거나 수정하지 않으며 임시 테이블은 연결 종료 시 사라진다.
- 전체 테스트 56개 실행: 48개 통과, 8개 실패. 변경 전 파일로도 같은 8개 실패를 재현했다. 기존 연결 요청·팝업·연결해제 UI 문자열 기대값 불일치 7개와 실행 메서드 없는 EgovEnvCryptoAlgorithmCreateTest 1개다. 이번 알림 관련 11개는 전부 통과했다.
- 실제 브라우저 화면, 발송기, 동시 요청 부하 및 실배포 테스트는 수행하지 않았다.

## 13–14. Migration

`20260903_guardian_notification.sql`에 실제 컬럼·키 이름으로 스키마 확인, 중복 목록·상세 조회, 참조 제약·트리거 확인, ALTER 제안을 작성했다. 실제 DB에는 실행하지 않았다. 배포 전에 쓰기 중단·백업·중복 재확인이 필요하다. 중복 값이 충돌하면 보호자가 선택한 공통값을 결정한 뒤 별도 승인된 데이터 정리 절차를 거쳐야 한다. 값이 동일한 테스트 데이터라도 자동으로 삭제하지 않는다. 현재 확인된 DB는 중복이 없으므로 데이터 삭제 없는 ALTER가 가능하다. 다른 환경은 재확인해야 한다.

새 코드만 먼저 배포하면 신규 컬럼 부재 및 protege_id NOT NULL 때문에 실패한다. 스키마 적용과 코드 배포를 함께 계획하고, 기존 피보호자별 앱/발송기가 동시에 쓰지 않도록 해야 한다. 예전 앱은 조회 조건과 삭제 동작이 다르므로 단순 앱 버전 롤백도 별도 검토해야 한다.

## 15. 다른 기능

승인·거절, 연결 요청 및 관계 삭제 SQL, 사용자 목록, QnA, 위치/단말기/개인정보/길찾기 코드는 변경하지 않았다. 연결 해제 서비스에서 알림 설정 삭제만 제거했다. PROTECTED 화면은 변경하지 않았다. 전체 사용자 흐름의 실브라우저 회귀 검증까지 완료했다는 뜻은 아니다.
