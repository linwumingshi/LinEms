package com.energyx.rule.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * energy-rule 配置（energyx.rule.*）。
 */
@Data
@ConfigurationProperties(prefix = "energyx.rule")
public class RuleProperties {

	private String nodeId = "rule-1";

	private String kafkaBootstrapServers = "127.0.0.1:9092";

	/** 各消费组线程数 */
	private int consumerThreads = 4;

	private long pollMs = 200;

	/** 规则缓存刷新间隔（毫秒） */
	private long ruleCacheRefreshMs = 30_000;

	/** 规则缓存首次刷新延迟（毫秒） */
	private long ruleCacheInitialDelayMs = 10_000;

	/** 动作执行线程池核心线程数 */
	private int executorCoreSize = 4;

	/** 动作执行线程池最大线程数 */
	private int executorMaxSize = 16;

	/** 动作执行线程池队列容量 */
	private int executorQueueCapacity = 1000;

	/** webhook 通知动作超时（毫秒） */
	private long webhookTimeoutMs = 5_000;

	/** 嵌套规则最大深度 */
	private int nestMaxDepth = 5;

	/** 规则执行日志保留天数（定时清理） */
	private int logRetentionDays = 30;

}
