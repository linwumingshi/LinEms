package com.energyx.broker.config;

import com.energyx.broker.handler.MqttChannelInboundHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.mqtt.MqttDecoder;
import io.netty.handler.codec.mqtt.MqttEncoder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
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
 * <p>Pipeline：{@code [ssl] → MqttDecoder → MqttEncoder → IdleStateHandler → MqttChannelInboundHandler}。
 * IdleStateHandler 在 CONNECT 携带 keepalive 后按 1.5× 动态替换（见 handler）。
 * TLS 开启时（{@code energyx.broker.tls.enabled=true}）SslHandler 置于 pipeline 头部，其余与明文完全一致；
 * 两个 acceptor 共享同一 boss/worker 事件循环组与同一 {@code mqttHandler} 单例。</p>
 *
 * <p>传输层决策（Phase 1 §4.2）：默认 NIO（跨平台）；生产 Linux 环境切换
 * {@code io.netty.transport.epoll}（EpollEventLoopGroup）并通过启动参数
 * {@code -Dio.netty.transport.auto-detect=true} 自动选择。Window 本机仅做功能验证，
 * NIO 吞吐已足够（单通道 10 万 TPS 量级）。TLS 用 JDK 默认 provider（RSA 自签），无 tcnative/BouncyCastle 依赖。</p>
 */
@Configuration
public class NettyServerConfig {

    private static final Logger log = LoggerFactory.getLogger(NettyServerConfig.class);

    public static final String SSL_HANDLER_NAME = "ssl";
    public static final String IDLE_HANDLER_NAME = "mqttIdleState";
    public static final String MQTT_HANDLER_NAME = "mqttHandler";

    private final BrokerProperties brokerProperties;
    private final MqttChannelInboundHandler mqttHandler;

    public NettyServerConfig(BrokerProperties brokerProperties,
                             MqttChannelInboundHandler mqttHandler) {
        this.brokerProperties = brokerProperties;
        this.mqttHandler = mqttHandler;
    }

    /** boss：accept 连接；worker：读/写事件循环 */
    @Bean(name = "bossGroup", destroyMethod = "shutdownGracefully")
    public EventLoopGroup bossGroup() {
        return new NioEventLoopGroup(1);
    }

    @Bean(name = "workerGroup", destroyMethod = "shutdownGracefully")
    public EventLoopGroup workerGroup() {
        int threads = brokerProperties.getWorkerThreads();
        if (threads <= 0) {
            threads = Math.max(4, Runtime.getRuntime().availableProcessors() * 2);
        }
        log.info("[Broker] worker 线程数 = {}", threads);
        return new NioEventLoopGroup(threads);
    }

    /**
     * 明文 1883 acceptor（共享配置骨架 + 无 SSL pipeline）。
     * TLS 开启时与 {@link #mqttTlsServerBootstrap} 并存，构成双监听。
     */
    @Bean(destroyMethod = "config")
    public ServerBootstrap mqttServerBootstrap(EventLoopGroup bossGroup, EventLoopGroup workerGroup) {
        return baseBootstrap(bossGroup, workerGroup).childHandler(mqttChannelInitializer(null));
    }

    /**
     * TLS 8883 服务端 SSLContext（仅 {@code energyx.broker.tls.enabled=true} 时存在）。
     * 证书缺失/损坏在 Bean 创建期即 fail-fast（早于任何端口绑定）。
     */
    @Bean
    @ConditionalOnProperty(prefix = "energyx.broker.tls", name = "enabled", havingValue = "true")
    public SslContext brokerSslContext() throws Exception {
        BrokerProperties.Tls tls = brokerProperties.getTls();
        File cert = new File(tls.getCertChainFile());
        File key = new File(tls.getPrivateKeyFile());
        if (!cert.isFile() || !key.isFile()) {
            throw new IllegalStateException("MQTT TLS 证书缺失: cert=" + cert.getAbsolutePath()
                    + " key=" + key.getAbsolutePath() + "（先运行 deploy/scripts/gen-mqtt-certs.sh）");
        }
        log.info("[Broker] MQTT TLS 证书加载 {} / {}", cert, key);
        return SslContextBuilder.forServer(cert, key).build();
    }

    /**
     * TLS 8883 acceptor（与明文共享 boss/worker 与 mqttHandler，仅 pipeline 头部多 SslHandler）。
     */
    @Bean(destroyMethod = "")
    @ConditionalOnProperty(prefix = "energyx.broker.tls", name = "enabled", havingValue = "true")
    public ServerBootstrap mqttTlsServerBootstrap(EventLoopGroup bossGroup, EventLoopGroup workerGroup,
                                                  SslContext brokerSslContext) {
        return baseBootstrap(bossGroup, workerGroup).childHandler(mqttChannelInitializer(brokerSslContext));
    }

    /** 共享 acceptor 骨架：event loop / channel / socket 选项 / 水位线（明文与 TLS 完全一致）。 */
    private ServerBootstrap baseBootstrap(EventLoopGroup bossGroup, EventLoopGroup workerGroup) {
        return new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 8192)
                .option(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.SO_RCVBUF, 262_144)
                .childOption(ChannelOption.SO_SNDBUF, 262_144)
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK,
                        new io.netty.channel.WriteBufferWaterMark(32 * 1024, 256 * 1024));
    }

    /**
     * 共享 pipeline 工厂：sslContext 非 null 时先加 SslHandler（必须位于 decoder 之前），
     * 其余解码/空闲/业务处理器与明文 1883 完全一致。
     */
    private ChannelInitializer<SocketChannel> mqttChannelInitializer(SslContext sslContext) {
        return new ChannelInitializer<>() {
            @Override
            protected void initChannel(SocketChannel ch) {
                if (sslContext != null) {
                    ch.pipeline().addLast(SSL_HANDLER_NAME, sslContext.newHandler(ch.alloc()));
                }
                ch.pipeline()
                        .addLast("decoder", new MqttDecoder(1024 * 1024)) // 单报文 ≤ 1MB
                        .addLast("encoder", MqttEncoder.INSTANCE)
                        .addLast(IDLE_HANDLER_NAME,
                                new IdleStateHandler(90, 0, 0)) // 预置，CONNECT 后按 keepalive 重设
                        .addLast(MQTT_HANDLER_NAME, mqttHandler);
            }
        };
    }
}
