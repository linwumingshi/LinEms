package com.energyx.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

/** 电站电网类型（iot_station.grid_type，DB 存 code，中文原值） */
public enum GridType {

	COMMERCIAL_INDUSTRIAL("工商业", "工商业"), PARK("园区", "园区"), GRID("电网侧", "电网侧");

	/** 存储值（DB 列值，@EnumValue 让 MyBatis-Plus 按此读写） */
	@EnumValue
	private final String code;

	/** 展示语义（前端/日志/文档共用，与前端 enums.ts 对齐） */
	private final String desc;

	GridType(String code, String desc) {
		this.code = code;
		this.desc = desc;
	}

	/** JSON 序列化输出 code（字符串），保持对外接口值不变 */
	@JsonValue
	public String getCode() {
		return code;
	}

	/** JSON 反序列化入参按 code 还原（兼容存量 DDL 默认值 'INDUSTRIAL'） */
	@JsonCreator
	public static GridType fromCode(String code) {
		GridType gridType = of(code);
		if (gridType == null) {
			throw new IllegalArgumentException("未知 GridType code=" + code);
		}
		return gridType;
	}

	/** 安全查找：未知/空返回 null（存量 iot_station.grid_type 默认 'INDUSTRIAL' → 工商业） */
	public static GridType of(String code) {
		if (code == null) {
			return null;
		}
		if ("INDUSTRIAL".equalsIgnoreCase(code)) {
			return COMMERCIAL_INDUSTRIAL;
		}
		return Arrays.stream(values()).filter(e -> e.code.equals(code)).findFirst().orElse(null);
	}

	public String getDesc() {
		return desc;
	}

}
