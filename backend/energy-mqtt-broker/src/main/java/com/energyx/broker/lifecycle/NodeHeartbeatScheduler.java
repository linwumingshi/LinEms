package com.energyx.broker.lifecycle;

import com.energyx.broker.config.BrokerProperties;
import com.energyx.broker.session.SessionStore;
import com.energyx.broker.util.BrokerKeys;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 节点心跳租约（下行路由避让：死节点 60s 悬空窗口 → 30s）。
 *
 * <p>
 * 启动时注册 {@code mqtt:node:{nodeId}}（TTL 30s），每 10s 刷新一次。节点宕机后心跳 key 最坏 30s 消失， access 侧
 * {@code BrokerNodeResolver} 解析下行目标时若发现 owner 心跳缺失即判定死节点 → 回落广播/离线队列， 不再把消息发给无人消费的
 * {@code mqtt.down.{deadNode}}。
 * </p>
 *
 * <p>
 * 优雅停机（{@code @PreDestroy}）删除本节点心跳 key + 批量释放本节点持有的连接锁，让其他节点立即接管， 不等锁 TTL 自然过期。
 * </p>
 */
@Slf4j
@Component
public class NodeHeartbeatScheduler {

	private final SessionStore sessionStore;

	private final BrokerProperties properties;

	private final ScheduledExecutorService scheduler;

	public NodeHeartbeatScheduler(SessionStore sessionStore, BrokerProperties properties,
			@Qualifier("brokerScheduler") ScheduledExecutorService scheduler) {
		this.sessionStore = sessionStore;
		this.properties = properties;
		this.scheduler = scheduler;
	}

	/** 启动注册心跳并定时刷新；单节点也照常（key 存在不影响任何逻辑） */
	@PostConstruct
	public void start() {
		heartbeat();
		scheduler.scheduleWithFixedDelay(this::heartbeat, properties.getNodeHeartbeatIntervalSeconds(),
				properties.getNodeHeartbeatIntervalSeconds(), TimeUnit.SECONDS);
		log.info("[NodeHeartbeat] 节点 {} 心跳启动 interval={}s ttl={}s", properties.getNodeId(),
				properties.getNodeHeartbeatIntervalSeconds(), properties.getNodeHeartbeatTtlSeconds());
	}

	private void heartbeat() {
		try {
			sessionStore.setString(BrokerKeys.nodeHeartbeat(properties.getNodeId()), properties.getNodeId(),
					properties.getNodeHeartbeatTtlSeconds());
		}
		catch (Exception e) {
			log.warn("[NodeHeartbeat] 心跳刷新失败 nodeId={}（Redis 不可用，稍后重试）", properties.getNodeId(), e);
		}
	}

	/** 优雅停机：删除心跳 + 释放本节点全部连接锁，触发其他节点立即接管 */
	@PreDestroy
	public void stop() {
		try {
			sessionStore.delete(BrokerKeys.nodeHeartbeat(properties.getNodeId()));
			int released = sessionStore.releaseAllConnLocksIfOwner(properties.getNodeId());
			log.info("[NodeHeartbeat] 节点 {} 停机：已删心跳，释放连接锁 {} 个", properties.getNodeId(), released);
		}
		catch (Exception e) {
			log.warn("[NodeHeartbeat] 停机清理失败 nodeId={}", properties.getNodeId(), e);
		}
	}

}
