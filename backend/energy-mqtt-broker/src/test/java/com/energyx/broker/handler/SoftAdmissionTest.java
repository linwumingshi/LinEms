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
import io.netty.handler.codec.mqtt.MqttConnAckMessage;
import io.netty.handler.codec.mqtt.MqttConnectMessage;
import io.netty.handler.codec.mqtt.MqttConnectReturnCode;
import io.netty.handler.codec.mqtt.MqttMessageBuilders;
import io.netty.handler.codec.mqtt.MqttVersion;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 双阈值准入的软拒分支验证（P2-9）。
 *
 * <p>
 * 构造真实 handler + 真实 {@link ConnectionCounter}，用反射把计数压入"软阈值之上、硬阈值之下" 的区间，再向
 * EmbeddedChannel 写入 CONNECT 报文，断言收到 CONNACK 0x03 且连接被关闭。
 * </p>
 */
class SoftAdmissionTest {

	/**
	 * 构造真实计数已到软区间（95 = max100 × 0.95，介于软 0.9 与硬 1.05 之间）的 handler。
	 * @return 待测 handler（内部计数已置位）
	 */
	private MqttChannelInboundHandler handlerAtSoftZone() {
		BrokerProperties properties = new BrokerProperties();
		properties.setMaxConnections(100);
		properties.getOverload().setSoftConnectionRatio(0.9);
		properties.getOverload().setHardConnectionRatio(1.05);
		ConnectionCounter counter = new ConnectionCounter();
		for (int i = 0; i < 95; i++) {
			counter.incrementAndGet();
		}
		return new MqttChannelInboundHandler(mock(DeviceAuthService.class), mock(SessionRegistry.class),
				mock(SessionStore.class), mock(LocalSubscriberIndex.class), mock(MessageDeliverer.class),
				mock(LifecycleNotifier.class), mock(KafkaEventProducer.class), properties, mock(BrokerStats.class),
				mock(BrokerMetrics.class), mock(PublishRateLimiter.class), mock(ExecutorService.class),
				mock(ScheduledExecutorService.class), counter);
	}

	/**
	 * 构造 MQTT 3.1.1 CONNECT 报文。
	 * @param clientId 设备标识（本平台 clientId 即设备身份）
	 * @return CONNECT 报文
	 */
	private MqttConnectMessage connectMessage(String clientId) {
		// Netty 4.1.109 起 protocolVersion 仅接受 MqttVersion 枚举（4 = MQTT 3.1.1，与 brief 常量一致）
		return MqttMessageBuilders.connect()
			.clientId(clientId)
			.protocolVersion(MqttVersion.MQTT_3_1_1)
			.cleanSession(true)
			.keepAlive(60)
			.username("device")
			.password("secret".getBytes())
			.build();
	}

	/**
	 * 软区间内发 CONNECT：channel 必须收到 CONNACK 0x03 后关闭；计数随后归位（channelInactive 减回）。
	 */
	@Test
	void 软阈值区间内CONNECT被回0x03拒绝() {
		MqttChannelInboundHandler handler = this.handlerAtSoftZone();
		EmbeddedChannel channel = new EmbeddedChannel(handler);
		// 写入 CONNECT 前本条连接已计入硬阈值的 increment（真实建连行为）
		channel.writeInbound(this.connectMessage("dev-soft-reject"));
		MqttConnAckMessage ack = channel.readOutbound();
		assertThat(ack).as("必须回 CONNACK").isNotNull();
		assertThat(ack.variableHeader().connectReturnCode()).as("软拒必须为 SERVER_UNAVAILABLE(0x03)")
			.isEqualTo(MqttConnectReturnCode.CONNECTION_REFUSED_SERVER_UNAVAILABLE);
		assertThat(channel.isOpen()).as("软拒后连接必须关闭").isFalse();
		channel.close();
		channel.finishAndReleaseAll();
	}

}
