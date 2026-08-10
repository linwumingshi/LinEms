package com.energyx.shadow.delta;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.energyx.common.constant.KafkaTopicConstant;
import com.energyx.common.message.ShadowDeltaMessage;
import com.energyx.shadow.mqtt.ShadowKafkaProducer;
import com.energyx.shadow.util.ShadowRedisKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/**
 * delta 发布：差异非空时投递 iot-shadow-delta（key=deviceId，消费方：指令中心物化为 set 指令 / ws-pusher 驾驶舱刷新），并写
 * Redis 短 TTL 的 delta 快照供设备上线拉取。
 */
@Slf4j
@Component
public class ShadowDeltaPublisher {

	private final ShadowKafkaProducer producer;

	private final StringRedisTemplate redis;

	private final ObjectMapper objectMapper;

	public ShadowDeltaPublisher(ShadowKafkaProducer producer, StringRedisTemplate redis, ObjectMapper objectMapper) {
		this.producer = producer;
		this.redis = redis;
		this.objectMapper = objectMapper;
	}

	public void publish(long deviceId, long tenantId, int version, Map<String, Object> delta) {
		if (delta == null || delta.isEmpty()) {
			return;
		}
		ShadowDeltaMessage m = new ShadowDeltaMessage();
		m.setDeviceId(deviceId);
		m.setTenantId(tenantId);
		m.setVersion(version);
		m.setDesired(delta);
		m.setTs(System.currentTimeMillis());
		try {
			String json = objectMapper.writeValueAsString(m);
			producer.send(KafkaTopicConstant.IOT_SHADOW_DELTA, String.valueOf(deviceId), json);
			redis.opsForValue().set(ShadowRedisKeys.delta(deviceId), json, Duration.ofSeconds(30));
		}
		catch (Exception e) {
			log.error("[Shadow] delta 发布失败 deviceId={} delta={}", deviceId, delta, e);
		}
	}

}
