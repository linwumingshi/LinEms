package com.energyx.common.message;

import lombok.Data;

/**
 * 设备 OTA 上行报文（Kafka ota.uplink，key=deviceId）。
 *
 * <p>
 * 由接入适配（energy-access）在设备上行链路识别 OTA 命名空间 topic
 * {@code {pk}/{dn}/ota/{inform|progress|result|pull}} 后透传产出； OTA
 * 中心（energy-ota）消费处理：inform 缓存版本、progress/result 更新任务进度、pull 触发补推。
 * </p>
 */
@Data
public class OtaUpMessage {

	/** 设备 ID（幂等锚点/分区键） */
	private Long deviceId;

	private Long tenantId;

	private String productKey;

	private String deviceName;

	/** OTA 子类型：inform/progress/result/pull */
	private String otaType;

	/** 设备原始 publish topic（{pk}/{dn}/ota/{type}） */
	private String topic;

	/** 报文体（JSON 字符串，由 ota 服务解析） */
	private String payload;

}
