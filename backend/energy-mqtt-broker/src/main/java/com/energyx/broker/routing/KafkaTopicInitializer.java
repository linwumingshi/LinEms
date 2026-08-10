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
 * Kafka topic 预创建（阶段 2 路由通道：上行/下行定向/广播）。
 *
 * <p>
 * 分区模型：
 * <ul>
 * <li>mqtt.uplink：24 分区，key=deviceKey（单设备有序、按设备分区）；唯一消费组 energy-access-uplink；</li>
 * <li>mqtt.down.{nodeId}：24 分区，仅目标节点消费（节点内分区并行 + 单分区保序）；</li>
 * <li>mqtt.broadcast：8 分区，每节点唯一消费组全量 fan-out（KICK 低频）；</li>
 * <li>mqtt.router：兼容期通道（router-legacy-broadcast=true 才建），全量升级后删除。</li>
 * </ul>
 * 依赖 broker 的 auto-create 只会得到默认 1 分区，故启动时用 AdminClient 显式建 topic （已存在则幂等）。后台线程异步执行 +
 * 有限重试，Kafka 暂不可用不阻塞 Broker 启动。
 * </p>
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
				}
				catch (Exception e) {
					log.warn("[Kafka] topic 预创建第 {} 次失败：{}", i, e.getMessage());
					try {
						Thread.sleep(2_000L * i);
					}
					catch (InterruptedException ie) {
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
			java.util.Map<String, org.apache.kafka.clients.admin.NewPartitions> toExpand = new java.util.HashMap<>();
			// 不存在的 topic 走 createTopics；已存在但分区不足走 createPartitions（显式扩容）
			addIfNeed(admin, existing, toCreate, toExpand, KafkaTopicConstant.MQTT_UPLINK,
					properties.getRouterTopicPartitions());
			addIfNeed(admin, existing, toCreate, toExpand, KafkaTopicConstant.MQTT_DOWN_PREFIX + properties.getNodeId(),
					properties.getDownTopicPartitions());
			addIfNeed(admin, existing, toCreate, toExpand, KafkaTopicConstant.MQTT_BROADCAST,
					properties.getBroadcastTopicPartitions());
			if (properties.isRouterLegacyBroadcast()) {
				addIfNeed(admin, existing, toCreate, toExpand, KafkaTopicConstant.MQTT_ROUTER,
						properties.getRouterTopicPartitions());
			}
			if (!existing.contains(KafkaTopicConstant.IOT_DEVICE_LIFECYCLE)) {
				toCreate.add(new NewTopic(KafkaTopicConstant.IOT_DEVICE_LIFECYCLE,
						properties.getLifecycleTopicPartitions(), (short) 1));
			}
			if (!toCreate.isEmpty()) {
				admin.createTopics(toCreate).all().get(10, TimeUnit.SECONDS);
				log.info("[Kafka] topic 预创建完成 {}", toCreate.stream().map(NewTopic::name).toList());
			}
			if (!toExpand.isEmpty()) {
				admin.createPartitions(toExpand).all().get(10, TimeUnit.SECONDS);
				log.info("[Kafka] topic 分区扩容完成 {}", toExpand.keySet());
			}
		}
	}

	/** 不存在的纳入创建；存在但分区不足纳入扩容；达标跳过 */
	private void addIfNeed(AdminClient admin, Set<String> existing, List<NewTopic> toCreate,
			java.util.Map<String, org.apache.kafka.clients.admin.NewPartitions> toExpand, String topic, int partitions)
			throws Exception {
		if (!existing.contains(topic)) {
			toCreate.add(new NewTopic(topic, partitions, (short) 1));
			return;
		}
		int current = admin.describeTopics(java.util.List.of(topic))
			.allTopicNames()
			.get(10, TimeUnit.SECONDS)
			.get(topic)
			.partitions()
			.size();
		if (current < partitions) {
			toExpand.put(topic, org.apache.kafka.clients.admin.NewPartitions.increaseTo(partitions));
			log.warn("[Kafka] topic {} 分区不足（{} < {}），扩容中", topic, current, partitions);
		}
	}

}
