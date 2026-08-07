package com.energyx.tsdb.kafka;

import com.energyx.tsdb.config.TsdbProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.springframework.stereotype.Component;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

import java.time.Duration;
import java.util.Properties;

/**
 * TDengine 摄取侧的 Kafka 生产者：仅用于消费失败记录的 DLQ 投递（iot-dlq）。
 *
 * <p>idempotent producer（enable.idempotence + acks=all），linger 5ms、batch 64KB，
 * 与 energy-access 生产者参数一致，复用共享 topic 布局。</p>
 */
@Slf4j
@Component
public class TsdbKafkaProducer {

    private final KafkaProducer<String, String> producer;

    public TsdbKafkaProducer(TsdbProperties props) {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, props.getKafkaBootstrapServers());
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        p.put(ProducerConfig.ACKS_CONFIG, "all");
        p.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        p.put(ProducerConfig.BATCH_SIZE_CONFIG, 64 * 1024);
        p.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 32 * 1024 * 1024);
        this.producer = new KafkaProducer<>(p);
    }

    public void send(String topic, String key, String value) {
        producer.send(new ProducerRecord<>(topic, key, value));
    }

    @PreDestroy
    public void close() {
        producer.close(Duration.ofSeconds(5));
    }
}
