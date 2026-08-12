package com.energyx.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

/**
 * 收益统计周期（收益接口入参 periodType，String code）。
 *
 * <p>
 * 设计文档初稿为 DAY/WEEK/MONTH，但实体注释、EmsRevenueService.resolveRange 与前端 EmsRevenue.vue 实际取值均为
 * DAY/MONTH/YEAR，全代码库无 WEEK；以运行实际值定码。
 * </p>
 */
public enum RevenuePeriodType {

	DAY("DAY", "日"), MONTH("MONTH", "月"), YEAR("YEAR", "年");

	/** 存储值（对外接口 code，@EnumValue 供 MyBatis-Plus 按此读写） */
	@EnumValue
	private final String code;

	/** 展示语义（前端/日志/文档共用，与前端 enums.ts 对齐） */
	private final String desc;

	RevenuePeriodType(String code, String desc) {
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
	public static RevenuePeriodType fromCode(String code) {
		return Arrays.stream(values())
			.filter(e -> e.code.equals(code))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("未知 RevenuePeriodType code=" + code));
	}

	/** 安全查找：未知/空返回 null（查询/宽容场景用，避免抛错） */
	public static RevenuePeriodType of(String code) {
		if (code == null) {
			return null;
		}
		return Arrays.stream(values()).filter(e -> e.code.equals(code)).findFirst().orElse(null);
	}

	public String getDesc() {
		return desc;
	}

}
