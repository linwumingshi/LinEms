package com.energyx.ota.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OTA 业务配置（energy.ota.*，见 application.yml）。
 */
@Data
@ConfigurationProperties(prefix = "energy.ota")
public class OtaProperties {

	/** 升级包文件本地存储根目录（后续替换为对象存储抽象） */
	private String storageDir;

	/** 升级包下载基础地址（文件下载接口前缀） */
	private String downloadBaseUrl;

	/** 设备 OTA 报文上行 Kafka topic */
	private String uplinkTopic;

	/** 设备 OTA 报文下行 Kafka topic 前缀（ota.down.{nodeId}） */
	private String downlinkPrefix;

	/** 本实例节点标识（下行信封 sourceNode） */
	private String nodeId = "ota-1";

}
