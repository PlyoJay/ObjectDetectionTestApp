<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core"%>
<%@ taglib prefix="form" uri="http://www.springframework.org/tags/form"%>
<%@ taglib prefix="fn" uri="http://java.sun.com/jsp/jstl/functions"%>
<%@ taglib prefix="fmt" uri="http://java.sun.com/jsp/jstl/fmt"%>
<%@ taglib prefix="spring" uri="http://www.springframework.org/tags"%>
<!DOCTYPE html>
<html>
<head>
<title>정보 수정</title>
<link rel="shortcut icon" type="image/png" href="/resources/images/GOTORO_ICON_A1.png">
</head>
<body>
<div class="full-main background2">
	<form id="infoEditForm" name="infoEditForm">
	<div class="scroll-style scroll-div">
		<div class="flex-between" style="align-items:center"><h4 id="personalInfoTitle" class="h4-title mark">정보 수정 - 개인정보 수정</h4></div>
		<table class="pas-table labelShow" aria-labelledby="personalInfoTitle">
			<caption>개인정보</caption>
	 		<colgroup><col style="width:12%"><col style="width:38%"><col style="width:12%"><col style="width:38%"></colgroup>
			<tbody>
			    <tr>
			    	<th scope="row"><label for="userName">이름</label></th>
		    		<td><input type="text" class="pas-input" name="userName" id="userName" aria-required="true"></td>
		    		<th scope="row"><label for="phoneNo">휴대폰 번호</label></th>
		    		<td><input type="text" class="pas-input" name="phoneNo" id="phoneNo" maxlength="13" oninput="phoneFormat(this)" aria-required="true"></td>
	    		</tr>
			    <tr>
			    	<th scope="row"><label for="addr">주소</label></th>
			    	<td>
			    		<input type="text" id="zipcode" name="zipcode" class="pas-input readonly" style="width:100px" readonly>
			    		<input type="text" id="addr" name="addr" class="pas-input margin_left5 readonly" style="width:270px" readonly>
			    		<button type="button" class="pas-button default-color" id="zipcodeBtn">우편번호 검색</button>
			    	</td>
			    	<th scope="row"><label for="addrDetail">상세주소</label></th>
			    	<td><input type="text" class="pas-input" style="width:40%" name="addrDetail" id="addrDetail" aria-required="true"></td>
		    	</tr>
			    <tr>
			    	<th scope="row"><label for="userEmail">이메일</label></th>
			    	<td colspan="3"><input type="text" class="pas-input" name="userEmail" id="userEmail"></td>
		    	</tr>
	 		</tbody>
		</table>

		<div class="flex-between margin_top20">
		 	<h4 id="passwordInfoTitle" class="h4-title mark">비밀번호 수정</h4>
		</div>
		<table class="pas-table labelShow" aria-labelledby="passwordInfoTitle">
			<caption>비밀번호 수정</caption>
			<colgroup>
				<col style="width: 12%">
				<col style="width: 21%">
				<col style="width: 12%">
				<col style="width: 21%">
				<col style="width: 12%">
				<col style="width: 21%">
			</colgroup>
		  	<tbody>
			<tr>
		    	<th>아이디</th>
            	<td><input type="text" class="pas-input width200 readonly" id="userId" name="userId" readonly /></td>
            	<th>현재 비밀번호</th>
            	<td><input type="password" class="pas-input width200" id="userPassword" name="userPassword" autocomplete="new-password"></td>
            	<th>변경할 비밀번호</th>
				<td>
					<input type="password" class="pas-input" style="width:160px;" id="newPassword" name="newPassword" autocomplete="new-password" placeholder="새 비밀번호 입력">
	            	<input type="password" class="pas-input" style="width:160px;" id="newPasswordChk" name="newPasswordChk" autocomplete="new-password" placeholder="새 비밀번호 확인">
	            	<button type="button" class="pas-button main-color" id="chgPwd" name="chgPwd"><i class="fa-solid fa-circle-check"></i> 비밀번호 변경</button>
				</td>
			</tr>
			</tbody>
		</table>

		<div class="margin_top20">
			<div class="flex-between margin_bottom5">
				<h3 id="protectedUserInfoTitle" class="grid-tilte"><i class="fa-solid fa-user-group" aria-hidden="true"></i> 피보호자 목록</h3>
				<div>
					<button type="button" class="margin_right5 main-color bar-btn" id="btnProtectedUserRequest" aria-label="피보호자 연결 요청"><i class="fa-solid fa-circle-plus" aria-hidden="true"></i> 피보호자 연결 요청</button>
				</div>
			</div>
			<div style="min-height: 250px;" id="protectedUserGrid" aria-labelledby="protectedUserInfoTitle"></div>
		</div>
	</div>
	<div class="flex-center padding_top15 margin_top25 top-line"><button type="button" class="main-color" id="saveInfoEdit"><i class="fa-solid fa-floppy-disk"></i> 저장</button></div>
	</form>
</div>
<!-- 피보호자 연결 요청 Layer: 보호자 정보 수정 페이지 DOM에 직접 포함 -->
<div id="protectedUserRequestLayer" class="modal-warp protected-request-layer" aria-hidden="true">
	<div class="protected-request-dim" aria-hidden="true"></div>
	<div class="protected-request-popup full-main background2 backgrounb_popup" role="dialog" aria-modal="true" aria-labelledby="protectedRequestTitle">
		<form id="protectedRequestForm" class="form1" role="form">
			<div id="protectedRequestDragHandle" class="protected-request-popup-header">
				<div class="pop-header">
					<h3 id="protectedRequestTitle"><i class="fa-solid fa-user-plus" aria-hidden="true"></i> 피보호자 연결 요청</h3>
				</div>
				<button type="button" id="closeProtectedRequest" class="protected-request-close" aria-label="닫기"><i class="fa-solid fa-xmark" aria-hidden="true"></i></button>
			</div>
			<div class="protected-request-popup-body">
				<div class="description-txt"><p>연결하려는 피보호자의 정보를 정확히 입력해 주세요.</p></div>
				<div class="protected-request-fields">
					<label for="protectedUserName">이름 <span aria-hidden="true">*</span></label>
					<input type="text" id="protectedUserName" autocomplete="name" aria-required="true">
					<label for="protectedLoginId">아이디 <span aria-hidden="true">*</span></label>
					<input type="text" id="protectedLoginId" autocomplete="username" aria-required="true">
					<label for="protectedPhoneNo">휴대폰 번호 <span aria-hidden="true">*</span></label>
					<input type="text" id="protectedPhoneNo" maxlength="13" oninput="phoneFormat(this)" autocomplete="tel" aria-required="true">
				</div>
				<div class="protected-request-check-area">
					<button type="button" id="verifyProtectedUser" class="default-color bar-btn"><i class="fa-solid fa-circle-check" aria-hidden="true"></i> 피보호자 확인</button>
					<div id="protectedVerificationResult" class="protected-request-result" role="status" aria-live="polite"></div>
				</div>
				<fieldset class="protected-request-relation">
					<legend>관계 <span aria-hidden="true">*</span></legend>
					<label for="relationFamily"><input type="radio" id="relationFamily" name="relation" value="FAMILY"> 가족</label>
					<label for="relationAcquaintance"><input type="radio" id="relationAcquaintance" name="relation" value="ACQUAINTANCE"> 지인</label>
				</fieldset>
			</div>
			<div class="protected-request-actions">
				<button type="button" id="cancelProtectedRequest" class="default-color none-border">취소</button>
				<button type="button" id="submitProtectedRequest" class="main-color none-border" disabled>연결 요청</button>
			</div>
		</form>
	</div>
</div>
<script src="//t1.daumcdn.net/mapjsapi/bundle/postcode/prod/postcode.v2.js"></script>
<script>
var isSavingInfoEdit=false;
var protectedUserGrid;
var isProtectedRequestDragging=false;
var protectedRequestDragOffsetX=0;
var protectedRequestDragOffsetY=0;
var verifiedProtectedUserId='';

$(function(){
  $('#zipcodeBtn').on('click',function(){new daum.Postcode({oncomplete:function(data){$('#zipcode').val(data.zonecode);$('#addr').val(data.roadAddress||data.jibunAddress);}}).open();});
  $('#saveInfoEdit').on('click',saveInfoEdit);
	  $('#chgPwd').on('click',changePassword);
	  $('#btnProtectedUserRequest').on('click',openProtectedUserRequestLayer);
  $('#closeProtectedRequest, #cancelProtectedRequest').on('click',closeProtectedUserRequestLayer);
  $('#protectedUserRequestLayer').on('click','.protected-request-dim',closeProtectedUserRequestLayer);
  $('#verifyProtectedUser').on('click',verifyProtectedUser);
  $('#protectedUserName, #protectedLoginId, #protectedPhoneNo').on('input',clearProtectedUserVerification)
    .on('keydown',function(e){if(e.key==='Enter'){e.preventDefault();verifyProtectedUser();}});
  $('#submitProtectedRequest').on('click',validateProtectedRequest);
  $('#protectedUserGrid').on('click','.connection-disconnect-btn',disconnectProtectedUser);
  $('#protectedRequestDragHandle').on('mousedown',startProtectedRequestDrag);
  $(document).on('mousemove.protectedRequestLayer',moveProtectedRequestDrag).on('mouseup.protectedRequestLayer',stopProtectedRequestDrag);
  initProtectedUserGrid();
  loadInfo();
});

function openProtectedUserRequestLayer(){
  resetProtectedUserRequestLayer();
  $('.protected-request-popup').css({left:'50%', top:'50%', transform:'translate(-50%, -50%)'});
  $('#protectedUserRequestLayer').css('display','block').attr('aria-hidden','false');
  $('body').addClass('protected-request-layer-open');
  setTimeout(function(){$('#protectedUserName').focus();},0);
}

function closeProtectedUserRequestLayer(){
  stopProtectedRequestDrag();
  $('#protectedUserRequestLayer').hide().attr('aria-hidden','true');
  $('body').removeClass('protected-request-layer-open');
  resetProtectedUserRequestLayer();
  $('#btnProtectedUserRequest').focus();
}

function resetProtectedUserRequestLayer(){
  $('#protectedRequestForm')[0].reset();
  clearProtectedUserVerification();
}

function clearProtectedUserVerification(){
  verifiedProtectedUserId='';
  $('#protectedVerificationResult').removeClass('is-success is-failure').empty();
  $('#submitProtectedRequest').prop('disabled',true);
}

function verifyProtectedUser(){
  var userName=$.trim($('#protectedUserName').val());
  var userId=$.trim($('#protectedLoginId').val());
  var phoneNo=$.trim($('#protectedPhoneNo').val());
  clearProtectedUserVerification();
  if(!userName){$.yg.alert('이름을 입력하세요.').done(function(){$('#protectedUserName').focus();});return;}
  if(!userId){$.yg.alert('아이디를 입력하세요.').done(function(){$('#protectedLoginId').focus();});return;}
  if(!phoneNo){$.yg.alert('휴대폰 번호를 입력하세요.').done(function(){$('#protectedPhoneNo').focus();});return;}
  $('#verifyProtectedUser').prop('disabled',true);
  yg.ajax.post({
    url:'<c:url value="/registUser/verifyProtectedUser.json"/>',contentType:'application/json',
    data:{userName:userName,userId:userId,phoneNo:phoneNo},
    success:function(res){
      var result=res.data||{};
      if(String(res.errorCode)==='0'&&result.success===true){
        verifiedProtectedUserId=result.protegeUserId;
        $('#protectedVerificationResult').addClass('is-success').text(result.protegeName+' 님이 확인되었습니다.');
        $('#submitProtectedRequest').prop('disabled',false);
      }else if(String(res.errorCode)==='0'){
        $('#protectedVerificationResult').addClass('is-failure').text(result.message||'입력한 정보와 일치하는 피보호자를 찾을 수 없습니다.');
      }else{
        $.yg.alert(res.errorMsg||'피보호자 확인을 처리하지 못했습니다.');
      }
    },
    complete:function(){$('#verifyProtectedUser').prop('disabled',false);}
  });
}

function validateProtectedRequest(){
  var relation=$('input[name="relation"]:checked').val();
  if(!verifiedProtectedUserId){$.yg.alert('피보호자 확인을 먼저 수행해 주세요.').done(function(){$('#protectedUserName').focus();});return;}
  if(!relation){$.yg.alert('관계를 선택해주세요.').done(function(){$('#relationFamily').focus();});return;}
  $('#submitProtectedRequest').prop('disabled',true);
  yg.ajax.post({
    url:'<c:url value="/registUser/requestProtectedUserConnection.json"/>',contentType:'application/json',
    data:{protectedUserId:verifiedProtectedUserId,relation:relation},
    success:function(res){
      if(String(res.errorCode)==='0'){
        closeProtectedUserRequestLayer();protectedUserGrid.reload();
        $.yg.alert((res.data&&res.data.message)||'피보호자에게 연결 요청을 보냈습니다.');
      }else{$.yg.alert(res.errorMsg||'연결 요청을 처리하지 못했습니다.');}
    },
    complete:function(){if(verifiedProtectedUserId){$('#submitProtectedRequest').prop('disabled',false);}}
  });
}

function startProtectedRequestDrag(e){
  if(e.which!==1||$(e.target).closest('button').length){return;}
  var popupRect=$('.protected-request-popup')[0].getBoundingClientRect();
  isProtectedRequestDragging=true;
  protectedRequestDragOffsetX=e.clientX-popupRect.left;
  protectedRequestDragOffsetY=e.clientY-popupRect.top;
  $('.protected-request-popup').css({transform:'none',left:popupRect.left+'px',top:popupRect.top+'px'});
  $('#protectedRequestDragHandle').addClass('is-dragging');
  e.preventDefault();
}

function moveProtectedRequestDrag(e){
  if(!isProtectedRequestDragging){return;}
  moveProtectedRequestPopupWithinViewport(e.clientX-protectedRequestDragOffsetX,e.clientY-protectedRequestDragOffsetY);
}

function stopProtectedRequestDrag(){
  isProtectedRequestDragging=false;
  $('#protectedRequestDragHandle').removeClass('is-dragging');
}

function moveProtectedRequestPopupWithinViewport(left,top){
  var $popup=$('.protected-request-popup');
  var maxLeft=Math.max(0,$(window).width()-$popup.outerWidth());
  var maxTop=Math.max(0,$(window).height()-$popup.outerHeight());
  $popup.css({left:Math.min(Math.max(0,left),maxLeft)+'px',top:Math.min(Math.max(0,top),maxTop)+'px'});
}

function initProtectedUserGrid(){
  protectedUserGrid=new yg.ui.grid({
    selector:'#protectedUserGrid',
    url:'<c:url value="/registUser/selectProtectedUserList.json"/>',
    postDataFunc:function(){return {};},
    pageable:false,filterable:false,sortable:true,resizable:true,editable:false,height:250,
    schema:{model:{id:'protectedUserId',fields:{protectedUserId:{type:'string'},protectedUserName:{type:'string'},relation:{type:'string'},relationName:{type:'string'},connectionStatus:{type:'string'}}}},
    columns:[
      {field:'protectedUserName',title:'이름',width:140,attributes:{style:'text-align:center'},headerAttributes:{style:'text-align:center'}},
      {field:'relationName',title:'관계',width:120,attributes:{style:'text-align:center'},headerAttributes:{style:'text-align:center'}},
      {field:'connectionStatus',title:'상태',width:120,template:function(data){return data.connectionStatus==='PENDING'?'승인 대기':'연결됨';},attributes:{style:'text-align:center'},headerAttributes:{style:'text-align:center'}},
      {title:'관리',width:120,attributes:{'class':'connection-list-manage-cell'},headerAttributes:{style:'text-align:center'},template:function(data){return data.connectionStatus==='APPROVED'?'<button type="button" class="connection-disconnect-btn">연결 해제</button>':'-';}}
    ],
    dataBound:function(){
      var grid=$('#protectedUserGrid').data('kendoGrid');
      if(grid&&grid.dataSource.total()===0){$('#protectedUserGrid .k-grid-norecords-template').text('연결된 피보호자가 없습니다.');}
    }
  });
  protectedUserGrid.reload();
}

function disconnectProtectedUser(){
  var $button=$(this);
  var grid=$('#protectedUserGrid').data('kendoGrid');
  var item=grid&&grid.dataItem($button.closest('tr'));
  if(!item||item.connectionStatus!=='APPROVED'){return;}
  var protectedUserName=item.protectedUserName||'피보호자';
  $.yg.confirm(protectedUserName+' 님과의 연결을 해제하시겠습니까?').done(function(){
    $button.prop('disabled',true);
    yg.ajax.post({
      url:'<c:url value="/registUser/disconnectProtectedUser.json"/>',contentType:'application/json',
      data:{protectedUserId:item.protectedUserId},
      success:function(res){
        if(String(res.errorCode)==='0'){
          protectedUserGrid.reload();
          $.yg.alert((res.data&&res.data.message)||'피보호자 연결이 해제되었습니다.');
        }else{
          $.yg.alert(res.errorMsg||'연결 해제 중 오류가 발생했습니다.');
          $button.prop('disabled',false);
        }
      },
      error:function(){
        $.yg.alert('연결 해제 중 오류가 발생했습니다.');
        $button.prop('disabled',false);
      }
    });
  });
}

function loadInfo(){
  yg.ajax.post('<c:url value="/settings/selectInfo.json"/>',{},function(res){
    if(res.data){
      $('#infoEditForm').populate(res.data);
      $('#passwordUserId').val(res.data.userId||'');
    }
  });
}

function saveInfoEdit(){
  if(isSavingInfoEdit)return;
  if(!validatePersonalInfo())return;
  var data={
    userName:$.trim($('#userName').val()),
    phoneNo:$.trim($('#phoneNo').val()),
    zipcode:$.trim($('#zipcode').val()),
    addr:$.trim($('#addr').val()),
    addrDetail:$.trim($('#addrDetail').val()),
    userEmail:$.trim($('#userEmail').val())
  };
  isSavingInfoEdit=true;
  $('#saveInfoEdit').prop('disabled',true);
  yg.ajax.post({
    url:'<c:url value="/settings/saveGuardianInfoEdit.json"/>',
    contentType:'application/json',
    data:data,
    success:function(res){
      if(String(res.errorCode)==='0'){
        $.yg.alert('저장 완료하였습니다.').done(loadInfo);
      }else{
        $.yg.alert(res.errorMsg||'저장 중 오류가 발생했습니다.');
      }
    },
    complete:function(){isSavingInfoEdit=false;$('#saveInfoEdit').prop('disabled',false);}
  });
}

function validatePersonalInfo(){
  if(!$.trim($('#userName').val())){$.yg.alert('이름을 입력하세요.');return false;}
  if(!$.trim($('#phoneNo').val())){$.yg.alert('휴대폰 번호를 입력하세요.');return false;}
  if(!$.trim($('#addrDetail').val())){$.yg.alert('상세주소를 입력하세요.');return false;}
  var email=$.trim($('#userEmail').val());
  if(email&&!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)){$.yg.alert('올바른 이메일 형식을 입력하세요.');return false;}
  return true;
}

function changePassword(){
  var current=$('#userPassword').val(),next=$('#newPassword').val(),check=$('#newPasswordChk').val();
  if(!current){$.yg.alert('현재 비밀번호를 입력하여 주세요.');return;}
  if(!/^(?=.*[A-Za-z])(?=.*\d)(?=.*[!@#$%^&*()_+\-=\[\]{};':"\\|,.<>\/?]).{8,}$/.test(next)){$.yg.alert('비밀번호는 영문, 숫자, 특수문자를 포함한 8자리 이상이어야 합니다.');return;}
  if(next!==check){$.yg.alert('변경할 비밀번호가 일치하지 않습니다.');return;}
  yg.ajax.post('<c:url value="/settings/updatePwd.json"/>',{userPassword:current,newPassword:next,newPasswordChk:check},function(res){
    if(res.data&&res.data.userChk){
      $.yg.alert('비밀번호가 변경되었습니다.').done(function(){location.reload();});
    }else{
      $.yg.alert('현재 비밀번호가 일치하지 않습니다.');
    }
  });
}

function phoneFormat(el){
  var v=el.value.replace(/[^0-9]/g,'');
  el.value=v.length<=3?v:v.length<=7?v.slice(0,3)+'-'+v.slice(3):v.slice(0,3)+'-'+v.slice(3,7)+'-'+v.slice(7,11);
}
</script>
</body>
</html>
