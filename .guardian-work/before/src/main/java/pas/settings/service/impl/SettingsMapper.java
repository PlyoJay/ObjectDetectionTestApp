package pas.settings.service.impl;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.egovframe.rte.fdl.cmmn.exception.EgovBizException;
import org.egovframe.rte.psl.dataaccess.mapper.Mapper;
import org.egovframe.rte.psl.dataaccess.util.EgovMap;
import org.springframework.dao.DataAccessException;


@Mapper("pas.settings.service.impl.SettingsMapper")
public interface SettingsMapper {
	List<EgovMap> selectGuardianProtegeList(Map<String, Object> requestMap) throws SQLException, DataAccessException, Exception;

	int countApprovedGuardianProtege(Map<String, Object> requestMap) throws SQLException, DataAccessException, Exception;

	EgovMap selectGuardianNotification(Map<String, Object> requestMap) throws SQLException, DataAccessException, Exception;

	void upsertGuardianNotification(Map<String, Object> requestMap) throws SQLException, DataAccessException, Exception;

	void saveSettingList(HashMap<String, Object> requestMap) throws SQLException, DataAccessException, Exception;

	String selectSettingList(HashMap<String, Object> requestMap) throws SQLException, DataAccessException, Exception;

	void updateUser(Map<String, Object> requestMap) throws SQLException, DataAccessException, Exception;

	void updateGuardianPersonalInfo(Map<String, Object> requestMap) throws SQLException, DataAccessException, Exception;

	void updateSetting(Map<String, Object> requestMap) throws SQLException, DataAccessException, Exception;

	EgovMap selectUserInfo(HashMap<String, Object> requestMap) throws SQLException, DataAccessException, Exception;

	EgovMap selectFamInfo(HashMap<String, Object> requestMap) throws SQLException, DataAccessException, Exception;

	EgovMap updatePwd(HashMap<String, Object> requestMap) throws SQLException, DataAccessException, Exception;

	boolean selectUserChk(HashMap<String, Object> requestMap) throws SQLException, DataAccessException, Exception;

	void updateUserPwd(Map<String, Object> requestMap) throws SQLException, DataAccessException, Exception;
	
	
	// 단말기 정보
	EgovMap selectDeviceInfo (HashMap<String, Object> requestMap) throws EgovBizException, SQLException, Exception;
	int checkDeviceSerial(String deviceSerialNum) throws SQLException, DataAccessException, Exception;
	void updateDeviceInfo(HashMap<String, Object> requestMap) throws SQLException, DataAccessException, Exception;
	
	
	//단말기 펌웨어 최신버전 확인
	public EgovMap selectFirmVer (HashMap<String, Object> requestMap) throws SQLException,EgovBizException,Exception;
	
	//단말기 펌웨어 업데이트
	void updateFirmware(HashMap<String, Object> requestMap) throws SQLException,DataAccessException, Exception;

}
