package com.energyx.ems.client;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 影子视图响应 DTO（Feign 反序列化 energy-shadow GET /api/shadow/{deviceId} 返回体）。 本地投影，不依赖
 * energy-shadow 模块；只读 reported 属性 Map（计划初始 SOC 取 reported.soc）。
 */
@Data
public class ShadowViewDto {

	private Long deviceId;

	private Map<String, Object> reported = new LinkedHashMap<>();

	private Map<String, Object> desired = new LinkedHashMap<>();

	/** 乐观锁版本；行不存在时为 null */
	private Integer version;

	/** 最后上报时间（ISO 本地时间字符串）；行不存在时为 null */
	private String lastReportedTime;

}
