package com.energyx.common.thingmodel;

import com.energyx.common.enums.EventSeverity;
import lombok.Data;

/**
 * 物模型事件定义（对应 schema_json.events[]）。
 */
@Data
public class ThingModelEvent {

	/** 事件标识符（上报 topic 子路径 / 影子字段名） */
	private String identifier;

	/** 事件中文名（展示用） */
	private String name;

	/** 事件级别（INFO/WARN/ERROR，映射 TDengine severity：1/2/3） */
	private EventSeverity type;

}
