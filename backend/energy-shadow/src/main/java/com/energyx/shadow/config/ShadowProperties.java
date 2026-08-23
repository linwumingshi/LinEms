package com.energyx.shadow.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * energy-shadow 配置（energyx.shadow.*）。
 */
@Data
@ConfigurationProperties(prefix = "energyx.shadow")
public class ShadowProperties {

	private String nodeId = "shadow-1";

	private String kafkaBootstrapServers = "127.0.0.1:9092";

	/** 影子 reported 消费线程数 */
	private int consumerThreads = 2;

	private long pollMs = 200;

	/** Redis 影子 Hash TTL（天） */
	private long reportedTtlDays = 7;

	/** 变更历史节流：每设备每分钟最多一条 */
	private long historyThrottleSeconds = 60;

	/** 是否落影子变更历史 */
	private boolean historyEnabled = true;

	/** 乐观锁重试上限 */
	private int optimisticMaxRetry = 3;

	/**
	 * desired 物模型写入校验模式（M2.2）：OFF 不校验（保持历史行为）/ WARN 校验并告警不阻止（缺省）/ ENFORCE 校验失败拒绝整个
	 * desired 写入。配置键：energyx.shadow.model-check.mode。
	 */
	private String modelCheckMode = "WARN";

	private String dlqTopic = "iot-dlq";

}
