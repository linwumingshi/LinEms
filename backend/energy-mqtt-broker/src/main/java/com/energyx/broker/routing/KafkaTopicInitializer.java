package com.energyx.broker.routing;

import com.energyx.broker.config.BrokerProperties;
import com.energyx.common.constant.KafkaTopicConstant;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Kafka topic 预创建（路由 + 生命周期）。
 *
 * <p>mqtt.router 需要 24 分区才能支撑「topic hash 分片 + 全量 fan-out」的容量模型；
 * 依赖 broker 的 auto-create 只会得到默认 1 分区，故启动时用 AdminClient 显式建 topic
 * （已存在则幂等）。后台线程异步执行 + 有限重试，Kafka 暂不可用不阻塞 Broker 启动。</p>
 */
@Slf4j
@Component
public class KafkaTopicInitializer {

    private final BrokerProperties properties;

    public KafkaTopicInitializer(BrokerProperties properties) {
        this.properties = properties;
    }

    /** 异步初始化，最多尝试 3 次 */
    public void initializeAsync() {
        if (!properties.isEnableRouter() || properties.getKafkaBootstrapServers() == null
                || properties.getKafkaBootstrapServers().isBlank()) {
            log.warn("[Kafka] bootstrap 未配置，跳过 topic 预创建");
            return;
        }
        Thread initThread = new Thread(() -> {
            for (int i = 1; i <= 3; i++) {
                try {
                    ensureTopics();
                    return;
                } catch (Exception e) {
                    log.warn("[Kafka] topic 预创建第 {} 次失败：{}", i, e.getMessage());
                    try {
                        Thread.sleep(2_000L * i);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
            log.error("[Kafka] topic 预创建 3 次失败，路由消息可能只有默认分区（功能可用，容量模型降级）");
        });
        initThread.setName("kafka-topic-init");
        initThread.setDaemon(true);
        initThread.start();
    }

    private void ensureTopics() throws Exception {
        Properties props = new Properties();
        props.put(org.apache.kafka.clients.admin.AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                properties.getKafkaBootstrapServers());
        props.put(org.apache.kafka.clients.admin.AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000);
        try (AdminClient admin = AdminClient.create(props)) {
            Set<String> existing = admin.listTopics().names().get(10, TimeUnit.SECONDS);
            List<NewTopic> toCreate = new ArrayList<>();
            // 已存在但分区不足 → 扩容（createTopics 幂等，仅新增分区）
            toCreate.add(new NewTopic(KafkaTopicConstant.MQTT_ROUTER,
                    properties.getRouterTopicPartitions(), (short) 1));
            if (!existing.contains(KafkaTopicConstant.IOT_DEVICE_LIFECYCLE)) {
                toCreate.add(new NewTopic(KafkaTopicConstant.IOT_DEVICE_LIFECYCLE,
                        properties.getLifecycleTopicPartitions(), (short) 1));
            }
            if (!toCreate.isEmpty()) {
                admin.createTopics(toCreate).all().get(10, TimeUnit.SECONDS);
                log.info("[Kafka] topic 预创建完成 {}", toCreate.stream()
                        .map(NewTopic::name).toList());
            }
        }
    }
}
