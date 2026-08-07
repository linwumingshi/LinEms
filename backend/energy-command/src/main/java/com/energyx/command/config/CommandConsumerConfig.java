package com.energyx.command.config;

import com.energyx.command.consumer.AckCommandConsumer;
import com.energyx.command.consumer.DeltaCommandConsumer;
import com.energyx.command.consumer.LifecycleCommandConsumer;
import com.energyx.command.mqtt.CommandKafkaProducer;
import com.energyx.common.constant.KafkaTopicConstant;
import com.energyx.common.kafka.KafkaConsumerEngine;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Properties;

/**
 * 指令消费引擎装配（三组独立消费组，各自独立线程池）。
 *
 * <ul>
 *   <li>energy-cmd-ack（iot-command-ack，key=commandId）：ACK 状态机；</li>
 *   <li>energy-cmd-delta（iot-shadow-delta，key=deviceId）：影子 delta 物化；</li>
 *   <li>energy-cmd-lifecycle（iot-device-lifecycle，key=deviceId）：上线补发离线队列。</li>
 * </ul>
 * 失败记录进 iot-dlq；状态条件更新保证重放幂等。
 */
@Configuration
public class CommandConsumerConfig {

    @Bean(destroyMethod = "close")
    public KafkaConsumerEngine ackEngine(AckCommandConsumer handler, CommandProperties props,
                                         CommandKafkaProducer producer) {
        return start(new KafkaConsumerEngine(
                KafkaTopicConstant.IOT_COMMAND_ACK, "energy-cmd-ack", handler,
                baseProps(props), props.getConsumerThreads(), props.getPollMs(),
                producer::send, props.getDlqTopic()));
    }

    @Bean(destroyMethod = "close")
    public KafkaConsumerEngine deltaEngine(DeltaCommandConsumer handler, CommandProperties props,
                                           CommandKafkaProducer producer) {
        return start(new KafkaConsumerEngine(
                KafkaTopicConstant.IOT_SHADOW_DELTA, "energy-cmd-delta", handler,
                baseProps(props), props.getConsumerThreads(), props.getPollMs(),
                producer::send, props.getDlqTopic()));
    }

    @Bean(destroyMethod = "close")
    public KafkaConsumerEngine lifecycleEngine(LifecycleCommandConsumer handler, CommandProperties props,
                                               CommandKafkaProducer producer) {
        return start(new KafkaConsumerEngine(
                KafkaTopicConstant.IOT_DEVICE_LIFECYCLE, "energy-cmd-lifecycle", handler,
                baseProps(props), props.getConsumerThreads(), props.getPollMs(),
                producer::send, props.getDlqTopic()));
    }

    private KafkaConsumerEngine start(KafkaConsumerEngine engine) {
        engine.start();
        return engine;
    }

    private Properties baseProps(CommandProperties props) {
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
