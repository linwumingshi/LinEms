package com.energyx.ota.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OTA 业务配置（energy.ota.*，见 application.yml）。
 */
@Data
@ConfigurationProperties(prefix = "energy.ota")
public class OtaProperties {

	/** 升级包文件本地存储根目录（对象存储抽象 local 实现使用） */
	private String storageDir;

	/** 升级包下载基础地址（文件下载接口前缀） */
	private String downloadBaseUrl;

	/** 设备 OTA 报文上行 Kafka topic */
	private String uplinkTopic;

	/** 设备 OTA 报文下行 Kafka topic 前缀（ota.down.{nodeId}） */
	private String downlinkPrefix;

	/** 本实例节点标识（下行信封 sourceNode） */
	private String nodeId = "ota-1";

	/** 文件存储类型（local=本地目录，预留 oss=对象存储） */
	private String storageType = "local";

	/** 签名 URL 时效（秒，默认 3600） */
	private long urlExpireSeconds = 3600;

	/** 签名 URL 密钥（下载链接 HMAC 签名，生产应通过环境变量注入） */
	private String urlSecret = "energyx-ota-url-secret";

	/** 差分包生成块大小（字节，默认 4KB 块级差分） */
	private int diffBlockSize = 4096;

	/** 分片下载块大小（字节，默认 1MB，Range 断点续传基础） */
	private int segmentSize = 1024 * 1024;

}
