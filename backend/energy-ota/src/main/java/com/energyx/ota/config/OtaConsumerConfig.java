package com.energyx.ota.config;

import com.energyx.common.constant.KafkaTopicConstant;
import com.energyx.common.kafka.KafkaConsumerEngine;
import com.energyx.common.kafka.KafkaRecordHandler;
import com.energyx.ota.mqtt.OtaUplinkHandler;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Properties;

/**
 * OTA 消费引擎装配（独立消费组 energy-ota-uplink）。
 *
 * <p>
 * 消费 ota.uplink（access 透传的设备 OTA 报文），String 消息（JSON）；单设备按 deviceId
 * 分区保证组内单分区单线程保序。kafka-clients 原生客户端（镜像无 spring-boot-starter-kafka）。
 * </p>
 */
@Configuration
public class OtaConsumerConfig {

	@Bean(destroyMethod = "close")
	public KafkaConsumerEngine otaUplinkEngine(OtaUplinkHandler handler, OtaProperties props) {
		Properties p = new Properties();
		p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:9092");
		p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
		p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
		p.put(ConsumerConfig.GROUP_ID_CONFIG, "energy-ota-uplink");
		p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
		p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
		p.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 200);
		KafkaConsumerEngine engine = new KafkaConsumerEngine(KafkaTopicConstant.OTA_UPLINK, "energy-ota-uplink",
				handler, p, 2, 200, (t, k, v) -> {
				}, KafkaTopicConstant.IOT_DLQ);
		engine.start();
		return engine;
	}

}
