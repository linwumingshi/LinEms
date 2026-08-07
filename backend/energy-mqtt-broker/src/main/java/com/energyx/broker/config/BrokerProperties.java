package com.energyx.broker.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Broker 运行配置（前缀 energyx.broker）。
 *
 * <p>容量目标锚点（Phase 1 §4）：单节点 25 万连接/5 万 msg/s，集群 100 万连接，
 * 跨节点路由 P99 路由延迟 ≤ 10ms。</p>
 */
@Data
@ConfigurationProperties(prefix = "energyx.broker")
public class BrokerProperties {

    /** 节点唯一 ID：集群内全局唯一，用于跨节点路由去重（envelope.sourceNode）与 Redis conn 锁归属 */
    private String nodeId = "broker-1";

    /** MQTT 监听端口 */
    private int port = 1883;

    /** MQTT TLS（8883）监听配置；enabled=false 时无 TLS acceptor，行为与明文单端口完全一致 */
    private Tls tls = new Tls();

    @Data
    public static class Tls {
        /** 是否启用 8883 TLS 监听（生产置 true；证书路径经 BROKER_TLS_* 注入） */
        private boolean enabled = false;
        /** TLS 监听端口 */
        private int port = 8883;
        /** 服务端证书链 PEM 文件路径 */
        private String certChainFile;
        /** 服务端私钥 PEM 文件路径 */
        private String privateKeyFile;
    }

    /** 单节点最大连接数（准入控制，超过后新连接拒绝） */
    private int maxConnections = 500_000;

    /** IO worker 线程数，0 = CPU 核数 × 2 */
    private int workerThreads = 0;

    /** 是否启用跨节点路由（mqtt.router 生产/消费）；单机演练可关闭 */
    private boolean enableRouter = true;

    /** 是否发布生命周期事件（Kafka iot-device-lifecycle + Redis iot:online） */
    private boolean enableLifecycleEvent = true;

    /** 单会话 outbound inflight 上限（QoS1/2 待确认报文数） */
    private int maxInflightPerSession = 64;

    /** 离线消息队列容量上限（持久会话离线期间积压条数） */
    private int offlineQueueCapacity = 500;

    /** 离线消息 TTL（秒），默认 7 天 */
    private long offlineQueueTtlSeconds = 604_800;

    /** 持久会话 TTL（秒），默认 7 天（Redis mqtt:session 过期时间） */
    private long sessionTtlSeconds = 604_800;

    /** 设备凭据缓存 TTL（秒），默认 30 分钟（Redis cache:cred） */
    private long credentialCacheTtlSeconds = 1_800;

    /** 认证时间戳防重放窗口（分钟），默认 ±2 分钟 */
    private int authTimestampWindowMinutes = 2;

    /** 在线标记 TTL（秒），默认 30 秒（Redis iot:online，配合心跳续期） */
    private int onlineTtlSeconds = 30;

    /** Kafka bootstrap servers（原生 kafka-clients 直连，不经过 spring.kafka） */
    private String kafkaBootstrapServers = "127.0.0.1:9092";

    /** 跨节点路由 topic 分区数（Phase 1 §7：24 partitions，按 topic hash 路由） */
    private int routerTopicPartitions = 24;

    /** 生命周期 topic 分区数 */
    private int lifecycleTopicPartitions = 24;

    /** 认证后连续失败多少次触发该 clientId 短期封禁（本地内存，生产可换 Redis 计数） */
    private int authFailureBanThreshold = 10;

    /** 认证失败封禁时长（秒） */
    private long authFailureBanSeconds = 300;

    /** 跨节点路由批量消费最大条数 */
    private int routerMaxPollRecords = 500;
}
