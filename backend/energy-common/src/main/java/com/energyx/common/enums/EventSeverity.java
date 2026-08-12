package com.energyx.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

/** 事件级别（物模型 schema events[].type，映射 TDengine severity 1/2/3） */
public enum EventSeverity {

	INFO("INFO", "提示（映射 TDengine 1）"), WARN("WARN", "一般（映射 TDengine 2）"), ERROR("ERROR", "严重（映射 TDengine 3）");

	/** 存储值（DB 列值，@EnumValue 让 MyBatis-Plus 按此读写） */
	@EnumValue
	private final String code;

	/** 展示语义（前端/日志/文档共用，与前端 enums.ts 对齐） */
	private final String desc;

	EventSeverity(String code, String desc) {
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
	public static EventSeverity fromCode(String code) {
		return Arrays.stream(values())
			.filter(e -> e.code.equals(code))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("未知 EventSeverity code=" + code));
	}

	/** 安全查找：未知/空返回 null（查询/宽容场景用，避免抛错） */
	public static EventSeverity of(String code) {
		if (code == null) {
			return null;
		}
		return Arrays.stream(values()).filter(e -> e.code.equals(code)).findFirst().orElse(null);
	}

	public String getDesc() {
		return desc;
	}

}
