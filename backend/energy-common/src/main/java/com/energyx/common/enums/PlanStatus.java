package com.energyx.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

/**
 * 充放电计划状态（ems_plan.status，DB 存 code）。
 *
 * <p>
 * 注：schema 注释仅列出 0-3，但状态收敛逻辑实际写 4（存在失败/超时点）落库，故枚举含 FAILED(4)。
 * </p>
 */
public enum PlanStatus {

	PENDING(0, "待执行"), RUNNING(1, "执行中"), COMPLETED(2, "完成"), CANCELED(3, "已取消"), FAILED(4, "失败");

	/** 存储值（DB 列值，@EnumValue 让 MyBatis-Plus 按此读写） */
	@EnumValue
	private final int code;

	/** 展示语义（前端/日志/文档共用，与前端 enums.ts 对齐） */
	private final String desc;

	PlanStatus(int code, String desc) {
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
	public static PlanStatus fromCode(int code) {
		return Arrays.stream(values())
			.filter(e -> e.code == code)
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("未知 PlanStatus code=" + code));
	}

	/** 安全查找：未知/空返回 null（查询/宽容场景用，避免抛错） */
	public static PlanStatus of(Integer code) {
		if (code == null) {
			return null;
		}
		return Arrays.stream(values()).filter(e -> e.code == code).findFirst().orElse(null);
	}

	public String getDesc() {
		return desc;
	}

}
