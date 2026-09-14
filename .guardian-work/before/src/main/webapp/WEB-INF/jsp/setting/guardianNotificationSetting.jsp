<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core"%>
<!DOCTYPE html>
<html>
<head>
<title>GOTORO</title>
<link rel="shortcut icon" type="image/png" href="/resources/images/GOTORO_ICON_A1.png">
<style>
.guardian-notification-page .notification-target { display:flex; align-items:center; gap:12px; }
.guardian-notification-page .notification-target select { min-width:220px; height:36px; }
.guardian-notification-page .battery-threshold-disabled { opacity:.45; }
.guardian-notification-page .notification-empty { padding:48px 20px; text-align:center; color:#777; }
</style>
</head>
<body>
<div class="full-main background2 settings-page guardian-notification-page">
    <form id="guardianNotificationForm" class="form1" name="guardianNotificationForm">
        <div class="scroll-style scroll-div">
            <div>
                <div class="flex-between margin_top20">
                    <h4 id="notificationSettingsTitle" class="h4-title mark">알림 수신 설정</h4>
                    <div id="protegeSelectorArea"></div>
                </div>
                <div id="notificationEmpty" class="notification-empty" style="display:none;">연결된 피보호자가 없습니다.</div>
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
    var currentProtegeId = null;

    function createSwitch(id, onChange) {
        return $('#' + id).kendoSwitch({
            checked: true,
            messages: { checked: 'ON', unchecked: 'OFF' },
            change: onChange || function() {},
            width: 80
        }).data('kendoSwitch');
    }

    function setThresholdEnabled(enabled) {
        $('#batteryThresholdArea').toggleClass('battery-threshold-disabled', !enabled)
            .attr('aria-disabled', String(!enabled));
        $('#batteryThresholdArea input').prop('disabled', !enabled);
    }

    function enabled(switchWidget) {
        return switchWidget.check() ? 1 : 0;
    }

    function loadSetting(protegeId) {
        currentProtegeId = protegeId;
        yg.ajax.post('<c:url value="/settings/selectGuardianNotification.json" />', { protegeId: protegeId }, function(res) {
            if (res.errorCode != 0) {
                $.yg.alert(res.errorMsg || '알림 설정을 불러오지 못했습니다.');
                return;
            }
            var data = res.data || {};
            switches.sos.check(Number(data.sosEnabled) === 1);
            switches.route.check(Number(data.routeDeviationEnabled) === 1);
            switches.battery.check(Number(data.lowBatteryEnabled) === 1);
            switches.offline.check(Number(data.deviceOfflineEnabled) === 1);
            $('input[name="lowBatteryThreshold"][value="' + (data.lowBatteryThreshold || 25) + '"]').prop('checked', true);
            setThresholdEnabled(switches.battery.check());
        });
    }

    function renderProteges(list) {
        if (!list || list.length === 0) {
            $('#notificationSettingsTable, #notificationSaveArea').hide();
            $('#notificationEmpty').show();
            return;
        }
        if (list.length > 1) {
            var $wrap = $('<div class="notification-target"></div>');
            var $label = $('<label for="protegeSelector">설정 대상</label>');
            var $select = $('<select id="protegeSelector"></select>');
            $.each(list, function(_, protege) {
                $('<option></option>').val(protege.protegeId)
                    .text(protege.protegeName + (protege.relation ? ' (' + protege.relation + ')' : ''))
                    .appendTo($select);
            });
            $select.on('change', function() { loadSetting(this.value); });
            $wrap.append($label, $select).appendTo('#protegeSelectorArea');
        }
        loadSetting(list[0].protegeId);
    }

    $(function() {
        switches.sos = createSwitch('sosEnabledSwitch');
        switches.route = createSwitch('routeDeviationEnabledSwitch');
        switches.battery = createSwitch('lowBatteryEnabledSwitch', function(e) { setThresholdEnabled(e.checked); });
        switches.offline = createSwitch('deviceOfflineEnabledSwitch');

        yg.ajax.post('<c:url value="/settings/selectGuardianProtegeList.json" />', {}, function(res) {
            if (res.errorCode != 0) {
                $.yg.alert(res.errorMsg || '피보호자 목록을 불러오지 못했습니다.');
                return;
            }
            renderProteges(res.data || []);
        });

        $('#saveGuardianNotification').on('click', function() {
            if (!currentProtegeId) return;
            var sendData = {
                protegeId: currentProtegeId,
                sosEnabled: enabled(switches.sos),
                routeDeviationEnabled: enabled(switches.route),
                lowBatteryEnabled: enabled(switches.battery),
                lowBatteryThreshold: Number($('input[name="lowBatteryThreshold"]:checked').val()),
                deviceOfflineEnabled: enabled(switches.offline)
            };
            yg.ajax.post('<c:url value="/settings/saveGuardianNotification.json" />', sendData, function(res) {
                if (res.errorCode == 0) {
                    $.yg.alert('저장 완료하였습니다.');
                } else {
                    $.yg.alert(res.errorMsg || '저장 중 오류가 발생했습니다.');
                }
            });
        });
    });
})();
</script>
</body>
</html>
