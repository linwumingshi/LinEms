package com.energyx.common.thingmodel;

import lombok.Data;

import java.util.List;

/**
 * 物模型服务定义（对应 schema_json.services[]，即可下发的指令）。
 *
 * <p>
 * M2.1 起由 Command 下发校验消费：按 identifier 做服务白名单匹配，input 入参定义交给
 * {@link ThingModelServiceValidator} 校验。
 * </p>
 */
@Data
public class ThingModelService {

	/** 服务（指令）标识符 */
	private String identifier;

	/** 服务中文名（展示用） */
	private String name;

	/** 入参定义（identifier/dataType 结构同属性） */
	private List<ThingModelParam> input;

}
