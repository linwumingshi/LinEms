package com.energyx.broker.stats;

import com.energyx.broker.config.BrokerProperties;
import com.energyx.broker.handler.ConnectionCounter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 过载就绪探针判定验证（连接数占比 + 堆内存占比 + 配置自检）。
 */
class BrokerLoadHealthIndicatorTest {

	/**
	 * 构造指定阈值配置（堆占比阈值给 1.5 以隔离连接数维度：堆永远不会触发）。
	 * @param maxConnections 单节点连接上限
	 * @param softRatio 连接数软阈值比例
	 * @return 配置对象
	 */
	private BrokerProperties props(long maxConnections, double softRatio) {
		BrokerProperties p = new BrokerProperties();
		p.setMaxConnections((int) maxConnections);
		p.getOverload().setSoftConnectionRatio(softRatio);
		p.getOverload().setMaxHeapRatio(1.5);
		return p;
	}

	/**
	 * 连接数未超软阈值 → UP；恰好等于阈值 → UP（判定用 &gt;）；超过 → DOWN。
	 */
	@Test
	void 连接数软阈值边界判定() {
		BrokerProperties p = this.props(100, 0.9);
		ConnectionCounter counter = new ConnectionCounter();
		for (int i = 0; i < 90; i++) {
			counter.incrementAndGet();
		}
		assertThat(new BrokerLoadHealthIndicator(counter, p).health().getStatus()).isEqualTo(Status.UP);
		counter.incrementAndGet();
		assertThat(new BrokerLoadHealthIndicator(counter, p).health().getStatus()).isEqualTo(Status.DOWN);
	}

	/**
	 * 堆内存占比超阈值（此处堆占比阈值 0，任何真实占用都超）→ DOWN，即使连接数为 0。
	 */
	@Test
	void 堆内存超阈值则DOWN() {
		BrokerProperties p = new BrokerProperties();
		p.setMaxConnections(1_000_000);
		p.getOverload().setSoftConnectionRatio(1.5);
		p.getOverload().setMaxHeapRatio(0.0);
		assertThat(new BrokerLoadHealthIndicator(new ConnectionCounter(), p).health().getStatus())
			.isEqualTo(Status.DOWN);
	}

	/**
	 * 软阈值不小于硬阈值时准入分层失效（软拒永不生效），配置自检必须抛异常快速失败。
	 */
	@Test
	void 软阈值不小于硬阈值则配置校验失败() {
		BrokerProperties p = new BrokerProperties();
		p.getOverload().setSoftConnectionRatio(0.9);
		p.getOverload().setHardConnectionRatio(0.8);
		BrokerLoadHealthIndicator indicator = new BrokerLoadHealthIndicator(new ConnectionCounter(), p);
		assertThatThrownBy(indicator::validate).isInstanceOf(IllegalStateException.class);
	}

}
