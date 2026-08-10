package com.energyx.access.model;

import lombok.Data;

/**
 * 物模型事件定义（对应 schema_json.events[]）。
 */
@Data
public class ThingModelEvent {

	private String identifier;

	private String name;

	/** INFO | WARN | ERROR（映射 TDengine severity：1/2/3） */
	private String type;

}
