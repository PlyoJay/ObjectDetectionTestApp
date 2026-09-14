package pas.settings.web;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.BeforeClass;
import org.junit.Test;

public class GuardianNotificationSettingTest {
    private static String jsp;
    private static String mapper;
    private static String service;

    @BeforeClass
    public static void loadSources() throws Exception {
        jsp = read("src/main/webapp/WEB-INF/jsp/setting/guardianNotificationSetting.jsp");
        mapper = read("src/main/java/pas/settings/service/impl/SettingsMapper.xml");
        service = read("src/main/java/pas/settings/service/impl/SettingsServiceImpl.java");
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    @Test
    public void guardianViewContainsOnlyNotificationControls() {
        assertTrue(jsp.contains("알림 수신 설정"));
        assertTrue(jsp.contains("SOS 알림 수신"));
        assertTrue(jsp.contains("경로 이탈 알림 수신"));
        assertTrue(jsp.contains("배터리 부족 알림 수신"));
        assertTrue(jsp.contains("단말 오프라인 알림 수신"));
        assertFalse(jsp.contains("전체 거리 안내"));
    }

    @Test
    public void protegeSelectorIsRemoved() {
        assertFalse(jsp.contains("protegeSelector"));
        assertFalse(jsp.contains("protegeId"));
        assertTrue(mapper.contains("G.status = 'APPROVED'"));
        assertTrue(mapper.contains("G.guardian_id = #{guardianId}"));
    }

    @Test
    public void missingSettingUsesDefaultsAndFirstSaveUsesInsert() {
        assertTrue(service.contains("setting.put(\"sosEnabled\", 1)"));
        assertTrue(service.contains("setting.put(\"lowBatteryThreshold\", 25)"));
        assertTrue(mapper.contains("INSERT INTO tb_guardian_notification_setting"));
        assertTrue(mapper.contains("UPDATE tb_guardian_notification_setting"));
    }

    @Test
    public void guardianComesFromTrustedServerState() {
        assertTrue(service.contains("params.put(\"guardianId\", loginInfo.getUserId())"));
        assertFalse(service.contains("verifiedGuardianProtegeParams"));
        assertTrue(mapper.contains("status = 'APPROVED'"));
    }
}
