package com.energyx.rule.config;

import com.energyx.common.constant.KafkaTopicConstant;
import com.energyx.common.kafka.KafkaConsumerEngine;
import com.energyx.rule.consumer.AlarmRuleConsumer;
import com.energyx.rule.consumer.LifecycleRuleConsumer;
import com.energyx.rule.consumer.PropertyRuleConsumer;
import com.energyx.rule.mqtt.RuleKafkaProducer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Properties;

/**
 * 规则引擎消费装配（三组独立消费组，各自独立线程池）。
 *
 * <ul>
 * <li>energy-rule-prop（iot-thing-property，key=deviceId）：属性触发规则；</li>
 * <li>energy-rule-lifecycle（iot-device-lifecycle，key=deviceId）：上下线触发规则；</li>
 * <li>energy-rule-alarm（iot-alarm，key=deviceId）：告警触发规则。</li>
 * </ul>
 * 失败记录进 iot-dlq；属性触发幂等由 rule 边界 MessageDedup 保障，上下线/告警天然低频由状态机兜底。
 */
@Configuration
public class RuleConsumerConfig {

	@Bean(destroyMethod = "close")
	public KafkaConsumerEngine propertyEngine(PropertyRuleConsumer handler, RuleProperties props,
			RuleKafkaProducer producer) {
		return start(new KafkaConsumerEngine(KafkaTopicConstant.IOT_THING_PROPERTY, "energy-rule-prop", handler,
				baseProps(props), props.getConsumerThreads(), props.getPollMs(), producer::send, "iot-dlq"));
	}

	@Bean(destroyMethod = "close")
	public KafkaConsumerEngine lifecycleEngine(LifecycleRuleConsumer handler, RuleProperties props,
			RuleKafkaProducer producer) {
		return start(new KafkaConsumerEngine(KafkaTopicConstant.IOT_DEVICE_LIFECYCLE, "energy-rule-lifecycle", handler,
				baseProps(props), props.getConsumerThreads(), props.getPollMs(), producer::send, "iot-dlq"));
	}

	@Bean(destroyMethod = "close")
	public KafkaConsumerEngine alarmEngine(AlarmRuleConsumer handler, RuleProperties props,
			RuleKafkaProducer producer) {
		return start(new KafkaConsumerEngine(KafkaTopicConstant.IOT_ALARM, "energy-rule-alarm", handler,
				baseProps(props), props.getConsumerThreads(), props.getPollMs(), producer::send, "iot-dlq"));
	}

	private KafkaConsumerEngine start(KafkaConsumerEngine engine) {
		engine.start();
		return engine;
	}

	private Properties baseProps(RuleProperties props) {
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
