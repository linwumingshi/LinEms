package com.energyx.ems.client;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/** 属性历史单行投影。ts 用 primitive long 保持数字序列化；values 缺键表示该时刻未上报该属性。 */
@Data
public class TsdbHistoryRecordDto {

	private long ts;

	private Map<String, Object> values = new LinkedHashMap<>();

}
