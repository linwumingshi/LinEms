package com.energyx.broker.mqtt;

import com.energyx.broker.config.BrokerProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Properties;

/**
 * 原生 kafka-clients 生产者（路由 + 生命周期共用单实例，线程安全）。
 *
 * <p>
 * 因为镜像缺失 spring-boot-starter-kafka（Phase 3 D9），此处直接使用 kafka-clients。 相比 Spring
 * Kafka，原生客户端线程模型更透明，适合 Broker 这种自定义分区/低延迟场景。 阶段 2：value 序列化改 byte[]（二进制信封免 Base64
 * 膨胀），String 发送按 UTF-8 编码， 消费端 StringDeserializer 读回后按 ISO-8859-1 还原字节仍无损。
 * </p>
 *
 * <p>
 * 幂等生产：enable.idempotence=true + acks=all，防止路由消息乱序/重复（跨节点投递一致性）。
 * </p>
 */
@Slf4j
@Component
public class KafkaEventProducer implements AutoCloseable {

	private final KafkaProducer<String, byte[]> producer;

	private final boolean enabled;

	public KafkaEventProducer(BrokerProperties properties) {
		this.enabled = properties.getKafkaBootstrapServers() != null
				&& !properties.getKafkaBootstrapServers().isBlank();
		if (!enabled) {
			log.warn("[Broker] Kafka bootstrap 未配置，路由与生命周期事件停用（单机调试模式）");
			this.producer = null;
			return;
		}
		Properties props = new Properties();
		props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.getKafkaBootstrapServers());
		props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
		props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
		props.put(ProducerConfig.ACKS_CONFIG, "all");
		props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
		props.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
		props.put(ProducerConfig.LINGER_MS_CONFIG, 5);
		props.put(ProducerConfig.BATCH_SIZE_CONFIG, 65_536);
		props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 64 * 1024 * 1024);
		props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
		// 关键防护：绝不允许 send() 在缓冲打满时长时间阻塞调用方（可能是 Netty IO 线程）。
		// 缓冲满 200ms 内无法入队即失败走降级；整体投递 10s 上限（>= linger + request.timeout）。
		props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 200);
		props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000);
		props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10_000);
		this.producer = new KafkaProducer<>(props);
		log.info("[Broker] Kafka 生产者初始化完成 bootstrap={}", properties.getKafkaBootstrapServers());
	}

	public boolean isEnabled() {
		return enabled;
	}

	/** 异步发送（不阻塞 IO 线程），失败回调记错误日志并触发指标 */
	public void send(String topic, String key, String value) {
		send(topic, key, value, null, null);
	}

	/** 字节信封发送（mqtt.uplink / mqtt.down.{nodeId} / mqtt.broadcast 二进制信封统一入口） */
	public void sendBytes(String topic, String key, byte[] value) {
		sendBytes(topic, key, value, null, null);
	}

	/**
	 * 异步发送（带结果回调，String 值 UTF-8 编码为字节）。
	 * @param onSuccess Broker 端持久化确认（acks=all 返回），在 producer sender 线程触发
	 * @param onFailure 最终失败（重试耗尽/超时/缓冲满快速失败），在 sender 线程或调用线程触发； 需要写 Netty channel
	 * 的回调必须自行回投 eventLoop
	 */
	public void send(String topic, String key, String value, Runnable onSuccess, Runnable onFailure) {
		sendBytes(topic, key, value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8), onSuccess,
				onFailure);
	}

	/** 字节信封发送（带结果回调；同上线程约束） */
	public void sendBytes(String topic, String key, byte[] value, Runnable onSuccess, Runnable onFailure) {
		if (!enabled || producer == null) {
			log.debug("[Broker] Kafka 停用，丢弃事件 topic={} key={}", topic, key);
			if (onSuccess != null) {
				onSuccess.run(); // 单机调试模式：无路由持久化语义，视为即时成功
			}
			return;
		}
		try {
			producer.send(new ProducerRecord<>(topic, key, value), (metadata, ex) -> {
				if (ex != null) {
					log.error("[Broker] Kafka 发送失败 topic={} key={}", topic, key, ex);
					if (onFailure != null) {
						onFailure.run();
					}
				}
				else if (onSuccess != null) {
					onSuccess.run();
				}
			});
		}
		catch (Exception e) {
			// 缓冲打满 max.block.ms=200ms 后 send 同步抛异常（快速失败），走降级而非阻塞调用线程
			log.error("[Broker] Kafka 发送快速失败（缓冲满/序列化异常）topic={} key={}", topic, key, e);
			if (onFailure != null) {
				onFailure.run();
			}
		}
	}

	@Override
	public void close() {
		if (producer != null) {
			producer.flush();
			producer.close(Duration.ofSeconds(3));
		}
	}

}
