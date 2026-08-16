package com.energyx.ota.mqtt;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * OTA 中心 Kafka 生产者（单实例，线程安全，幂等模式）。
 *
 * <p>
 * 原生 kafka-clients（镜像缺失 spring-boot-starter-kafka）。value 支持 byte[]（RouterEnvelope
 * 二进制信封，下行定向投递）与 String（JSON）。send 异步不阻塞；失败记日志。
 * </p>
 */
@Slf4j
@Component
public class OtaKafkaProducer implements AutoCloseable {

	private final KafkaProducer<String, byte[]> producer;

	public OtaKafkaProducer() {
		Properties props = new Properties();
		props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:9092");
		props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
		props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
		props.put(ProducerConfig.ACKS_CONFIG, "all");
		props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
		props.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
		props.put(ProducerConfig.LINGER_MS_CONFIG, 5);
		props.put(ProducerConfig.BATCH_SIZE_CONFIG, 65_536);
		props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 64 * 1024 * 1024);
		props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
		this.producer = new KafkaProducer<>(props);
	}

	/** 发送二进制信封（下行定向/广播） */
	public void sendBytes(String topic, String key, byte[] value) {
		producer.send(new ProducerRecord<>(topic, key, value), (meta, err) -> {
			if (err != null) {
				log.error("[OTA] Kafka 发送失败 topic={} key={}", topic, key, err);
			}
		});
	}

	/** 发送 JSON 消息 */
	public void send(String topic, String key, String value) {
		sendBytes(topic, key, value.getBytes(StandardCharsets.UTF_8));
	}

	@Override
	public void close() {
		producer.close();
	}

}
