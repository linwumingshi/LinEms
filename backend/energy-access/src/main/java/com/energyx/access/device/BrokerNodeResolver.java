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

	/** 解析设备当前所在 Broker 节点；离线/异常/节点死亡返回 null（调用方回落广播/离线队列） */
	public String resolveNode(String deviceKey) {
		try {
			String owner = redis.opsForValue().get(AccessKeys.brokerConnLock(deviceKey));
			if (owner == null || owner.isBlank()) {
				return null;
			}
			// 节点心跳避让：owner 心跳 key 已消失（节点宕机后 30s 内未续期）→ 判定死节点，
			// 返回 null 让下行回落广播/离线队列，不再把消息发给无人消费的 mqtt.down.{deadNode}
			// 心跳判定自身异常时保守视为存活（正常定向），不因新功能阻断既有下行
			if (!isOwnerAlive(owner)) {
				log.info("[Access] owner 节点心跳消失视为宕机，下行回落 deviceKey={} owner={}", deviceKey, owner);
				return null;
			}
			return owner;
		}
		catch (Exception e) {
			log.warn("[Access] Broker 节点解析异常 deviceKey={}，回落广播", deviceKey, e);
			return null;
		}
	}

	/** 判定 owner 节点存活：心跳 key 存在为存活；判定异常保守视为存活 */
	private boolean isOwnerAlive(String owner) {
		try {
			return Boolean.TRUE.equals(redis.hasKey(AccessKeys.nodeHeartbeat(owner)));
		}
		catch (Exception e) {
			log.warn("[Access] 节点心跳判定异常 owner={}，保守视为存活", owner, e);
			return true;
		}
	}

}
