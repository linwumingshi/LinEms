package com.energyx.common.tenant;

import net.sf.jsqlparser.expression.LongValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 条件化租户 handler 测试：
 * <ul>
 * <li>无租户上下文（Kafka 消费 / 调度 / Netty 线程）→ 全部表忽略；</li>
 * <li>有上下文 → 带 tenant_id 的表不忽略、无 tenant_id 列的表忽略。</li>
 * </ul>
 */
class ConditionalTenantLineHandlerTest {

	private final ConditionalTenantLineHandler handler = new ConditionalTenantLineHandler();

	@AfterEach
	void tearDown() {
		TenantContext.clear();
	}

	@Test
	void withoutContext_ignoreAllTables() {
		// 模拟消费/调度/Broker 线程：无任何租户上下文
		assertTrue(handler.ignoreTable("iot_device"));
		assertTrue(handler.ignoreTable("iot_alarm_record"));
		assertTrue(handler.ignoreTable("sys_user"));
		assertTrue(handler.ignoreTable("iot_station_device"));
	}

	@Test
	void withContext_tenantTablesNotIgnored() {
		TenantContext.set(new TenantInfo(1L, 2L));
		assertFalse(handler.ignoreTable("iot_device"));
		assertFalse(handler.ignoreTable("iot_alarm_record"));
		assertFalse(handler.ignoreTable("sys_user"));
		assertFalse(handler.ignoreTable("IOT_STATION")); // 大小写不敏感
	}

	@Test
	void withContext_tablesWithoutTenantColumnIgnored() {
		TenantContext.set(new TenantInfo(1L, 2L));
		assertTrue(handler.ignoreTable("sys_user_role"));
		assertTrue(handler.ignoreTable("sys_permission"));
		assertTrue(handler.ignoreTable("sys_role_permission"));
		assertTrue(handler.ignoreTable("iot_device_certificate"));
		assertTrue(handler.ignoreTable("iot_device_group_relation"));
		assertTrue(handler.ignoreTable("iot_device_tag"));
		assertTrue(handler.ignoreTable("iot_shadow_history"));
		assertTrue(handler.ignoreTable("iot_command_ack"));
		assertTrue(handler.ignoreTable("iot_station_device"));
		assertTrue(handler.ignoreTable("sys_tenant")); // 租户主表自身
	}

	@Test
	void tenantColumnIsTenantId() {
		assertEquals("tenant_id", handler.getTenantIdColumn());
	}

	@Test
	void getTenantId_returnsCurrentTenantWhenPresent() {
		TenantContext.set(new TenantInfo(5L, null));
		assertEquals(5L, ((LongValue) handler.getTenantId()).getValue());
	}

}
