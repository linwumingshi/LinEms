package com.energyx.common.message;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OTA 升级通知信封（下行，Kafka mqtt.down.{nodeId} / mqtt.broadcast，key=deviceKey）。
 *
 * <p>
 * 由 OTA 中心（energy-ota）产出，Broker 消费后 PUBLISH 到设备订阅的 {@code {pk}/{dn}/ota/down}。字段对齐设计文档
 * §14 消息格式。
 * </p>
 */
@Data
public class OtaDownMessage {

	/** 任务 ID */
	private Long taskId;

	private Long deviceId;

	private Long tenantId;

	private String productKey;

	private String deviceName;

	/** 升级包 ID */
	private Long packageId;

	/** 目标版本 */
	private String version;

	/** 1全量包 2差分包 */
	private Integer packageType;

	/** 差分源版本（差分下发时携带） */
	private String baseVersion;

	/** 固件模块 */
	private String module;

	/** 下载地址 */
	private String url;

	/** 文件大小（字节） */
	private Long size;

	/** 文件 SHA256（差分时为差分包自身 sha256） */
	private String sha256;

	/** 目标完整固件 SHA256（差分合并后校验；全量包与 sha256 相同） */
	private String targetSha256;

	/** 签名方式：MD5/SHA256 */
	private String signMethod;

	/** 分片大小（字节），默认 1MB */
	private Integer segmentSize;

	/** 扩展信息（升级说明等） */
	private Map<String, Object> extData = new LinkedHashMap<>();

	private long ts;

}
