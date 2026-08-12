package com.energyx.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

/**
 * 充放电策略类型（ems_strategy.strategy_type，DB 存 code）。
 *
 * <p>
 * PEAK_VALLEY 峰谷套利 / DEMAND 需量削峰 / TIME 时间策略可在生成期独立产出计划点； DR（需求响应，事件驱动） 与 SOC_CTRL（SOC
 * 约束型）生成期无独立动作语义，计划生成返回空（前端标注不可生成）。
 * </p>
 */
public enum StrategyType {

	PEAK_VALLEY("PEAK_VALLEY", "峰谷套利"), DEMAND("DEMAND", "需量管理"), DR("DR", "需求响应"), SOC_CTRL("SOC_CTRL", "SOC约束"),
	TIME("TIME", "时间策略");

	/** 存储值（DB 列值，@EnumValue 让 MyBatis-Plus 按此读写） */
	@EnumValue
	private final String code;

	/** 展示语义（前端/日志/文档共用，与前端 enums.ts 对齐） */
	private final String desc;

	StrategyType(String code, String desc) {
		this.code = code;
		this.desc = desc;
	}

	/** JSON 序列化输出 code（字符串），保持对外接口值不变 */
	@JsonValue
	public String getCode() {
		return code;
	}

	/** JSON 反序列化入参按 code 还原 */
	@JsonCreator
	public static StrategyType fromCode(String code) {
		return Arrays.stream(values())
			.filter(e -> e.code.equals(code))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("未知 StrategyType code=" + code));
	}

	/** 安全查找：未知/空返回 null（查询/宽容场景用，避免抛错） */
	public static StrategyType of(String code) {
		if (code == null) {
			return null;
		}
		return Arrays.stream(values()).filter(e -> e.code.equals(code)).findFirst().orElse(null);
	}

	/** 是否可在生成期独立产出计划点（PEAK_VALLEY/DEMAND/TIME，与前端 STRATEGY_GENERATABLE_TYPES 对齐） */
	public boolean isGeneratable() {
		return this == PEAK_VALLEY || this == DEMAND || this == TIME;
	}

	public String getDesc() {
		return desc;
	}

}
