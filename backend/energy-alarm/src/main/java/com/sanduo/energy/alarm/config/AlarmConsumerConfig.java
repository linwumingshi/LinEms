package com.sanduo.energy.alarm.config;

import com.sanduo.energy.alarm.consumer.EventAlarmConsumer;
import com.sanduo.energy.alarm.consumer.PropertyAlarmConsumer;
import com.sanduo.energy.alarm.service.AlarmKafkaPublisher;
import com.sanduo.energy.common.constant.KafkaTopicConstant;
import com.sanduo.energy.common.kafka.KafkaConsumerEngine;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Properties;

/**
 * 告警消费引擎装配（两组独立消费组，各自独立线程池）。
 *
 * <ul>
 *   <li>energy-alarm-prop（iot-thing-property，key=deviceId）：属性规则（阈值/窗口/恢复）；</li>
 *   <li>energy-alarm-event（iot-thing-event，key=deviceId）：事件规则。</li>
 * </ul>
 * 失败记录进 iot-dlq；触发/恢复幂等由静默 SETNX + 雪花主键 + 条件更新保障。
 */
@Configuration
public class AlarmConsumerConfig {

    @Bean(destroyMethod = "close")
    public KafkaConsumerEngine propertyEngine(PropertyAlarmConsumer handler, AlarmProperties props,
                                              AlarmKafkaPublisher publisher) {
        return start(new KafkaConsumerEngine(
                KafkaTopicConstant.IOT_THING_PROPERTY, "energy-alarm-prop", handler,
                baseProps(props), props.getConsumerThreads(), props.getPollMs(),
                publisher::send, props.getDlqTopic()));
    }

    @Bean(destroyMethod = "close")
    public KafkaConsumerEngine eventEngine(EventAlarmConsumer handler, AlarmProperties props,
                                           AlarmKafkaPublisher publisher) {
        return start(new KafkaConsumerEngine(
                KafkaTopicConstant.IOT_THING_EVENT, "energy-alarm-event", handler,
                baseProps(props), props.getConsumerThreads(), props.getPollMs(),
                publisher::send, props.getDlqTopic()));
    }

    private KafkaConsumerEngine start(KafkaConsumerEngine engine) {
        engine.start();
        return engine;
    }

    private Properties baseProps(AlarmProperties props) {
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
