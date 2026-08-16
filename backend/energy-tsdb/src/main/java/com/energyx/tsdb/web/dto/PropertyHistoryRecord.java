package com.energyx.tsdb.web.dto;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 属性历史单行。ts 用 primitive long（JacksonConfig 只装箱 Long→字符串，primitive 保持数字）。
 */
@Data
public class PropertyHistoryRecord {

	/** 时间戳（epoch 毫秒） */
	private long ts;

	/** 该时刻所选属性快照，键为属性标识、值为属性值；某属性该行为 NULL（设备未上报）时省略该键 */
	private Map<String, Object> values = new LinkedHashMap<>();

}
