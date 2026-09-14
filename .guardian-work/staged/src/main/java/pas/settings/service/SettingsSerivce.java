package pas.settings.service;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.egovframe.rte.fdl.cmmn.exception.EgovBizException;
import org.egovframe.rte.psl.dataaccess.util.EgovMap;

public interface SettingsSerivce {
	List<EgovMap> selectGuardianProtegeList() throws EgovBizException, SQLException, Exception;

	EgovMap selectGuardianNotification() throws EgovBizException, SQLException, Exception;

	void saveGuardianNotification(HashMap<String, Object> data) throws EgovBizException, SQLException, Exception;

	void saveGuardianInfoEdit(HashMap<String, Object> data) throws EgovBizException, SQLException, Exception;

	void saveInfoEditAll(HashMap<String, Object> data) throws EgovBizException, SQLException, Exception;

	void uptSettingList(HashMap<String, Object> requestMap) throws EgovBizException, SQLException, Exception;

	EgovMap selectInfo(HashMap<String, Object> data) throws EgovBizException, SQLException, Exception;

	EgovMap updatePwd(HashMap<String, Object> data) throws EgovBizException, SQLException, Exception;
	
	
	// 단말기 정보 불러오기
	EgovMap selectDeviceInfo (HashMap<String, Object> requestMap) throws EgovBizException, SQLException, Exception;
	
	// 단말기 사용자 등록 
	int updateDeviceInfo(HashMap<String, Object> requestMap) throws EgovBizException, SQLException, Exception;
	
	//단말기 펌웨어 버전 체크
	public EgovMap selectFirmVer (HashMap<String, Object> requestMap) throws SQLException,EgovBizException,Exception;
	
	//단말기 펌웨어업데이트
	
	int updateFirmware(HashMap<String,Object> requestMap) throws EgovBizException, SQLException,Exception;
	
	
	
	
	
	

}
