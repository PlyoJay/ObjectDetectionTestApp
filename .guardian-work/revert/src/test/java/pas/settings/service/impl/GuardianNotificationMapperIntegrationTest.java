package pas.settings.service.impl;

import static org.junit.Assert.*;
import java.nio.file.*;
import java.io.*;
import java.sql.*;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import org.junit.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.*;
import org.egovframe.rte.psl.dataaccess.util.EgovMap;

/** Opt-in local integration test. All writes target connection-local TEMPORARY tables. */
public class GuardianNotificationMapperIntegrationTest {
    private Connection connection;
    private SqlSession session;
    private SettingsMapper mapper;
    @Before public void setup() throws Exception {
        String config=System.getProperty("guardian.test.tomcatConfig");
        Assume.assumeNotNull(config);
        NodeList nodes=DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(config).getElementsByTagName("Resource");
        for(int i=0;i<nodes.getLength();i++) {
            Element e=(Element)nodes.item(i);
            if("pasDS".equals(e.getAttribute("name"))) {
                connection=DriverManager.getConnection(e.getAttribute("url"),e.getAttribute("username"),e.getAttribute("password"));
            }
        }
        assertNotNull(connection);
        // Shadow production table names only within this JDBC connection.
        try(Statement s=connection.createStatement(); ResultSet r=s.executeQuery("SHOW CREATE TABLE tb_guardian_notification_setting")) {
            assertTrue(r.next()); String ddl=r.getString(2); execute(ddl.replaceFirst("CREATE TABLE", "CREATE TEMPORARY TABLE"));
        }
        execute("CREATE TEMPORARY TABLE tb_user (user_id VARCHAR(50) PRIMARY KEY, user_type VARCHAR(20), user_name VARCHAR(50))");
        execute("CREATE TEMPORARY TABLE tb_guardian (guardian_id VARCHAR(50), user_id VARCHAR(50), status VARCHAR(20), guardian_seq INT, relation VARCHAR(20))");
        execute("INSERT INTO tb_user VALUES ('a','GUARDIAN','a'),('b','GUARDIAN','b'),('p1','PROTECTED','p1'),('p2','PROTECTED','p2'),('p3','PROTECTED','p3')");
        Configuration cfg=new Configuration(); cfg.setMapUnderscoreToCamelCase(true);
        cfg.getTypeAliasRegistry().registerAlias("egovMap", EgovMap.class);
        String path="src/main/java/pas/settings/service/impl/SettingsMapper.xml";
        try(InputStream in=Files.newInputStream(Paths.get(path))) {
            new XMLMapperBuilder(in,cfg,path,cfg.getSqlFragments()).parse();
        }
        session=new SqlSessionFactoryBuilder().build(cfg).openSession(connection);
        mapper=session.getMapper(SettingsMapper.class);
    }
    @After public void cleanup() throws Exception {
        if(session!=null) session.close();
        if(connection!=null && !connection.isClosed()) connection.close();
    }
    private void execute(String sql) throws Exception { try(Statement s=connection.createStatement()){s.execute(sql);} }
    private Map<String,Object> values(String id) {
        Map<String,Object> p=new HashMap<>(); p.put("guardianId",id);
        for(String f:new String[]{"sosEnabled","routeDeviationEnabled","lowBatteryEnabled","deviceOfflineEnabled"}) p.put(f,1);
        p.put("lowBatteryThreshold",25);
        return p;
    }
    @Test public void multipleConnectionsDisconnectAndReconnectKeepOneCommonSetting() throws Exception {
        Map<String,Object> a=values("a"); a.put("routeDeviationEnabled",0);
        mapper.insertGuardianNotification(a);
        execute("INSERT INTO tb_guardian VALUES ('a','p1','APPROVED',1,'FAMILY'),('a','p2','APPROVED',2,'FAMILY')");
        assertEquals(2,mapper.selectGuardianProtegeList(a).size());
        for(String protectedId:new String[]{"p1","p2","p3"}) {
            a.put("protegeId",protectedId); mapper.updateGuardianNotification(a);
            assertEquals(1,mapper.countGuardianNotification(a));
            assertEquals(0,((Number)mapper.selectGuardianNotification(a).get("routeDeviationEnabled")).intValue());
        }
        execute("DELETE FROM tb_guardian WHERE guardian_id='a'"); session.clearCache();
        assertEquals(0,mapper.selectGuardianProtegeList(a).size());
        assertEquals(1,mapper.countGuardianNotification(a));
        execute("INSERT INTO tb_guardian VALUES ('a','p3','APPROVED',3,'FAMILY')"); session.clearCache();
        assertEquals(1,mapper.selectGuardianProtegeList(a).size());
        assertEquals(0,((Number)mapper.selectGuardianNotification(a).get("routeDeviationEnabled")).intValue());
        Map<String,Object> b=values("b"); b.put("sosEnabled",0); mapper.insertGuardianNotification(b);
        assertEquals(1,((Number)mapper.selectGuardianNotification(a).get("sosEnabled")).intValue());
        assertEquals(0,((Number)mapper.selectGuardianNotification(b).get("sosEnabled")).intValue());
    }
    @Test public void existingBatteryValuesAndAllThresholdsRoundTripWithoutSchemaChanges() throws Exception {
        execute("INSERT INTO tb_guardian_notification_setting (guardian_id,low_battery_enabled,low_battery_threshold) VALUES ('a',1,25)");
        Map<String,Object> a=values("a");
        EgovMap legacy=mapper.selectGuardianNotification(a);
        assertEquals(1,((Number)legacy.get("lowBatteryEnabled")).intValue());
        assertEquals(25,((Number)legacy.get("lowBatteryThreshold")).intValue());
        for(int threshold:new int[]{50,25,10}) {
            for(int enabled:new int[]{0,1}) {
                a.put("lowBatteryThreshold",threshold); a.put("lowBatteryEnabled",enabled);
                mapper.updateGuardianNotification(a);
                EgovMap saved=mapper.selectGuardianNotification(a);
                assertEquals(enabled,((Number)saved.get("lowBatteryEnabled")).intValue());
                assertEquals(threshold,((Number)saved.get("lowBatteryThreshold")).intValue());
                assertEquals(1,mapper.countGuardianNotification(a));
            }
        }
    }
}
