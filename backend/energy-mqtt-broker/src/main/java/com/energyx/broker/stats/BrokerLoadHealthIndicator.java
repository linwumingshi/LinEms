package com.energyx.broker.stats;

import com.energyx.broker.config.BrokerProperties;
import com.energyx.broker.handler.ConnectionCounter;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Broker 过载就绪探针（P2-9）。
 *
 * <p>
 * 通过 {@code /actuator/health/readiness} 对外暴露"能否接新连接"的二态判定：接入连接数占比 或堆内存占比任一超过阈值即返回 DOWN，由
 * LB / 编排层摘除节点（只挡新连接，存量连接不受影响， 属 graceful drain）。
 * </p>
 *
 * <p>
 * 设计约束（spec §7 坑 1）：本指示器**只挂 readiness group，绝不参与 liveness** —— 过载只应 "不接新客"，若误挂 liveness
 * 会导致过载节点被重启、存量连接全部断开，自造灾难级重连风暴。 判定用 {@code >}（等于阈值视为可接），与 Task 5 的准入软拒判定保持一致。
 * </p>
 */
@Component
public class BrokerLoadHealthIndicator implements HealthIndicator {

	private final ConnectionCounter connectionCounter;

	private final BrokerProperties properties;

	public BrokerLoadHealthIndicator(ConnectionCounter connectionCounter, BrokerProperties properties) {
		this.connectionCounter = connectionCounter;
		this.properties = properties;
	}

	/**
	 * 启动期配置自检：软阈值必须严格小于硬阈值，否则准入分层失效（软拒永不生效或风暴期仍在解码）。 违规直接抛异常 fail-fast，避免运行期行为不可预测。
	 */
	@PostConstruct
	void validate() {
		BrokerProperties.Overload overload = properties.getOverload();
		if (overload.getSoftConnectionRatio() >= overload.getHardConnectionRatio()) {
			throw new IllegalStateException(
					"[Broker] overload.soft-connection-ratio(" + overload.getSoftConnectionRatio()
							+ ") 必须小于 hard-connection-ratio(" + overload.getHardConnectionRatio() + ")");
		}
		// 取整后有效阈值自检（原始比例合法但取整后分层可能失效的配置态）：
		// maxConnections 过小（如 1×0.9 取整为 0）→ 首条合法连接即被软拒；
		// 比例过近（如 0.9/0.9005 × 1000 取整后相等）→ 软拒层永不生效。
		// 两类均属准入分层失效，启动期拦下而非运行期暴露。
		long maxConnections = properties.getMaxConnections();
		long softLimit = (long) (maxConnections * overload.getSoftConnectionRatio());
		long hardLimit = (long) (maxConnections * overload.getHardConnectionRatio());
		if (softLimit < 1) {
			throw new IllegalStateException("[Broker] max-connections(" + maxConnections + ") × soft-connection-ratio("
					+ overload.getSoftConnectionRatio() + ") 取整后软阈值 = 0，首条合法连接即被软拒，"
					+ "请调大 max-connections 或提高 soft-connection-ratio");
		}
		if (softLimit >= hardLimit) {
			throw new IllegalStateException(
					"[Broker] 取整后软阈值(" + softLimit + ") 不小于硬阈值(" + hardLimit + ")，软拒层失效，请调大 max-connections 或拉开两比例差距");
		}
	}

	/**
	 * 过载判定：连接数占比或堆内存占比任一超阈值即 DOWN，并回填各维度实测值明细。
	 * @return 含连接数/堆内存实测值的 Health
	 */
	@Override
	public Health health() {
		BrokerProperties.Overload overload = properties.getOverload();
		long maxConnections = properties.getMaxConnections();
		long connections = this.connectionCounter.get();
		Runtime runtime = Runtime.getRuntime();
		long usedHeap = runtime.totalMemory() - runtime.freeMemory();
		long maxHeap = runtime.maxMemory();
		boolean connOverload = isConnectionOverloaded(connections, maxConnections, overload.getSoftConnectionRatio());
		boolean heapOverload = isHeapOverloaded(usedHeap, maxHeap, overload.getMaxHeapRatio());
		if (connOverload || heapOverload) {
			return Health.down()
				.withDetail("connections", connections)
				.withDetail("maxConnections", maxConnections)
				.withDetail("connectionRatio", ratio(connections, maxConnections))
				.withDetail("heapUsedBytes", usedHeap)
				.withDetail("heapMaxBytes", maxHeap)
				.withDetail("heapRatio", ratio(usedHeap, maxHeap))
				.build();
		}
		return Health.up()
			.withDetail("connections", connections)
			.withDetail("maxConnections", maxConnections)
			.withDetail("connectionRatio", ratio(connections, maxConnections))
			.withDetail("heapUsedBytes", usedHeap)
			.withDetail("heapMaxBytes", maxHeap)
			.withDetail("heapRatio", ratio(usedHeap, maxHeap))
			.build();
	}

	/**
	 * 连接数占比过载判定（与准入软拒同一判定：超过才拒/才 DOWN）。
	 * @param connections 当前接入连接数
	 * @param maxConnections 单节点连接上限
	 * @param softRatio 软阈值比例
	 * @return 是否超过
	 */
	static boolean isConnectionOverloaded(long connections, long maxConnections, double softRatio) {
		return connections > (long) (maxConnections * softRatio);
	}

	/**
	 * 堆内存占比过载判定；maxHeap 为 0（异常采集）时按不过载处理，避免除零误判。
	 * @param usedHeap 已用堆字节数
	 * @param maxHeap 堆上限字节数
	 * @param heapRatio 阈值比例
	 * @return 是否超过
	 */
	static boolean isHeapOverloaded(long usedHeap, long maxHeap, double heapRatio) {
		return maxHeap > 0 && ratio(usedHeap, maxHeap) > heapRatio;
	}

	/**
	 * 占比计算；total 非正（异常）时返回 0，避免除零与 NaN 传染判定。
	 * @param part 分子
	 * @param total 分母
	 * @return 占比（0~1）
	 */
	private static double ratio(long part, long total) {
		return total <= 0 ? 0D : (double) part / total;
	}

}
