package com.energyx.broker.routing;

import com.energyx.broker.config.BrokerProperties;
import com.energyx.broker.mqtt.KafkaEventProducer;
import com.energyx.broker.retained.RetainedMessageStore;
import com.energyx.broker.session.InflightMessage;
import com.energyx.broker.session.MqttSubscription;
import com.energyx.broker.session.OfflineMessage;
import com.energyx.broker.session.Session;
import com.energyx.broker.session.SessionStore;
import com.energyx.broker.stats.BrokerStats;
import com.energyx.broker.util.TopicMatcher;
import com.energyx.common.constant.KafkaTopicConstant;
import com.energyx.common.mqtt.MqttTopicUtil;
import com.energyx.common.mqtt.RouterEnvelope;
import com.energyx.common.mqtt.RouterEnvelopeCodec;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.handler.codec.mqtt.MqttFixedHeader;
import io.netty.handler.codec.mqtt.MqttMessage;
import io.netty.handler.codec.mqtt.MqttMessageIdVariableHeader;
import io.netty.handler.codec.mqtt.MqttMessageType;
import io.netty.handler.codec.mqtt.MqttProperties;
import io.netty.handler.codec.mqtt.MqttPublishMessage;
import io.netty.handler.codec.mqtt.MqttPublishVariableHeader;
import io.netty.handler.codec.mqtt.MqttQoS;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * 消息投递核心：本地投递 + 跨节点路由 + QoS 状态机 + 保留消息 + 离线队列 + 背压。
 *
 * <p>
 * 投递路径： <pre>
 * 设备 PUBLISH(ACL 通过) ──▶ deliverLocal（本节点订阅者）
 *                        └─▶ mqtt.router（key=topic）──▶ 其他节点 RouterConsumer ──▶ deliverLocal
 * </pre>
 *
 * <p>
 * 可靠性契约（P0 修复后）：
 * <ul>
 * <li>QoS1/2 上行：Kafka 持久化回调成功后才回 PUBACK/PUBCOMP（{@code onRouted}）；
 * 路由失败（{@code onRouteFailed}）由 handler 关连接，设备重连重传，at-least-once 不破；</li>
 * <li>跨节点投递为「至少一次」（QoS0 无重试，QoS1/2 依赖订阅端会话状态机续传）， 上游去重由 Phase 5 按 deviceId+上报序号处理。</li>
 * </ul>
 *
 * <p>
 * 背压：channel 不可写时报文挂入 {@link Session#getPendingWrites()}（容量上限
 * max-pending-messages-per-session），恢复可写后由 {@link #flushPending} 冲刷； 超限 QoS0 丢弃、QoS1/2
 * 丢弃本次写出但保留 inflight（重连续传兜底），杜绝慢设备打爆内存。
 * </p>
 *
 * <p>
 * 线程安全：deliverToSession 可被 IO 线程与 Router 消费线程并发调用； 所有 channel 写出收敛到目标 channel 的 EventLoop
 * 执行（保证同连接写序）； Redis 慢操作（in-flight 持久化、离线入队）经 brokerExecutor 异步执行，IO 线程零阻塞。
 * </p>
 */
@Slf4j
@Component
public class MessageDeliverer {

	private final LocalSubscriberIndex subscriberIndex;

	private final SessionStore sessionStore;

	private final KafkaEventProducer kafkaProducer;

	private final RetainedMessageStore retainedStore;

	private final BrokerProperties properties;

	private final BrokerStats stats;

	private final ExecutorService executor;

	public MessageDeliverer(LocalSubscriberIndex subscriberIndex, SessionStore sessionStore,
			KafkaEventProducer kafkaProducer, RetainedMessageStore retainedStore, BrokerProperties properties,
			BrokerStats stats, ExecutorService brokerExecutor) {
		this.subscriberIndex = subscriberIndex;
		this.sessionStore = sessionStore;
		this.kafkaProducer = kafkaProducer;
		this.retainedStore = retainedStore;
		this.properties = properties;
		this.stats = stats;
		this.executor = brokerExecutor;
	}

	/**
	 * 设备上行报文入口（ACL 已在 handler 校验）。等价于无回调版本： 路由结果不通知调用方（用于遗嘱、跨节点转发等无需 ACK 的场景）。
	 * @param topic 发布主题
	 * @param payload 报文载荷
	 * @param qos 发布 QoS（0/1/2）
	 * @param retain 是否保留消息
	 * @param sourceNode 路由源节点；null 表示本节点发起
	 */
	public void deliver(String topic, byte[] payload, int qos, boolean retain, String sourceNode) {
		deliver(topic, payload, qos, retain, sourceNode, null, null);
	}

	/**
	 * 设备上行报文入口（带路由确认回调）。
	 *
	 * <p>
	 * 阶段 2 定向路由（directedRouting=true）：
	 * <ul>
	 * <li>设备上行（{pk}/{dn}/up/*）→ mqtt.uplink（key=deviceKey，单设备有序）， 仅 access 唯一消费组摄取，Broker
	 * 自身不再 fan-out（Topic ACL 保证设备只订自己 down/*）；</li>
	 * <li>平台下行（{pk}/{dn}/down/*）→ 按 mqtt:conn:{deviceKey} owner 定向投递
	 * mqtt.down.{nodeId}（跨节点）或本节点直投；owner 缺失（离线/竞态）→ 持久会话入 Redis 离线队列，否则回落
	 * mqtt.broadcast；</li>
	 * <li>遗嘱/其他 topic → mqtt.broadcast（每节点唯一消费组，sourceNode 去重）。</li>
	 * </ul>
	 * 定向路由下 IO 线程零 Redis：owner 解析全部在 brokerExecutor 完成（仅下行/遗嘱路径）。
	 * </p>
	 * @param topic 发布主题
	 * @param payload 报文载荷
	 * @param qos 发布 QoS（0/1/2）
	 * @param retain 是否保留消息
	 * @param sourceNode 路由源节点；null 表示本节点发起（需要发路由），否则为远端已路由消息
	 * @param onRouted 路由持久化确认回调（Kafka 回调成功，或路由停用/远端消息时立即触发）； 可能在任何线程触发，需要写 channel
	 * 的回调必须自行回投 eventLoop
	 * @param onRouteFailed 路由持久化失败回调（同上线程约束）；为 null 时失败仅记日志
	 */
	public void deliver(String topic, byte[] payload, int qos, boolean retain, String sourceNode, Runnable onRouted,
			Runnable onRouteFailed) {
		// 保留消息更新（无论 QoS，retain 位生效；内存同步 + Redis 异步）
		if (retain) {
			retainedStore.put(topic, payload, qos);
		}
		// 本地投递（本节点订阅者；含持久离线会话幽灵订阅入队）
		deliverLocal(topic, payload, qos, retain);
		// 跨节点路由（仅本节点发起时）
		if (sourceNode == null && properties.isEnableRouter() && kafkaProducer.isEnabled()) {
			if (properties.isDirectedRouting()) {
				routeDirected(topic, payload, qos, retain, onRouted, onRouteFailed);
			}
			else {
				routeLegacy(topic, payload, qos, retain, onRouted, onRouteFailed);
			}
		}
		else if (onRouted != null) {
			onRouted.run();
		}
	}

	/**
	 * 阶段 2 定向路由：上行走 mqtt.uplink 快路径（IO 线程直接发，无 Redis）； 下行/遗嘱走 owner
	 * 解析（brokerExecutor，Redis 连接锁定位）。
	 * @param topic 发布主题
	 * @param payload 报文载荷
	 * @param qos 发布 QoS（0/1/2）
	 * @param retain 是否保留消息
	 * @param onRouted 路由持久化确认回调
	 * @param onRouteFailed 路由持久化失败回调（为 null 时仅记日志）
	 */
	private void routeDirected(String topic, byte[] payload, int qos, boolean retain, Runnable onRouted,
			Runnable onRouteFailed) {
		if (MqttTopicUtil.parseUpTopic(topic) != null || MqttTopicUtil.isOtaUpTopic(topic)) {
			// 设备上行快路径：mqtt.uplink，key=deviceKey（分区有序）。IO 线程直接发。
			// up/* 与 ota/*（OTA 上行 inform/progress/result/pull）均经此通道，由 access 分流处理。
			String deviceKey = MqttTopicUtil.buildDeviceKey(topic.split("/")[0], topic.split("/")[1]);
			byte[] envelope = RouterEnvelopeCodec
				.encode(RouterEnvelope.publish(properties.getNodeId(), topic, payload, qos, retain));
			kafkaProducer.sendBytes(KafkaTopicConstant.MQTT_UPLINK, deviceKey, envelope, () -> {
				stats.recordCrossNode();
				if (onRouted != null) {
					onRouted.run();
				}
			}, () -> {
				stats.recordRouteFailure();
				log.error("[Deliver] 上行路由持久化失败 topic={}，触发降级", topic);
				if (onRouteFailed != null) {
					onRouteFailed.run();
				}
			});
			return;
		}
		// 非上行（下行指令/遗嘱）：owner 解析 + 定向投递，Redis 慢路径全在 executor
		executor.execute(() -> routeDirectedSlow(topic, payload, qos, retain, onRouted, onRouteFailed));
	}

	/**
	 * 定向路由的 Redis 慢路径：解析下行 owner 节点并定向投递，或离线/竞态回落广播。
	 * @param topic 发布主题
	 * @param payload 报文载荷
	 * @param qos 发布 QoS（0/1/2）
	 * @param retain 是否保留消息
	 * @param onRouted 路由持久化确认回调
	 * @param onRouteFailed 路由持久化失败回调（为 null 时仅记日志）
	 */
	private void routeDirectedSlow(String topic, byte[] payload, int qos, boolean retain, Runnable onRouted,
			Runnable onRouteFailed) {
		String deviceKey = MqttTopicUtil.deviceKeyOfDownTopic(topic);
		if (deviceKey != null) {
			// 下行：按连接锁定位设备所在节点（owner 仅赋值一次，保证 lambda 内 effectively final）
			String owner;
			try {
				owner = sessionStore.resolveOwnerNode(deviceKey);
			}
			catch (Exception e) {
				log.warn("[Deliver] owner 解析异常 deviceKey={}，回落广播", deviceKey, e);
				owner = null;
			}
			final String targetOwner = owner;
			if (targetOwner == null) {
				// 离线/竞态窗口：持久会话直接入 Redis 离线队列（不依赖幽灵订阅所在节点）
				if (sessionStore.existsSession(deviceKey)) {
					try {
						sessionStore.pushOffline(deviceKey, new OfflineMessage(topic, payload, qos));
						stats.recordCrossNode();
						if (onRouted != null) {
							onRouted.run();
						}
						return;
					}
					catch (Exception e) {
						log.warn("[Deliver] 离线入队失败 deviceKey={}，回落广播", deviceKey, e);
					}
				}
				// 无持久会话或入队失败：广播兜底（多节点幽灵订阅场景）
				sendToBroadcast(topic, payload, qos, retain, onRouted, onRouteFailed);
				return;
			}
			if (properties.getNodeId().equals(targetOwner)) {
				// owner 是本节点：已本地投递（在线直投/幽灵订阅入队），无需跨节点
				if (onRouted != null) {
					onRouted.run();
				}
				return;
			}
			// 跨节点：定向投递到 owner 节点专属 topic
			byte[] envelope = RouterEnvelopeCodec
				.encode(RouterEnvelope.publish(properties.getNodeId(), topic, payload, qos, retain));
			kafkaProducer.sendBytes(KafkaTopicConstant.MQTT_DOWN_PREFIX + targetOwner, deviceKey, envelope, () -> {
				stats.recordCrossNode();
				if (onRouted != null) {
					onRouted.run();
				}
			}, () -> {
				stats.recordRouteFailure();
				log.error("[Deliver] 下行定向投递失败 topic={} owner={}，触发降级", topic, targetOwner);
				if (onRouteFailed != null) {
					onRouteFailed.run();
				}
			});
			return;
		}
		// 遗嘱/其他 topic：广播（KICK 同类低频通道）
		sendToBroadcast(topic, payload, qos, retain, onRouted, onRouteFailed);
	}

	/**
	 * 广播兜底通道：发往 mqtt.broadcast（每节点唯一消费组全量 fan-out），由 sourceNode 去重避免重复投递。
	 * @param topic 发布主题
	 * @param payload 报文载荷
	 * @param qos 发布 QoS（0/1/2）
	 * @param retain 是否保留消息
	 * @param onRouted 路由持久化确认回调
	 * @param onRouteFailed 路由持久化失败回调（为 null 时仅记日志）
	 */
	private void sendToBroadcast(String topic, byte[] payload, int qos, boolean retain, Runnable onRouted,
			Runnable onRouteFailed) {
		byte[] envelope = RouterEnvelopeCodec
			.encode(RouterEnvelope.publish(properties.getNodeId(), topic, payload, qos, retain));
		kafkaProducer.sendBytes(KafkaTopicConstant.MQTT_BROADCAST, topic, envelope, () -> {
			stats.recordCrossNode();
			if (onRouted != null) {
				onRouted.run();
			}
		}, () -> {
			stats.recordRouteFailure();
			log.error("[Deliver] 广播投递失败 topic={}，触发降级", topic);
			if (onRouteFailed != null) {
				onRouteFailed.run();
			}
		});
	}

	/**
	 * 兼容期旧通道：mqtt.router 全量 fan-out（JSON 信封，仅多版本混布时启用）。
	 * @param topic 发布主题
	 * @param payload 报文载荷
	 * @param qos 发布 QoS（0/1/2）
	 * @param retain 是否保留消息
	 * @param onRouted 路由持久化确认回调
	 * @param onRouteFailed 路由持久化失败回调（为 null 时仅记日志）
	 */
	private void routeLegacy(String topic, byte[] payload, int qos, boolean retain, Runnable onRouted,
			Runnable onRouteFailed) {
		// OTA 上行与 up/* 同源：即便回落旧通道，也必须进 mqtt.uplink 供 access 透传 ota.uplink
		if (MqttTopicUtil.parseUpTopic(topic) != null || MqttTopicUtil.isOtaUpTopic(topic)) {
			String deviceKey = MqttTopicUtil.buildDeviceKey(topic.split("/")[0], topic.split("/")[1]);
			byte[] envelope = RouterEnvelopeCodec
				.encode(RouterEnvelope.publish(properties.getNodeId(), topic, payload, qos, retain));
			kafkaProducer.sendBytes(KafkaTopicConstant.MQTT_UPLINK, deviceKey, envelope, () -> {
				stats.recordCrossNode();
				if (onRouted != null) {
					onRouted.run();
				}
			}, () -> {
				stats.recordRouteFailure();
				log.error("[Deliver] 上行路由持久化失败 topic={}，触发降级", topic);
				if (onRouteFailed != null) {
					onRouteFailed.run();
				}
			});
			return;
		}
		String envelope = toJson(RouterEnvelope.publish(properties.getNodeId(), topic, payload, qos, retain));
		kafkaProducer.send(KafkaTopicConstant.MQTT_ROUTER, topic, envelope, () -> {
			stats.recordCrossNode();
			if (onRouted != null) {
				onRouted.run();
			}
		}, () -> {
			stats.recordRouteFailure();
			log.error("[Deliver] 路由持久化失败 topic={}，触发降级", topic);
			if (onRouteFailed != null) {
				onRouteFailed.run();
			}
		});
	}

	/**
	 * 消费失败毒丸报文兜底进 DLQ（RouterConsumer 调用；不重复投递）。
	 * @param key 路由键（如 topic 或 deviceKey）
	 * @param value 原始信封字节
	 */
	public void sendToDlq(String key, byte[] value) {
		try {
			kafkaProducer.sendBytes(properties.getDlqTopicName(), key, value, null, null);
		}
		catch (Exception e) {
			log.warn("[Deliver] DLQ 写入失败 key={}", key, e);
		}
	}

	/**
	 * 本地投递：在线会话即时下发，持久离线会话进离线队列（Redis 写异步，不阻塞调用线程）。
	 * @param topic 发布主题
	 * @param payload 报文载荷
	 * @param qos 发布 QoS（0/1/2）
	 * @param retain 是否保留消息
	 */
	private void deliverLocal(String topic, byte[] payload, int qos, boolean retain) {
		List<LocalSubscriberIndex.SubscriberMatch> matches = subscriberIndex.match(topic);
		for (LocalSubscriberIndex.SubscriberMatch m : matches) {
			Session session = m.session();
			int effQos = Math.min(qos, m.qos());
			if (session.isOnline()) {
				deliverToSession(session, topic, payload, effQos, retain);
			}
			else if (!session.isCleanSession()) {
				// 持久会话离线：进离线队列（容量/TTL 由 SessionStore Lua 原子兜底）
				String deviceKey = session.getDeviceKey();
				executor.execute(() -> sessionStore.pushOffline(deviceKey, new OfflineMessage(topic, payload, qos)));
				log.debug("[Deliver] 持久会话离线入队 deviceKey={} topic={}", deviceKey, topic);
			}
		}
	}

	/**
	 * 向单会话投递（QoS 状态机 + inflight 上限 + Redis in-flight 异步持久化 + 背压挂起）。 QoS0 直接写出；QoS1/2 分配
	 * packetId、登记 outbound inflight 并异步持久化，供 ACK 与重连续传； 超 Maximum Packet Size / inflight
	 * 上限时持久会话转离线队列避免静默丢失。
	 * @param session 目标会话（在线才真正下发）
	 * @param topic 发布主题
	 * @param payload 报文载荷
	 * @param effQos 协商后的有效 QoS（发布 QoS 与订阅 QoS 取小）
	 * @param retain 是否保留消息
	 */
	public void deliverToSession(Session session, String topic, byte[] payload, int effQos, boolean retain) {
		if (!session.isOnline()) {
			return;
		}
		// v5 属性协商：Maximum Packet Size——估算报文大小超过客户端声明上限时不得发送（[MQTT-3.1.2-25]）。
		// 超限 QoS0 丢弃、QoS1/2 转离线队列（持久会话）避免静默丢失
		int maxPacketSize = session.getMaxPacketSize();
		if (maxPacketSize > 0 && estimatePacketSize(topic, payload) > maxPacketSize) {
			stats.recordPacketSizeExceeded();
			if (effQos > 0 && !session.isCleanSession()) {
				String deviceKey = session.getDeviceKey();
				executor.execute(() -> sessionStore.pushOffline(deviceKey, new OfflineMessage(topic, payload, effQos)));
			}
			log.warn("[Deliver] 报文超过客户端 Maximum Packet Size 限制 deviceKey={} topic={} " + "est={}B limit={}B，{}",
					session.getDeviceKey(), topic, estimatePacketSize(topic, payload), maxPacketSize,
					effQos > 0 ? "转离线队列" : "QoS0 丢弃");
			return;
		}
		if (effQos == MqttQoS.AT_MOST_ONCE.value()) {
			writeToChannel(session, buildPublish(topic, payload, effQos, retain, 0), effQos);
			stats.recordOutgoing();
			return;
		}
		// inflight 上限：配置值 与 v5 Receive Maximum（客户端声明）取较小者（P1-11）
		int inflightLimit = Math.min(properties.getMaxInflightPerSession(), session.getReceiveMaximum());
		if (session.getOutboundInflight().size() >= inflightLimit) {
			stats.recordInflightOverflow();
			if (!session.isCleanSession()) {
				String deviceKey = session.getDeviceKey();
				executor.execute(() -> sessionStore.pushOffline(deviceKey, new OfflineMessage(topic, payload, effQos)));
			}
			else {
				log.warn("[Deliver] inflight 超限丢弃 deviceKey={} topic={} inflight={}", session.getDeviceKey(), topic,
						session.getOutboundInflight().size());
			}
			return;
		}
		// QoS1/2：分配 packetId + 记录 in-flight（重连续传依据）
		int packetId = session.allocPacketId();
		if (packetId < 0) {
			log.warn("[Deliver] 会话 in-flight 已满，丢弃 QoS>0 消息 deviceKey={} topic={}", session.getDeviceKey(), topic);
			return;
		}
		int state = effQos == 1 ? InflightMessage.STATE_AWAITING_PUBACK : InflightMessage.STATE_AWAITING_PUBREC;
		InflightMessage inflight = new InflightMessage(packetId, topic, payload, effQos, retain, state,
				System.nanoTime());
		session.getOutboundInflight().put(packetId, inflight);
		// 异步持久化 in-flight（不阻塞 IO 线程）
		String deviceKey = session.getDeviceKey();
		executor.execute(() -> {
			try {
				sessionStore.saveInflight(deviceKey, inflight);
			}
			catch (Exception e) {
				log.warn("[Deliver] in-flight 持久化失败 deviceKey={}", deviceKey, e);
			}
		});
		writeToChannel(session, buildPublish(topic, payload, effQos, retain, packetId), effQos);
		stats.recordOutgoing();
	}

	/**
	 * 统一写出入口：收敛到目标 channel 的 EventLoop 后走背压逻辑，保证同连接写序。 Netty 跨线程 writeAndFlush
	 * 线程安全但顺序不保证，QoS 状态机依赖 ACK 顺序，必须收敛到单线程。
	 * @param session 目标会话（提供 channel 与 EventLoop）
	 * @param message 待写出的 MQTT 报文
	 * @param qos 报文 QoS（背压超限时判定丢弃策略）
	 */
	private void writeToChannel(Session session, MqttMessage message, int qos) {
		Channel channel = session.getChannel();
		if (channel.eventLoop().inEventLoop()) {
			writeOrPark(session, message, qos);
		}
		else {
			channel.eventLoop().execute(() -> writeOrPark(session, message, qos));
		}
	}

	/**
	 * 背压核心：可写且无积压直接写；否则挂入 pending 队列；超限时 QoS0 丢弃、QoS1/2 保留 inflight。
	 * @param session 目标会话（提供 channel 与 pending 队列）
	 * @param message 待写出的 MQTT 报文
	 * @param qos 报文 QoS（背压超限时判定保留 inflight 还是丢弃）
	 */
	private void writeOrPark(Session session, MqttMessage message, int qos) {
		Channel channel = session.getChannel();
		if (!session.isOnline()) {
			return;
		}
		if (session.getPendingWrites().isEmpty() && channel.isWritable()) {
			channel.writeAndFlush(message);
			return;
		}
		if (session.getPendingWrites().size() >= properties.getMaxPendingMessagesPerSession()) {
			stats.recordBackpressureDrop();
			if (qos > 0) {
				log.warn("[Deliver] pending 超限，丢弃本次写出（inflight 保留待重连续传）deviceKey={} pending={}", session.getDeviceKey(),
						session.getPendingWrites().size());
			}
			return;
		}
		session.getPendingWrites().offer(message);
		stats.recordBackpressureParked();
	}

	/**
	 * channel 恢复可写时冲刷挂起队列（由 handler 的 channelWritabilityChanged 在 eventLoop 上调用）。
	 * @param session 目标会话（提供 channel 与 pending 队列）
	 */
	public void flushPending(Session session) {
		Channel channel = session.getChannel();
		if (!session.isOnline()) {
			session.getPendingWrites().clear();
			return;
		}
		MqttMessage msg;
		while (channel.isWritable() && (msg = session.getPendingWrites().poll()) != null) {
			channel.write(msg);
		}
		channel.flush();
	}

	/**
	 * 构造 PUBLISH 报文（按 QoS 映射 MqttQoS，空 properties 对 v3.1.1/v5 均合法）。
	 * @param topic 发布主题
	 * @param payload 报文载荷
	 * @param qos 报文 QoS（0/1/2）
	 * @param retain 是否保留消息
	 * @param packetId QoS1/2 的报文标识（QoS0 传 0）
	 * @return 构造完成的 MqttPublishMessage
	 */
	private MqttPublishMessage buildPublish(String topic, byte[] payload, int qos, boolean retain, int packetId) {
		MqttQoS mqttQos = switch (qos) {
			case 1 -> MqttQoS.AT_LEAST_ONCE;
			case 2 -> MqttQoS.EXACTLY_ONCE;
			default -> MqttQoS.AT_MOST_ONCE;
		};
		MqttFixedHeader header = new MqttFixedHeader(MqttMessageType.PUBLISH, false, mqttQos, retain, 0);
		// 空 MqttProperties 对 v3.1.1 与 v5 均合法
		MqttPublishVariableHeader variableHeader = new MqttPublishVariableHeader(topic, packetId,
				MqttProperties.NO_PROPERTIES);
		return new MqttPublishMessage(header, variableHeader, Unpooled.wrappedBuffer(payload));
	}

	/**
	 * 新订阅：投递匹配的保留消息（按订阅 QoS 与保留消息 QoS 取小授予）。
	 * @param session 新订阅的会话
	 * @param topicFilter 订阅表达式（通配匹配保留消息）
	 */
	public void deliverRetainedOnSubscribe(Session session, String topicFilter) {
		List<RetainedMessageStore.RetainedEntry> entries = retainedStore.match(topicFilter);
		for (RetainedMessageStore.RetainedEntry entry : entries) {
			int effQos = Math.min(entry.getQos(), sessionSubscriptionQos(session, topicFilter));
			deliverToSession(session, entry.getTopic(), entry.payload(), effQos, true);
		}
	}

	/**
	 * 取会话对某 filter 的订阅 QoS（未订阅返回 0）。
	 * @param session 目标会话
	 * @param topicFilter 订阅表达式
	 * @return 订阅授予的 QoS（未订阅为 0）
	 */
	private int sessionSubscriptionQos(Session session, String topicFilter) {
		MqttSubscription sub = session.getSubscriptions().get(topicFilter);
		return sub == null ? 0 : sub.getQos();
	}

	/**
	 * 平台→设备下行指令（Phase 6 command 模块调用；对指定设备单发，不走通配订阅）。
	 * @param session 目标设备会话
	 * @param topic 下行指令主题
	 * @param payload 指令载荷
	 * @param qos 指令 QoS（0/1/2）
	 */
	public void sendCommandToDevice(Session session, String topic, byte[] payload, int qos) {
		deliverToSession(session, topic, payload, qos, false);
	}

	/**
	 * 重连续传：恢复 outbound in-flight。 QoS1 重发 dup PUBLISH（状态 AWAITING_PUBACK）；QoS2 未完成
	 * PUBLISH 阶段的重发 dup PUBLISH 且状态必须保持 AWAITING_PUBREC（否则 PUBREC 到达后不发 PUBREL，消息卡死）； 已完成
	 * PUBREC 的（AWAITING_PUBCOMP）按原 packetId 重发 PUBREL。 重发后按新 packetId
	 * 重新持久化，避免恢复窗口内再次断连丢消息。
	 * @param session 刚重连的会话（加载其离线 in-flight 并重发）
	 */
	public void resendInflight(Session session) {
		List<InflightMessage> pending = sessionStore.loadInflight(session.getDeviceKey());
		sessionStore.deleteInflight(session.getDeviceKey()); // 旧 packetId 记录清除，下面按新 id 重写
		for (InflightMessage msg : pending) {
			if (msg.getState() == InflightMessage.STATE_AWAITING_PUBACK
					|| msg.getState() == InflightMessage.STATE_AWAITING_PUBREC) {
				// 重新分配 packetId（原 id 已失效）；QoS2 重发 PUBLISH dup 后仍等待 PUBREC
				int newId = session.allocPacketId();
				if (newId < 0) {
					log.warn("[Deliver] 续传分配 packetId 失败 deviceKey={}", session.getDeviceKey());
					continue;
				}
				msg.setPacketId(newId);
				msg.setState(msg.getQos() == 2 ? InflightMessage.STATE_AWAITING_PUBREC
						: InflightMessage.STATE_AWAITING_PUBACK);
				session.getOutboundInflight().put(newId, msg);
				asyncSaveInflight(session.getDeviceKey(), msg);
				MqttQoS mqttQos = msg.getQos() == 2 ? MqttQoS.EXACTLY_ONCE : MqttQoS.AT_LEAST_ONCE;
				MqttFixedHeader header = new MqttFixedHeader(MqttMessageType.PUBLISH, true, mqttQos, msg.isRetain(), 0);
				MqttPublishVariableHeader vh = new MqttPublishVariableHeader(msg.getTopic(), newId,
						MqttProperties.NO_PROPERTIES);
				writeToChannel(session, new MqttPublishMessage(header, vh, Unpooled.wrappedBuffer(msg.getPayload())),
						msg.getQos());
			}
			else {
				// STATE_AWAITING_PUBCOMP：按原 packetId 重发 PUBREL，并恢复内存/持久化状态
				session.getOutboundInflight().put(msg.getPacketId(), msg);
				asyncSaveInflight(session.getDeviceKey(), msg);
				writeToChannel(session,
						new MqttMessage(
								new MqttFixedHeader(MqttMessageType.PUBREL, false, MqttQoS.AT_LEAST_ONCE, false, 0),
								MqttMessageIdVariableHeader.from(msg.getPacketId())),
						0);
			}
		}
	}

	/**
	 * 重连续传后的离线队列补发（持久会话上线时调用）。 用 TopicMatcher 按订阅 filter 通配匹配（取最高授予 QoS），修复按具体 topic 精确查表
	 * 导致通配订阅下离线消息被静默丢弃的缺陷。
	 * @param session 刚上线（持久会话）的会话（提供订阅与连接状态）
	 */
	public void deliverOfflineQueue(Session session) {
		String deviceKey = session.getDeviceKey();
		// 逐条确认投递（可靠性）：先 peek 队首，投递成功（QoS1/2 进入 inflight 由持久化续传兜底，
		// QoS0 写出）才 LTRIM 删除该条；连接中途断开时未处理消息保留在 Redis 队列，下次重连继续补发，
		// 替代原先「LRANGE+DEL 整批取走」导致的断连丢消息。
		while (session.isOnline()) {
			SessionStore.OfflinePeek peek = sessionStore.peekOfflineFirst(deviceKey);
			if (peek.message() == null) {
				if (peek.skipped()) {
					continue; // 非法消息已删除，继续处理下一条
				}
				break; // 队列已空
			}
			OfflineMessage m = peek.message();
			int effQos = -1;
			for (MqttSubscription sub : session.getSubscriptions().values()) {
				if (TopicMatcher.matches(m.getTopic(), sub.getTopicFilter())) {
					effQos = Math.max(effQos, Math.min(m.getQos(), sub.getQos()));
				}
			}
			if (effQos < 0) {
				log.debug("[Deliver] 离线消息无匹配订阅，删除 deviceKey={} topic={}", deviceKey, m.getTopic());
				sessionStore.removeOfflineFirst(deviceKey); // 订阅已变化，直接跳过并删除
				continue;
			}
			deliverToSession(session, m.getTopic(), m.payload(), effQos, false);
			sessionStore.removeOfflineFirst(deviceKey); // 投递已接管（inflight/写出），确认删除
		}
	}

	/**
	 * 经 brokerExecutor 异步持久化 outbound in-flight（不阻塞 IO 线程）。
	 * @param deviceKey 设备唯一键
	 * @param msg 待持久化的 in-flight 报文
	 */
	private void asyncSaveInflight(String deviceKey, InflightMessage msg) {
		executor.execute(() -> {
			try {
				sessionStore.saveInflight(deviceKey, msg);
			}
			catch (Exception e) {
				log.warn("[Deliver] 续传 in-flight 持久化失败 deviceKey={}", deviceKey, e);
			}
		});
	}

	/**
	 * 估算 MQTT PUBLISH 报文大小（字节）：topic + payload + 固定开销（固定头 2 + topic 长度 2 + packetId 2 +
	 * 剩余长度 1-4 + v5 properties 1-2）。估算偏保守（含 32B 裕量）， 确保不超客户端声明的 Maximum Packet Size。
	 * @param topic 发布主题
	 * @param payload 报文载荷
	 * @return 估算报文字节数（保守上界）
	 */
	private int estimatePacketSize(String topic, byte[] payload) {
		return (topic == null ? 0 : topic.length()) + (payload == null ? 0 : payload.length) + 32;
	}

	/**
	 * 信封序列化为 JSON 字符串（兼容期旧通道使用）。
	 * @param value 待序列化对象（RouterEnvelope）
	 * @return JSON 字符串
	 * @throws IllegalStateException 序列化失败时抛出
	 */
	private String toJson(Object value) {
		try {
			return sessionStore.objectMapper().writeValueAsString(value);
		}
		catch (Exception e) {
			throw new IllegalStateException("信封序列化失败", e);
		}
	}

}
