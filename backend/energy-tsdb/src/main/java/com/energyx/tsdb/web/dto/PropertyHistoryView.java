package com.energyx.tsdb.web.dto;

import lombok.Data;

import java.util.List;

/**
 * 属性历史分页视图。total 用 primitive long 保持数字序列化。
 */
@Data
public class PropertyHistoryView {

	/** 设备 ID */
	private String deviceId;

	/** 产品标识（ProductKey） */
	private String productKey;

	/** 命中行总数（分页 total） */
	private long total;

	/** 当前页历史记录列表，元素说明见 {@link PropertyHistoryRecord} */
	private List<PropertyHistoryRecord> records;

}
