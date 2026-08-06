package com.sanduo.energy.shadow.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * energy-shadow 配置（sanduo.shadow.*）。
 */
@Data
@ConfigurationProperties(prefix = "sanduo.shadow")
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

    private String dlqTopic = "iot-dlq";
}
