package com.energyx.common.enums;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * DeviceStatus 枚举基座测试：code 解析、安全查找、desc 语义与 JSON 数字往返。
 */
class DeviceStatusTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void fromCode_normal() {
		assertEquals(DeviceStatus.UNREGISTERED, DeviceStatus.fromCode(0));
		assertEquals(DeviceStatus.INACTIVE, DeviceStatus.fromCode(1));
		assertEquals(DeviceStatus.OFFLINE, DeviceStatus.fromCode(2));
		assertEquals(DeviceStatus.ONLINE, DeviceStatus.fromCode(3));
		assertEquals(DeviceStatus.DISABLED, DeviceStatus.fromCode(4));
		assertEquals(DeviceStatus.BANNED, DeviceStatus.fromCode(5));
	}

	@Test
	void fromCode_unknown_throws() {
		assertThrows(IllegalArgumentException.class, () -> DeviceStatus.fromCode(-1));
		assertThrows(IllegalArgumentException.class, () -> DeviceStatus.fromCode(99));
	}

	@Test
	void of_nullSafe() {
		assertNull(DeviceStatus.of(null));
		assertNull(DeviceStatus.of(99));
		assertEquals(DeviceStatus.ONLINE, DeviceStatus.of(3));
	}

	@Test
	void getDesc() {
		assertEquals("未注册", DeviceStatus.UNREGISTERED.getDesc());
		assertEquals("未激活", DeviceStatus.INACTIVE.getDesc());
		assertEquals("已激活离线", DeviceStatus.OFFLINE.getDesc());
		assertEquals("在线", DeviceStatus.ONLINE.getDesc());
		assertEquals("禁用", DeviceStatus.DISABLED.getDesc());
		assertEquals("封禁", DeviceStatus.BANNED.getDesc());
	}

	@Test
	void codeValues() {
		assertEquals(0, DeviceStatus.UNREGISTERED.getCode());
		assertEquals(1, DeviceStatus.INACTIVE.getCode());
		assertEquals(2, DeviceStatus.OFFLINE.getCode());
		assertEquals(3, DeviceStatus.ONLINE.getCode());
		assertEquals(4, DeviceStatus.DISABLED.getCode());
		assertEquals(5, DeviceStatus.BANNED.getCode());
	}

	@Test
	void json_roundTripAsNumber() throws Exception {
		// @JsonValue 序列化为 code 数字、@JsonCreator 按 code 还原，保证对外接口值不变
		assertEquals("2", objectMapper.writeValueAsString(DeviceStatus.OFFLINE));
		assertEquals("5", objectMapper.writeValueAsString(DeviceStatus.BANNED));
		assertEquals(DeviceStatus.OFFLINE, objectMapper.readValue("2", DeviceStatus.class));
		assertEquals(DeviceStatus.BANNED, objectMapper.readValue("5", DeviceStatus.class));
	}

}
