package com.energyx.command.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * energy-command 配置（energyx.command.*）。
 */
@Data
@ConfigurationProperties(prefix = "energyx.command")
public class CommandProperties {

	private String nodeId = "command-1";

	private String kafkaBootstrapServers = "127.0.0.1:9092";

	/** 各消费组线程数 */
	private int consumerThreads = 4;

	private long pollMs = 200;

	/** 指令幂等窗口（秒），对齐 Redis-key规范 iot:cmd:idem 24h */
	private long idempotencyTtlSeconds = 86_400;

	/** 离线队列容量上限（条），超限丢最旧 */
	private long offlineQueueMax = 500;

	/** 单次补发上限（防止一次上线风暴拉爆下行） */
	private int offlineQueueDrainMax = 500;

	/** 在途 Hash TTL（毫秒），对齐 iot:cmd:inflight 5min */
	private long inflightTtlMs = 300_000;

	/** 超时扫描间隔（毫秒） */
	private long scanIntervalMs = 5_000;

	/** 单次扫描批量上限 */
	private int scanBatchSize = 500;

	/** 指令默认超时（毫秒），对齐 iot_command.timeout_ms 默认 15000 */
	private int defaultTimeoutMs = 15_000;

	/** 指令默认最大重试 */
	private int defaultMaxRetry = 3;

	private String dlqTopic = "iot-dlq";

}
