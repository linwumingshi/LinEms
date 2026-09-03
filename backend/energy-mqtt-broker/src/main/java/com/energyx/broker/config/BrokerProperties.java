package com.energyx.broker.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Broker 运行配置（前缀 energyx.broker）。
 *
 * <p>
 * 容量目标锚点（Phase 1 §4）：单节点 25 万连接/5 万 msg/s，集群 100 万连接， 跨节点路由 P99 路由延迟 ≤ 10ms。
 * </p>
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

		/** 是否启用 mTLS 双向认证（P1-12）：要求客户端提供设备证书并校验 CN=clientId */
		private boolean clientAuth = false;

		/** 设备 CA 根证书 PEM 文件路径（clientAuth=true 时必填，用于校验设备证书链） */
		private String trustCertFile;

	}

	/** 过载处置配置（P2-9：双阈值准入 + readiness 探针判定） */
	private Overload overload = new Overload();

	@Data
	public static class Overload {

		/**
		 * 软阈值比例：接入连接数超过 {@code maxConnections × 该值} 后，新连接回 CONNACK 0x03
		 * SERVER_UNAVAILABLE（readiness 同步 DOWN）。
		 */
		private double softConnectionRatio = 0.9;

		/** 硬阈值比例：接入连接数超过 {@code maxConnections × 该值} 后，TCP 层直接关闭（连接风暴保命） */
		private double hardConnectionRatio = 1.05;

		/** 堆内存占比阈值：usedHeap / maxHeap 超过该值后 readiness DOWN（探针第二判定维度） */
		private double maxHeapRatio = 0.85;

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

	/** 连接锁 TTL（秒）：短租约 + 随在线续期，避免长连接锁过期被误判空闲；宕机节点最坏该时长后被接管 */
	private long connLockTtlSeconds = 20;

	/** 节点心跳刷新间隔（秒）：NodeHeartbeatScheduler 每该时长刷新 mqtt:node:{nodeId} */
	private long nodeHeartbeatIntervalSeconds = 10;

	/** 节点心跳 TTL（秒）：节点宕机后心跳 key 最坏该时长消失，新节点据此判定旧节点死亡并接管锁 */
	private long nodeHeartbeatTtlSeconds = 30;

	/** 单连接下行挂起消息上限（背压）：超过后 QoS0 丢弃、QoS1/2 转离线队列 */
	private int maxPendingMessagesPerSession = 1_000;

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

	/**
	 * 阶段 2 路由通道开关：设备上行 → mqtt.uplink；下行 → mqtt.down.{nodeId} 定向；KICK/回落 → mqtt.broadcast
	 */
	private boolean directedRouting = true;

	/** 兼容期旧通道 mqtt.router fan-out（仅多版本混布升级期开启；全量升级后关闭并删除 topic） */
	private boolean routerLegacyBroadcast = false;

	/** 节点专属下行 topic 分区数（mqtt.down.{nodeId}，24 分区支撑单节点下行并发） */
	private int downTopicPartitions = 24;

	/** 跨节点广播 topic 分区数（KICK/回落，低频） */
	private int broadcastTopicPartitions = 8;

	/** 消费失败毒丸报文兜底 DLQ topic */
	private String dlqTopicName = "iot-dlq";

	/** RouterConsumer 每个消费引擎线程数（分区并行；down/broadcast/legacy 各引擎独立） */
	private int routerConsumerThreads = 2;

	/** 单设备发布速率限制（每秒条数，P2-7）：0 = 不限制；超限 QoS0 丢弃、QoS1/2 关连接 */
	private int publishRateLimitRps = 0;

	/** 速率限制桶容量封顶（防海量随机 deviceKey 打爆内存，P2-7） */
	private int rateLimitBucketCapacity = 200_000;

	/** 认证并发上限（P2-8 认证风暴防护）：同时进行中的认证请求数，超限新连接直接拒绝 */
	private int authMaxConcurrent = 2_000;

	/** 会话恢复并发上限（重连风暴防护）：同时执行的订阅恢复/inflight 续传/离线补发任务数，超限延迟重试 */
	private int sessionRestoreMaxConcurrent = 1_000;

	/** 会话恢复限流重试延迟（秒） */
	private int sessionRestoreRetryDelaySeconds = 2;

	/** 会话恢复最大重试次数（防止限流期间任务无限堆积） */
	private int sessionRestoreMaxAttempts = 3;

}
