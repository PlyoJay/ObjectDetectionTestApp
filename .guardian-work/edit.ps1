$ErrorActionPreference='Stop'
$root=Join-Path $PSScriptRoot 'staged'
function ReadSource($f) { [IO.File]::ReadAllText((Join-Path $root $f)) }
function WriteSource($f,$s) { [IO.File]::WriteAllText((Join-Path $root $f),$s.Replace("`r`n","`n").Replace("`n","`r`n"),[Text.UTF8Encoding]::new($false)) }
$f='src/main/java/pas/settings/service/impl/SettingsServiceImpl.java'
$s=ReadSource $f
$s=$s.Replace('selectGuardianNotification(HashMap<String, Object> requestMap)', 'selectGuardianNotification()').Replace('verifiedGuardianProtegeParams(requestMap)', 'authenticatedGuardianParams()')
$s=$s.Replace('EgovMap setting = settingsMapper.selectGuardianNotification(params);', 'requireSingleGuardianSetting(params);'+"`n`t`tEgovMap setting = settingsMapper.selectGuardianNotification(params);")
$s=$s.Replace('setting.put("lowBatteryEnabled", 1);', 'setting.put("battery50Enabled", 0);').Replace('setting.put("lowBatteryThreshold", 25);', 'setting.put("battery25Enabled", 1);'+"`n`t`t`t"+'setting.put("battery10Enabled", 0);')
$s=$s.Replace("`t`t"+'setting.put("protegeId", params.get("protegeId"));'+"`r`n",'')
$s=$s.Replace('params.put("lowBatteryEnabled", enabledValue(requestMap.get("lowBatteryEnabled")));', 'params.put("battery50Enabled", enabledValue(requestMap.get("battery50Enabled")));'+"`n`t`t"+'params.put("battery25Enabled", enabledValue(requestMap.get("battery25Enabled")));'+"`n`t`t"+'params.put("battery10Enabled", enabledValue(requestMap.get("battery10Enabled")));')
$s=[regex]::Replace($s,'\t\tint threshold;[\s\S]*?params.put\("lowBatteryThreshold", threshold\);', "`t`t"+'// Serialize first saves for the same account before inspecting existing rows.'+"`n`t`t"+'if (settingsMapper.lockGuardianAccount(params) == null) {'+"`n`t`t`t"+'throw new EgovBizException("보호자 계정을 확인할 수 없습니다.");'+"`n`t`t}"+"`n`t`t"+'requireSingleGuardianSetting(params);')
$s=$s.Replace('HashMap<String, Object> params = authenticatedGuardianParams();'+"`r`n`t`t"+'params.put("sosEnabled"', 'HashMap<String, Object> params = authenticatedGuardianParams();'+"`n`t`t"+'if (requestMap == null) throw new EgovBizException("알림 설정값이 없습니다.");'+"`n`t`t"+'params.put("sosEnabled"')
$s=[regex]::Replace($s,'\tprivate HashMap<String, Object> verifiedGuardianProtegeParams[\s\S]*?(?=\tprivate int enabledValue)', @'
	private void requireSingleGuardianSetting(Map<String, Object> params) throws Exception {
		if (settingsMapper.countGuardianNotification(params) > 1) {
			throw new EgovBizException("피보호자별 기존 알림 설정이 여러 건입니다. 관리자에게 공통 설정 전환을 요청해 주세요.");
		}
	}

'@)
WriteSource $f $s
$f='src/main/java/pas/settings/service/SettingsSerivce.java'; WriteSource $f ((ReadSource $f).Replace('selectGuardianNotification(HashMap<String, Object> data)','selectGuardianNotification()'))
$f='src/main/java/pas/settings/web/SettingsController.java'; WriteSource $f ((ReadSource $f).Replace('selectGuardianNotification(@RequestBody DataSourceRequest request)','selectGuardianNotification()').Replace('settingsSerivce.selectGuardianNotification(request.getData())','settingsSerivce.selectGuardianNotification()'))
$f='src/main/java/pas/settings/service/impl/SettingsMapper.java'; $s=ReadSource $f; $s=$s.Replace('int countApprovedGuardianProtege(Map<String, Object> requestMap)', 'String lockGuardianAccount(Map<String, Object> requestMap)'); $s=$s.Replace('EgovMap selectGuardianNotification', 'int countGuardianNotification(Map<String, Object> requestMap) throws Exception;'+"`n`n`t"+'EgovMap selectGuardianNotification'); WriteSource $f $s
$f='src/main/java/pas/settings/service/impl/SettingsMapper.xml'; $s=ReadSource $f
$s=[regex]::Replace($s,'\t<select id="countApprovedGuardianProtege"[\s\S]*?(?=\t<insert id="saveSettingList")', @'
	<select id="lockGuardianAccount" parameterType="map" resultType="string">
		SELECT user_id FROM tb_user
		 WHERE user_id = #{guardianId} AND user_type = 'GUARDIAN'
		 FOR UPDATE
	</select>

	<select id="countGuardianNotification" parameterType="map" resultType="int">
		SELECT COUNT(*) FROM tb_guardian_notification_setting WHERE guardian_id = #{guardianId}
	</select>

	<select id="selectGuardianNotification" parameterType="map" resultType="egovMap">
		SELECT sos_enabled, route_deviation_enabled, device_offline_enabled,
		       COALESCE(battery_50_enabled, IF(low_battery_enabled = 1 AND low_battery_threshold = 50, 1, 0)) AS battery_50_enabled,
		       COALESCE(battery_25_enabled, IF(low_battery_enabled = 1 AND low_battery_threshold = 25, 1, 0)) AS battery_25_enabled,
		       COALESCE(battery_10_enabled, IF(low_battery_enabled = 1 AND low_battery_threshold = 10, 1, 0)) AS battery_10_enabled
		  FROM tb_guardian_notification_setting
		 WHERE guardian_id = #{guardianId}
	</select>

	<!-- Requires DATABASE/20260903_guardian_notification.sql before deployment. -->
	<insert id="upsertGuardianNotification" parameterType="map">
		INSERT INTO tb_guardian_notification_setting (
			guardian_id, sos_enabled, route_deviation_enabled,
			battery_50_enabled, battery_25_enabled, battery_10_enabled, device_offline_enabled
		) VALUES (
			#{guardianId}, #{sosEnabled}, #{routeDeviationEnabled},
			#{battery50Enabled}, #{battery25Enabled}, #{battery10Enabled}, #{deviceOfflineEnabled}
		)
		ON DUPLICATE KEY UPDATE
			sos_enabled = #{sosEnabled}, route_deviation_enabled = #{routeDeviationEnabled},
			battery_50_enabled = #{battery50Enabled}, battery_25_enabled = #{battery25Enabled},
			battery_10_enabled = #{battery10Enabled}, device_offline_enabled = #{deviceOfflineEnabled}
	</insert>

'@)
WriteSource $f $s
$f='src/main/java/pas/registUser/service/impl/RegistUserServiceImpl.java'; WriteSource $f ((ReadSource $f).Replace("`t`tregistUserMapper.deleteGuardianNotificationSetting(connection);", "`t`t// Guardian notification preferences survive connection removal."))
$f='src/main/java/pas/registUser/service/impl/RegistUserMapper.java'; WriteSource $f ([regex]::Replace((ReadSource $f),'\tint deleteGuardianNotificationSetting[^\r\n]*\r?\n',''))
$f='src/main/java/pas/registUser/service/impl/RegistUserMapper.xml'; WriteSource $f ([regex]::Replace((ReadSource $f),'\t<delete id="deleteGuardianNotificationSetting"[\s\S]*?</delete>\r?\n',''))
$f='src/main/webapp/WEB-INF/jsp/setting/guardianNotificationSetting.jsp'; $s=ReadSource $f
$s=[regex]::Replace($s,'<style>[\s\S]*?</style>','')
$s=[regex]::Replace($s,'(?m)^.*(?:protegeSelectorArea|id="notificationEmpty"|var currentProtegeId).*\r?\n','')
$s=[regex]::Replace($s,'                    <tr>\r?\n                        <th scope="row"><label for="lowBatteryEnabledSwitch">[\s\S]*?</tr>', @'
                    <tr>
                        <th scope="row"><label for="battery50EnabledSwitch">배터리 부족 50%</label></th>
                        <td><div class="switch-settings slider-custom"><input type="checkbox" id="battery50EnabledSwitch"></div></td>
                        <th scope="row"><label for="battery25EnabledSwitch">배터리 부족 25%</label></th>
                        <td><div class="switch-settings slider-custom"><input type="checkbox" id="battery25EnabledSwitch"></div></td>
                    </tr>
                    <tr>
                        <th scope="row"><label for="battery10EnabledSwitch">배터리 부족 10%</label></th>
                        <td><div class="switch-settings slider-custom"><input type="checkbox" id="battery10EnabledSwitch"></div></td>
                        <th></th><td></td>
                    </tr>
'@)
$s=[regex]::Replace($s,'<script>[\s\S]*?</script>', @'
<script>
(function() {
    var switches = {};
    var fields = ['sosEnabled', 'routeDeviationEnabled', 'battery50Enabled',
        'battery25Enabled', 'battery10Enabled', 'deviceOfflineEnabled'];
    var loaded = false;
    function editable(value) {
        $.each(fields, function(_, field) { switches[field].enable(value); });
        $('#saveGuardianNotification').prop('disabled', !value);
    }
    $(function() {
        $.each(fields, function(_, field) {
            switches[field] = $('#' + field + 'Switch').kendoSwitch({
                checked: false, messages: { checked: 'ON', unchecked: 'OFF' }, width: 80
            }).data('kendoSwitch');
        });
        editable(false);
        yg.ajax.post('<c:url value="/settings/selectGuardianNotification.json" />', {}, function(res) {
            if (res.errorCode != 0 || !res.data) {
                $.yg.alert(res.errorMsg || '알림 설정을 불러오지 못했습니다.');
                return;
            }
            $.each(fields, function(_, field) { switches[field].check(Number(res.data[field]) === 1); });
            loaded = true;
            editable(true);
        });
        $('#saveGuardianNotification').on('click', function() {
            if (!loaded) return;
            var sendData = {};
            $.each(fields, function(_, field) { sendData[field] = switches[field].check() ? 1 : 0; });
            yg.ajax.post('<c:url value="/settings/saveGuardianNotification.json" />', sendData, function(res) {
                $.yg.alert(res.errorCode == 0 ? '저장 완료하였습니다.' : (res.errorMsg || '저장 중 오류가 발생했습니다.'));
            });
        });
    });
})();
</script>
'@)
WriteSource $f $s
