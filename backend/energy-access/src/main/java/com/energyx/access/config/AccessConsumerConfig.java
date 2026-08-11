package com.energyx.access.config;

import com.energyx.access.mqtt.AccessKafkaProducer;
import com.energyx.access.processor.CommandDownConsumer;
import com.energyx.access.processor.LifecycleConsumer;
import com.energyx.access.processor.UplinkProcessor;
import com.energyx.common.constant.KafkaTopicConstant;
import com.energyx.common.kafka.BytesKafkaConsumerEngine;
import com.energyx.common.kafka.KafkaConsumerEngine;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Properties;

/**
 * 接入适配消费引擎装配（三个独立消费组）。
 *
 * <ul>
 * <li>energy-access-uplink（mqtt.uplink，4 线程）：设备上行摄取（阶段 2：Broker 唯一生产者， 本组唯一消费，无
 * fan-out；value 为二进制 RouterEnvelope，消费端须用 ByteArrayDeserializer 保字节无损）；</li>
 * <li>energy-access-lifecycle（iot-device-lifecycle，2 线程）：在线态/记录/补发；</li>
 * <li>energy-access-command-down（iot-command-down，2 线程）：下行桥接。</li>
 * </ul>
 * 单设备消息按 deviceId/deviceKey 分区 ⇒ 组内单分区单线程，天然保序。
 */
@Configuration
public class AccessConsumerConfig {

	@Bean(destroyMethod = "close")
	public BytesKafkaConsumerEngine uplinkEngine(UplinkProcessor handler, AccessKafkaProducer producer,
			AccessProperties props) {
		Properties p = baseProps(props);
		// 阶段 2 二进制信封：value 按原始字节反序列化。不能用 StringDeserializer——UTF-8 解码会把 magic
		// 0xE9（非法 UTF-8 首字节）替换为 U+FFFD，经 ISO-8859-1 还原成 0x3F，RouterEnvelopeCodec 判非二进制
		// 而全部「信封解码失败」丢弃。
		p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
		BytesKafkaConsumerEngine engine = new BytesKafkaConsumerEngine(List.of(KafkaTopicConstant.MQTT_UPLINK),
				"energy-access-uplink", handler, p, props.getConsumerThreads(), props.getPollMs(), producer::sendBytes,
				props.getDlqTopic());
		engine.start();
		return engine;
	}

	@Bean(destroyMethod = "close")
	public KafkaConsumerEngine lifecycleEngine(LifecycleConsumer handler, AccessKafkaProducer producer,
			AccessProperties props) {
		return start(new KafkaConsumerEngine(KafkaTopicConstant.IOT_DEVICE_LIFECYCLE, "energy-access-lifecycle",
				handler, baseProps(props), Math.max(1, props.getConsumerThreads() / 2), props.getPollMs(),
				producer::send, props.getDlqTopic()));
	}

	@Bean(destroyMethod = "close")
	public KafkaConsumerEngine commandDownEngine(CommandDownConsumer handler, AccessKafkaProducer producer,
			AccessProperties props) {
		return start(new KafkaConsumerEngine(KafkaTopicConstant.IOT_COMMAND_DOWN, "energy-access-command-down", handler,
				baseProps(props), Math.max(1, props.getConsumerThreads() / 2), props.getPollMs(), producer::send,
				props.getDlqTopic()));
	}

	private KafkaConsumerEngine start(KafkaConsumerEngine engine) {
		engine.start();
		return engine;
	}

	private Properties baseProps(AccessProperties props) {
		Properties p = new Properties();
		p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, props.getKafkaBootstrapServers());
		p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
		p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
		p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
		p.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);
		p.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 10_000);
		p.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 3_000);
		return p;
	}

}
