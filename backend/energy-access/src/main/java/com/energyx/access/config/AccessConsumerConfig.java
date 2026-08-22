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

	/**
	 * 装配上行消费引擎（组 energy-access-uplink）：消费 mqtt.uplink 二进制路由信封，单分区单线程天然保序。
	 * @param handler 上行报文处理器（UplinkProcessor）
	 * @param producer 下行/回执生产者（消费失败兜底写入 DLQ）
	 * @param props 接入配置（线程数、poll 间隔、bootstrap、DLQ topic）
	 * @return 已启动的上行消费引擎
	 */
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

	/**
	 * 装配生命周期消费引擎（组 energy-access-lifecycle）：消费 iot-device-lifecycle，处理在线态/记录/离线补发。
	 * @param handler 生命周期处理器（LifecycleConsumer）
	 * @param producer 下行生产者（消费失败兜底写入 DLQ）
	 * @param props 接入配置（线程数减半、poll 间隔、bootstrap、DLQ topic）
	 * @return 已启动的生命周期消费引擎
	 */
	@Bean(destroyMethod = "close")
	public KafkaConsumerEngine lifecycleEngine(LifecycleConsumer handler, AccessKafkaProducer producer,
			AccessProperties props) {
		return start(new KafkaConsumerEngine(KafkaTopicConstant.IOT_DEVICE_LIFECYCLE, "energy-access-lifecycle",
				handler, baseProps(props), Math.max(1, props.getConsumerThreads() / 2), props.getPollMs(),
				producer::send, props.getDlqTopic()));
	}

	/**
	 * 装配下行指令消费引擎（组 energy-access-command-down）：消费 iot-command-down，桥接平台下行指令到 Broker。
	 * @param handler 下行指令处理器（CommandDownConsumer）
	 * @param producer 下行生产者（消费失败兜底写入 DLQ）
	 * @param props 接入配置（线程数减半、poll 间隔、bootstrap、DLQ topic）
	 * @return 已启动的下行指令消费引擎
	 */
	@Bean(destroyMethod = "close")
	public KafkaConsumerEngine commandDownEngine(CommandDownConsumer handler, AccessKafkaProducer producer,
			AccessProperties props) {
		return start(new KafkaConsumerEngine(KafkaTopicConstant.IOT_COMMAND_DOWN, "energy-access-command-down", handler,
				baseProps(props), Math.max(1, props.getConsumerThreads() / 2), props.getPollMs(), producer::send,
				props.getDlqTopic()));
	}

	/** 启动消费引擎并原样返回，便于 @Bean 方法链式收尾 */
	private KafkaConsumerEngine start(KafkaConsumerEngine engine) {
		engine.start();
		return engine;
	}

	/**
	 * 构造消费端公共配置：bootstrap、key/value 反序列化（String）、earliest 偏移重置、poll 上限与会话/心跳超时。
	 * @param props 接入配置（提供 bootstrap servers）
	 * @return 预置的 Kafka consumer Properties
	 */
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
