package com.energyx.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

/** 计划点执行状态（ems_execution_record.state，DB 存 code） */
public enum PlanPointState {

	PENDING(0, "待下发"), DISPATCHED(1, "已下发"), SUCCESS(2, "成功"), FAILED(3, "失败"), TIMEOUT(4, "超时");

	/** 存储值（DB 列值，@EnumValue 让 MyBatis-Plus 按此读写） */
	@EnumValue
	private final int code;

	/** 展示语义（前端/日志/文档共用，与前端 enums.ts 对齐） */
	private final String desc;

	PlanPointState(int code, String desc) {
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
	public static PlanPointState fromCode(int code) {
		return Arrays.stream(values())
			.filter(e -> e.code == code)
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("未知 PlanPointState code=" + code));
	}

	/** 安全查找：未知/空返回 null（查询/宽容场景用，避免抛错） */
	public static PlanPointState of(Integer code) {
		if (code == null) {
			return null;
		}
		return Arrays.stream(values()).filter(e -> e.code == code).findFirst().orElse(null);
	}

	/** 终态判定：SUCCESS/FAILED/TIMEOUT 后不再流转（计划收敛用） */
	public boolean isTerminal() {
		return this == SUCCESS || this == FAILED || this == TIMEOUT;
	}

	/** 失败判定：FAILED/TIMEOUT（计划状态推进归入失败分支） */
	public boolean isFailure() {
		return this == FAILED || this == TIMEOUT;
	}

	public String getDesc() {
		return desc;
	}

}
