package pas.settings.service.impl;

import java.io.File;
import java.io.FileWriter;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.annotation.Resource;

import org.egovframe.rte.fdl.cmmn.exception.EgovBizException;
import org.egovframe.rte.fdl.cryptography.EgovPasswordEncoder;
import org.egovframe.rte.psl.dataaccess.util.EgovMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import egovframework.com.cmm.service.EgovProperties;
import egovframework.com.cmm.service.LoginVO;
import egovframework.com.cmm.util.CmmUserDetailsHelper;
import pas.settings.service.SettingsSerivce;
import pas.user.service.UserType;
import pas.registUser.service.impl.RegistUserMapper;

@Service("SettingsService")
public class SettingsServiceImpl implements SettingsSerivce {

	@Autowired
	SettingsMapper settingsMapper;

	@Autowired
	RegistUserMapper registUserMapper;

	@Autowired
	private EgovPasswordEncoder egovPasswordEncoder;

	@Override
	public List<EgovMap> selectGuardianProtegeList() throws EgovBizException, SQLException, Exception {
		HashMap<String, Object> params = authenticatedGuardianParams();
		return settingsMapper.selectGuardianProtegeList(params);
	}

	@Override
	public EgovMap selectGuardianNotification() throws EgovBizException, SQLException, Exception {
		HashMap<String, Object> params = authenticatedGuardianParams();
		requireSingleGuardianSetting(params);
		EgovMap setting = settingsMapper.selectGuardianNotification(params);
		if (setting == null) {
			setting = new EgovMap();
			setting.put("sosEnabled", 1);
			setting.put("routeDeviationEnabled", 1);
			setting.put("lowBatteryEnabled", 1);
			setting.put("lowBatteryThreshold", 25);
			setting.put("deviceOfflineEnabled", 1);
		}
		return setting;
	}

	@Override
	@Transactional(rollbackFor = { EgovBizException.class, SQLException.class, Exception.class })
	public void saveGuardianNotification(HashMap<String, Object> requestMap) throws EgovBizException, SQLException, Exception {
		HashMap<String, Object> params = authenticatedGuardianParams();
		if (requestMap == null) throw new EgovBizException("알림 설정값이 없습니다.");
		params.put("sosEnabled", enabledValue(requestMap.get("sosEnabled")));
		params.put("routeDeviationEnabled", enabledValue(requestMap.get("routeDeviationEnabled")));
		params.put("lowBatteryEnabled", enabledValue(requestMap.get("lowBatteryEnabled")));
		int threshold;
		try {
			threshold = Integer.parseInt(String.valueOf(requestMap.get("lowBatteryThreshold")));
		} catch (NumberFormatException e) {
			throw new EgovBizException("배터리 알림 기준이 올바르지 않습니다.");
		}
		if (threshold != 50 && threshold != 25 && threshold != 10) {
			throw new EgovBizException("배터리 알림 기준이 올바르지 않습니다.");
		}
		params.put("lowBatteryThreshold", threshold);
		params.put("deviceOfflineEnabled", enabledValue(requestMap.get("deviceOfflineEnabled")));
		// Serialize first saves for the same account before inspecting existing rows.
		if (settingsMapper.lockGuardianAccount(params) == null) {
			throw new EgovBizException("보호자 계정을 확인할 수 없습니다.");
		}
		int count = requireSingleGuardianSetting(params);
		if (count == 0) {
			settingsMapper.insertGuardianNotification(params);
		} else {
			settingsMapper.updateGuardianNotification(params);
		}
	}

	private HashMap<String, Object> authenticatedGuardianParams() throws EgovBizException {
		LoginVO loginInfo = CmmUserDetailsHelper.getAuthenticatedUser();
		if (loginInfo == null || !UserType.GUARDIAN.name().equals(loginInfo.getUserType())) {
			throw new EgovBizException("보호자 계정만 사용할 수 있습니다.");
		}
		HashMap<String, Object> params = new HashMap<String, Object>();
		params.put("guardianId", loginInfo.getUserId());
		return params;
	}

	private int requireSingleGuardianSetting(Map<String, Object> params) throws Exception {
		int count = settingsMapper.countGuardianNotification(params);
		if (count > 1) {
			throw new EgovBizException("피보호자별 기존 알림 설정이 여러 건입니다. 관리자에게 공통 설정 전환을 요청해 주세요.");
		}
		return count;
	}
	private int enabledValue(Object value) throws EgovBizException {
		String text = String.valueOf(value);
		if ("1".equals(text) || "true".equalsIgnoreCase(text) || "Y".equalsIgnoreCase(text)) return 1;
		if ("0".equals(text) || "false".equalsIgnoreCase(text) || "N".equalsIgnoreCase(text)) return 0;
		throw new EgovBizException("알림 설정값이 올바르지 않습니다.");
	}

	@Override
	@Transactional(rollbackFor = { EgovBizException.class, SQLException.class, Exception.class })
	public void saveGuardianInfoEdit(HashMap<String, Object> requestMap) throws EgovBizException, SQLException, Exception {
		LoginVO loginInfo = CmmUserDetailsHelper.getAuthenticatedUser();
		if (loginInfo == null) {
			throw new EgovBizException("로그인 정보가 없습니다.");
		}
		requestMap.put("userId", loginInfo.getUserId());
		settingsMapper.updateGuardianPersonalInfo(requestMap);
	}

	@Override
	public void uptSettingList(HashMap<String, Object> requestMap) throws EgovBizException, SQLException, Exception {
	    LoginVO loginInfo = CmmUserDetailsHelper.getAuthenticatedUser();
	    String userId = loginInfo.getUserId();
	    Map<String, Object> formData = (Map<String, Object>) requestMap.get("formData");
	    formData.put("userId", userId);

	    List<Map<String, Object>> changeLog = (List<Map<String, Object>>) requestMap.get("changeLog");

	    // 서머리 설정
	    settingsMapper.updateSetting(formData);
	    
	    // ===== 변경사항이 있을 때만 로그 파일 저장 =====
	    if (changeLog != null && !changeLog.isEmpty()) {
	        Date now = new Date();
	        
	        // 날짜 포맷팅
	        String currentYear = new SimpleDateFormat("yyyy").format(now); // YYYY
	        String currentDate = new SimpleDateFormat("yyyyMMdd").format(now); // yyyymmdd
	        String currentTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(now); // yyyy-MM-dd HH:mm:ss

	        //  로그 파일 내용 생성
	        StringBuilder logContent = new StringBuilder();
	        for (Map<String, Object> change : changeLog) {
	            // 요청하신 포맷에 맞춰 작성
	            logContent.append("변경된 설정 내용 : ").append(change.get("fieldLabel")).append("\n");
	            logContent.append(change.get("oldValue")).append(" -> ").append(change.get("newValue")).append("\n");
	            logContent.append(currentTime).append("\n\n");
	        }

	        // 디렉토리 및 파일 경로 설정
	        // 베이스 경로 (Rocky Linux 서버 경로)
	        String basePath = "/home/hitechU/settingLog"; 
	        
	        // 디렉토리명: YYYYuserId (예: 2026admin)
	        String dirName = currentYear + userId; 
	        
	        // 파일명: yyyymmdd_userId.log (예: 20260303_admin.log)
	        String fileName = currentDate + "_" + userId + ".log"; 

	        File logDir = new File(basePath, dirName);

	        //  디렉토리가 없으면 다중 디렉토리(mkdirs) 생성
	        if (!logDir.exists()) {
	            logDir.mkdirs();
	        }

	        File saveFile = new File(logDir, fileName);

	        // 파일에 추가 모드로 저장 (append = true)
	        try (FileWriter fw = new FileWriter(saveFile, true)) {
	            fw.write(logContent.toString());
	        } catch (Exception e) {
	            // 로그 기록 실패 시 서버 콘솔에 에러 출력 (운영 중단 방지)
	            e.printStackTrace(); 
	        }
	    }
	}

	@Override
	@Transactional(rollbackFor = { EgovBizException.class, SQLException.class, Exception.class })
	public void saveInfoEditAll(HashMap<String, Object> requestMap) throws EgovBizException, SQLException, Exception {
		LoginVO loginInfo = CmmUserDetailsHelper.getAuthenticatedUser();
		if (loginInfo == null) {
			throw new EgovBizException("로그인 정보가 없습니다.");
		}
		requestMap.put("userId", loginInfo.getUserId());
		settingsMapper.updateUser(requestMap);

		List<Map<String, Object>> guardianList = (List<Map<String, Object>>) requestMap.get("guardianList");
		if (guardianList == null) {
			return;
		}

		for (Map<String, Object> guardian : guardianList) {
			String status = String.valueOf(guardian.get("status"));
			guardian.put("userId", loginInfo.getUserId());
			guardian.put("regUserId", loginInfo.getUserId());
			if ("I".equals(status)) {
				registUserMapper.insertGuardianRegistInfo(guardian);
			} else if ("D".equals(status)) {
				registUserMapper.deleteGuardianRegistInfo(guardian);
			}
		}
	}

	@Override
	@Transactional
	public EgovMap selectInfo(HashMap<String, Object> requestMap) throws EgovBizException, SQLException, Exception {

	    // 1. 로그인 정보 가져오기
	    LoginVO loginInfo = CmmUserDetailsHelper.getAuthenticatedUser();

	    if (loginInfo == null) {
	        throw new Exception("로그인 정보가 없습니다.");
	    }

	    String userId = loginInfo.getUserId();

	    // 2. ROLE 안전하게 가져오기 
	    String userRole = "ROLE_USER"; // 기본값

	    if (loginInfo.getUserRoleList() != null && !loginInfo.getUserRoleList().isEmpty()) {
	        userRole = loginInfo.getUserRoleList().get(0);
	    }

	    // 3. requestMap에 userId 세팅
	    requestMap.put("userId", userId);

	    EgovMap result = null;

	    // 4. ROLE 기준으로 조회
	    if ("ROLE_USER".equals(userRole)) {
	        result = (EgovMap) settingsMapper.selectUserInfo(requestMap);
	    } else {
	        result = (EgovMap) settingsMapper.selectFamInfo(requestMap);
	    }

	    // 5. 결과 null 방어 
	    if (result == null) {
	        result = new EgovMap();
	    }

	    // 6. ROLE 넣기
	    result.put("userRole", userRole);

	    return result;
	}

	@Override
	@Transactional
	public EgovMap updatePwd(HashMap<String, Object> requestMap) throws EgovBizException, SQLException, Exception {
		EgovMap result = new EgovMap();
		LoginVO loginInfo = CmmUserDetailsHelper.getAuthenticatedUser();
		String userId = loginInfo.getUserId();
		requestMap.put("userId", userId); // 로그인 정보 등록

		String userPassword = Optional.ofNullable(requestMap.get("userPassword")).map(Object::toString).orElse("");
		String enPassword = egovPasswordEncoder.encryptPassword(userPassword);
		requestMap.put("enPassword", enPassword);

		boolean userChk = settingsMapper.selectUserChk(requestMap);

		if(userChk) {
			String newPassword = Optional.ofNullable(requestMap.get("newPassword")).map(Object::toString).orElse("");
			String newPasswordChk = Optional.ofNullable(requestMap.get("newPasswordChk")).map(Object::toString).orElse("");

			if( !("").equals(newPassword) && newPassword.equals(newPasswordChk)) {
				String newEnPassword = egovPasswordEncoder.encryptPassword(newPassword);
				requestMap.put("newEnPassword", newEnPassword);

				settingsMapper.updateUserPwd(requestMap);

				result.put("userChk", userChk);
			}
		} else {
			result.put("userChk", userChk);
		}

		return result;
	}
	
	

	@Override
	@Transactional(rollbackFor = { EgovBizException.class, SQLException.class, Exception.class })
	public int updateDeviceInfo(HashMap<String, Object> requestMap) throws EgovBizException, SQLException, Exception {
	    String deviceSerialNum = (String) requestMap.get("deviceSerialNum");
	    
	    //시리얼 넘버 확인
	    int count = settingsMapper.checkDeviceSerial(deviceSerialNum);
	    
	    if (count > 0) {
	    	
	        LoginVO loginInfo = CmmUserDetailsHelper.getAuthenticatedUser();
	        requestMap.put("userId", loginInfo.getUserId());
	        requestMap.put("lastChgUserId", loginInfo.getUserId());
	        
	        settingsMapper.updateDeviceInfo(requestMap);
	        return 1; // 성공
	    } else if (count == 0) {
	        return 0; // 실패 (존재하지 않음)
	    } else {
	    	return -1;
	    }
	}
	
	@Override
	public EgovMap selectDeviceInfo (HashMap<String,Object> requestMap)throws EgovBizException, SQLException,Exception {
		return settingsMapper.selectDeviceInfo(requestMap);
	}
	
	
	@Override
	public EgovMap selectFirmVer(HashMap<String,Object> requestMap) throws EgovBizException,SQLException, Exception {
		return settingsMapper.selectFirmVer(requestMap);
	}
	
	@Override
	@Transactional(rollbackFor = { EgovBizException.class, SQLException.class, Exception.class })
	public int updateFirmware (HashMap<String,Object> requestMap) throws EgovBizException, SQLException,Exception{
		
		String myFirmVerStr = (String) requestMap.get("myFirmVer");
		
		EgovMap MapLastVer = settingsMapper.selectFirmVer(requestMap);
		
		if(MapLastVer !=null ) {
			String lastVerStr = String.valueOf(MapLastVer.get("version"));
			
			double myFirmVer = Double.parseDouble(myFirmVerStr);
			double lastVer = Double.parseDouble(lastVerStr);
			
			//System.out.println("내 펌웨어 버전 ======="+myFirmVer+"////"+"DB 라스트 버전 ======="+lastVer);
			
			if (myFirmVer < lastVer) {
				
				requestMap.put("latestVersion", lastVerStr);
				
			    LoginVO loginInfo = CmmUserDetailsHelper.getAuthenticatedUser();
		        requestMap.put("userId", loginInfo.getUserId());
		        
				settingsMapper.updateFirmware(requestMap);
				
				return 1;
				
		    }else if(myFirmVer == lastVer) {
		    	return 0;
		    }else if(myFirmVer > lastVer){
		    	return -2;
		    }else {
		    	return -1;
		    }
			
		}
		return 0;
		
		
	}


}

