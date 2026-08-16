package com.energyx.ota.config;

import com.energyx.common.constant.KafkaTopicConstant;
import com.energyx.common.kafka.KafkaConsumerEngine;
import com.energyx.common.kafka.KafkaRecordHandler;
import com.energyx.ota.mqtt.OtaLifecycleHandler;
import com.energyx.ota.mqtt.OtaUplinkHandler;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Properties;

/**
 * OTA 消费引擎装配（独立消费组）。
 *
 * <ul>
 * <li>energy-ota-uplink（ota.uplink，2 线程）：设备 OTA 上行报文（inform/progress/result/pull），
 * key=deviceId 分区保序；</li>
 * <li>energy-ota-lifecycle（iot-device-lifecycle，1 线程）：设备上线触发任务补推。</li>
 * </ul>
 * kafka-clients 原生客户端（镜像无 spring-boot-starter-kafka）。
 */
@Configuration
public class OtaConsumerConfig {

	@Bean(destroyMethod = "close")
	public KafkaConsumerEngine otaUplinkEngine(OtaUplinkHandler handler) {
		KafkaConsumerEngine engine = new KafkaConsumerEngine(KafkaTopicConstant.OTA_UPLINK, "energy-ota-uplink",
				handler, baseProps(), 2, 200, (t, k, v) -> {
				}, KafkaTopicConstant.IOT_DLQ);
		engine.start();
		return engine;
	}

	@Bean(destroyMethod = "close")
	public KafkaConsumerEngine otaLifecycleEngine(OtaLifecycleHandler handler) {
		KafkaConsumerEngine engine = new KafkaConsumerEngine(KafkaTopicConstant.IOT_DEVICE_LIFECYCLE,
				"energy-ota-lifecycle", handler, baseProps(), 1, 200, (t, k, v) -> {
				}, KafkaTopicConstant.IOT_DLQ);
		engine.start();
		return engine;
	}

	private Properties baseProps() {
		Properties p = new Properties();
		p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:9092");
		p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
		p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
		p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
		p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
		p.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 200);
		return p;
	}

}
