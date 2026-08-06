package com.sanduo.energy.broker.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanduo.energy.broker.config.BrokerProperties;
import com.sanduo.energy.broker.session.Session;
import com.sanduo.energy.broker.session.SessionRegistry;
import com.sanduo.energy.broker.stats.BrokerStats;
import com.sanduo.energy.common.constant.KafkaTopicConstant;
import com.sanduo.energy.common.mqtt.RouterEnvelope;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 跨节点路由消费者（每节点唯一消费组 `mqtt-router-{nodeId}`）。
 *
 * <p>消费组唯一 ⇒ mqtt.router 每个分区都被所有节点消费（全量 fan-out）；
 * sourceNode 等于本节点的信封直接丢弃，实现「一次投递」。KICK 信封用于同 clientId
 * 多节点连接时的远程踢线（Phase 1 §4.4 连接锁的跨节点落地）。</p>
 *
 * <p>路由延迟目标：本节点 PUBLISH → 远端节点投递 P99 ≤ 10ms（Kafka 端到端 + 消费处理）。</p>
 */
@Slf4j
@Component
public class RouterConsumer implements Runnable {

    private final BrokerProperties properties;
    private final MessageDeliverer deliverer;
    private final SessionRegistry sessionRegistry;
    private final BrokerStats stats;
    private final ObjectMapper objectMapper;

    private volatile KafkaConsumer<String, String> consumer;
    private volatile Thread thread;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public RouterConsumer(BrokerProperties properties, MessageDeliverer deliverer,
                          SessionRegistry sessionRegistry, BrokerStats stats, ObjectMapper objectMapper) {
        this.properties = properties;
        this.deliverer = deliverer;
        this.sessionRegistry = sessionRegistry;
        this.stats = stats;
        this.objectMapper = objectMapper;
    }

    public synchronized void start() {
        if (running.get() || !properties.isEnableRouter() || !isKafkaEnabled()) {
            log.warn("[Router] 路由消费者未启动（enableRouter=false 或 Kafka 停用）");
            return;
        }
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.getKafkaBootstrapServers());
        // 关键：每节点唯一消费组 → 全量 fan-out
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "mqtt-router-" + properties.getNodeId());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);
        props.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, 200);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, properties.getRouterMaxPollRecords());
        props.put(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG, 4 * 1024 * 1024);
        this.consumer = new KafkaConsumer<>(props);
        this.consumer.subscribe(List.of(KafkaTopicConstant.MQTT_ROUTER));
        this.running.set(true);
        this.thread = new Thread(this, "router-consumer");
        this.thread.setDaemon(true);
        this.thread.start();
        log.info("[Router] 路由消费者启动 group=mqtt-router-{} topic={}",
                properties.getNodeId(), KafkaTopicConstant.MQTT_ROUTER);
    }

    private boolean isKafkaEnabled() {
        return properties.getKafkaBootstrapServers() != null
                && !properties.getKafkaBootstrapServers().isBlank();
    }

    @Override
    public void run() {
        try {
            while (running.get()) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(200));
                for (ConsumerRecord<String, String> record : records) {
                    handle(record.value());
                }
            }
        } catch (org.apache.kafka.common.errors.WakeupException e) {
            log.info("[Router] 路由消费者被唤醒退出");
        } catch (Exception e) {
            log.error("[Router] 路由消费者异常退出", e);
        } finally {
            closeConsumer();
        }
    }

    private void handle(String json) {
        RouterEnvelope envelope;
        try {
            envelope = objectMapper.readValue(json, RouterEnvelope.class);
        } catch (Exception e) {
            log.warn("[Router] 信封反序列化失败，丢弃");
            return;
        }
        if (properties.getNodeId().equals(envelope.getSourceNode())) {
            return; // 自己发起的消息，跳过（一次投递）
        }
        switch (envelope.getType()) {
            case RouterEnvelope.TYPE_PUBLISH -> {
                byte[] payload = Base64.getDecoder().decode(envelope.getPayloadBase64());
                deliverer.deliver(envelope.getTopic(), payload, envelope.getQos(),
                        envelope.isRetain(), envelope.getSourceNode());
                stats.recordIncoming();
            }
            case RouterEnvelope.TYPE_KICK -> {
                Session session = sessionRegistry.get(envelope.getDeviceKey());
                if (session != null) {
                    log.info("[Router] 远端踢线 deviceKey={} 源节点={}",
                            envelope.getDeviceKey(), envelope.getSourceNode());
                    session.getChannel().close();
                }
            }
            default -> log.warn("[Router] 未知信封类型 type={}", envelope.getType());
        }
    }

    public synchronized void stop() {
        running.set(false);
        if (consumer != null) {
            consumer.wakeup();
        }
        if (thread != null) {
            try {
                thread.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void closeConsumer() {
        if (consumer != null) {
            try {
                consumer.close(Duration.ofSeconds(1));
            } catch (Exception e) {
                log.debug("[Router] 关闭消费者异常", e);
            }
        }
    }
}
