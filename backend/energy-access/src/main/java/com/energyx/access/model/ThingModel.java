package com.energyx.access.model;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 物模型内存表示（由 schema_json 解析，cache:model:current 缓存）。
 *
 * <p>映射自 iot_thing_model.schema_json：properties/services/events 三类元素，
 * 按 identifier 索引，供接入适配做上报校验白名单 + 类型强转 + 事件级别映射。</p>
 */
@Data
public class ThingModel {

    /** 物模型版本（iot_product.model_version） */
    private String version;

    private Map<String, ThingModelProperty> properties = new LinkedHashMap<>();
    private Map<String, ThingModelService> services = new LinkedHashMap<>();
    private Map<String, ThingModelEvent> events = new LinkedHashMap<>();
}
