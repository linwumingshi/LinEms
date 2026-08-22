package com.energyx.broker.config;

import com.energyx.broker.handler.MqttChannelInboundHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.mqtt.MqttDecoder;
import io.netty.handler.codec.mqtt.MqttEncoder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;

/**
 * Netty 服务端装配：事件循环组 + Pipeline 骨架（明文 1883 + 可选 TLS 8883）。
 *
 * <p>
 * Pipeline：{@code [ssl] → MqttDecoder → MqttEncoder → IdleStateHandler → MqttChannelInboundHandler}。
 * IdleStateHandler 在 CONNECT 携带 keepalive 后按 1.5× 动态替换（见 handler）。 TLS
 * 开启时（{@code energyx.broker.tls.enabled=true}）SslHandler 置于 pipeline 头部，其余与明文完全一致； 两个
 * acceptor 共享同一 boss/worker 事件循环组与同一 {@code mqttHandler} 单例。
 * </p>
 *
 * <p>
 * 传输层（阶段 2）：经 {@link TransportFactory} 自适应——Linux 有 netty-transport-native-epoll 时启用
 * Epoll（SO_REUSEPORT/零拷贝/批量唤醒），macOS 反射 KQueue， 其余回退 NIO（Windows 开发机恒为 NIO）。TLS 用 JDK 默认
 * provider（RSA 自签），无 tcnative/BouncyCastle 依赖。
 * </p>
 */
@Configuration
public class NettyServerConfig {

	private static final Logger log = LoggerFactory.getLogger(NettyServerConfig.class);

	/** pipeline 中 SslHandler 注册名（TLS 开启时置于 decoder 之前） */
	public static final String SSL_HANDLER_NAME = "ssl";

	/** pipeline 中 IdleStateHandler 注册名（连接空闲检测） */
	public static final String IDLE_HANDLER_NAME = "mqttIdleState";

	/** pipeline 中业务处理器 MqttChannelInboundHandler 注册名 */
	public static final String MQTT_HANDLER_NAME = "mqttHandler";

	private final BrokerProperties brokerProperties;

	private final MqttChannelInboundHandler mqttHandler;

	public NettyServerConfig(BrokerProperties brokerProperties, MqttChannelInboundHandler mqttHandler) {
		this.brokerProperties = brokerProperties;
		this.mqttHandler = mqttHandler;
	}

	/**
	 * boss 事件循环组：仅 1 线程负责 accept 新连接（连接处理全在 worker，避免 accept 争用）。
	 * @return boss 事件循环组
	 */
	@Bean(name = "bossGroup", destroyMethod = "shutdownGracefully")
	public EventLoopGroup bossGroup() {
		return TransportFactory.newEventLoopGroup(1);
	}

	/**
	 * worker 事件循环组：处理已建立连接的读/写事件（原生传输自适应，见 {@link TransportFactory}）。
	 * @return worker 事件循环组
	 */
	@Bean(name = "workerGroup", destroyMethod = "shutdownGracefully")
	public EventLoopGroup workerGroup() {
		int threads = brokerProperties.getWorkerThreads();
		// 未显式配置时按 CPU 核数推导（≥4），避免单核机器线程数过少
		if (threads <= 0) {
			threads = Math.max(4, Runtime.getRuntime().availableProcessors() * 2);
		}
		log.info("[Broker] worker 线程数 = {}（传输={}）", threads, TransportFactory.detect());
		return TransportFactory.newEventLoopGroup(threads);
	}

	/**
	 * 明文 1883 acceptor（共享配置骨架 + 无 SSL pipeline）。 TLS 开启时与 {@link #mqttTlsServerBootstrap}
	 * 并存，构成双监听。
	 */
	@Bean(destroyMethod = "config")
	public ServerBootstrap mqttServerBootstrap(EventLoopGroup bossGroup, EventLoopGroup workerGroup) {
		return this.baseBootstrap(bossGroup, workerGroup).childHandler(this.mqttChannelInitializer(null));
	}

	/**
	 * TLS 8883 服务端 SSLContext（仅 {@code energyx.broker.tls.enabled=true} 时存在）。 证书缺失/损坏在
	 * Bean 创建期即 fail-fast（早于任何端口绑定）。
	 */
	@Bean
	@ConditionalOnProperty(prefix = "energyx.broker.tls", name = "enabled", havingValue = "true")
	public SslContext brokerSslContext() throws Exception {
		BrokerProperties.Tls tls = brokerProperties.getTls();
		File cert = new File(tls.getCertChainFile());
		File key = new File(tls.getPrivateKeyFile());
		if (!cert.isFile() || !key.isFile()) {
			throw new IllegalStateException("MQTT TLS 证书缺失: cert=" + cert.getAbsolutePath() + " key="
					+ key.getAbsolutePath() + "（先运行 deploy/scripts/gen-mqtt-certs.sh）");
		}
		SslContextBuilder builder = SslContextBuilder.forServer(cert, key);
		// P1-12 mTLS：要求设备证书 + 校验其信任链；CN=clientId 绑定在 handler 握手后校验
		if (tls.isClientAuth()) {
			File trust = new File(tls.getTrustCertFile());
			if (!trust.isFile()) {
				throw new IllegalStateException("mTLS 设备 CA 证书缺失: " + trust.getAbsolutePath()
						+ "（clientAuth=true 必须提供 trust-cert-file，见 gen-mqtt-certs.sh -c）");
			}
			builder.clientAuth(ClientAuth.REQUIRE).trustManager(trust);
			log.info("[Broker] MQTT mTLS 双向认证已启用，设备 CA={}", trust);
		}
		log.info("[Broker] MQTT TLS 证书加载 {} / {}", cert, key);
		return builder.build();
	}

	/**
	 * TLS 8883 acceptor（与明文共享 boss/worker 与 mqttHandler，仅 pipeline 头部多 SslHandler）。
	 */
	@Bean(destroyMethod = "")
	@ConditionalOnProperty(prefix = "energyx.broker.tls", name = "enabled", havingValue = "true")
	public ServerBootstrap mqttTlsServerBootstrap(EventLoopGroup bossGroup, EventLoopGroup workerGroup,
			SslContext brokerSslContext) {
		return this.baseBootstrap(bossGroup, workerGroup).childHandler(this.mqttChannelInitializer(brokerSslContext));
	}

	/**
	 * 共享 acceptor 骨架：事件循环组、channel 类型、socket 选项与背压水位线（明文与 TLS 完全一致）。
	 *
	 * <p>
	 * 该骨架是所有监听端口的唯一装配入口，option 为 server channel 级（accept 队列），childOption 为 已建立连接的 channel
	 * 级。调参影响全局连接行为，修改需与容量规划对齐（P2-5 池化分配器）。
	 * </p>
	 * @param bossGroup accept 事件循环组（连接接入）
	 * @param workerGroup 读写事件循环组（连接处理）
	 * @return 已装配 server channel 级选项的 {@link ServerBootstrap}（尚未绑定端口）
	 */
	private ServerBootstrap baseBootstrap(EventLoopGroup bossGroup, EventLoopGroup workerGroup) {
		return new ServerBootstrap().group(bossGroup, workerGroup)
			.channel(TransportFactory.serverChannelClass())
			// accept 队列长度：积压连接数上限（超限由 TCP 层丢弃/重传，防内核队列溢出）
			.option(ChannelOption.SO_BACKLOG, 8192)
			// 允许端口快速复用：重启/集群多实例监听同端口不因 TIME_WAIT 失败
			.option(ChannelOption.SO_REUSEADDR, true)
			// 连接级：禁用 Nagle 合并，小报文（MQTT 控制报文普遍 < 1KB）立即发出，降低交互时延
			.childOption(ChannelOption.TCP_NODELAY, true)
			// 连接级：开启 TCP keepalive，对端异常（断电/网线拔除）时由内核探测并断开僵尸连接
			.childOption(ChannelOption.SO_KEEPALIVE, true)
			// 连接级：接收缓冲区 256KB，与 1MB 报文上限匹配，减少大数据包下的系统调用次数
			.childOption(ChannelOption.SO_RCVBUF, 262_144)
			// 连接级：发送缓冲区 256KB，支撑下行大报文（OTA 分片）的批量写出
			.childOption(ChannelOption.SO_SNDBUF, 262_144)
			// P2-5：显式启用池化 ByteBuf 分配器，减少高并发下堆外内存分配与 GC 压力
			.childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
			// 背压水位线：写缓冲超 256KB 标记不可写（触发 handler 背压），低于 32KB 恢复可写
			.childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(32 * 1024, 256 * 1024));
	}

	/**
	 * 共享 pipeline 工厂：sslContext 非 null 时先加 SslHandler（必须位于 decoder 之前）， 其余解码/空闲/业务处理器与明文
	 * 1883 完全一致。
	 */
	private ChannelInitializer<SocketChannel> mqttChannelInitializer(SslContext sslContext) {
		return new ChannelInitializer<>() {
			@Override
			protected void initChannel(SocketChannel ch) {
				if (sslContext != null) {
					ch.pipeline().addLast(SSL_HANDLER_NAME, sslContext.newHandler(ch.alloc()));
				}
				// 解码器：单报文上限 1MB（超过按协议错误帧处理，防超大报文拖垮内存）
				ch.pipeline()
					.addLast("decoder", new MqttDecoder(1024 * 1024))
					.addLast("encoder", MqttEncoder.INSTANCE)
					// 预置空闲检测 90s：CONNECT 携带 keepalive 后由 handler 按 1.5× 动态重设
					.addLast(IDLE_HANDLER_NAME, new IdleStateHandler(90, 0, 0))
					.addLast(MQTT_HANDLER_NAME, mqttHandler);
			}
		};
	}

}
