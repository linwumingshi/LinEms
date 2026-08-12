package com.energyx.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 指令生命周期状态（替代 Constants.CMD_STATE_*，DB 存 code）。
 *
 * <p>
 * ACK 驱动的前向转移表见 {@link #ACK_TRANSITIONS}；终态后一律拒绝后续 ACK（幂等）。 重试（FAILED/TIMEOUT →
 * SENT）与超时置终态由内部动作驱动，不在此表。
 * </p>
 */
public enum CommandState {

	CREATED(0, "已创建"), SENT(1, "已发送"), DEVICE_RECEIVED(2, "设备已接收"), EXECUTING(3, "执行中"), SUCCESS(4, "成功"),
	FAILED(5, "失败"), TIMEOUT(6, "超时");

	/** 存储值（DB 列值，@EnumValue 让 MyBatis-Plus 按此读写） */
	@EnumValue
	private final int code;

	/** 展示语义（前端/日志/文档共用，与前端 enums.ts 对齐） */
	private final String desc;

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

	CommandState(int code, String desc) {
		this.code = code;
		this.desc = desc;
	}

	/** JSON 序列化输出 code（数字），保持对外接口值不变 */
	@JsonValue
	public int getCode() {
		return code;
	}

	/** JSON 反序列化入参按 code 还原 */
	@JsonCreator
	public static CommandState fromCode(int code) {
		return Arrays.stream(values())
			.filter(e -> e.code == code)
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("未知 CommandState code=" + code));
	}

	/** 安全查找：未知/空返回 null（查询/宽容场景用，避免抛错） */
	public static CommandState of(Integer code) {
		if (code == null) {
			return null;
		}
		return Arrays.stream(values()).filter(e -> e.code == code).findFirst().orElse(null);
	}

	/**
	 * ACK 报文字符串 → 状态（access 侧 up/ack 报文标准化后的 status 取值）。
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

	public String getDesc() {
		return desc;
	}

}
