package com.energyx.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

/** 企业层级（sys_enterprise.level，DB 存 code：1集团直属 2子企业） */
public enum EnterpriseLevel {

	GROUP(1, "集团直属"), SUB(2, "子企业");

	/** 存储值（DB 列值，@EnumValue 让 MyBatis-Plus 按此读写） */
	@EnumValue
	private final int code;

	/** 展示语义（前端/日志/文档共用，与前端 enums.ts 对齐） */
	private final String desc;

	EnterpriseLevel(int code, String desc) {
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
	public static EnterpriseLevel fromCode(int code) {
		return Arrays.stream(values())
			.filter(e -> e.code == code)
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("未知 EnterpriseLevel code=" + code));
	}

	/** 安全查找：未知/空返回 null（查询/宽容场景用，避免抛错） */
	public static EnterpriseLevel of(Integer code) {
		if (code == null) {
			return null;
		}
		return Arrays.stream(values()).filter(e -> e.code == code).findFirst().orElse(null);
	}

	public String getDesc() {
		return desc;
	}

}
