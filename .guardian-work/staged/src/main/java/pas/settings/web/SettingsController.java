package pas.settings.web;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.egovframe.rte.psl.dataaccess.util.EgovMap;
import org.egovframe.rte.fdl.cmmn.exception.EgovBizException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import egovframework.com.cmm.kendo.DataSourceRequest;
import egovframework.com.cmm.kendo.DataSourceResult;
import egovframework.com.cmm.service.LoginVO;
import egovframework.com.cmm.util.CmmUserDetailsHelper;
import pas.settings.service.SettingsSerivce;
import pas.user.service.UserType;


@Controller
public class SettingsController {
	

	@Autowired
	SettingsSerivce settingsSerivce;

	@GetMapping("/setting.do")
	public String getSetting() throws Exception {
		LoginVO loginInfo = CmmUserDetailsHelper.getAuthenticatedUser();
		return getSettingView(loginInfo);
	}

	static String getSettingView(LoginVO loginInfo) {
		return isGuardian(loginInfo) ? "setting/guardianNotificationSetting" : "setting/setting";
	}

	@GetMapping("/infoEdit.do")
	public String getInfoEdit() throws Exception {
		LoginVO loginInfo = CmmUserDetailsHelper.getAuthenticatedUser();
		return getInfoEditView(loginInfo);
	}

	static String getInfoEditView(LoginVO loginInfo) {
		if (isGuardian(loginInfo)) {
			return "setting/guardianInfoEdit";
		}
		return "setting/infoEdit";
	}

	static boolean isGuardian(LoginVO loginInfo) {
		return loginInfo != null && UserType.GUARDIAN.name().equals(loginInfo.getUserType());
	}

	@RequestMapping("/popup/jusoPopup.do")
	public String zipCode() {
		return "/popup/jusoPopup";
	}

	@RequestMapping("/popup/guardianPwd.do")
	public String guardianPwd() {
		return "/popup/guardianPwd";
	}

	@GetMapping("/popup/caregiverInfoPopup.do")
	public String getCaregiverInfoPopup() {
		System.out.println("caregiverInfoPopup");
		return "popup/caregiverInfoPopup";
	}

	@RequestMapping(value = "/settings/saveSettingList.json")
	@ResponseBody
	public ResponseEntity<DataSourceResult> saveSettingList(@RequestBody DataSourceRequest request) throws Exception {
		DataSourceResult result = new DataSourceResult();
		settingsSerivce.uptSettingList(request.getData());

		return new ResponseEntity<DataSourceResult>(result, HttpStatus.OK);
	}

	@PostMapping("/settings/selectGuardianProtegeList.json")
	@ResponseBody
	public ResponseEntity<DataSourceResult> selectGuardianProtegeList() throws Exception {
		DataSourceResult result = new DataSourceResult();
		LoginVO loginInfo = CmmUserDetailsHelper.getAuthenticatedUser();
		if (!isGuardian(loginInfo)) {
			result.setErrorCode(-1);
			result.setErrorMsg("보호자 계정만 사용할 수 있습니다.");
			return new ResponseEntity<DataSourceResult>(result, HttpStatus.OK);
		}
		result.setData(settingsSerivce.selectGuardianProtegeList());
		return new ResponseEntity<DataSourceResult>(result, HttpStatus.OK);
	}

	@PostMapping("/settings/selectGuardianNotification.json")
	@ResponseBody
	public ResponseEntity<DataSourceResult> selectGuardianNotification() throws Exception {
		DataSourceResult result = new DataSourceResult();
		LoginVO loginInfo = CmmUserDetailsHelper.getAuthenticatedUser();
		if (!isGuardian(loginInfo)) {
			result.setErrorCode(-1);
			result.setErrorMsg("보호자 계정만 사용할 수 있습니다.");
			return new ResponseEntity<DataSourceResult>(result, HttpStatus.OK);
		}
		try {
			result.setData(settingsSerivce.selectGuardianNotification());
		} catch (EgovBizException e) {
			result.setErrorCode(-1);
			result.setErrorMsg(e.getMessage());
		}
		return new ResponseEntity<DataSourceResult>(result, HttpStatus.OK);
	}

	@PostMapping("/settings/saveGuardianNotification.json")
	@ResponseBody
	public ResponseEntity<DataSourceResult> saveGuardianNotification(@RequestBody DataSourceRequest request) throws Exception {
		DataSourceResult result = new DataSourceResult();
		LoginVO loginInfo = CmmUserDetailsHelper.getAuthenticatedUser();
		if (!isGuardian(loginInfo)) {
			result.setErrorCode(-1);
			result.setErrorMsg("보호자 계정만 사용할 수 있습니다.");
			return new ResponseEntity<DataSourceResult>(result, HttpStatus.OK);
		}
		try {
			settingsSerivce.saveGuardianNotification(request.getData());
		} catch (EgovBizException e) {
			result.setErrorCode(-1);
			result.setErrorMsg(e.getMessage());
		}
		return new ResponseEntity<DataSourceResult>(result, HttpStatus.OK);
	}

	@PostMapping("/settings/saveInfoEditAll.json")
	@ResponseBody
	public ResponseEntity<DataSourceResult> saveInfoEditAll(@RequestBody DataSourceRequest request) throws Exception {
		DataSourceResult result = new DataSourceResult();
		settingsSerivce.saveInfoEditAll(request.getData());
		return new ResponseEntity<DataSourceResult>(result, HttpStatus.OK);
	}

	@PostMapping("/settings/saveGuardianInfoEdit.json")
	@ResponseBody
	public ResponseEntity<DataSourceResult> saveGuardianInfoEdit(@RequestBody DataSourceRequest request) throws Exception {
		DataSourceResult result = new DataSourceResult();
		LoginVO loginInfo = CmmUserDetailsHelper.getAuthenticatedUser();
		if (!isGuardian(loginInfo)) {
			result.setErrorCode(-1);
			result.setErrorMsg("보호자 계정만 사용할 수 있습니다.");
			return new ResponseEntity<DataSourceResult>(result, HttpStatus.OK);
		}
		settingsSerivce.saveGuardianInfoEdit(request.getData());
		return new ResponseEntity<DataSourceResult>(result, HttpStatus.OK);
	}

	@RequestMapping(value = "/settings/selectInfo.json")
	@ResponseBody
	public ResponseEntity<DataSourceResult> selectInfo(@RequestBody DataSourceRequest request) throws Exception {
		HashMap<String, Object> requestMap = request.getData();
		DataSourceResult response = new DataSourceResult();
		EgovMap responseList = settingsSerivce.selectInfo(requestMap);
		response.setData(responseList);
		return new ResponseEntity<DataSourceResult>(response, HttpStatus.OK);
	}

	@RequestMapping(value = "/settings/updatePwd.json")
	@ResponseBody
	public ResponseEntity<DataSourceResult> updatePwd(@RequestBody DataSourceRequest request) throws Exception {
		HashMap<String, Object> requestMap = request.getData();
		DataSourceResult response = new DataSourceResult();
		EgovMap result = settingsSerivce.updatePwd(requestMap);

		response.setData(result);
		return new ResponseEntity<DataSourceResult>(response, HttpStatus.OK);
	}
	
	@RequestMapping(value = "/settings/selectDeviceInfo.json")
	@ResponseBody
	public ResponseEntity<DataSourceResult> selectDeviceInfo(@RequestBody DataSourceRequest request) throws Exception {
		HashMap<String, Object> requestMap = request.getData();
	    
	    // 세션에서 로그인한 사용자 ID 가져오기
	    LoginVO loginInfo = CmmUserDetailsHelper.getAuthenticatedUser();
	    requestMap.put("userId", loginInfo.getUserId());
	    
	    EgovMap deviceInfo = settingsSerivce.selectDeviceInfo(requestMap);
	    
	    DataSourceResult response = new DataSourceResult();
	    response.setData(deviceInfo); // 조회된 데이터 담기
	    
	    System.out.println("selectDeviceInfo Controller 확인 ======"+response);
	    
	    
	    return new ResponseEntity<DataSourceResult>(response, HttpStatus.OK);
	}
	
	@RequestMapping(value = "/settings/updateDeviceInfo.json")
	@ResponseBody
	public ResponseEntity<DataSourceResult> updateDeviceInfo(@RequestBody DataSourceRequest request) throws Exception {
	    HashMap<String, Object> requestMap = request.getData();
	    DataSourceResult response = new DataSourceResult();

	    // Service 호출 (업데이트된 행의 개수를 반환받음)
	    int result = settingsSerivce.updateDeviceInfo(requestMap);
	    
	    // ⭐ 핵심: 공통 함수의 강제 빈 팝업을 막기 위해 무조건 0(성공)으로 보냅니다!
	    response.setErrorCode(0); 
	    
	    // 진짜 상태와 메시지를 담을 Map 생성
	    HashMap<String, Object> resultMap = new HashMap<>();
	    resultMap.put("status", result); // 1이면 성공, 0이면 실패
	    
	    if (result > 0) {
	        resultMap.put("msg", "단말기 등록이 완료되었습니다.");
	    } else if (result == 0){
	        resultMap.put("msg", "일치하는 시리얼넘버가 없습니다.");
	    } else {
	        resultMap.put("msg", "단말기 등록 중 오류가 발생했습니다.");
	    }
	    
	    // data에 진짜 결과를 담아서 프론트로 전달
	    response.setData(resultMap);
	    
	    return new ResponseEntity<DataSourceResult>(response, HttpStatus.OK);
	}
	

	
	@RequestMapping(value="/settings/selectFirmVer.json")
	@ResponseBody
	public ResponseEntity<DataSourceResult> selectFirmVer (@RequestBody DataSourceRequest requestMap ) throws Exception {
		
		DataSourceResult response = new DataSourceResult();
		EgovMap firmVer = settingsSerivce.selectFirmVer(requestMap.getData());
		
		response.setData(firmVer);
		
		System.out.println("firmVer ======="+firmVer);
		
		return new ResponseEntity<DataSourceResult>(response, HttpStatus.OK);
		
		
	}
	
	@RequestMapping(value="/settings/updateFirmware.json")
	@ResponseBody
	public ResponseEntity<DataSourceResult> updateFirmware(@RequestBody DataSourceRequest request) throws Exception{
		HashMap<String,Object> requestMap =request.getData();
		DataSourceResult response = new DataSourceResult();

		int result = settingsSerivce.updateFirmware(requestMap);
		
		if(result == 1 ) {
			response.setErrorCode(0);
		}else if (result == 0){
			response.setErrorCode(-1);
			response.setErrorMsg("제일 최신의 펌웨어가 적용되어 있습니다.");
		}else {
			response.setErrorCode(-2);
			response.setErrorMsg("펌웨어 버전 오류. 현재 버전 > 최신버전 ");
			System.out.println("펌웨어 버전 오류. 현재 버전 > 최신버전");
		}
		return new ResponseEntity<DataSourceResult>(response,HttpStatus.OK);
	}

	
	
	
	
}
