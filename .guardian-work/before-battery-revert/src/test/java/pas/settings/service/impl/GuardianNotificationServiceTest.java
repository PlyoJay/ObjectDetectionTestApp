package pas.settings.service.impl;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.HashMap;
import java.lang.reflect.Field;
import org.junit.*;
import org.mockito.ArgumentCaptor;
import org.egovframe.rte.fdl.cmmn.exception.EgovBizException;
import org.egovframe.rte.psl.dataaccess.util.EgovMap;
import egovframework.com.cmm.security.service.UserDetailsService;
import egovframework.com.cmm.service.LoginVO;
import egovframework.com.cmm.util.CmmUserDetailsHelper;

public class GuardianNotificationServiceTest {
    private SettingsServiceImpl service;
    private SettingsMapper mapper;
    private UserDetailsService auth;
    private Object previousAuth;
    private Field authField;
    @Before public void setup() throws Exception {
        authField = CmmUserDetailsHelper.class.getDeclaredField("userDetailsService");
        authField.setAccessible(true);
        previousAuth = authField.get(null);
        auth = mock(UserDetailsService.class);
        authField.set(null, auth);
        LoginVO login = new LoginVO(); login.setUserId("guardian-a"); login.setUserType("GUARDIAN");
        when(auth.getAuthenticatedUser()).thenReturn(login);
        mapper = mock(SettingsMapper.class);
        when(mapper.lockGuardianAccount(anyMap())).thenReturn("guardian-a");
        service = new SettingsServiceImpl(); service.settingsMapper = mapper;
    }
    @After public void cleanup() throws Exception { authField.set(null, previousAuth); }
    private HashMap<String,Object> values() {
        HashMap<String,Object> p = new HashMap<>();
        for (String key : new String[]{"sosEnabled","routeDeviationEnabled","battery50Enabled","battery25Enabled","battery10Enabled","deviceOfflineEnabled"}) p.put(key, 1);
        return p;
    }
    @Test public void defaultsWorkWithoutAnyProtectedConnection() throws Exception {
        EgovMap p = service.selectGuardianNotification();
        assertEquals(1, p.get("sosEnabled")); assertEquals(1, p.get("battery25Enabled"));
        assertEquals(0, p.get("battery50Enabled")); assertEquals(0, p.get("battery10Enabled"));
        assertFalse(p.containsKey("protegeId"));
        verify(mapper, never()).selectGuardianProtegeList(anyMap());
    }
    @Test public void forgedIdsAreIgnoredAndThresholdsAreIndependent() throws Exception {
        HashMap<String,Object> p=values(); p.put("guardianId","attacker"); p.put("protegeId","unrelated");
        p.put("battery50Enabled",0); p.put("battery25Enabled",1); p.put("battery10Enabled",0);
        service.saveGuardianNotification(p);
        ArgumentCaptor<java.util.Map> captured=ArgumentCaptor.forClass(java.util.Map.class);
        verify(mapper).upsertGuardianNotification(captured.capture());
        assertEquals("guardian-a",captured.getValue().get("guardianId"));
        assertFalse(captured.getValue().containsKey("protegeId"));
        assertEquals(0,captured.getValue().get("battery50Enabled"));
        assertEquals(1,captured.getValue().get("battery25Enabled"));
        org.mockito.InOrder order=inOrder(mapper);
        order.verify(mapper).lockGuardianAccount(anyMap());
        order.verify(mapper).countGuardianNotification(anyMap());
        order.verify(mapper).upsertGuardianNotification(anyMap());
    }
    @Test public void duplicateLegacyRowsAreNeverChosenOrOverwritten() throws Exception {
        when(mapper.countGuardianNotification(anyMap())).thenReturn(2);
        assertThrows(EgovBizException.class, () -> service.selectGuardianNotification());
        assertThrows(EgovBizException.class, () -> service.saveGuardianNotification(values()));
        verify(mapper,never()).selectGuardianNotification(anyMap());
        verify(mapper,never()).upsertGuardianNotification(anyMap());
    }
    @Test public void protectedAndAnonymousUsersCannotReadOrWrite() throws Exception {
        LoginVO login=new LoginVO(); login.setUserType("PROTECTED");
        when(auth.getAuthenticatedUser()).thenReturn(login);
        assertThrows(EgovBizException.class, () -> service.selectGuardianNotification());
        assertThrows(EgovBizException.class, () -> service.saveGuardianNotification(values()));
        when(auth.getAuthenticatedUser()).thenReturn(null);
        assertThrows(EgovBizException.class, () -> service.selectGuardianNotification());
        assertThrows(EgovBizException.class, () -> service.saveGuardianNotification(values()));
        verifyNoInteractions(mapper);
    }
    @Test public void incompleteAndInvalidValuesCannotWrite() throws Exception {
        assertThrows(EgovBizException.class, () -> service.saveGuardianNotification(null));
        HashMap<String,Object> p=values(); p.remove("battery10Enabled");
        assertThrows(EgovBizException.class, () -> service.saveGuardianNotification(p));
        p.put("battery10Enabled",2);
        assertThrows(EgovBizException.class, () -> service.saveGuardianNotification(p));
        verify(mapper,never()).upsertGuardianNotification(anyMap());
    }
}
