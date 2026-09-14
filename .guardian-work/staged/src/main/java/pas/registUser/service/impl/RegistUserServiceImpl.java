package pas.registUser.service.impl;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import org.egovframe.rte.fdl.cmmn.exception.EgovBizException;
import org.egovframe.rte.fdl.cryptography.EgovPasswordEncoder;
import org.egovframe.rte.psl.dataaccess.util.EgovMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import egovframework.com.cmm.service.LoginVO;
import egovframework.com.cmm.util.CmmUserDetailsHelper;
import pas.registUser.service.RegistUserService;
import pas.user.service.UserType;

@Service("RegistUserService")
public class RegistUserServiceImpl implements RegistUserService {

	@Autowired
	RegistUserMapper registUserMapper;

	@Autowired
	private EgovPasswordEncoder egovPasswordEncoder;

	@Override
	public boolean existsGuardianByUserId(String userId) throws EgovBizException, SQLException, Exception {
		if (userId == null || userId.isEmpty()) {
			return false;
		}
		return registUserMapper.countGuardianByUserId(userId) > 0;
	}

	// 보호자 조회
	@Override
	public List<EgovMap> selectGuardianList(Map<String, Object> requestMap) throws EgovBizException, SQLException, Exception  {
		LoginVO loginInfo = CmmUserDetailsHelper.getAuthenticatedUser();
		if (loginInfo == null || !UserType.PROTECTED.name().equals(loginInfo.getUserType())) {
			throw new EgovBizException("피보호자 계정만 보호자 목록을 조회할 수 있습니다.");
		}
	    String userId = loginInfo.getUserId();
	    requestMap.put("userId", userId);
		List<EgovMap> result = (List<EgovMap>) registUserMapper.selectGuardianList(requestMap);

		return result;
	}

	@Override
	public List<EgovMap> selectProtectedUserList(Map<String, Object> requestMap) throws EgovBizException, SQLException, Exception {
		LoginVO loginInfo = CmmUserDetailsHelper.getAuthenticatedUser();
		if (loginInfo == null || !UserType.GUARDIAN.name().equals(loginInfo.getUserType())) {
			throw new EgovBizException("보호자 계정만 피보호자 목록을 조회할 수 있습니다.");
		}

		requestMap.put("guardianId", loginInfo.getUserId());
		return registUserMapper.selectProtectedUserList(requestMap);
	}

	@Override
	public EgovMap verifyProtectedUser(Map<String, Object> requestMap) throws EgovBizException, SQLException, Exception {
		LoginVO loginInfo = requireUserType(UserType.GUARDIAN, "보호자 계정만 피보호자를 확인할 수 있습니다.");
		Map<String, Object> condition = new java.util.HashMap<String, Object>();
		condition.put("userName", stringValue(requestMap == null ? null : requestMap.get("userName")));
		condition.put("userId", stringValue(requestMap == null ? null : requestMap.get("userId")));
		condition.put("phoneNo", digitsOnly(requestMap == null ? null : requestMap.get("phoneNo")));
		if (stringValue(condition.get("userName")).isEmpty()
				|| stringValue(condition.get("userId")).isEmpty()
				|| stringValue(condition.get("phoneNo")).isEmpty()) {
			return null;
		}
		if (loginInfo.getUserId().equals(condition.get("userId"))) {
			return null;
		}
		return registUserMapper.selectProtectedUserForConnection(condition);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void requestProtectedUserConnection(Map<String, Object> requestMap) throws EgovBizException, SQLException, Exception {
		LoginVO loginInfo = requireUserType(UserType.GUARDIAN, "보호자 계정만 연결 요청을 보낼 수 있습니다.");
		if (requestMap == null) {
			requestMap = new java.util.HashMap<String, Object>();
		}
		String protectedUserId = stringValue(requestMap.get("protectedUserId"));
		String relation = stringValue(requestMap.get("relation"));
		if (protectedUserId.isEmpty()) {
			throw new EgovBizException("피보호자 확인을 먼저 수행해 주세요.");
		}
		if (!("FAMILY".equals(relation) || "ACQUAINTANCE".equals(relation))) {
			throw new EgovBizException("올바른 관계를 선택해주세요.");
		}
		if (loginInfo.getUserId().equals(protectedUserId)) {
			throw new EgovBizException("자기 자신에게 연결 요청을 보낼 수 없습니다.");
		}
		EgovMap protectedUser = registUserMapper.selectConnectionUser(protectedUserId);
		if (protectedUser == null || !UserType.PROTECTED.name().equals(String.valueOf(protectedUser.get("userType")))) {
			throw new EgovBizException("입력한 정보와 일치하는 피보호자를 찾을 수 없습니다.");
		}
		Map<String, Object> connection = new java.util.HashMap<String, Object>();
		connection.put("protectedUserId", protectedUserId);
		connection.put("guardianId", loginInfo.getUserId());
		String currentStatus = registUserMapper.selectGuardianConnectionStatus(connection);
		if ("PENDING".equals(currentStatus)) {
			throw new EgovBizException("이미 연결 요청을 보낸 피보호자입니다.");
		}
		if ("APPROVED".equals(currentStatus)) {
			throw new EgovBizException("이미 연결된 피보호자입니다.");
		}
		EgovMap guardian = registUserMapper.selectConnectionUser(loginInfo.getUserId());
		if (guardian == null || !UserType.GUARDIAN.name().equals(String.valueOf(guardian.get("userType")))) {
			throw new EgovBizException("로그인한 보호자 정보를 확인할 수 없습니다.");
		}
		connection.put("guardianName", guardian.get("userName"));
		connection.put("relation", relation);
		if (registUserMapper.insertProtectedUserConnectionRequest(connection) != 1) {
			throw new EgovBizException("피보호자 연결 요청을 저장하지 못했습니다.");
		}
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void disconnectProtectedUser(Map<String, Object> requestMap) throws EgovBizException, SQLException, Exception {
		LoginVO loginInfo = requireUserType(UserType.GUARDIAN, "보호자 계정만 피보호자 연결을 해제할 수 있습니다.");
		String protectedUserId = stringValue(requestMap == null ? null : requestMap.get("protectedUserId"));
		if (protectedUserId.isEmpty()) {
			throw new EgovBizException("연결 해제할 피보호자 정보가 없습니다.");
		}

		Map<String, Object> connection = new java.util.HashMap<String, Object>();
		connection.put("guardianId", loginInfo.getUserId());
		connection.put("protectedUserId", protectedUserId);
		String currentStatus = registUserMapper.selectGuardianConnectionStatus(connection);
		if (!"APPROVED".equals(currentStatus)) {
			throw new EgovBizException("이미 연결이 해제된 피보호자입니다.");
		}

		// Guardian notification preferences survive connection removal.
		if (registUserMapper.deleteApprovedProtectedUserConnection(connection) != 1) {
			throw new EgovBizException("연결 해제 중 오류가 발생했습니다.");
		}
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void disconnectGuardian(Map<String, Object> requestMap) throws EgovBizException, SQLException, Exception {
		LoginVO loginInfo = requireUserType(UserType.PROTECTED, "피보호자 계정만 보호자 연결을 해제할 수 있습니다.");
		String guardianId = stringValue(requestMap == null ? null : requestMap.get("guardianId"));
		if (guardianId.isEmpty()) {
			throw new EgovBizException("연결 해제할 보호자 정보가 없습니다.");
		}

		Map<String, Object> connection = new java.util.HashMap<String, Object>();
		connection.put("protectedUserId", loginInfo.getUserId());
		connection.put("guardianId", guardianId);
		String currentStatus = registUserMapper.selectGuardianConnectionStatus(connection);
		if (!"APPROVED".equals(currentStatus)) {
			throw new EgovBizException("이미 연결이 해제된 보호자입니다.");
		}

		// Guardian notification preferences survive connection removal.
		if (registUserMapper.deleteApprovedGuardianConnectionByProtected(connection) != 1) {
			throw new EgovBizException("연결 해제 중 오류가 발생했습니다.");
		}
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void approveGuardianRequest(Map<String, Object> requestMap) throws EgovBizException, SQLException, Exception {
		Map<String, Object> condition = protectedRequestCondition(requestMap);
		if (registUserMapper.approveGuardianRequest(condition) != 1) {
			throw new EgovBizException("이미 처리되었거나 유효하지 않은 연결 요청입니다.");
		}
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void rejectGuardianRequest(Map<String, Object> requestMap) throws EgovBizException, SQLException, Exception {
		Map<String, Object> condition = protectedRequestCondition(requestMap);
		if (registUserMapper.rejectGuardianRequest(condition) != 1) {
			throw new EgovBizException("이미 처리되었거나 유효하지 않은 연결 요청입니다.");
		}
	}

	private Map<String, Object> protectedRequestCondition(Map<String, Object> requestMap) throws EgovBizException {
		LoginVO loginInfo = requireUserType(UserType.PROTECTED, "피보호자 계정만 연결 요청을 처리할 수 있습니다.");
		String guardianId = stringValue(requestMap == null ? null : requestMap.get("guardianId"));
		if (guardianId.isEmpty()) {
			throw new EgovBizException("보호자 요청 정보가 없습니다.");
		}
		Map<String, Object> condition = new java.util.HashMap<String, Object>();
		condition.put("protectedUserId", loginInfo.getUserId());
		condition.put("guardianId", guardianId);
		return condition;
	}

	private LoginVO requireUserType(UserType userType, String message) throws EgovBizException {
		LoginVO loginInfo = CmmUserDetailsHelper.getAuthenticatedUser();
		if (loginInfo == null || !userType.name().equals(loginInfo.getUserType())) {
			throw new EgovBizException(message);
		}
		return loginInfo;
	}

	private String stringValue(Object value) {
		return value == null ? "" : value.toString().trim();
	}

	private String digitsOnly(Object value) {
		return stringValue(value).replaceAll("[^0-9]", "");
	}

	@Override
	public EgovMap selectUserChk(LoginVO loginVo) throws EgovBizException, SQLException, Exception {
		String userPwd = loginVo.getUserPwd();
		if(userPwd != null && !userPwd.isEmpty()) {
			String enpassword = egovPasswordEncoder.encryptPassword(userPwd);
			loginVo.setUserPwd(enpassword);
		}

		return registUserMapper.selectGuardianToRegist(loginVo);
	}

}
