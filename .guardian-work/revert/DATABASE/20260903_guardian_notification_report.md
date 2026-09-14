# 보호자 공통 알림 설정: 기존 배터리 구조 복원

이 보고서는 앞선 독립 배터리 토글 및 Migration 선행 필요 안내를 대체한다. low_battery_enabled + low_battery_threshold 구조를 복원했고 피보호자 선택 제거 및 guardian_id 단독 기준은 유지한다. 실제 DB 스키마와 데이터는 변경하지 않았다.

## 수정 파일

- src/main/java/pas/settings/service/impl/SettingsMapper.xml
- src/main/java/pas/settings/service/impl/SettingsMapper.java
- src/main/java/pas/settings/service/impl/SettingsServiceImpl.java
- src/main/webapp/WEB-INF/jsp/setting/guardianNotificationSetting.jsp
- src/test/java/pas/settings/service/impl/GuardianNotificationServiceTest.java
- src/test/java/pas/settings/service/impl/GuardianNotificationMapperIntegrationTest.java
- src/test/java/pas/settings/web/GuardianNotificationSettingTest.java
- DATABASE/20260903_guardian_notification.sql
- DATABASE/20260903_guardian_notification_report.md

## 제거 및 복원

새 배터리 컬럼 3개를 사용하는 COALESCE SELECT, INSERT/UPSERT, Java Map 필드와 기본값, JSP 독립 토글, JS 요청/응답 코드 및 테스트를 제거했다. 이전 Migration SQL은 읽기 전용 진단 SQL로 대체했다. ALTER/DROP/신규 컬럼 추가는 실행하지 않았다.

기존 GOTORO/Kendo 배터리 ON/OFF 토글과 50%·25%·10% 중 하나를 선택하는 라디오 UI를 복원했다. OFF일 때 임계값 선택을 비활성화하지만 값은 유지·저장한다. 설정이 없는 경우 기본값은 기존과 동일하게 SOS/경로이탈/배터리/오프라인 ON, 임계값 25다. 서버는 사용 여부 0/1과 임계값 50/25/10만 허용한다.

## 최종 SELECT

```sql
SELECT sos_enabled, route_deviation_enabled,
       low_battery_enabled, low_battery_threshold, device_offline_enabled
FROM tb_guardian_notification_setting
WHERE guardian_id = #{guardianId};
```

## 최종 INSERT / UPDATE

```sql
INSERT INTO tb_guardian_notification_setting (
    guardian_id, sos_enabled, route_deviation_enabled,
    low_battery_enabled, low_battery_threshold, device_offline_enabled
) VALUES (
    #{guardianId}, #{sosEnabled}, #{routeDeviationEnabled},
    #{lowBatteryEnabled}, #{lowBatteryThreshold}, #{deviceOfflineEnabled}
);

UPDATE tb_guardian_notification_setting
SET sos_enabled = #{sosEnabled},
    route_deviation_enabled = #{routeDeviationEnabled},
    low_battery_enabled = #{lowBatteryEnabled},
    low_battery_threshold = #{lowBatteryThreshold},
    device_offline_enabled = #{deviceOfflineEnabled}
WHERE guardian_id = #{guardianId};
```

세션 GUARDIAN ID로 tb_user 계정 행을 FOR UPDATE 잠근 뒤, 해당 guardian_id의 설정 수를 조회한다. 0건은 INSERT, 1건은 UPDATE, 2건 이상은 오류로 중단한다. 여러 기존 설정을 임의로 통합하지 않는다. 이 저장 서비스의 동시 최초 저장은 계정 잠금으로 직렬화한다. 별도 앱/직접 SQL이 잠금 절차 없이 쓰는 경우까지 DB 차원에서 강제할 수는 없다.

## 실제 DB 확인

2026-09-03 Tomcat pasDS로 SHOW CREATE TABLE을 재확인했다. low_battery_enabled와 low_battery_threshold가 있고 독립 배터리 컬럼은 없다. 설정 1건, 보호자 1명, 중복 0명이다.

사용자 설명과 달리 현재 연결된 이 DB에서는 protege_id가 이미 없고 PRIMARY KEY(setting_id)만 존재한다. 이번 작업에서 제거한 것이 아니다. guardian_id UNIQUE도 없으므로 ON DUPLICATE KEY UPDATE를 단순 사용하면 설정이 늘어날 수 있어 INSERT/UPDATE 분기로 바꿨다.

다른 배포 DB에 protege_id가 NOT NULL이며 기본값 없이 남아 있다면 위 INSERT가 제약으로 거부될 수 있다. 그런 DB는 별도 확인이 필요하다. 이번에는 스키마 변경이나 가짜 피보호자 ID 입력을 하지 않았다. 현재 확인한 DB는 스키마 변경 없이 조회·저장 가능하다.

## 검증

- Java 8 변경 소스 컴파일 성공.
- JUnit 13개 통과: 서비스 7개, Mapper 통합 2개, 화면 소스 검사 4개.
- JSP 내 JavaScript Node 문법 검사 통과.
- 서비스: 기본값, 요청 ID 위조 무시, GUARDIAN/비로그인 권한, 누락·잘못된 값 거부, INSERT/UPDATE 분기, 중복 차단 검증.
- MariaDB/MyBatis: 실제 구조 그대로 만든 연결 전용 TEMPORARY TABLE에서 ALTER 없이 SELECT/INSERT/UPDATE 성공. 배터리 50/25/10 각각 ON/OFF 총 6조합 저장·재조회 성공.
- 다수 피보호자 ID와 무관한 동일 설정 UPDATE, 반복 저장 시 1건 유지, 보호자별 설정 분리, 연결 해제·새 연결 이후 설정 유지 확인.
- 실제 업무 테이블은 수정하지 않았고 임시 테이블은 JDBC 연결 종료 시 소멸한다.
- GUARDIAN/PROTECTED 연결·승인·거절·목록 코드는 이번 보정에서 변경하지 않았다. 앞선 작업의 연결 해제 시 설정 삭제 제거는 유지했다.
- 실제 브라우저 UI 조작, 운영 배포 및 이벤트 발송 종단간 테스트는 수행하지 않았다. 이벤트 발송 구현 위치는 이전 작업과 마찬가지로 확인되지 않았다.
