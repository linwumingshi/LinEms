package com.energyx.common.thingmodel;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 物模型内存表示（由 schema_json 解析，M2.1 起为 energy-common 共享模型）。
 *
 * <p>
 * 映射自 iot_thing_model.schema_json：properties/services/events 三类元素， 按 identifier 索引：access
 * 用于上报校验白名单 + 类型强转 + 事件级别映射；command 用于 Service 白名单与入参校验。
 * </p>
 */
@Data
public class ThingModel {

	/** 物模型版本（iot_product.model_version） */
	private String version;

	/** 属性集合，按 identifier 索引（对应 schema_json.properties[]，上行校验白名单数据源） */
	private Map<String, ThingModelProperty> properties = new LinkedHashMap<>();

	/** 服务（可下发指令）集合，按 identifier 索引（对应 schema_json.services[]，Command 校验数据源） */
	private Map<String, ThingModelService> services = new LinkedHashMap<>();

	/** 事件集合，按 identifier 索引（对应 schema_json.events[]，事件级别映射数据源） */
	private Map<String, ThingModelEvent> events = new LinkedHashMap<>();

}
