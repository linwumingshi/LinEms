package com.energyx.broker.server;

import com.energyx.broker.config.BrokerProperties;
import com.energyx.broker.mqtt.KafkaEventProducer;
import com.energyx.broker.retained.RetainedMessageStore;
import com.energyx.broker.routing.KafkaTopicInitializer;
import com.energyx.broker.routing.RouterConsumer;
import com.energyx.broker.session.Session;
import com.energyx.broker.session.SessionRegistry;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.mqtt.MqttFixedHeader;
import io.netty.handler.codec.mqtt.MqttMessage;
import io.netty.handler.codec.mqtt.MqttMessageType;
import io.netty.handler.codec.mqtt.MqttProperties;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.netty.handler.codec.mqtt.MqttReasonCodeAndPropertiesVariableHeader;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;

/**
 * Broker 生命周期管理：绑定端口、启动路由消费与 topic 预创建、优雅停机。
 *
 * <p>优雅停机步骤（Phase 1 §4.9）：
 * <ol>
 *   <li>关闭 accept 通道（拒绝新连接）；</li>
 *   <li>对在线 MQTT5 客户端发送 DISCONNECT 0x8B（服务器关闭），v3.1.1 客户端直接断开；</li>
 *   <li>停路由消费者（wakeup）→ 停 producer（flush）→ 停业务线程池与 event loop。</li>
 * </ol>
 * 持久会话状态（订阅/inflight/离线队列）已在 Redis，设备重连其他节点即可接管。</p>
 */
@Slf4j
@Component
public class MqttBrokerServer {

    private final ServerBootstrap bootstrap;
    private final ObjectProvider<ServerBootstrap> tlsBootstrapProvider;
    private final BrokerProperties properties;
    private final SessionRegistry sessionRegistry;
    private final RouterConsumer routerConsumer;
    private final KafkaTopicInitializer topicInitializer;
    private final KafkaEventProducer kafkaProducer;
    private final RetainedMessageStore retainedStore;
    private final ExecutorService executor;

    private volatile Channel serverChannel;
    private volatile Channel tlsChannel;

    /**
     * 两个同类型 {@link ServerBootstrap} Bean（明文 + TLS）必须按名限定注入；
     * TLS bootstrap 仅在 {@code energyx.broker.tls.enabled=true} 时存在，用 {@link ObjectProvider}
     * 惰性获取，TLS 关闭时 {@code getIfAvailable()} 返回 null。
     */
    public MqttBrokerServer(@Qualifier("mqttServerBootstrap") ServerBootstrap bootstrap,
                            @Qualifier("mqttTlsServerBootstrap") ObjectProvider<ServerBootstrap> tlsBootstrapProvider,
                            BrokerProperties properties,
                            SessionRegistry sessionRegistry,
                            RouterConsumer routerConsumer,
                            KafkaTopicInitializer topicInitializer,
                            KafkaEventProducer kafkaProducer,
                            RetainedMessageStore retainedStore,
                            ExecutorService brokerExecutor) {
        this.bootstrap = bootstrap;
        this.tlsBootstrapProvider = tlsBootstrapProvider;
        this.properties = properties;
        this.sessionRegistry = sessionRegistry;
        this.routerConsumer = routerConsumer;
        this.topicInitializer = topicInitializer;
        this.kafkaProducer = kafkaProducer;
        this.retainedStore = retainedStore;
        this.executor = brokerExecutor;
    }

    @PostConstruct
    public void start() {
        try {
            serverChannel = bootstrap.bind(properties.getPort()).sync().channel();
            log.info("[Broker] MQTT 端口 {} 监听成功，nodeId={}", properties.getPort(), properties.getNodeId());
            ServerBootstrap tlsBootstrap = tlsBootstrapProvider.getIfAvailable();
            if (tlsBootstrap != null) {
                tlsChannel = tlsBootstrap.bind(properties.getTls().getPort()).sync().channel();
                log.info("[Broker] MQTT TLS 端口 {} 监听成功", properties.getTls().getPort());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Broker 绑定端口失败: " + properties.getPort(), e);
        }
        routerConsumer.start();
        topicInitializer.initializeAsync();
        retainedStore.warmUp(); // 冷启动预热保留消息（后台线程，不阻塞启动）
    }

    @PreDestroy
    public void stop() {
        log.info("[Broker] 优雅停机开始，在线会话 {}", sessionRegistry.connectionCount());
        // 1. 停止接收新连接（明文 + TLS 双监听）
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
        }
        if (tlsChannel != null) {
            tlsChannel.close().syncUninterruptibly();
        }
        // 2. 通知在线设备（MQTT5 DISCONNECT reason=0x8B 服务器关闭）
        sessionRegistry.closeAll(session -> {
            if (session.getMqttVersion() == 5 && session.isOnline()) {
                // 修复：0x8B 是 v5 reason code，必须走 VariableHeader 编码；
                // 误放 MqttFixedHeader 第 5 参（remainingLength）会被编码器重算覆盖，reason 静默丢失
                session.getChannel().writeAndFlush(new MqttMessage(
                        new MqttFixedHeader(MqttMessageType.DISCONNECT, false, MqttQoS.AT_MOST_ONCE, false, 0),
                        new MqttReasonCodeAndPropertiesVariableHeader((byte) 0x8B, MqttProperties.NO_PROPERTIES)));
            }
            session.getChannel().close();
        });
        // 3. 停路由消费与生产
        routerConsumer.stop();
        kafkaProducer.close();
        // 4. 停线程池
        executor.shutdown();
        log.info("[Broker] 优雅停机完成");
    }
}
