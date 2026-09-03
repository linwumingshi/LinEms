package com.energyx.broker.handler;

import com.energyx.broker.auth.DeviceAuthService;
import com.energyx.broker.config.BrokerProperties;
import com.energyx.broker.lifecycle.LifecycleNotifier;
import com.energyx.broker.mqtt.KafkaEventProducer;
import com.energyx.broker.ratelimit.PublishRateLimiter;
import com.energyx.broker.routing.LocalSubscriberIndex;
import com.energyx.broker.routing.MessageDeliverer;
import com.energyx.broker.session.SessionRegistry;
import com.energyx.broker.session.SessionStore;
import com.energyx.broker.stats.BrokerMetrics;
import com.energyx.broker.stats.BrokerStats;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 连接准入计数（{@code ConnectionCounter}）的一致性验证。
 *
 * <p>
 * 关注点：{@code channelActive} 超限拒绝分支在手动回退计数后又调用 {@code ctx.close()}， 而 close 必然触发
 * {@code channelInactive} 再回退一次——若两者叠加会导致计数负偏移累积， 使 {@code maxConnections} 准入逐步失效。 本测试用
 * EmbeddedChannel 复现真实 Netty 生命周期回调顺序，不依赖对框架行为的假设。
 * </p>
 */
class ConnectionAdmissionCounterTest {

	/**
	 * 构造待测 handler（依赖全部 mock，仅 BrokerProperties 使用真实对象）。
	 * @param properties 真实配置，用于设置 maxConnections
	 * @return 待测 handler 实例
	 */
	private MqttChannelInboundHandler newHandler(BrokerProperties properties) {
		return new MqttChannelInboundHandler(mock(DeviceAuthService.class), mock(SessionRegistry.class),
				mock(SessionStore.class), mock(LocalSubscriberIndex.class), mock(MessageDeliverer.class),
				mock(LifecycleNotifier.class), mock(KafkaEventProducer.class), properties, mock(BrokerStats.class),
				mock(BrokerMetrics.class), mock(PublishRateLimiter.class), mock(ExecutorService.class),
				mock(ScheduledExecutorService.class), new ConnectionCounter());
	}

	/**
	 * 读取 handler 内部的连接计数字段。
	 * @param handler 待测 handler
	 * @return 连接计数引用
	 * @throws Exception 反射失败
	 */
	private ConnectionCounter counterOf(MqttChannelInboundHandler handler) throws Exception {
		Field field = MqttChannelInboundHandler.class.getDeclaredField("connectionCounter");
		field.setAccessible(true);
		return (ConnectionCounter) field.get(handler);
	}

	/**
	 * 场景：单节点上限 1 条连接。第 1 条放行、第 2 条超限被拒后，计数必须仍为 1（第 1 条仍在线）。
	 *
	 * <p>
	 * 若测试结果小于 1，说明拒绝分支与 channelInactive 各扣了一次，计数被重复扣减。
	 * </p>
	 * @throws Exception 反射失败
	 */
	@Test
	void 拒绝连接后计数不应被重复扣减() throws Exception {
		BrokerProperties properties = new BrokerProperties();
		properties.setMaxConnections(1);
		MqttChannelInboundHandler handler = this.newHandler(properties);
		ConnectionCounter counter = this.counterOf(handler);

		EmbeddedChannel first = new EmbeddedChannel(handler);
		assertThat(counter.get()).as("第 1 条连接放行后计数应为 1").isEqualTo(1);

		EmbeddedChannel second = new EmbeddedChannel(handler);
		assertThat(second.isOpen()).as("超过 maxConnections 的新连接必须被关闭").isFalse();

		assertThat(first.isOpen()).as("第 1 条连接未受影响").isTrue();
		assertThat(counter.get()).as("第 1 条仍在线，计数应保留为 1，实际为 %s（重复扣减则偏小）", counter.get()).isEqualTo(1);

		first.close();
		second.close();
	}

	/**
	 * 场景：连续拒绝多次后，计数负偏移累积，导致后续本应被拒的连接被放行（准入穿透）。
	 *
	 * <p>
	 * 上限 1、已放行 1 条的前提下，再建 1 条必须仍被拒绝。若被放行即证明准入已失效。
	 * </p>
	 * @throws Exception 反射失败
	 */
	@Test
	void 连续拒绝后准入不应穿透() throws Exception {
		BrokerProperties properties = new BrokerProperties();
		properties.setMaxConnections(1);
		MqttChannelInboundHandler handler = this.newHandler(properties);
		ConnectionCounter counter = this.counterOf(handler);

		EmbeddedChannel keep = new EmbeddedChannel(handler);
		EmbeddedChannel rejected = new EmbeddedChannel(handler);
		assertThat(rejected.isOpen()).as("第 2 条应被拒").isFalse();

		EmbeddedChannel shouldAlsoReject = new EmbeddedChannel(handler);
		assertThat(shouldAlsoReject.isOpen()).as("上限已满且已有 1 条在线，第 3 条必须被拒；若被放行说明计数已漂移（当前计数=%s）", counter.get())
			.isFalse();

		keep.close();
		rejected.close();
		shouldAlsoReject.close();
	}

}
