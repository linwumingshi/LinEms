package com.energyx.broker.routing;

import com.energyx.broker.config.BrokerProperties;
import com.energyx.broker.session.Session;
import com.energyx.broker.session.SessionRegistry;
import com.energyx.broker.stats.BrokerStats;
import com.energyx.common.constant.KafkaTopicConstant;
import com.energyx.common.kafka.BytesKafkaConsumerEngine;
import com.energyx.common.kafka.BytesKafkaRecordHandler;
import com.energyx.common.mqtt.RouterEnvelope;
import com.energyx.common.mqtt.RouterEnvelopeCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Properties;

/**
 * 跨节点路由消费者（阶段 2 定向投递模型，替代 mqtt.router 全量 fan-out）。
 *
 * <p>
 * 三通道（每通道独立消费组、独立线程池，互不阻塞）：
 * <ul>
 * <li><b>mqtt.down.{nodeId}</b>：节点专属下行（access/跨节点 Broker 定向写入）， 消费组
 * mqtt-down-{nodeId}，仅本节点能收到；PUBLISH → deliverToSession（本节点即目标）；</li>
 * <li><b>mqtt.broadcast</b>：跨节点广播（KICK 踢线、owner 解析失败/离线回落）， 消费组 mqtt-bc-{nodeId} 每节点唯一 →
 * 全量 fan-out + sourceNode 去重；</li>
 * <li><b>mqtt.router（兼容期）</b>：旧 fan-out 通道，仅 router-legacy-broadcast=true 时启用。</li>
 * </ul>
 *
 * <p>
 * 消费语义：手动提交（整批处理完 commitSync）+ earliest + 多线程分区并行（单分区保序）， 单条失败不阻塞分区（记日志 + 进 DLQ
 * 后照常提交，at-least-once）。
 * </p>
 *
 * <p>
 * 信封编码：二进制（RouterEnvelopeCodec，magic 0xE9 0x01）；兼容期通道同时接受 JSON（自动探测）。
 * </p>
 */
@Slf4j
@Component
public class RouterConsumer implements BytesKafkaRecordHandler {

	private final BrokerProperties properties;

	private final MessageDeliverer deliverer;

	private final SessionRegistry sessionRegistry;

	private final BrokerStats stats;

	private final ObjectMapper objectMapper;

	private volatile BytesKafkaConsumerEngine downEngine;

	private volatile BytesKafkaConsumerEngine broadcastEngine;

	private volatile BytesKafkaConsumerEngine legacyEngine;

	private volatile boolean started;

	public RouterConsumer(BrokerProperties properties, MessageDeliverer deliverer, SessionRegistry sessionRegistry,
			BrokerStats stats, ObjectMapper objectMapper) {
		this.properties = properties;
		this.deliverer = deliverer;
		this.sessionRegistry = sessionRegistry;
		this.stats = stats;
		this.objectMapper = objectMapper;
	}

	public synchronized void start() {
		if (started || !properties.isEnableRouter() || !isKafkaEnabled()) {
			log.warn("[Router] 路由消费者未启动（enableRouter=false 或 Kafka 停用）");
			return;
		}
		int threads = properties.getRouterConsumerThreads();
		String dlq = properties.getDlqTopicName();
		// 1. 下行定向：仅本节点 topic
		String downTopic = KafkaTopicConstant.MQTT_DOWN_PREFIX + properties.getNodeId();
		downEngine = new BytesKafkaConsumerEngine(List.of(downTopic), "mqtt-down-" + properties.getNodeId(), this,
				baseProps(), threads, 100, this::dlq, dlq);
		downEngine.start();
		// 2. 跨节点广播：每节点唯一消费组全量 fan-out
		broadcastEngine = new BytesKafkaConsumerEngine(List.of(KafkaTopicConstant.MQTT_BROADCAST),
				"mqtt-bc-" + properties.getNodeId(), this, baseProps(), threads, 100, this::dlq, dlq);
		broadcastEngine.start();
		// 3. 兼容期旧通道（默认关闭）
		if (properties.isRouterLegacyBroadcast()) {
			legacyEngine = new BytesKafkaConsumerEngine(List.of(KafkaTopicConstant.MQTT_ROUTER),
					"mqtt-router-" + properties.getNodeId(), this, baseProps(), threads, 100, this::dlq, dlq);
			legacyEngine.start();
		}
		started = true;
		log.info("[Router] 路由消费者启动 down={} broadcast={} legacy={} threads={}", downTopic,
				KafkaTopicConstant.MQTT_BROADCAST,
				properties.isRouterLegacyBroadcast() ? KafkaTopicConstant.MQTT_ROUTER : "off", threads);
	}

	private boolean isKafkaEnabled() {
		return properties.getKafkaBootstrapServers() != null && !properties.getKafkaBootstrapServers().isBlank();
	}

	private Properties baseProps() {
		Properties props = new Properties();
		props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.getKafkaBootstrapServers());
		// 手动提交：处理完成才 commitSync（at-least-once，配合设备侧幂等/去重）
		props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
		// earliest：节点重启后从分区最早可提交偏移恢复，避免停机窗口消息丢失
		props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
		props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, properties.getRouterMaxPollRecords());
		props.put(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG, 4 * 1024 * 1024);
		props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 10_000);
		props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 3_000);
		props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300_000);
		return props;
	}

	/** 二进制信封统一处理入口（down/broadcast/legacy 三通道共用） */
	@Override
	public void handle(ConsumerRecord<String, byte[]> record) {
		final RouterEnvelope envelope;
		try {
			envelope = RouterEnvelopeCodec.decode(record.value(), objectMapper);
		}
		catch (Exception e) {
			log.warn("[Router] 信封反序列化失败 topic={} key={} offset={}，丢弃", record.topic(), record.key(), record.offset());
			return;
		}
		String topic = record.topic();
		if (topic.startsWith(KafkaTopicConstant.MQTT_DOWN_PREFIX)) {
			// 定向通道：本节点即投递目标（不按 sourceNode 过滤；target 可能写回广播兜底）
			handlePublish(envelope, true);
			return;
		}
		if (properties.getNodeId().equals(envelope.getSourceNode())) {
			return; // 广播/兼容通道：自己发起的消息跳过（一次投递）
		}
		if (RouterEnvelope.TYPE_KICK.equals(envelope.getType())) {
			handleKick(envelope);
		}
		else {
			handlePublish(envelope, false);
		}
	}

	private void handlePublish(RouterEnvelope envelope, boolean directed) {
		byte[] payload = envelope.decodePayload();
		// directed=true：消息已按 owner 定位到本节点，直接投递（本节点无订阅则静默丢弃，属正常竞态回落）
		deliverer.deliver(envelope.getTopic(), payload, envelope.getQos(), envelope.isRetain(),
				envelope.getSourceNode());
		stats.recordIncoming();
	}

	private void handleKick(RouterEnvelope envelope) {
		Session session = sessionRegistry.get(envelope.getDeviceKey());
		if (session != null) {
			log.info("[Router] 远端踢线 deviceKey={} 源节点={}", envelope.getDeviceKey(), envelope.getSourceNode());
			session.getChannel().close();
		}
	}

	private void dlq(String topic, String key, byte[] value) {
		// 消费失败的毒丸报文进 DLQ 兜底，避免重复阻塞分区
		deliverer.sendToDlq(key, value);
	}

	public synchronized void stop() {
		started = false;
		if (downEngine != null) {
			downEngine.close();
		}
		if (broadcastEngine != null) {
			broadcastEngine.close();
		}
		if (legacyEngine != null) {
			legacyEngine.close();
		}
	}

}
