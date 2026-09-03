package com.energyx.broker.handler;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 节点接入连接计数（TCP 建连数，含未认证/半开连接）。
 *
 * <p>
 * 从 {@link MqttChannelInboundHandler} 抽出的共享计数：准入控制（硬/软阈值）与
 * {@code BrokerLoadHealthIndicator} 都要读取同一份"当前接入连接数"，保证 readiness 过载判定
 * 与准入拒绝基于同一个数，不会出现"探针说 DOWN 但准入还在放行"的错位。
 * </p>
 *
 * <p>
 * 计数契约：{@code channelActive} 只增、{@code channelInactive} 统一减；拒绝分支禁止手动回退， 否则与 close 触发的
 * channelInactive 双重扣减导致计数负偏移、准入逐步失效。
 * </p>
 */
@Component
public class ConnectionCounter {

	private final AtomicInteger connections = new AtomicInteger();

	/**
	 * 当前接入连接数（含半开连接）。
	 * @return 当前计数
	 */
	public int get() {
		return connections.get();
	}

	/**
	 * 接入计数 +1（仅 {@code channelActive} 调用）。
	 * @return 递增后的计数
	 */
	public int incrementAndGet() {
		return connections.incrementAndGet();
	}

	/**
	 * 接入计数 -1（仅 {@code channelInactive} 调用）。
	 * @return 递减后的计数
	 */
	public int decrementAndGet() {
		return connections.decrementAndGet();
	}

}
