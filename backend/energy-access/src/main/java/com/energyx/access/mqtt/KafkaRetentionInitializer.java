package com.energyx.access.mqtt;

import com.energyx.access.config.AccessProperties;
import com.energyx.common.constant.KafkaTopicConstant;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.DescribeConfigsResult;
import org.apache.kafka.common.config.ConfigResource;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * 业务 topic 保留策略初始化（D-04：iot-raw 等大流量留痕 topic 滚动清理，防磁盘耗尽）。
 *
 * <p>
 * iot-raw 为原始报文留痕（access 写入，无消费方，纯追踪/补数），默认保留会持续膨胀； 此处按「数据保留矩阵」（docs/design/数据保留策略）对高频
 * topic 显式设置 retention.ms：
 * <ul>
 * <li>iot-raw：24h（原始报文量大，仅作近期追踪/补数）；</li>
 * <li>iot-thing-property / iot-thing-event / iot-device-lifecycle：7 天（下游落库后 topic
 * 仅作重放）；</li>
 * </ul>
 * 幂等：重复执行仅收敛到目标值；后台异步 + 失败仅告警，不阻塞服务启动。
 * </p>
 */
@Slf4j
@Component
public class KafkaRetentionInitializer {

	/** topic → 保留毫秒 */
	private static final Map<String, Long> RETENTION_MS = new HashMap<>();

	static {
		RETENTION_MS.put(KafkaTopicConstant.IOT_RAW, 24L * 3600 * 1000);
		RETENTION_MS.put(KafkaTopicConstant.IOT_THING_PROPERTY, 7L * 24 * 3600 * 1000);
		RETENTION_MS.put(KafkaTopicConstant.IOT_THING_EVENT, 7L * 24 * 3600 * 1000);
		RETENTION_MS.put(KafkaTopicConstant.IOT_DEVICE_LIFECYCLE, 7L * 24 * 3600 * 1000);
	}

	private final AccessProperties props;

	public KafkaRetentionInitializer(AccessProperties props) {
		this.props = props;
	}

	@PostConstruct
	public void initializeAsync() {
		Thread t = new Thread(() -> {
			try {
				applyRetention();
			}
			catch (Exception e) {
				log.warn("[Kafka] topic 保留策略设置失败（不影响启动，topic 保留默认值）", e);
			}
		}, "kafka-retention-init");
		t.setDaemon(true);
		t.start();
	}

	private void applyRetention() throws Exception {
		Properties adminProps = new Properties();
		adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, props.getKafkaBootstrapServers());
		adminProps.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000);
		try (AdminClient admin = AdminClient.create(adminProps)) {
			for (Map.Entry<String, Long> e : RETENTION_MS.entrySet()) {
				ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, e.getKey());
				DescribeConfigsResult result = admin.describeConfigs(List.of(resource));
				Config current = result.all().get(10, TimeUnit.SECONDS).get(resource);
				if (retentionMs(current) == e.getValue()) {
					continue; // 已达标
				}
				// incrementalAlterConfigs：增量修改保留配置，不影响 topic 其他配置
				Map<ConfigResource, Collection<AlterConfigOp>> ops = new HashMap<>();
				ops.put(resource,
						List.of(new AlterConfigOp(new ConfigEntry("retention.ms", String.valueOf(e.getValue())),
								AlterConfigOp.OpType.SET)));
				admin.incrementalAlterConfigs(ops).all().get(10, TimeUnit.SECONDS);
				log.info("[Kafka] topic {} 保留策略调整为 {}ms", e.getKey(), e.getValue());
			}
		}
	}

	private long retentionMs(Config config) {
		for (ConfigEntry entry : config.entries()) {
			if ("retention.ms".equals(entry.name()) && entry.value() != null) {
				try {
					return Long.parseLong(entry.value());
				}
				catch (NumberFormatException ignore) {
					return -1;
				}
			}
		}
		return -1;
	}

}
