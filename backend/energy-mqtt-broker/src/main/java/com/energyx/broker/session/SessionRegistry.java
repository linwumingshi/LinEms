package com.energyx.broker.session;

import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 节点内 Session 注册表（纯内存）。
 *
 * <p>
 * 职责：deviceKey → Session 索引、连接数计量（准入控制）、优雅停机时全量下线。
 * </p>
 */
@Component
public class SessionRegistry {

	private static final Logger log = LoggerFactory.getLogger(SessionRegistry.class);

	private final Map<String, Session> sessions = new ConcurrentHashMap<>();

	private final AtomicInteger connectionCount = new AtomicInteger(0);

	/** 按 deviceKey 取当前节点内 Session；未注册返回 null */
	public Session get(String deviceKey) {
		return sessions.get(deviceKey);
	}

	/** 注册 session 并累加连接计数（准入控制口径，O(1)） */
	public void register(Session session) {
		sessions.put(session.getDeviceKey(), session);
		connectionCount.incrementAndGet();
	}

	/** 注销并返回被移除的 session（可能为 null） */
	public Session unregister(String deviceKey) {
		Session removed = sessions.remove(deviceKey);
		if (removed != null) {
			connectionCount.decrementAndGet();
		}
		return removed;
	}

	/**
	 * 按 deviceKey + channel 一致性校验注销（channelInactive 场景，O(1)）。 仅当注册表中的 session 持有的是同一个
	 * channel 才移除——防止旧连接的 channelInactive 晚到时误删已被同 clientId 新连接注册的新 session。
	 * @return 被移除的 session；channel 不匹配或未注册返回 null
	 */
	public Session unregisterIfChannelMatches(String deviceKey, Channel channel) {
		Session current = sessions.get(deviceKey);
		// 原子校验：仅当注册表 session 仍持有同一 channel 才移除，防止旧连接晚到的 channelInactive 误删新会话
		if (current != null && current.getChannel() == channel && sessions.remove(deviceKey, current)) {
			connectionCount.decrementAndGet();
			return current;
		}
		return null;
	}

	/** 当前节点连接数（准入口径，用于上限判定） */
	public int connectionCount() {
		return connectionCount.get();
	}

	/** 关闭并注销本节点全部连接（优雅停机时调用，触发 offline 事件与持久化） */
	public void closeAll(ChannelCloser closer) {
		// 快照当前全部会话后逐个关闭；closer 由调用方实现真正 channel 关闭 + offline 事件/持久化
		List<Session> all = new ArrayList<>(sessions.values());
		log.info("[Broker] 优雅停机：关闭 {} 个在线会话", all.size());
		for (Session session : all) {
			try {
				closer.close(session);
			}
			catch (Exception e) {
				// 单会话关闭异常不影响其余会话的优雅下线
				log.warn("[Broker] 关闭会话异常 deviceKey={}", session.getDeviceKey(), e);
			}
		}
	}

	/** 会话关闭回调：优雅停机时由调用方实现真正的 channel 关闭 + 离线事件/持久化 */
	@FunctionalInterface
	public interface ChannelCloser {

		void close(Session session) throws Exception;

	}

}
