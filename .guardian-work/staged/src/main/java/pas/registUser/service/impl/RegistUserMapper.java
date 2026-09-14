package pas.registUser.service.impl;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import org.egovframe.rte.fdl.cmmn.exception.EgovBizException;
import org.egovframe.rte.psl.dataaccess.mapper.Mapper;
import org.egovframe.rte.psl.dataaccess.util.EgovMap;
import org.springframework.dao.DataAccessException;

import egovframework.com.cmm.service.LoginVO;

@Mapper("pas.registUser.service.impl.RegistUserMapper")
public interface RegistUserMapper {

	int countGuardianByUserId(String userId) throws SQLException, DataAccessException, Exception;

	List<EgovMap> selectGuardianList(Map<String, Object> requestMap) throws SQLException, DataAccessException, Exception;

	List<EgovMap> selectProtectedUserList(Map<String, Object> requestMap) throws SQLException, DataAccessException, Exception;

	EgovMap selectProtectedUserForConnection(Map<String, Object> requestMap) throws SQLException, DataAccessException, Exception;

	EgovMap selectConnectionUser(String userId) throws SQLException, DataAccessException, Exception;

	String selectGuardianConnectionStatus(Map<String, Object> requestMap) throws SQLException, DataAccessException, Exception;

	int insertProtectedUserConnectionRequest(Map<String, Object> requestMap) throws SQLException, DataAccessException, Exception;


	int deleteApprovedProtectedUserConnection(Map<String, Object> requestMap) throws SQLException, DataAccessException, Exception;

	int deleteApprovedGuardianConnectionByProtected(Map<String, Object> requestMap) throws SQLException, DataAccessException, Exception;

	int approveGuardianRequest(Map<String, Object> requestMap) throws SQLException, DataAccessException, Exception;

	int rejectGuardianRequest(Map<String, Object> requestMap) throws SQLException, DataAccessException, Exception;

	EgovMap selectGuardianToRegist(LoginVO loginVO) throws EgovBizException, SQLException, Exception;

	void insertGuardianRegistInfo(Map<String, Object> mapDtl) throws SQLException, DataAccessException, Exception;

	void deleteGuardianRegistInfo(Map<String, Object> mapDtl) throws SQLException, DataAccessException, Exception;

}
