package com.energyx.tsdb.web.dto;

import lombok.Data;

import java.util.List;

/**
 * 属性历史分页视图。total 用 primitive long 保持数字序列化。
 */
@Data
public class PropertyHistoryView {

	private String deviceId;

	private String productKey;

	/** 命中行总数（分页 total） */
	private long total;

	private List<PropertyHistoryRecord> records;

}
