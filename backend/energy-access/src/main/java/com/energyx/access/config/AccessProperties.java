package com.energyx.access.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 接入适配服务配置（energyx.access.*）。
 */
@Data
@ConfigurationProperties(prefix = "energyx.access")
public class AccessProperties {

	/** 节点 ID（下行信封 sourceNode，标识来源） */
	private String nodeId = "access-1";

	private String kafkaBootstrapServers = "127.0.0.1:9092";

	/** 每个消费引擎的线程数（分区并行数，单分区内仍保序） */
	private int consumerThreads = 4;

	/** 消费 poll 间隔（毫秒） */
	private long pollMs = 200;

	/** 物模型缓存 TTL（秒） */
	private int modelCacheTtlSeconds = 600;

	/** 设备信息缓存 TTL（秒） */
	private int deviceCacheTtlSeconds = 1800;

	/** 消息幂等窗口（秒）：同一边界内重复报文在此窗口内被去重 */
	private int msgDedupTtlSeconds = 300;

	/** 消费失败兜底 DLQ topic */
	private String dlqTopic = "iot-dlq";

	/** 设备上线单次最多补发的离线指令数（防极端积压撑爆内存） */
	private int offlineMaxRedeliver = 200;

}
