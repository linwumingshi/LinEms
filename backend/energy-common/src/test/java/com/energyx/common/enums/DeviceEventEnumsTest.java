package com.energyx.common.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** device/access 模块业务枚举统一测试：code/desc/fromCode/of 语义与 DB 存量值一致（P2 枚举化 Task 3） */
class DeviceEventEnumsTest {

	@Test
	void deviceType_codesAndLookup() {
		assertEquals("ENERGY_CABINET", DeviceType.ENERGY_CABINET.getCode());
		assertEquals("BATTERY_CLUSTER", DeviceType.BATTERY_CLUSTER.getCode());
		assertEquals("PCS", DeviceType.PCS.getCode());
		assertEquals("BMS", DeviceType.BMS.getCode());
		assertEquals("EMS", DeviceType.EMS.getCode());
		assertEquals("EDGE_GW", DeviceType.EDGE_GW.getCode());
		assertEquals("METER", DeviceType.METER.getCode());
		assertEquals("储能柜", DeviceType.ENERGY_CABINET.getDesc());
		assertEquals("进线电能表", DeviceType.METER.getDesc());
		assertEquals(DeviceType.METER, DeviceType.fromCode("METER"));
		assertEquals(DeviceType.PCS, DeviceType.of("PCS"));
		assertNull(DeviceType.of("GATEWAY"));
		assertNull(DeviceType.of(null));
		assertThrows(IllegalArgumentException.class, () -> DeviceType.fromCode("UNKNOWN"));
	}

	@Test
	void credentialAuthStatus_codesAndLookup() {
		assertEquals(1, CredentialAuthStatus.NORMAL.getCode());
		assertEquals(2, CredentialAuthStatus.REVOKED.getCode());
		assertEquals("正常", CredentialAuthStatus.NORMAL.getDesc());
		assertEquals("吊销", CredentialAuthStatus.REVOKED.getDesc());
		assertEquals(CredentialAuthStatus.REVOKED, CredentialAuthStatus.fromCode(2));
		assertEquals(CredentialAuthStatus.NORMAL, CredentialAuthStatus.of(1));
		assertNull(CredentialAuthStatus.of(0));
		assertNull(CredentialAuthStatus.of(null));
	}

	@Test
	void eventSeverity_codesAndLookup() {
		assertEquals("INFO", EventSeverity.INFO.getCode());
		assertEquals("WARN", EventSeverity.WARN.getCode());
		assertEquals("ERROR", EventSeverity.ERROR.getCode());
		assertEquals(EventSeverity.WARN, EventSeverity.fromCode("WARN"));
		assertEquals(EventSeverity.ERROR, EventSeverity.of("ERROR"));
		assertNull(EventSeverity.of("CRITICAL"));
		assertNull(EventSeverity.of(null));
		assertThrows(IllegalArgumentException.class, () -> EventSeverity.fromCode("CRITICAL"));
	}

}
