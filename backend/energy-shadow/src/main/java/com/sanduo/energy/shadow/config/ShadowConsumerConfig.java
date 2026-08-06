package com.sanduo.energy.shadow.config;

import com.sanduo.energy.common.constant.KafkaTopicConstant;
import com.sanduo.energy.common.kafka.KafkaConsumerEngine;
import com.sanduo.energy.shadow.consumer.PropertyShadowConsumer;
import com.sanduo.energy.shadow.mqtt.ShadowKafkaProducer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Properties;

/**
 * 影子消费引擎装配（单消费组 energy-shadow-prop，线程数可配）。
 *
 * <p>消费 iot-thing-property（key=deviceId，单分区单线程 ⇒ 单设备保序），
 * 失败记录进 iot-dlq；因影子合并且有乐观锁，不设消息去重（详见 PropertyShadowConsumer 注释）。</p>
 */
@Configuration
public class ShadowConsumerConfig {

    @Bean(destroyMethod = "close")
    public KafkaConsumerEngine propertyShadowEngine(PropertyShadowConsumer handler, ShadowProperties props,
                                                    ShadowKafkaProducer producer) {
        KafkaConsumerEngine engine = new KafkaConsumerEngine(
                KafkaTopicConstant.IOT_THING_PROPERTY, "energy-shadow-prop", handler,
                baseProps(props), props.getConsumerThreads(), props.getPollMs(),
                producer::send, props.getDlqTopic());
        engine.start();
        return engine;
    }

    private Properties baseProps(ShadowProperties props) {
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
