import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.sql.*;
class InspectGuardianDb {
 public static void main(String[] args) throws Exception {
  Properties p = new Properties();
  javax.xml.parsers.DocumentBuilderFactory factory=javax.xml.parsers.DocumentBuilderFactory.newInstance(); org.w3c.dom.NodeList nodes=factory.newDocumentBuilder().parse(args[0]).getElementsByTagName("Resource"); for(int n=0;n<nodes.getLength();n++){ org.w3c.dom.Element e=(org.w3c.dom.Element)nodes.item(n); if("pasDS".equals(e.getAttribute("name"))){p.setProperty("Globals.pas.Url",e.getAttribute("url"));p.setProperty("Globals.pas.UserName",e.getAttribute("username"));p.setProperty("Globals.pas.Password",e.getAttribute("password"));}}
  String url=p.getProperty("Globals.pas.Url").replace("jdbc:log4jdbc:","jdbc:");
  try(Connection c=DriverManager.getConnection(url+"?connectTimeout=5000&socketTimeout=5000",p.getProperty("Globals.pas.UserName"),p.getProperty("Globals.pas.Password"))) {
   c.setReadOnly(true);
   for(String sql:new String[]{"SHOW CREATE TABLE tb_guardian_notification_setting", "SELECT COUNT(*) AS total_rows, COUNT(DISTINCT guardian_id) AS guardians FROM tb_guardian_notification_setting", "SELECT COUNT(*) AS guardians_with_duplicates FROM (SELECT guardian_id FROM tb_guardian_notification_setting GROUP BY guardian_id HAVING COUNT(*)>1) d", "SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tb_guardian_notification_setting'"}) {
    System.out.println(sql);
    try(Statement s=c.createStatement(); ResultSet r=s.executeQuery(sql)) { while(r.next()) { for(int i=1;i<=r.getMetaData().getColumnCount();i++) System.out.print(r.getMetaData().getColumnLabel(i)+"="+r.getString(i)+" "); System.out.println(); } }
   }
  } catch(SQLException e) { System.out.println("DB inspection failed: SQLState="+e.getSQLState()+", code="+e.getErrorCode()+", "+e.getClass().getSimpleName()); System.exit(2); }
 }
}

