package com.energyx.access.device;

import com.energyx.access.util.AccessKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Broker 节点解析器（阶段 2 下行定向路由）。
 *
 * <p>
 * 设备在线时，其连接锁 {@code mqtt:conn:{deviceKey}} 持有者是设备所在 Broker 节点 （TTL 60s，心跳续期，与 Broker
 * 侧「连接锁 + 路由定位」双职责一致）。下行指令先查 owner：命中 → 定向投递 mqtt.down.{nodeId}；未命中（离线/竞态窗口）→ 回落
 * mqtt.broadcast 由 Broker 幽灵订阅/离线队列兜底。
 * </p>
 *
 * <p>
 * 异常降级：Redis 不可用时返回 null（走广播），不抛异常阻断下发。
 * </p>
 */
@Slf4j
@Component
public class BrokerNodeResolver {

	private final StringRedisTemplate redis;

	public BrokerNodeResolver(StringRedisTemplate redis) {
		this.redis = redis;
	}

	/** 解析设备当前所在 Broker 节点；离线/异常返回 null（调用方回落广播） */
	public String resolveNode(String deviceKey) {
		try {
			return redis.opsForValue().get(AccessKeys.brokerConnLock(deviceKey));
		}
		catch (Exception e) {
			log.warn("[Access] Broker 节点解析异常 deviceKey={}，回落广播", deviceKey, e);
			return null;
		}
	}

}
