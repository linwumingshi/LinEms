package com.sanduo.energy.tsdb.config;

import com.sanduo.energy.common.constant.KafkaTopicConstant;
import com.sanduo.energy.common.kafka.KafkaConsumerEngine;
import com.sanduo.energy.tsdb.consumer.EventTsdbConsumer;
import com.sanduo.energy.tsdb.consumer.PropertyTsdbConsumer;
import com.sanduo.energy.tsdb.kafka.TsdbKafkaProducer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Properties;

/**
 * TDengine 摄取消费引擎装配（两组独立消费组）。
 *
 * <ul>
 *   <li>energy-tsdb-prop（iot-thing-property，4 线程）：属性宽表摄取；</li>
 *   <li>energy-tsdb-event（iot-thing-event，4 线程）：事件摄取。</li>
 * </ul>
 * 消息按 deviceId 分区 ⇒ 单分区单线程，单设备保序；失败记录进 iot-dlq。
 */
@Configuration
public class TsdbConsumerConfig {

    @Bean(destroyMethod = "close")
    public KafkaConsumerEngine propertyEngine(PropertyTsdbConsumer handler, TsdbProperties props,
                                              TsdbKafkaProducer producer) {
        return start(new KafkaConsumerEngine(
                KafkaTopicConstant.IOT_THING_PROPERTY, "energy-tsdb-prop", handler,
                baseProps(props), props.getConsumerThreads(), props.getPollMs(),
                producer::send, props.getDlqTopic()));
    }

    @Bean(destroyMethod = "close")
    public KafkaConsumerEngine eventEngine(EventTsdbConsumer handler, TsdbProperties props,
                                           TsdbKafkaProducer producer) {
        return start(new KafkaConsumerEngine(
                KafkaTopicConstant.IOT_THING_EVENT, "energy-tsdb-event", handler,
                baseProps(props), props.getConsumerThreads(), props.getPollMs(),
                producer::send, props.getDlqTopic()));
    }

    private KafkaConsumerEngine start(KafkaConsumerEngine engine) {
        engine.start();
        return engine;
    }

    private Properties baseProps(TsdbProperties props) {
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
