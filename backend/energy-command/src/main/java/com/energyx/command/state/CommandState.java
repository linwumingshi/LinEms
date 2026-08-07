package com.energyx.command.state;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 指令状态机（对齐 iot_command.state 编码与 ADR-009 状态机）。
 *
 * <p>编码：0 CREATED → 1 SENT → 2 DEVICE_RECEIVED → 3 EXECUTING → 4 SUCCESS / 5 FAILED / 6 TIMEOUT。</p>
 *
 * <p>ACK 驱动的前向转移表见 {@link #ACK_TRANSITIONS}；终态后一律拒绝后续 ACK（幂等）。
 * 重试（FAILED/TIMEOUT → SENT）与超时置终态由内部动作驱动，不在此表。</p>
 */
public enum CommandState {

    CREATED(0),
    SENT(1),
    DEVICE_RECEIVED(2),
    EXECUTING(3),
    SUCCESS(4),
    FAILED(5),
    TIMEOUT(6);

    private final int code;

    CommandState(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    /** ACK 允许的前向转移（key=当前状态，value=合法目标状态） */
    private static final Map<CommandState, Set<CommandState>> ACK_TRANSITIONS = new EnumMap<>(CommandState.class);

    static {
        // 防御性兜底：CREATED 即收到 ACK（正常不会发生），放行所有前向状态避免卡死
        ACK_TRANSITIONS.put(CREATED, EnumSet.of(DEVICE_RECEIVED, EXECUTING, SUCCESS, FAILED, TIMEOUT));
        ACK_TRANSITIONS.put(SENT, EnumSet.of(DEVICE_RECEIVED, EXECUTING, SUCCESS, FAILED, TIMEOUT));
        ACK_TRANSITIONS.put(DEVICE_RECEIVED, EnumSet.of(EXECUTING, SUCCESS, FAILED, TIMEOUT));
        ACK_TRANSITIONS.put(EXECUTING, EnumSet.of(SUCCESS, FAILED, TIMEOUT));
        // SUCCESS/FAILED/TIMEOUT 为终态，无出边
    }

    public static CommandState fromCode(int code) {
        for (CommandState s : values()) {
            if (s.code == code) {
                return s;
            }
        }
        throw new IllegalArgumentException("未知指令状态码: " + code);
    }

    /**
     * ACK 报文字符串 → 状态（access 侧 up/ack 报文标准化后的 status 取值）。
     *
     * @return 无法识别返回 null（调用方应丢弃）
     */
    public static CommandState fromAckStatus(String status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case "DEVICE_RECEIVED" -> DEVICE_RECEIVED;
            case "EXECUTING" -> EXECUTING;
            case "SUCCESS" -> SUCCESS;
            case "FAILED" -> FAILED;
            case "TIMEOUT" -> TIMEOUT;
            default -> null;
        };
    }

    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED || this == TIMEOUT;
    }

    /** ACK 转移合法性：非终态、且目标在允许转移表中 */
    public static boolean isAllowedAck(CommandState current, CommandState target) {
        if (current == null || target == null || current.isTerminal()) {
            return false;
        }
        return ACK_TRANSITIONS.getOrDefault(current, Set.of()).contains(target);
    }
}
