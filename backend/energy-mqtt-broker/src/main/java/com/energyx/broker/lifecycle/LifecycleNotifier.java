package com.energyx.broker.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.energyx.broker.auth.DeviceCredential;
import com.energyx.broker.config.BrokerProperties;
import com.energyx.broker.mqtt.KafkaEventProducer;
import com.energyx.broker.session.SessionStore;
import com.energyx.broker.util.BrokerKeys;
import com.energyx.common.constant.KafkaTopicConstant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 设备生命周期通知（上线/下线）。
 *
 * <p>
 * 双通道（Phase 1 §4.4 / Redis-key规范 §3.1）：
 * <ul>
 * <li>Redis iot:online:{deviceId} = brokerNode，TTL 30s（心跳续期，离线判定）；</li>
 * <li>Kafka iot-device-lifecycle 事件（key=deviceId），消费方：影子服务刷新在线态、指令服务补发离线队列。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
public class LifecycleNotifier {

	private final SessionStore sessionStore;

	private final KafkaEventProducer producer;

	private final BrokerProperties properties;

	private final ObjectMapper objectMapper;

	public LifecycleNotifier(SessionStore sessionStore, KafkaEventProducer producer, BrokerProperties properties,
			ObjectMapper objectMapper) {
		this.sessionStore = sessionStore;
		this.producer = producer;
		this.properties = properties;
		this.objectMapper = objectMapper;
	}

	/** 上线：写 Redis 在线标记 + 发 lifecycle 事件 */
	public void notifyOnline(DeviceCredential cred, String remoteIp) {
		String onlineKey = BrokerKeys.online(cred.getDeviceId());
		sessionStore.setString(onlineKey, properties.getNodeId(), properties.getOnlineTtlSeconds());
		publish(cred, "ONLINE", "NORMAL", remoteIp);
	}

	/** 下线（非优雅/心跳超时/被踢），reason ∈ NORMAL/HEARTBEAT_TIMEOUT/DUPLICATE_CLIENT/KICK */
	public void notifyOffline(DeviceCredential cred, String reason, String remoteIp) {
		String onlineKey = BrokerKeys.online(cred.getDeviceId());
		sessionStore.delete(onlineKey);
		publish(cred, "OFFLINE", reason, remoteIp);
	}

	/** 心跳续期：刷新在线 TTL + 连接锁 TTL（只写 Redis，不重复发事件） */
	public void renewOnline(DeviceCredential cred) {
		sessionStore.setString(BrokerKeys.online(cred.getDeviceId()), properties.getNodeId(),
				properties.getOnlineTtlSeconds());
		// 连接锁短租约随在线状态续期，长连接不会因锁过期被误判空闲
		sessionStore.refreshConnLockIfOwner(cred.getDeviceKey(), properties.getNodeId());
	}

	private void publish(DeviceCredential cred, String eventType, String reason, String ip) {
		if (!properties.isEnableLifecycleEvent() || !producer.isEnabled()) {
			return;
		}
		Map<String, Object> event = new LinkedHashMap<>();
		event.put("eventType", eventType);
		event.put("deviceId", cred.getDeviceId());
		event.put("tenantId", cred.getTenantId());
		event.put("productKey", cred.getProductKey());
		event.put("deviceName", cred.getDeviceName());
		event.put("brokerNode", properties.getNodeId());
		event.put("ip", ip);
		event.put("reason", reason);
		event.put("ts", System.currentTimeMillis());
		try {
			producer.send(KafkaTopicConstant.IOT_DEVICE_LIFECYCLE, String.valueOf(cred.getDeviceId()),
					objectMapper.writeValueAsString(event));
		}
		catch (Exception e) {
			log.warn("[Lifecycle] 事件序列化失败 deviceId={}", cred.getDeviceId(), e);
		}
	}

}
