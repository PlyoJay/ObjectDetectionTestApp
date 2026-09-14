$root=Join-Path $PSScriptRoot 'revert'
function ReadS($f){[IO.File]::ReadAllText((Join-Path $root $f)).Replace("`r`n","`n")}
function WriteS($f,$s){[IO.File]::WriteAllText((Join-Path $root $f),$s.Replace("`r`n","`n").Replace("`n","`r`n"),[Text.UTF8Encoding]::new($false))}
$f='src/main/java/pas/settings/service/impl/SettingsServiceImpl.java'; $s=ReadS $f
$s=[regex]::Replace($s,'\t\t\tsetting.put\("battery50Enabled"[\s\S]*?setting.put\("battery10Enabled", 0\);',"`t`t`t"+'setting.put("lowBatteryEnabled", 1);'+"`n`t`t`t"+'setting.put("lowBatteryThreshold", 25);')
$s=[regex]::Replace($s,'\t\tparams.put\("battery50Enabled"[\s\S]*?params.put\("battery10Enabled"[^\n]*',@'
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
'@)
$s=$s.Replace("`t`trequireSingleGuardianSetting(params);`n`t`tsettingsMapper.upsertGuardianNotification(params);",@'
		int count = requireSingleGuardianSetting(params);
		if (count == 0) {
			settingsMapper.insertGuardianNotification(params);
		} else {
			settingsMapper.updateGuardianNotification(params);
		}
'@)
$s=$s.Replace('private void requireSingleGuardianSetting', 'private int requireSingleGuardianSetting').Replace('if (settingsMapper.countGuardianNotification(params) > 1) {', 'int count = settingsMapper.countGuardianNotification(params);'+"`n`t`t"+'if (count > 1) {')
$s=$s.Replace("`t}`n`tprivate int enabledValue", "`t`treturn count;`n`t}`n`tprivate int enabledValue")
WriteS $f $s
$f='src/main/java/pas/settings/service/impl/SettingsMapper.java'; $s=ReadS $f
$s=$s.Replace('void upsertGuardianNotification(Map<String, Object> requestMap) throws SQLException, DataAccessException, Exception;', 'void insertGuardianNotification(Map<String, Object> requestMap) throws SQLException, DataAccessException, Exception;'+"`n`n`t"+'void updateGuardianNotification(Map<String, Object> requestMap) throws SQLException, DataAccessException, Exception;'); WriteS $f $s
$f='src/main/java/pas/settings/service/impl/SettingsMapper.xml'; $s=ReadS $f
$s=[regex]::Replace($s,'\t<select id="selectGuardianNotification"[\s\S]*?(?=\t<insert id="saveSettingList")',@'
	<select id="selectGuardianNotification" parameterType="map" resultType="egovMap">
		SELECT sos_enabled,
		       route_deviation_enabled,
		       low_battery_enabled,
		       low_battery_threshold,
		       device_offline_enabled
		  FROM tb_guardian_notification_setting
		 WHERE guardian_id = #{guardianId}
	</select>

	<!-- Called after locking the guardian account and counting its settings. -->
	<insert id="insertGuardianNotification" parameterType="map">
		INSERT INTO tb_guardian_notification_setting (
			guardian_id, sos_enabled, route_deviation_enabled,
			low_battery_enabled, low_battery_threshold, device_offline_enabled
		) VALUES (
			#{guardianId}, #{sosEnabled}, #{routeDeviationEnabled},
			#{lowBatteryEnabled}, #{lowBatteryThreshold}, #{deviceOfflineEnabled}
		)
	</insert>

	<update id="updateGuardianNotification" parameterType="map">
		UPDATE tb_guardian_notification_setting
		   SET sos_enabled = #{sosEnabled},
		       route_deviation_enabled = #{routeDeviationEnabled},
		       low_battery_enabled = #{lowBatteryEnabled},
		       low_battery_threshold = #{lowBatteryThreshold},
		       device_offline_enabled = #{deviceOfflineEnabled}
		 WHERE guardian_id = #{guardianId}
	</update>

'@); WriteS $f $s
$f='src/main/webapp/WEB-INF/jsp/setting/guardianNotificationSetting.jsp'; $s=ReadS $f
$old=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'before/src/main/webapp/WEB-INF/jsp/setting/guardianNotificationSetting.jsp')).Replace("`r`n","`n")
$battery=[regex]::Match($old,'                    <tr>\n                        <th scope="row"><label for="lowBatteryEnabledSwitch">[\s\S]*?</tr>').Value
$s=[regex]::Replace($s,'                    <tr>\n                        <th scope="row"><label for="battery50EnabledSwitch">[\s\S]*?<th></th><td></td>\n                    </tr>',$battery)
$s=$s.Replace('</head>','<style>.guardian-notification-page .battery-threshold-disabled { opacity:.45; }</style>'+"`n"+'</head>')
$s=$s.Replace("'battery50Enabled',`n        'battery25Enabled', 'battery10Enabled'", "'lowBatteryEnabled'")
$s=$s.Replace('    function editable(value) {',@'
    function setThresholdEnabled(value) {
        $('#batteryThresholdArea').toggleClass('battery-threshold-disabled', !value)
            .attr('aria-disabled', String(!value));
        $('#batteryThresholdArea input').prop('disabled', !value);
    }
    function editable(value) {
        setThresholdEnabled(value && switches.lowBatteryEnabled.check());
'@)
$s=$s.Replace("checked: false, messages: { checked: 'ON', unchecked: 'OFF' }, width: 80", "checked: false, messages: { checked: 'ON', unchecked: 'OFF' }, width: 80,`n                change: function() { setThresholdEnabled(loaded && switches.lowBatteryEnabled.check()); }")
$s=$s.Replace('            loaded = true;', '            $(''input[name="lowBatteryThreshold"][value="'' + res.data.lowBatteryThreshold + ''"]'').prop(''checked'', true);'+"`n"+'            loaded = true;')
$s=$s.Replace("            yg.ajax.post('<c:url value=""/settings/saveGuardianNotification.json""", "            sendData.lowBatteryThreshold = Number($('input[name=""lowBatteryThreshold""]:checked').val());`n            yg.ajax.post('<c:url value=""/settings/saveGuardianNotification.json""")
WriteS $f $s
$f='src/test/java/pas/settings/web/GuardianNotificationSettingTest.java'; $s=ReadS $f
$s=$s.Replace('배터리 부족 50%','배터리 부족 알림 수신').Replace('missingSettingUsesDefaultsAndFirstSaveUsesUpsert','missingSettingUsesDefaultsAndFirstSaveUsesInsert').Replace('setting.put(\"battery25Enabled\", 1)','setting.put(\"lowBatteryThreshold\", 25)').Replace('assertTrue(mapper.contains("ON DUPLICATE KEY UPDATE"));','assertTrue(mapper.contains("UPDATE tb_guardian_notification_setting"));'); WriteS $f $s
