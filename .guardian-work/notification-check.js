
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
        yg.ajax.post('/settings/selectGuardianNotification.json', {}, function(res) {
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
            yg.ajax.post('/settings/saveGuardianNotification.json', sendData, function(res) {
                $.yg.alert(res.errorCode == 0 ? '저장 완료하였습니다.' : (res.errorMsg || '저장 중 오류가 발생했습니다.'));
            });
        });
    });
})();

