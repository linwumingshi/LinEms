package com.energyx.broker.session;

import io.netty.channel.Channel;
import io.netty.handler.codec.mqtt.MqttMessage;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 内存热 Session：一个设备连接对应一个 Session，只存活于设备所在节点。
 *
 * <p>
 * 设计要点（Phase 1 §4.3）：
 * <ul>
 * <li>Session 是「内存态」，Redis 只存「可重建的持久化态」（订阅/inflight/离线队列）；</li>
 * <li>跨节点不迁移 Session——设备重连被 LB 打到新节点时，从 Redis 重建持久会话（cleanSession=false）；</li>
 * <li>所有 map 为并发容器，支持 Router 消费线程与 IO 线程并发写。</li>
 * </ul>
 * </p>
 */
@Getter
public class Session {

	/** clientId = {productKey}_{deviceName}（MqttTopicUtil.buildClientId 锁定） */
	private final String deviceKey;

	private final Channel channel;

	/** MQTT 3.1.1 传 4，MQTT 5.0 传 5 */
	private final int mqttVersion;

	/** 会话是否持久（cleanSession=false） */
	@Setter
	private boolean cleanSession;

	/** 遗嘱消息（v3.1.1 从 payload 提取；v5 优先用 will 属性），null 表示无遗嘱 */
	@Setter
	private MqttWill will;

	/** 订阅集合：topicFilter → 授予 QoS */
	private final Map<String, MqttSubscription> subscriptions = new ConcurrentHashMap<>();

	/** outbound in-flight：packetId → 消息 */
	private final Map<Integer, InflightMessage> outboundInflight = new ConcurrentHashMap<>();

	/** inbound QoS2 待 PUBREL 的 packetId → 报文（收到 PUBREL 才路由，保证恰好一次） */
	private final Map<Integer, InboundPublish> inboundQos2 = new ConcurrentHashMap<>();

	/**
	 * 背压挂起队列：channel 不可写时暂存已构建的待发报文，channelWritabilityChanged 恢复可写后冲刷。 仅在该 channel 的
	 * EventLoop 上读写（投递层已收敛到 eventLoop 执行）。 QoS1/2 报文同时登记在
	 * outboundInflight，连接断开时挂起报文随连接释放，可靠性由重连续传兜底。
	 */
	private final ConcurrentLinkedDeque<MqttMessage> pendingWrites = new ConcurrentLinkedDeque<>();

	/** 业务载荷（认证结果、IP、设备ID等），避免 handler 到处塞字段 */
	private final Map<String, Object> attributes = new ConcurrentHashMap<>();

	private final AtomicInteger packetIdCursor = new AtomicInteger(1);

	/** 最近活跃时间（心跳/收发报文刷新，供连接锁与超时审计） */
	private volatile long lastActivityNanos = System.nanoTime();

	@Setter
	private long connectedAtMillis = System.currentTimeMillis();

	@Setter
	private String remoteIp;

	/** 已被同 clientId 新连接取代（踢线），清理时跳过生命周期/持久化，避免误报离线 */
	@Setter
	private boolean superseded;

	/** 设备主动 DISCONNECT（优雅断开）标记，channelInactive 据此区分离线原因 */
	@Setter
	private boolean disconnectedGracefully;

	/** 在线 TTL 续期节流：距上次续期不足该值则跳过 Redis 写 */
	public static final long RENEW_THROTTLE_NANOS = 10_000_000_000L; // 10s

	private volatile long lastRenewNanos;

	/**
	 * 会话过期时间（秒，P1-11 MQTT5 Session Expiry Interval）： 0 = 断开即过期（等同 clean 会话）；&gt;0 为 Redis
	 * 会话 TTL；v3.1.1 持久会话用配置默认值。
	 */
	@Setter
	private long sessionExpirySeconds;

	/**
	 * 客户端声明的 Receive Maximum（P1-11 MQTT5，CONNECT 属性 0x21）： 本节点可同时发送的 QoS1/2 未确认报文上限；配合
	 * max-inflight-per-session 取较小者。 默认 65535（协议上限，未声明时）。
	 */
	@Setter
	private int receiveMaximum = 65_535;

	/**
	 * 客户端声明的 Maximum Packet Size（v5 属性协商，CONNECT 属性 0x27）： 本节点可发送的最大报文大小（字节，含 MQTT 固定头）；0
	 * = 不限制（未声明或 v3）。 超限报文 QoS0 丢弃、QoS1/2 转离线队列，绝不超过客户端接收能力（[MQTT-3.1.2-25]）。
	 */
	@Setter
	private int maxPacketSize;

	/**
	 * 构造一个设备内存热会话。
	 * @param deviceKey 设备键 {productKey}_{deviceName}
	 * @param channel 设备当前所在节点的 Netty 连接 channel
	 * @param mqttVersion MQTT 协议版本（3.1.1=4 / 5.0=5）
	 * @param cleanSession 是否干净会话（true 表示断开即清持久态）
	 */
	public Session(String deviceKey, Channel channel, int mqttVersion, boolean cleanSession) {
		this.deviceKey = deviceKey;
		this.channel = channel;
		this.mqttVersion = mqttVersion;
		this.cleanSession = cleanSession;
	}

	/** 判断连接是否在线（channel 存在且活跃） */
	public boolean isOnline() {
		return channel != null && channel.isActive();
	}

	/** 分配下一个未占用的 packetId（1~65535 循环，跳过 in-flight 占用）；已满返回 -1 */
	public int allocPacketId() {
		// 容量闸门：in-flight 已满（达协议上限 65535）直接拒绝分配
		if (outboundInflight.size() >= 65_535) {
			return -1;
		}
		// 1~65535 循环防回绕分配；跳过已被占用者，全占用则返回 -1 由调用方降级（QoS>0 丢弃）
		for (int i = 0; i < 65_535; i++) {
			int id = packetIdCursor.getAndUpdate(v -> (v % 65_535) + 1);
			if (!outboundInflight.containsKey(id)) {
				return id;
			}
		}
		return -1;
	}

	/** 刷新最近活跃时间（心跳/收发报文时调用，供连接锁与超时审计） */
	public void touch() {
		this.lastActivityNanos = System.nanoTime();
	}

	/** 读取最近活跃时间戳（纳秒） */
	public long lastActivityNanos() {
		return lastActivityNanos;
	}

	/** 是否允许续期在线 TTL（10s 节流） */
	public boolean shouldRenewOnline() {
		long now = System.nanoTime();
		// 节流：距上次续期不足 10s 则跳过，避免每条上行报文都打 Redis（降低心跳续期开销）
		if (now - lastRenewNanos >= RENEW_THROTTLE_NANOS) {
			lastRenewNanos = now;
			return true;
		}
		return false;
	}

	/** 附加属性，键常量见 {@link SessionAttr} */
	public Object attr(String key) {
		return attributes.get(key);
	}

	/** 写入业务附加属性（键常量见 {@link SessionAttr}） */
	public void putAttr(String key, Object value) {
		attributes.put(key, value);
	}

	/** Session 附加属性键常量 */
	public static final class SessionAttr {

		public static final String DEVICE_ID = "deviceId";

		public static final String TENANT_ID = "tenantId";

		public static final String PRODUCT_KEY = "productKey";

		public static final String DEVICE_NAME = "deviceName";

		public static final String DEVICE_STATUS = "deviceStatus";

		public static final String WILL = "will";

	}

	/** 遗嘱消息封装（与 MqttProperties.WillProperties 解耦，便于 Redis 序列化） */
	@Getter
	@Setter
	public static class MqttWill {

		private String topic;

		private byte[] payload;

		private int qos;

		private boolean retain;

		/** v5 will delay 秒，未配置为 0 */
		private int delaySeconds;

		public MqttWill() {
		}

		public MqttWill(String topic, byte[] payload, int qos, boolean retain, int delaySeconds) {
			this.topic = topic;
			this.payload = payload;
			this.qos = qos;
			this.retain = retain;
			this.delaySeconds = delaySeconds;
		}

	}

}
