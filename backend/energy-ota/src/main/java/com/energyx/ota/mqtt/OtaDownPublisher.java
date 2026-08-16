package com.energyx.ota.mqtt;

import com.energyx.common.constant.KafkaTopicConstant;
import com.energyx.common.message.OtaDownMessage;
import com.energyx.common.mqtt.MqttTopicUtil;
import com.energyx.common.mqtt.RouterEnvelope;
import com.energyx.common.mqtt.RouterEnvelopeCodec;
import com.energyx.ota.config.OtaProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * OTA 升级通知下发（下行信封桥接，与命令下行同构）。
 *
 * <p>
 * 按设备连接锁 {@code mqtt:conn:{deviceKey}} 解析所在 Broker 节点：命中 → 定向投递 mqtt.down.{nodeId}（Broker
 * 消费后 PUBLISH 到设备订阅的 {pk}/{dn}/ota/down）； 未命中（离线）→ 回落 mqtt.broadcast
 * 由幽灵订阅/上线接管兜底，同时任务明细保持 PENDING 等待 lifecycle 上线补推（双保险）。
 * </p>
 */
@Slf4j
@Component
public class OtaDownPublisher {

	private final OtaKafkaProducer producer;

	private final OtaProperties props;

	private final StringRedisTemplate redis;

	private final ObjectMapper objectMapper;

	public OtaDownPublisher(OtaKafkaProducer producer, OtaProperties props, StringRedisTemplate redis,
			ObjectMapper objectMapper) {
		this.producer = producer;
		this.props = props;
		this.redis = redis;
		this.objectMapper = objectMapper;
	}

	/**
	 * 下发升级通知信封。
	 * @return true=定向投递成功（设备在线）；false=离线（已回落广播，待上线补推）
	 */
	public boolean publish(OtaDownMessage msg) {
		String topic = otaDownTopic(msg.getProductKey(), msg.getDeviceName());
		String deviceKey = MqttTopicUtil.buildDeviceKey(msg.getProductKey(), msg.getDeviceName());
		try {
			msg.setTs(System.currentTimeMillis());
			byte[] payload = objectMapper.writeValueAsBytes(msg);
			RouterEnvelope env = RouterEnvelope.publish(props.getNodeId(), topic, payload, 1, false);
			byte[] envelope = RouterEnvelopeCodec.encode(env);
			String owner = resolveOwner(deviceKey);
			if (owner != null && !owner.isBlank()) {
				producer.sendBytes(KafkaTopicConstant.MQTT_DOWN_PREFIX + owner, deviceKey, envelope);
				return true;
			}
			log.debug("[OTA] 设备离线回落广播 deviceKey={} taskId={}", deviceKey, msg.getTaskId());
			producer.sendBytes(KafkaTopicConstant.MQTT_BROADCAST, deviceKey, envelope);
			return false;
		}
		catch (Exception e) {
			log.error("[OTA] 升级通知信封下发失败 deviceKey={} taskId={}", deviceKey, msg.getTaskId(), e);
			return false;
		}
	}

	/** 设备 OTA 下行 topic：{pk}/{dn}/ota/down */
	private String otaDownTopic(String productKey, String deviceName) {
		return productKey + "/" + deviceName + "/ota/down";
	}

	/** 解析设备所在 Broker 节点（mqtt:conn:{deviceKey}，Redis 异常降级返回 null 走广播） */
	private String resolveOwner(String deviceKey) {
		try {
			String owner = redis.opsForValue().get("mqtt:conn:" + deviceKey);
			return owner == null || owner.isBlank() ? null : owner;
		}
		catch (Exception e) {
			log.warn("[OTA] 连接锁查询失败 deviceKey={}，回落广播", deviceKey, e);
			return null;
		}
	}

}
