package com.energyx.common.enums;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CommandState 枚举基座测试：code 解析、安全查找、desc 语义、ACK 状态机与 JSON 数字往返。
 */
class CommandStateTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void fromCode_normal() {
		assertEquals(CommandState.CREATED, CommandState.fromCode(0));
		assertEquals(CommandState.SENT, CommandState.fromCode(1));
		assertEquals(CommandState.DEVICE_RECEIVED, CommandState.fromCode(2));
		assertEquals(CommandState.EXECUTING, CommandState.fromCode(3));
		assertEquals(CommandState.SUCCESS, CommandState.fromCode(4));
		assertEquals(CommandState.FAILED, CommandState.fromCode(5));
		assertEquals(CommandState.TIMEOUT, CommandState.fromCode(6));
	}

	@Test
	void fromCode_unknown_throws() {
		assertThrows(IllegalArgumentException.class, () -> CommandState.fromCode(-1));
		assertThrows(IllegalArgumentException.class, () -> CommandState.fromCode(99));
	}

	@Test
	void of_nullSafe() {
		assertNull(CommandState.of(null));
		assertNull(CommandState.of(99));
		assertEquals(CommandState.SUCCESS, CommandState.of(4));
	}

	@Test
	void getDesc() {
		assertEquals("已创建", CommandState.CREATED.getDesc());
		assertEquals("已发送", CommandState.SENT.getDesc());
		assertEquals("设备已接收", CommandState.DEVICE_RECEIVED.getDesc());
		assertEquals("执行中", CommandState.EXECUTING.getDesc());
		assertEquals("成功", CommandState.SUCCESS.getDesc());
		assertEquals("失败", CommandState.FAILED.getDesc());
		assertEquals("超时", CommandState.TIMEOUT.getDesc());
	}

	@Test
	void codeValues() {
		assertEquals(0, CommandState.CREATED.getCode());
		assertEquals(1, CommandState.SENT.getCode());
		assertEquals(2, CommandState.DEVICE_RECEIVED.getCode());
		assertEquals(3, CommandState.EXECUTING.getCode());
		assertEquals(4, CommandState.SUCCESS.getCode());
		assertEquals(5, CommandState.FAILED.getCode());
		assertEquals(6, CommandState.TIMEOUT.getCode());
	}

	@Test
	@DisplayName("ACK 字符串 → 状态映射")
	void fromAckStatus_mapping() {
		assertEquals(CommandState.DEVICE_RECEIVED, CommandState.fromAckStatus("DEVICE_RECEIVED"));
		assertEquals(CommandState.EXECUTING, CommandState.fromAckStatus("EXECUTING"));
		assertEquals(CommandState.SUCCESS, CommandState.fromAckStatus("SUCCESS"));
		assertEquals(CommandState.FAILED, CommandState.fromAckStatus("FAILED"));
		assertEquals(CommandState.TIMEOUT, CommandState.fromAckStatus("TIMEOUT"));
		assertNull(CommandState.fromAckStatus("UNKNOWN"));
		assertNull(CommandState.fromAckStatus(null));
	}

	@Test
	@DisplayName("终态判定：SUCCESS/FAILED/TIMEOUT")
	void isTerminal() {
		assertTrue(CommandState.SUCCESS.isTerminal());
		assertTrue(CommandState.FAILED.isTerminal());
		assertTrue(CommandState.TIMEOUT.isTerminal());
		assertFalse(CommandState.SENT.isTerminal());
		assertFalse(CommandState.CREATED.isTerminal());
	}

	@Test
	@DisplayName("合法前向转移")
	void allowedAck_forward() {
		assertTrue(CommandState.isAllowedAck(CommandState.SENT, CommandState.DEVICE_RECEIVED));
		assertTrue(CommandState.isAllowedAck(CommandState.SENT, CommandState.EXECUTING));
		assertTrue(CommandState.isAllowedAck(CommandState.SENT, CommandState.SUCCESS));
		assertTrue(CommandState.isAllowedAck(CommandState.DEVICE_RECEIVED, CommandState.EXECUTING));
		assertTrue(CommandState.isAllowedAck(CommandState.DEVICE_RECEIVED, CommandState.SUCCESS));
		assertTrue(CommandState.isAllowedAck(CommandState.EXECUTING, CommandState.SUCCESS));
		assertTrue(CommandState.isAllowedAck(CommandState.EXECUTING, CommandState.FAILED));
	}

	@Test
	@DisplayName("非法转移：终态无出边、跳级被拒")
	void allowedAck_invalid() {
		assertFalse(CommandState.isAllowedAck(CommandState.SUCCESS, CommandState.SENT));
		assertFalse(CommandState.isAllowedAck(CommandState.SUCCESS, CommandState.SUCCESS));
		assertFalse(CommandState.isAllowedAck(CommandState.TIMEOUT, CommandState.SENT));
		assertFalse(CommandState.isAllowedAck(CommandState.DEVICE_RECEIVED, CommandState.DEVICE_RECEIVED));
		// SENT 仅由内部动作（下发/重发）到达，ACK 不允许直跳 SENT
		assertFalse(CommandState.isAllowedAck(CommandState.CREATED, CommandState.SENT));
		assertFalse(CommandState.isAllowedAck(null, CommandState.SUCCESS));
	}

	@Test
	@DisplayName("JSON 序列化为 code 数字、按 code 反序列化还原")
	void json_roundTripAsNumber() throws Exception {
		// @JsonValue 序列化为 code 数字、@JsonCreator 按 code 还原，保证对外接口值不变
		assertEquals("4", objectMapper.writeValueAsString(CommandState.SUCCESS));
		assertEquals("6", objectMapper.writeValueAsString(CommandState.TIMEOUT));
		assertEquals(CommandState.SUCCESS, objectMapper.readValue("4", CommandState.class));
		assertEquals(CommandState.TIMEOUT, objectMapper.readValue("6", CommandState.class));
	}

}
