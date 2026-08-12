package com.energyx.access.mapper;

import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQL 契约测试（无 DB 环境，直接断言注解 SQL）：updateOffline 必须带状态保护 WHERE status IN (2,3)，防止乱序 OFFLINE
 * 事件覆盖禁用(4)/封禁(5) 管理态（Critical-1 防回退）。
 */
class DeviceStatusMapperTest {

	@Test
	void updateOffline_sqlGuardsAgainstDisabledAndBanned() throws Exception {
		Method m = DeviceStatusMapper.class.getMethod("updateOffline", long.class, Date.class);
		Update update = m.getAnnotation(Update.class);
		String sql = update.value()[0];
		assertTrue(sql.contains("WHERE device_id = #{deviceId}"), "updateOffline 必须按 deviceId 定位，实际 SQL: " + sql);
		assertTrue(sql.contains("status IN (2,3)"),
				"updateOffline 必须仅对 2 已激活(离线)/3 在线 回写，防止禁用/封禁被 OFFLINE 事件撤销，实际 SQL: " + sql);
	}

}
