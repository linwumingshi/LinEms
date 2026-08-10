package com.energyx.alarm.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * energy-alarm 配置（energyx.alarm.*）。
 */
@Data
@ConfigurationProperties(prefix = "energyx.alarm")
public class AlarmProperties {

	private String nodeId = "alarm-1";

	private String kafkaBootstrapServers = "127.0.0.1:9092";

	/** 各消费组线程数 */
	private int consumerThreads = 4;

	private long pollMs = 200;

	/** 规则缓存刷新间隔（毫秒） */
	private long ruleCacheRefreshMs = 30_000;

	/** product_key → product_id 本地缓存 TTL（毫秒） */
	private long productCacheTtlMs = 60_000;

	/** 持续窗口键 TTL 缓冲（秒），保证窗口期数据不提前过期 */
	private int sustainKeyBufferSeconds = 10;

	/** ES 写入开关（单元测试/无 ES 环境置 false） */
	private boolean esEnabled = true;

	/** ES HTTP 地址 */
	private String esUrl = "http://127.0.0.1:9200";

	private String dlqTopic = "iot-dlq";

}
