package com.sanduo.energy.access.mqtt;

import com.sanduo.energy.access.config.AccessProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Properties;

/**
 * 接入适配 Kafka 生产者（单实例，线程安全，幂等模式）。
 *
 * <p>原生 kafka-clients（镜像缺失 spring-boot-starter-kafka）。要点：
 * <ul>
 *   <li>idempotence + acks=all：生产者幂等，配合消费端 MessageDedup 实现端到端去重；</li>
 *   <li>linger 5ms + batch 64KB：批量发送降低小报文开销（设备上行以短 JSON 为主）；</li>
 *   <li>send 异步不阻塞消费线程；失败回调记日志（DLQ 兜底在消费引擎层）。</li>
 * </ul></p>
 */
@Slf4j
@Component
public class AccessKafkaProducer implements AutoCloseable {

    private final KafkaProducer<String, String> producer;
    private final boolean enabled;

    public AccessKafkaProducer(AccessProperties properties) {
        this.enabled = properties.getKafkaBootstrapServers() != null
                && !properties.getKafkaBootstrapServers().isBlank();
        if (!enabled) {
            log.warn("[Access] Kafka bootstrap 未配置，消息生产停用");
            this.producer = null;
            return;
        }
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.getKafkaBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
        props.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 65_536);
        props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 64 * 1024 * 1024);
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        this.producer = new KafkaProducer<>(props);
        log.info("[Access] Kafka 生产者初始化完成 bootstrap={}", properties.getKafkaBootstrapServers());
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 异步发送；Kafka 停用时静默丢弃（单机调试模式） */
    public void send(String topic, String key, String value) {
        if (!enabled || producer == null) {
            log.debug("[Access] Kafka 停用，丢弃消息 topic={} key={}", topic, key);
            return;
        }
        producer.send(new ProducerRecord<>(topic, key, value), (metadata, ex) -> {
            if (ex != null) {
                log.error("[Access] Kafka 发送失败 topic={} key={}", topic, key, ex);
            }
        });
    }

    @Override
    public void close() {
        if (producer != null) {
            producer.flush();
            producer.close(Duration.ofSeconds(3));
        }
    }
}
