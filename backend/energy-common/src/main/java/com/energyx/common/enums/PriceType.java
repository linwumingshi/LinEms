package com.energyx.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

/** 分时电价档位（ems_electricity_price.price_type，DB 存 code） */
public enum PriceType {

	DEEP("DEEP", "深谷"), VALLEY("VALLEY", "低谷"), FLAT("FLAT", "平段"), PEAK("PEAK", "高峰"), PEEK("PEEK", "尖峰");

	/** 存储值（DB 列值，@EnumValue 让 MyBatis-Plus 按此读写） */
	@EnumValue
	private final String code;

	/** 展示语义（前端/日志/文档共用，与前端 enums.ts 对齐） */
	private final String desc;

	PriceType(String code, String desc) {
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
	public static PriceType fromCode(String code) {
		return Arrays.stream(values())
			.filter(e -> e.code.equals(code))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("未知 PriceType code=" + code));
	}

	/** 安全查找：未知/空返回 null（查询/宽容场景用，避免抛错） */
	public static PriceType of(String code) {
		if (code == null) {
			return null;
		}
		return Arrays.stream(values()).filter(e -> e.code.equals(code)).findFirst().orElse(null);
	}

	public String getDesc() {
		return desc;
	}

}
