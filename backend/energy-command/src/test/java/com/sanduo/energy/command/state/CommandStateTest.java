package com.sanduo.energy.command.state;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 指令状态机纯函数测试：编码映射、ACK 前向转移表、终态判定。
 */
class CommandStateTest {

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
    @DisplayName("状态码 → 枚举（0~6）")
    void fromCode_roundTrip() {
        assertEquals(CommandState.CREATED, CommandState.fromCode(0));
        assertEquals(CommandState.SENT, CommandState.fromCode(1));
        assertEquals(CommandState.DEVICE_RECEIVED, CommandState.fromCode(2));
        assertEquals(CommandState.EXECUTING, CommandState.fromCode(3));
        assertEquals(CommandState.SUCCESS, CommandState.fromCode(4));
        assertEquals(CommandState.FAILED, CommandState.fromCode(5));
        assertEquals(CommandState.TIMEOUT, CommandState.fromCode(6));
        assertThrows(IllegalArgumentException.class, () -> CommandState.fromCode(99));
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
        assertFalse(CommandState.isAllowedAck(CommandState.CREATED, CommandState.SENT)); // SENT 仅内部动作
        assertFalse(CommandState.isAllowedAck(null, CommandState.SUCCESS));
    }
}
