package com.energyx.ems.mqtt;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Properties;

/**
 * EMS Kafka 生产者：发布 ems-plan（计划生成事件，key=stationId，Phase1 §7.1）。 idempotent
 * producer（enable.idempotence + acks=all），与 access/command/alarm 等模块参数一致。
 */
@Slf4j
@Component
public class EmsKafkaProducer {

	private final KafkaProducer<String, String> producer;

	public EmsKafkaProducer(@Value("${spring.kafka.bootstrap-servers:127.0.0.1:9092}") String bootstrapServers) {
		Properties p = new Properties();
		p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
		p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
		p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
		p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
		p.put(ProducerConfig.ACKS_CONFIG, "all");
		p.put(ProducerConfig.LINGER_MS_CONFIG, 5);
		p.put(ProducerConfig.BATCH_SIZE_CONFIG, 64 * 1024);
		this.producer = new KafkaProducer<>(p);
	}

	public void send(String topic, String key, String value) {
		producer.send(new ProducerRecord<>(topic, key, value));
	}

	@PreDestroy
	public void close() {
		producer.close(Duration.ofSeconds(5));
	}

}
