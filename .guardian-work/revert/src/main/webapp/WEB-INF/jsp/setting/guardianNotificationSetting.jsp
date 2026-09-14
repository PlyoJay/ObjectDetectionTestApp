<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core"%>
<!DOCTYPE html>
<html>
<head>
<title>GOTORO</title>
<link rel="shortcut icon" type="image/png" href="/resources/images/GOTORO_ICON_A1.png">

<style>.guardian-notification-page .battery-threshold-disabled { opacity:.45; }</style>
</head>
<body>
<div class="full-main background2 settings-page guardian-notification-page">
    <form id="guardianNotificationForm" class="form1" name="guardianNotificationForm">
        <div class="scroll-style scroll-div">
            <div>
                <div class="flex-between margin_top20">
                    <h4 id="notificationSettingsTitle" class="h4-title mark">알림 수신 설정</h4>
                </div>
                <table id="notificationSettingsTable" class="pas-table labelShow" aria-labelledby="notificationSettingsTitle">
                    <caption>보호자 알림 수신 설정</caption>
                    <colgroup>
                        <col style="width:20%"><col style="width:30%">
                        <col style="width:20%"><col style="width:30%">
                    </colgroup>
                    <tr>
                        <th scope="row"><label for="sosEnabledSwitch">SOS 알림 수신</label></th>
                        <td><div class="switch-settings slider-custom"><input type="checkbox" id="sosEnabledSwitch"></div></td>
                        <th scope="row"><label for="routeDeviationEnabledSwitch">경로 이탈 알림 수신</label></th>
                        <td><div class="switch-settings slider-custom"><input type="checkbox" id="routeDeviationEnabledSwitch"></div></td>
                    </tr>
                    <tr>
                        <th scope="row"><label for="lowBatteryEnabledSwitch">배터리 부족 알림 수신</label></th>
                        <td><div class="switch-settings slider-custom"><input type="checkbox" id="lowBatteryEnabledSwitch"></div></td>
                        <th scope="row"><span id="batteryThresholdLabel">배터리 알림 기준</span></th>
                        <td>
                            <div id="batteryThresholdArea" class="radio-btn-style" role="radiogroup" aria-labelledby="batteryThresholdLabel">
                                <label class="radio-style"><input type="radio" name="lowBatteryThreshold" value="50"><span>50%</span></label>
                                <label class="radio-style"><input type="radio" name="lowBatteryThreshold" value="25" checked><span>25%</span></label>
                                <label class="radio-style"><input type="radio" name="lowBatteryThreshold" value="10"><span>10%</span></label>
                            </div>
                        </td>
                    </tr>
                    <tr>
                        <th scope="row"><label for="deviceOfflineEnabledSwitch">단말 오프라인 알림 수신</label></th>
                        <td><div class="switch-settings slider-custom"><input type="checkbox" id="deviceOfflineEnabledSwitch"></div></td>
                        <th></th><td></td>
                    </tr>
                </table>
            </div>
        </div>
        <div id="notificationSaveArea" class="flex-center padding_top15 margin_top25 top-line">
            <button type="button" class="main-color" id="saveGuardianNotification" aria-label="저장"><i class="fa-solid fa-floppy-disk" aria-hidden="true"></i> 저장</button>
        </div>
    </form>
</div>
<script>
(function() {
    var switches = {};
    var fields = ['sosEnabled', 'routeDeviationEnabled', 'lowBatteryEnabled', 'deviceOfflineEnabled'];
    var loaded = false;
    function setThresholdEnabled(value) {
        $('#batteryThresholdArea').toggleClass('battery-threshold-disabled', !value)
            .attr('aria-disabled', String(!value));
        $('#batteryThresholdArea input').prop('disabled', !value);
    }
    function editable(value) {
        setThresholdEnabled(value && switches.lowBatteryEnabled.check());
        $.each(fields, function(_, field) { switches[field].enable(value); });
        $('#saveGuardianNotification').prop('disabled', !value);
    }
    $(function() {
        $.each(fields, function(_, field) {
            switches[field] = $('#' + field + 'Switch').kendoSwitch({
                checked: false, messages: { checked: 'ON', unchecked: 'OFF' }, width: 80,
                change: function() { setThresholdEnabled(loaded && switches.lowBatteryEnabled.check()); }
            }).data('kendoSwitch');
        });
        editable(false);
        yg.ajax.post('<c:url value="/settings/selectGuardianNotification.json" />', {}, function(res) {
            if (res.errorCode != 0 || !res.data) {
                $.yg.alert(res.errorMsg || '알림 설정을 불러오지 못했습니다.');
                return;
            }
            $.each(fields, function(_, field) { switches[field].check(Number(res.data[field]) === 1); });
            $('input[name="lowBatteryThreshold"][value="' + res.data.lowBatteryThreshold + '"]').prop('checked', true);
            loaded = true;
            editable(true);
        });
        $('#saveGuardianNotification').on('click', function() {
            if (!loaded) return;
            var sendData = {};
            $.each(fields, function(_, field) { sendData[field] = switches[field].check() ? 1 : 0; });
            sendData.lowBatteryThreshold = Number($('input[name="lowBatteryThreshold"]:checked').val());
            yg.ajax.post('<c:url value="/settings/saveGuardianNotification.json" />', sendData, function(res) {
                $.yg.alert(res.errorCode == 0 ? '저장 완료하였습니다.' : (res.errorMsg || '저장 중 오류가 발생했습니다.'));
            });
        });
    });
})();
</script>
</body>
</html>
