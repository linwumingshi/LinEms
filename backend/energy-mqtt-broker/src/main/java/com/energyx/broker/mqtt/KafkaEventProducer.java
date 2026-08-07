package com.energyx.broker.mqtt;

import com.energyx.broker.config.BrokerProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Properties;

/**
 * 原生 kafka-clients 生产者（路由 + 生命周期共用单实例，线程安全）。
 *
 * <p>因为镜像缺失 spring-boot-starter-kafka（Phase 3 D9），此处直接使用 kafka-clients。
 * 相比 Spring Kafka，原生客户端线程模型更透明，适合 Broker 这种自定义分区/低延迟场景；
 * 指标（发送延迟/重试）Phase 8 通过 JMX + prometheus 暴露。</p>
 *
 * <p>幂等生产：enable.idempotence=true + acks=all，防止路由消息乱序/重复（跨节点投递一致性）。</p>
 */
@Slf4j
@Component
public class KafkaEventProducer implements AutoCloseable {

    private final KafkaProducer<String, String> producer;
    private final boolean enabled;

    public KafkaEventProducer(BrokerProperties properties) {
        this.enabled = properties.getKafkaBootstrapServers() != null
                && !properties.getKafkaBootstrapServers().isBlank();
        if (!enabled) {
            log.warn("[Broker] Kafka bootstrap 未配置，路由与生命周期事件停用（单机调试模式）");
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
        log.info("[Broker] Kafka 生产者初始化完成 bootstrap={}", properties.getKafkaBootstrapServers());
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 异步发送（不阻塞 IO 线程），失败回调记错误日志并触发指标 */
    public void send(String topic, String key, String value) {
        if (!enabled || producer == null) {
            log.debug("[Broker] Kafka 停用，丢弃事件 topic={} key={}", topic, key);
            return;
        }
        producer.send(new ProducerRecord<>(topic, key, value), (metadata, ex) -> {
            if (ex != null) {
                log.error("[Broker] Kafka 发送失败 topic={} key={}", topic, key, ex);
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
