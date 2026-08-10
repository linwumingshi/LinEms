package com.energyx.ems.config;

import com.energyx.common.constant.KafkaTopicConstant;
import com.energyx.common.kafka.KafkaConsumerEngine;
import com.energyx.ems.consumer.EmsAckConsumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Properties;

/**
 * EMS 消费引擎装配（P0 执行闭环）：消费 iot-command-ack 回写计划执行记录。
 *
 * <p>
 * 独立消费组 energy-ems-ack，与 energy-command 的 ack 消费组（command 状态机）隔离—— 两个消费者各取所需：command
 * 收敛指令状态机，ems 回写计划点执行结果。
 * </p>
 */
@Configuration
public class EmsConsumerConfig {

	@Value("${spring.kafka.bootstrap-servers:127.0.0.1:9092}")
	private String bootstrapServers;

	@Bean(destroyMethod = "close")
	public KafkaConsumerEngine emsAckEngine(EmsAckConsumer handler) {
		KafkaConsumerEngine engine = new KafkaConsumerEngine(KafkaTopicConstant.IOT_COMMAND_ACK, "energy-ems-ack",
				handler, baseProps(), 2, 200, null, null);
		engine.start();
		return engine;
	}

	private Properties baseProps() {
		Properties p = new Properties();
		p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
		p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
		p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
		p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
		p.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);
		p.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 10_000);
		p.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 3_000);
		return p;
	}

}
