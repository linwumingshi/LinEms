package com.energyx.access.model;

import com.energyx.common.enums.EventSeverity;
import lombok.Data;

/**
 * 物模型事件定义（对应 schema_json.events[]）。
 */
@Data
public class ThingModelEvent {

	private String identifier;

	private String name;

	/** 事件级别（INFO/WARN/ERROR，映射 TDengine severity：1/2/3） */
	private EventSeverity type;

}
