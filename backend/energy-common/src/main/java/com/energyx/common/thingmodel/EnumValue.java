package com.energyx.common.thingmodel;

import lombok.Data;

/**
 * 枚举取值项（如 runMode：0待机 1充电 2放电）。
 */
@Data
public class EnumValue {

	/** 枚举取值（数值或字符串，与强转后的上报值做归一比较） */
	private Object value;

	/** 枚举取值说明（展示用，如 充电/放电） */
	private String desc;

}
