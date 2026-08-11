package com.energyx.ems.client;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 属性历史分页视图投影（本地 DTO，不依赖 energy-tsdb 模块）。total 用 primitive long 保持数字序列化。 */
@Data
public class TsdbHistoryViewDto {

	private String deviceId;

	private String productKey;

	private long total;

	private List<TsdbHistoryRecordDto> records = new ArrayList<>();

}
