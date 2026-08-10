package com.energyx.broker.handler;

import com.energyx.broker.auth.AuthResult;
import com.energyx.broker.auth.DeviceAuthService;
import com.energyx.broker.auth.DeviceCredential;
import com.energyx.broker.auth.TopicAcl;
import com.energyx.broker.config.BrokerProperties;
import com.energyx.broker.config.NettyServerConfig;
import com.energyx.broker.lifecycle.LifecycleNotifier;
import com.energyx.broker.mqtt.KafkaEventProducer;
import com.energyx.broker.ratelimit.PublishRateLimiter;
import com.energyx.broker.routing.LocalSubscriberIndex;
import com.energyx.broker.routing.MessageDeliverer;
import com.energyx.common.mqtt.RouterEnvelope;
import com.energyx.common.mqtt.RouterEnvelopeCodec;
import com.energyx.broker.session.InboundPublish;
import com.energyx.broker.session.InflightMessage;
import com.energyx.broker.session.MqttSubscription;
import com.energyx.broker.session.Session;
import com.energyx.broker.session.SessionRegistry;
import com.energyx.broker.session.SessionStore;
import com.energyx.broker.stats.BrokerMetrics;
import com.energyx.broker.stats.BrokerStats;
import com.energyx.common.constant.KafkaTopicConstant;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.mqtt.*;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MQTT 报文分发处理器（@Sharable 单例，跨 channel 共享，channel 态全部放 channel attribute）。
 *
 * <p>处理矩阵（MQTT 3.1.1 §2.2 + v5 兼容子集）：
 * <pre>
 * CONNECT/PUBLISH/PUBACK/PUBREC/PUBREL/PUBCOMP/SUBSCRIBE/UNSUBSCRIBE/PINGREQ/DISCONNECT
 * </pre>
 * 职责边界：协议握手、ACL、QoS 状态机、路由分发；业务无关（上行报文不落库，Phase 5 摄取）。
 *
 * <p>线程模型（P0 修复后严格执行）：EventLoop 上只做内存操作与 channel 写出；
 * 认证（Redis/MySQL）、连接锁、会话恢复、持久化、在线续期等阻塞操作一律经 brokerExecutor 剥离；
 * 连接锁抢占与 sessionPresent 判定在业务线程完成后再回投 eventLoop 注册会话。</p>
 *
 * <p>可靠性契约：QoS1/2 上行的 PUBACK/PUBCOMP 在 Kafka 路由持久化回调成功后才发送；
 * 路由失败关闭连接，设备重连重传（at-least-once）。</p>
 */
@Slf4j
@Component
@ChannelHandler.Sharable
public class MqttChannelInboundHandler extends ChannelInboundHandlerAdapter {

    private static final AttributeKey<Session> SESSION_ATTR = AttributeKey.valueOf("mqtt.session");
    private static final AttributeKey<String> DEVICE_KEY_ATTR = AttributeKey.valueOf("mqtt.deviceKey");

    private final DeviceAuthService authService;
    private final SessionRegistry sessionRegistry;
    private final SessionStore sessionStore;
    private final LocalSubscriberIndex subscriberIndex;
    private final MessageDeliverer deliverer;
    private final LifecycleNotifier lifecycleNotifier;
    private final KafkaEventProducer kafkaProducer;
    private final BrokerProperties properties;
    private final BrokerStats stats;
    private final BrokerMetrics metrics;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;
    private final ScheduledExecutorService scheduler;
    private final PublishRateLimiter rateLimiter;
    private final AtomicInteger rawConnections = new AtomicInteger();
    /**
     * 认证并发信号量（P2-8）：控制同时进行中的认证数，超限快速拒绝新连接防风暴
     */
    private final java.util.concurrent.Semaphore authSlots;
    /**
     * 会话恢复并发信号量（重连风暴防护）：控制同时执行的恢复任务数，超限延迟重试
     */
    private final java.util.concurrent.Semaphore sessionRestoreSlots;

    public MqttChannelInboundHandler(DeviceAuthService authService,
                                     SessionRegistry sessionRegistry,
                                     SessionStore sessionStore,
                                     LocalSubscriberIndex subscriberIndex,
                                     MessageDeliverer deliverer,
                                     LifecycleNotifier lifecycleNotifier,
                                     KafkaEventProducer kafkaProducer,
                                     BrokerProperties properties,
                                     BrokerStats stats,
                                     BrokerMetrics metrics,
                                     PublishRateLimiter rateLimiter,
                                     ObjectMapper objectMapper,
                                     @Qualifier("brokerExecutor") ExecutorService executor,
                                     @Qualifier("brokerScheduler") ScheduledExecutorService scheduler) {
        this.authService = authService;
        this.sessionRegistry = sessionRegistry;
        this.sessionStore = sessionStore;
        this.subscriberIndex = subscriberIndex;
        this.deliverer = deliverer;
        this.lifecycleNotifier = lifecycleNotifier;
        this.kafkaProducer = kafkaProducer;
        this.properties = properties;
        this.stats = stats;
        this.metrics = metrics;
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.scheduler = scheduler;
        this.authSlots = new java.util.concurrent.Semaphore(Math.max(1, properties.getAuthMaxConcurrent()));
        this.sessionRestoreSlots = new java.util.concurrent.Semaphore(
                Math.max(1, properties.getSessionRestoreMaxConcurrent()));
    }

    // ---------------- 连接生命周期 ----------------

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        // 准入控制：超过单节点上限拒绝新连接
        if (rawConnections.incrementAndGet() > properties.getMaxConnections()) {
            rawConnections.decrementAndGet();
            stats.recordRejected();
            log.warn("[Broker] 超过最大连接数 {}，拒绝 {}", properties.getMaxConnections(), ctx.channel().remoteAddress());
            ctx.close();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        rawConnections.decrementAndGet();
        Session session = ctx.channel().attr(SESSION_ATTR).getAndSet(null);
        ctx.channel().attr(DEVICE_KEY_ATTR).set(null);
        if (session == null) {
            return; // 未认证连接直接断开
        }
        session.getPendingWrites().clear();
        String deviceKey = session.getDeviceKey();
        // O(1) 注销：仅当注册表中仍是本 channel 的会话才移除（防旧连接晚到误删新会话）
        sessionRegistry.unregisterIfChannelMatches(deviceKey, ctx.channel());

        if (session.isSuperseded()) {
            // 同 clientId 新连接已接管：本连接静默清理，不触发离线事件/持久化/锁释放
            log.info("[Broker] 旧连接被新连接取代 deviceKey={}", deviceKey);
            return;
        }

        boolean graceful = session.isDisconnectedGracefully();
        // P1-11：v5 Session Expiry Interval=0（或 clean 会话）→ 断开即删；>0 → 按过期时间持久化
        long expiry = session.getSessionExpirySeconds();
        boolean transientSession = session.isCleanSession() || expiry <= 0;
        if (transientSession) {
            subscriberIndex.removeAll(deviceKey);
            executor.execute(() -> {
                sessionStore.deleteSession(deviceKey);
                sessionStore.deleteSubscriptions(deviceKey);
                sessionStore.deleteInflight(deviceKey);
                sessionStore.releaseConnLockIfOwner(deviceKey, properties.getNodeId());
            });
        } else {
            // 持久会话：幽灵订阅保留（支撑离线队列），持久化会话元数据（含过期时间）+ 订阅
            executor.execute(() -> {
                try {
                    sessionStore.saveSession(deviceKey, properties.getNodeId(), false, expiry);
                    sessionStore.saveSubscriptions(deviceKey,
                            new HashSet<>(session.getSubscriptions().values()));
                } catch (Exception e) {
                    log.error("[Broker] 持久会话保存失败 deviceKey={}", deviceKey, e);
                }
                sessionStore.releaseConnLockIfOwner(deviceKey, properties.getNodeId());
            });
        }

        DeviceCredential cred = attrCredential(session);
        if (cred != null) {
            String reason = graceful ? "NORMAL" : "HEARTBEAT_TIMEOUT";
            String remoteIp = session.getRemoteIp();
            // 离线通知含 Redis 删除，剥离到业务线程
            executor.execute(() -> lifecycleNotifier.notifyOffline(cred, reason, remoteIp));
            // 遗嘱处理（Redis 持久化，节点宕机不丢）：
            //  非优雅断开 → 投递遗嘱（delay=0 立即 / delay>0 调度，窗口内重连取消）→ 删除 Redis 遗嘱；
            //  优雅断开 → 不投递，直接删除 Redis 遗嘱（视为正常下线，不再补投）
            Session.MqttWill will = session.getWill();
            if (!graceful && will != null) {
                if (will.getDelaySeconds() <= 0) {
                    deliverer.deliver(will.getTopic(), will.getPayload(), will.getQos(), will.isRetain(), null);
                    executor.execute(() -> sessionStore.deleteWill(deviceKey));
                } else {
                    String willDeviceKey = deviceKey;
                    scheduler.schedule(() -> {
                        Session current = sessionRegistry.get(willDeviceKey);
                        if (current != null && current.isOnline()) {
                            log.info("[Broker] Will Delay 窗口内设备已重连，取消遗嘱 deviceKey={}", willDeviceKey);
                            return;
                        }
                        deliverer.deliver(will.getTopic(), will.getPayload(),
                                will.getQos(), will.isRetain(), null);
                        executor.execute(() -> sessionStore.deleteWill(willDeviceKey));
                    }, will.getDelaySeconds(), TimeUnit.SECONDS);
                }
            } else if (graceful) {
                // 优雅断开：遗嘱不投递，清除 Redis 持久化遗嘱（下次重连不再补投）
                executor.execute(() -> sessionStore.deleteWill(deviceKey));
            }
        }
        log.info("[Broker] 连接关闭 deviceKey={} reason={}", deviceKey, graceful ? "NORMAL" : "ABNORMAL");
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            Session session = ctx.channel().attr(SESSION_ATTR).get();
            log.warn("[Broker] 心跳超时断开 deviceKey={} remote={}",
                    session == null ? "?" : session.getDeviceKey(), ctx.channel().remoteAddress());
            ctx.close(); // 触发 channelInactive → 离线事件
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }

    /**
     * channel 恢复可写：冲刷该会话的背压挂起队列（在 eventLoop 上触发）
     */
    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        Session session = ctx.channel().attr(SESSION_ATTR).get();
        if (session != null && ctx.channel().isWritable()) {
            deliverer.flushPending(session);
        }
        super.channelWritabilityChanged(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        Session session = ctx.channel().attr(SESSION_ATTR).get();
        log.warn("[Broker] 通道异常关闭 deviceKey={} err={}",
                session == null ? "?" : session.getDeviceKey(), cause.getMessage());
        ctx.close();
    }

    // ---------------- 报文分发 ----------------

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msgObj) {
        if (!(msgObj instanceof MqttMessage msg)) {
            return;
        }
        try {
            MqttMessageType type = msg.fixedHeader().messageType();
            switch (type) {
                case CONNECT -> handleConnect(ctx, (MqttConnectMessage) msg);
                case PUBLISH -> handlePublish(ctx, (MqttPublishMessage) msg);
                case PUBACK -> handlePubAck(ctx, (MqttPubAckMessage) msg);
                case PUBREC -> handlePubRec(ctx, msg);
                case PUBREL -> handlePubRel(ctx, msg);
                case PUBCOMP -> handlePubComp(ctx, msg);
                case SUBSCRIBE -> handleSubscribe(ctx, (MqttSubscribeMessage) msg);
                case UNSUBSCRIBE -> handleUnsubscribe(ctx, (MqttUnsubscribeMessage) msg);
                case PINGREQ -> handlePingReq(ctx);
                case DISCONNECT -> handleDisconnect(ctx, msg);
                default -> log.debug("[Broker] 忽略报文类型 {}", type);
            }
        } finally {
            ReferenceCountUtil.release(msgObj);
        }
    }

    // ---------------- CONNECT ----------------

    private void handleConnect(ChannelHandlerContext ctx, MqttConnectMessage msg) {
        Channel channel = ctx.channel();
        MqttConnectVariableHeader vh = msg.variableHeader();
        String clientId = msg.payload().clientIdentifier() == null ? "" : msg.payload().clientIdentifier();
        int version = vh.version();

        if (version != 3 && version != 4 && version != 5) {
            sendConnAck(channel, MqttConnectReturnCode.CONNECTION_REFUSED_UNACCEPTABLE_PROTOCOL_VERSION, false);
            channel.close();
            return;
        }
        // P2-4 决策：MQTT 3.1.1/5.0 允许 cleanSession=1 时空 clientId 由 Broker 分配，
        // 但本平台认证为「clientId 即设备身份」（HMAC username 内嵌 clientId，凭据按 clientId 绑定），
        // 空 clientId 无法通过认证且构成匿名接入风险，故维持拒绝（安全约束优先于协议完备）。
        if (clientId.isEmpty()) {
            sendConnAck(channel, MqttConnectReturnCode.CONNECTION_REFUSED_IDENTIFIER_REJECTED, false);
            channel.close();
            return;
        }
        // 协议合规（MQTT-3.1.0-2）：同一连接上第二个 CONNECT 必须关断
        if (channel.attr(SESSION_ATTR).get() != null) {
            log.warn("[Broker] 重复 CONNECT，按规范关断 clientId={} remote={}",
                    clientId, channel.remoteAddress());
            channel.close();
            return;
        }

        String username = msg.payload().userName();
        String password = msg.payload().passwordInBytes() == null
                ? "" : new String(msg.payload().passwordInBytes(), StandardCharsets.UTF_8);

        // ---- v5 连接属性协商：Session Expiry Interval / Receive Maximum / Maximum Packet Size ----
        long sessionExpirySeconds = -1; // -1 = 未指定，沿用默认
        int receiveMaximum = -1;        // -1 = 未指定，用协议上限
        int maxPacketSize = 0;          // 0 = 未指定，不限制
        if (version == 5) {
            MqttProperties props = msg.variableHeader().properties();
            MqttProperties.IntegerProperty expiry =
                    (MqttProperties.IntegerProperty) props.getProperty(
                            MqttProperties.MqttPropertyType
                                    .SESSION_EXPIRY_INTERVAL.value());
            if (expiry != null) {
                sessionExpirySeconds = expiry.value();
            }
            MqttProperties.IntegerProperty recvMax =
                    (MqttProperties.IntegerProperty) props.getProperty(
                            MqttProperties.MqttPropertyType
                                    .RECEIVE_MAXIMUM.value());
            if (recvMax != null) {
                receiveMaximum = Math.max(1, recvMax.value());
            }
            MqttProperties.IntegerProperty maxPkt =
                    (MqttProperties.IntegerProperty) props.getProperty(
                            MqttProperties.MqttPropertyType
                                    .MAXIMUM_PACKET_SIZE.value());
            if (maxPkt != null && maxPkt.value() != null) {
                maxPacketSize = Math.max(1, maxPkt.value());
            }
        }

        // 遗嘱（v3 从 payload 提取；v5 will delay 读取 will properties，0 则立即投递）
        Session.MqttWill will = null;
        if (vh.isWillFlag()) {
            String willTopic = msg.payload().willTopic();
            if (willTopic != null && !willTopic.isEmpty()) {
                // P2-3：用 willMessageInBytes 提取原始字节（二进制安全），
                // willMessage() 按 UTF-8 String 解码会损坏二进制载荷
                byte[] willBytes = msg.payload().willMessageInBytes();
                int willDelay = 0;
                if (version == 5 && msg.payload().willProperties() != null) {
                    MqttProperties.IntegerProperty delay =
                            (MqttProperties.IntegerProperty)
                                    msg.payload().willProperties().getProperty(
                                            MqttProperties.MqttPropertyType
                                                    .WILL_DELAY_INTERVAL.value());
                    if (delay != null) {
                        willDelay = Math.max(0, delay.value());
                    }
                }
                will = new Session.MqttWill(willTopic,
                        willBytes == null ? new byte[0] : willBytes,
                        vh.willQos(), vh.isWillRetain(), willDelay);
            }
        }

        ConnectParams params = new ConnectParams(version, clientId,
                vh.keepAliveTimeSeconds(), vh.isCleanSession(),
                username, password, will, remoteIp(channel),
                sessionExpirySeconds, receiveMaximum, maxPacketSize);

        // 慢路径全部在业务线程：认证（Redis/MySQL）→ 连接锁（Redis）→ sessionPresent 判定（Redis），
        // 仅最终会话注册与 CONNACK 回投 eventLoop（IO 线程零阻塞）
        // P2-8 认证风暴防护：并发认证数超限（信号量 3s 拿不到许可）快速拒绝新连接，保护 executor 不被打满
        boolean acquired;
        try {
            acquired = authSlots.tryAcquire(3, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            acquired = false;
        }
        if (!acquired) {
            stats.recordAuthOverloadRejected();
            log.warn("[Auth] 认证并发超限拒绝 clientId={} remote={}",
                    clientId, channel.remoteAddress());
            channel.eventLoop().execute(() -> {
                sendConnAck(channel, MqttConnectReturnCode.CONNECTION_REFUSED_SERVER_UNAVAILABLE, false);
                channel.close();
            });
            return;
        }
        executor.execute(() -> {
            AuthResult result;
            try {
                result = authService.authenticate(clientId, username, password);
            } catch (Exception e) {
                log.error("[Auth] 认证异常 clientId={}", clientId, e);
                result = AuthResult.deny(3, false, "认证服务异常");
            } finally {
                // 认证（含后续 mTLS/连接锁）完成释放并发许可
                authSlots.release();
            }
            if (!result.isAllowed()) {
                stats.recordAuthFailure();
                AuthResult denied = result;
                channel.eventLoop().execute(() -> {
                    sendConnAck(channel, returnCode(denied.getConnackCode()), false);
                    channel.close();
                });
                return;
            }
            DeviceCredential cred = result.getCredential();
            String deviceKey = cred.getDeviceKey();

            // P1-12 mTLS：设备证书 CN 必须等于 clientId（身份绑定；信任链由 TLS 握手层校验）
            if (properties.getTls().isClientAuth() && !verifyClientCert(channel, clientId)) {
                stats.recordAuthFailure();
                channel.eventLoop().execute(() -> {
                    sendConnAck(channel, MqttConnectReturnCode.CONNECTION_REFUSED_NOT_AUTHORIZED, false);
                    channel.close();
                });
                return;
            }

            // 跨节点连接锁：被远端占用 → 通知远端踢线，本节点接管
            if (!sessionStore.tryAcquireConnLock(deviceKey, properties.getNodeId())) {
                String owner = sessionStore.getConnLockOwner(deviceKey);
                if (owner != null && !owner.equals(properties.getNodeId())) {
                    kickRemote(owner, deviceKey);
                }
                sessionStore.overwriteConnLock(deviceKey, properties.getNodeId());
            }

            boolean sessionPresent = !params.cleanSession && sessionStore.existsSession(deviceKey);
            channel.eventLoop().execute(() -> completeConnect(ctx, params, cred, sessionPresent));
        });
    }

    private void completeConnect(ChannelHandlerContext ctx, ConnectParams p,
                                 DeviceCredential cred, boolean sessionPresent) {
        Channel channel = ctx.channel();
        if (!channel.isActive()) {
            return;
        }
        String deviceKey = cred.getDeviceKey();

        // 踢本节点旧连接（同 clientId）
        Session old = sessionRegistry.get(deviceKey);
        if (old != null && old.getChannel() != channel) {
            old.setSuperseded(true);
            old.getChannel().close();
            sessionRegistry.unregister(deviceKey);
        }

        Session session = new Session(deviceKey, channel, p.version, p.cleanSession);
        session.putAttr(Session.SessionAttr.DEVICE_ID, cred.getDeviceId());
        session.putAttr(Session.SessionAttr.TENANT_ID, cred.getTenantId());
        session.putAttr(Session.SessionAttr.PRODUCT_KEY, cred.getProductKey());
        session.putAttr(Session.SessionAttr.DEVICE_NAME, cred.getDeviceName());
        session.putAttr(Session.SessionAttr.DEVICE_STATUS, cred.getDeviceStatus());
        session.setWill(p.will);
        session.setRemoteIp(p.remoteIp);
        // P1-11 v5 属性：Session Expiry Interval（-1 未指定 → 默认 7 天；0 → 断开即过期）
        if (p.sessionExpirySeconds >= 0) {
            session.setSessionExpirySeconds(p.sessionExpirySeconds);
        } else {
            session.setSessionExpirySeconds(p.cleanSession ? 0 : properties.getSessionTtlSeconds());
        }
        // P1-11 v5 Receive Maximum（-1 未指定 → 协议上限 65535，由 inflight 配置再收敛）
        if (p.receiveMaximum > 0) {
            session.setReceiveMaximum(p.receiveMaximum);
        }
        // v5 属性协商：Maximum Packet Size（0 = 未指定/v3，不限制下行报文大小）
        if (p.maxPacketSize > 0) {
            session.setMaxPacketSize(p.maxPacketSize);
        }
        channel.attr(SESSION_ATTR).set(session);
        channel.attr(DEVICE_KEY_ATTR).set(deviceKey);
        sessionRegistry.register(session);

        // keepalive：>0 按 1.5× 重设 IdleStateHandler；=0 按协议不启用超时，移除预置 idle handler
        if (p.keepAliveSeconds > 0) {
            replaceIdleHandler(channel, p.keepAliveSeconds);
        } else {
            ChannelPipeline pipeline = channel.pipeline();
            if (pipeline.get(NettyServerConfig.IDLE_HANDLER_NAME) != null) {
                pipeline.remove(NettyServerConfig.IDLE_HANDLER_NAME);
            }
        }

        // 干净会话重连：清除残留幽灵订阅（上一连接遗留）
        if (p.cleanSession) {
            subscriberIndex.removeAll(deviceKey);
        }

        if (p.version == 5) {
            // v5 CONNACK 能力声明（属性协商）：Maximum QoS=2、Retain Available=1（本 broker 能力）
            sendConnAckV5(channel, MqttConnectReturnCode.CONNECTION_ACCEPTED, sessionPresent);
        } else {
            sendConnAck(channel, MqttConnectReturnCode.CONNECTION_ACCEPTED, sessionPresent);
        }
        stats.recordAccepted();
        log.info("[Broker] 设备上线 deviceKey={} version={} remote={}", deviceKey, p.version, p.remoteIp);

        // 异步恢复 + 上线通知：Redis/Kafka 阻塞操作全部走业务线程池。
        // 重连风暴防护（可靠性）：恢复任务受信号量并发限流，超限延迟重试而非丢弃——
        // 海量设备同时重连（断电恢复/基站批量上线）时恢复任务排开执行，不把 executor 打满。
        executor.execute(() -> runSessionRestore(p, session, cred, 0));
    }

    /**
     * 会话恢复任务（限流 + 延迟重试）。
     *
     * <p>恢复内容包括：clean 会话清理残留 / 持久会话订阅加载、inflight 续传、离线队列补发、
     * 上线通知。并发超过 {@code session-restore-max-concurrent} 时按配置延迟重试（默认 2s，
     * 最多 3 次），限流期间不丢弃恢复任务——重连风暴下表现为「连接先建立、会话逐步恢复」，
     * 而非恢复任务被线程池拒绝策略直接丢弃。</p>
     */
    private void runSessionRestore(ConnectParams p, Session session, DeviceCredential cred, int attempt) {
        if (attempt >= properties.getSessionRestoreMaxAttempts()) {
            log.warn("[Broker] 会话恢复重试耗尽（限流持续）deviceKey={}，下次重连再恢复", session.getDeviceKey());
            return;
        }
        // 无参 tryAcquire 不抛 InterruptedException，非阻塞快速判定
        if (!sessionRestoreSlots.tryAcquire()) {
            log.info("[Broker] 会话恢复并发受限，{}s 后重试 deviceKey={} attempt={}",
                    properties.getSessionRestoreRetryDelaySeconds(), session.getDeviceKey(), attempt + 1);
            scheduler.schedule(() -> runSessionRestore(p, session, cred, attempt + 1),
                    properties.getSessionRestoreRetryDelaySeconds(), TimeUnit.SECONDS);
            return;
        }
        try {
            String deviceKey = session.getDeviceKey();
            try {
                if (p.cleanSession) {
                    sessionStore.deleteSession(deviceKey);
                    sessionStore.deleteSubscriptions(deviceKey);
                    sessionStore.deleteInflight(deviceKey);
                } else {
                    // 遗嘱持久化（节点宕机不丢）：先补投上次连接未投递的遗嘱（如节点宕机场景），
                    // 再以本次 CONNECT 声明的遗嘱覆盖；正常非优雅断开已投递并删除，此处 loadWill 为空
                    Session.MqttWill persistedWill = sessionStore.loadWill(deviceKey);
                    if (persistedWill != null) {
                        log.info("[Broker] 补投上次未投递的遗嘱 deviceKey={} topic={}",
                                deviceKey, persistedWill.getTopic());
                        deliverer.deliver(persistedWill.getTopic(), persistedWill.getPayload(),
                                persistedWill.getQos(), persistedWill.isRetain(), null);
                        sessionStore.deleteWill(deviceKey);
                    }
                    sessionStore.saveWill(deviceKey, session.getWill());

                    Set<MqttSubscription> subs = sessionStore.loadSubscriptions(deviceKey);
                    for (MqttSubscription sub : subs) {
                        session.getSubscriptions().put(sub.getTopicFilter(), sub);
                        subscriberIndex.add(session, sub.getTopicFilter(), sub.getQos());
                    }
                    deliverer.resendInflight(session);
                    deliverer.deliverOfflineQueue(session);
                }
            } catch (Exception e) {
                log.error("[Broker] 会话恢复失败 deviceKey={}", deviceKey, e);
            }
            try {
                lifecycleNotifier.notifyOnline(cred, p.remoteIp);
            } catch (Exception e) {
                log.error("[Broker] 上线通知失败 deviceKey={}", deviceKey, e);
            }
        } finally {
            sessionRestoreSlots.release();
        }
    }

    private void kickRemote(String ownerNode, String deviceKey) {
        try {
            RouterEnvelope envelope = RouterEnvelope.kick(properties.getNodeId(), deviceKey);
            // 阶段 2：KICK 走 mqtt.broadcast 广播通道（每节点唯一消费组，sourceNode 去重）
            byte[] payload = RouterEnvelopeCodec.encode(envelope);
            kafkaProducer.sendBytes(KafkaTopicConstant.MQTT_BROADCAST, deviceKey, payload);
        } catch (Exception e) {
            log.warn("[Broker] 远程踢线信封发送失败 deviceKey={} owner={}", deviceKey, ownerNode, e);
        }
    }

    /**
     * mTLS 设备证书校验（P1-12）：TLS 握手完成后（CONNECT 报文到达即已握手），读取对端证书
     * 链，校验叶子证书 subject CN 与 clientId 一致（证书签发/吊销由 CA 体系负责，见
     * deploy/scripts/gen-mqtt-certs.sh -c）。握手未完成/证书缺失一律拒绝。
     */
    private boolean verifyClientCert(Channel channel, String clientId) {
        try {
            io.netty.handler.ssl.SslHandler sslHandler = channel.pipeline().get(io.netty.handler.ssl.SslHandler.class);
            if (sslHandler == null) {
                log.warn("[mTLS] 非 TLS 连接尝试设备接入，拒绝 clientId={}", clientId);
                return false;
            }
            java.security.cert.Certificate[] chain =
                    sslHandler.engine().getSession().getPeerCertificates();
            if (chain == null || chain.length == 0) {
                log.warn("[mTLS] 对端未提供证书 clientId={}", clientId);
                return false;
            }
            if (!(chain[0] instanceof java.security.cert.X509Certificate leaf)) {
                return false;
            }
            String cn = extractCn(leaf.getSubjectX500Principal().getName());
            boolean ok = clientId.equals(cn);
            if (!ok) {
                log.warn("[mTLS] 证书 CN={} 与 clientId={} 不匹配，拒绝接入", cn, clientId);
            }
            return ok;
        } catch (Exception e) {
            log.warn("[mTLS] 证书校验异常 clientId={} err={}", clientId, e.getMessage());
            return false;
        }
    }

    /**
     * 从 X500 名称提取 CN（LDAP 序，兼容 "CN=xx,O=yy" 与 RFC2253 "O=yy,CN=xx"）
     */
    private String extractCn(String dn) {
        try {
            for (javax.naming.ldap.Rdn rdn : new javax.naming.ldap.LdapName(dn).getRdns()) {
                if ("CN".equalsIgnoreCase(rdn.getType())) {
                    return String.valueOf(rdn.getValue());
                }
            }
        } catch (Exception ignore) {
            // 空
        }
        return null;
    }

    // ---------------- PUBLISH（设备上行） ----------------

    private void handlePublish(ChannelHandlerContext ctx, MqttPublishMessage msg) {
        Session session = ctx.channel().attr(SESSION_ATTR).get();
        if (session == null) {
            ctx.close();
            return;
        }
        String topic = msg.variableHeader().topicName();
        int qos = msg.fixedHeader().qosLevel().value();
        boolean retain = msg.fixedHeader().isRetain();
        int packetId = msg.variableHeader().packetId();
        byte[] payload = new byte[msg.payload().readableBytes()];
        msg.payload().getBytes(msg.payload().readerIndex(), payload);

        DeviceCredential cred = attrCredential(session);
        if (!TopicAcl.canPublish(cred, topic)) {
            log.warn("[ACL] 拒绝越权发布 deviceKey={} topic={}", session.getDeviceKey(), topic);
            stats.recordRejected();
            ctx.close();
            return;
        }

        session.touch();
        maybeRenewOnline(session, cred);
        // P2-7 单设备发布限速：超限 QoS0 丢弃、QoS1/2 关连接迫使设备节流
        if (!rateLimiter.tryAcquire(session.getDeviceKey())) {
            stats.recordRateLimited();
            if (qos > 0) {
                log.warn("[RateLimit] 发布超限关连接 deviceKey={} topic={} qos={}",
                        session.getDeviceKey(), topic, qos);
                ctx.close();
            } else {
                log.warn("[RateLimit] QoS0 发布超限丢弃 deviceKey={} topic={}",
                        session.getDeviceKey(), topic);
            }
            return;
        }
        Channel channel = ctx.channel();
        // P1-10 链路追踪：同一上报报文的处理链日志共用 traceId（deviceKey+毫秒+序号）
        String traceId = deviceKeyOf(session) + "-" + (System.currentTimeMillis() & 0xFFFFF)
                + "-" + Integer.toHexString(System.identityHashCode(msg));
        switch (qos) {
            case 0 -> deliverer.deliver(topic, payload, 0, retain, null);
            case 1 -> {
                // PUBACK 推迟到 Kafka 路由持久化确认之后；路由失败关连接，设备重连重传（at-least-once）
                long publishStart = System.nanoTime();
                deliverer.deliver(topic, payload, 1, retain, null,
                        () -> {
                            metrics.recordPubAckLatency(publishStart);
                            channel.eventLoop().execute(() -> {
                                if (channel.isActive()) {
                                    sendPubAck(channel, packetId);
                                }
                            });
                        },
                        () -> channel.eventLoop().execute(() -> {
                            log.error("[Deliver] 路由失败关连接迫使重传 traceId={} deviceKey={} topic={}",
                                    traceId, session.getDeviceKey(), topic);
                            channel.close();
                        }));
            }
            case 2 -> {
                // 收到 PUBREL 才路由（恰好一次入站）
                session.getInboundQos2().put(packetId, new InboundPublish(topic, payload, qos));
                sendPubRec(channel, packetId);
            }
            default -> log.warn("[Broker] 非法 QoS={} deviceKey={}", qos, session.getDeviceKey());
        }
        stats.recordIncoming();
    }

    /**
     * 会话 deviceKey（工具方法，避免重复 get）
     */
    private String deviceKeyOf(Session session) {
        return session.getDeviceKey();
    }

    private void handlePubAck(ChannelHandlerContext ctx, MqttPubAckMessage msg) {
        Session session = ctx.channel().attr(SESSION_ATTR).get();
        if (session == null) {
            return;
        }
        int packetId = msg.variableHeader().messageId();
        InflightMessage inflight = session.getOutboundInflight().remove(packetId);
        if (inflight != null) {
            asyncRemoveInflight(session.getDeviceKey(), packetId);
        }
    }

    private void handlePubRec(ChannelHandlerContext ctx, MqttMessage msg) {
        Session session = ctx.channel().attr(SESSION_ATTR).get();
        if (session == null) {
            return;
        }
        int packetId = messageId(msg);
        InflightMessage inflight = session.getOutboundInflight().get(packetId);
        if (inflight == null) {
            return;
        }
        if (inflight.getState() == InflightMessage.STATE_AWAITING_PUBREC) {
            inflight.setState(InflightMessage.STATE_AWAITING_PUBCOMP);
            session.getOutboundInflight().put(packetId, inflight);
            asyncSaveInflight(session.getDeviceKey(), inflight);
            sendPubRel(ctx.channel(), packetId);
        } else if (inflight.getState() == InflightMessage.STATE_AWAITING_PUBCOMP) {
            // 防御分支：PUBREL 可能丢失，收到重复 PUBREC 时重发 PUBREL
            sendPubRel(ctx.channel(), packetId);
        }
    }

    private void handlePubRel(ChannelHandlerContext ctx, MqttMessage msg) {
        Session session = ctx.channel().attr(SESSION_ATTR).get();
        if (session == null) {
            return;
        }
        int packetId = messageId(msg);
        InboundPublish inbound = session.getInboundQos2().remove(packetId);
        Channel channel = ctx.channel();
        if (inbound != null) {
            // PUBCOMP 推迟到路由持久化确认之后；失败关连接，设备重连后重发 PUBREL 完成恰好一次
            deliverer.deliver(inbound.topic(), inbound.payload(), inbound.qos(), false, null,
                    () -> channel.eventLoop().execute(() -> {
                        if (channel.isActive()) {
                            sendPubComp(channel, packetId);
                        }
                    }),
                    () -> channel.eventLoop().execute(() -> {
                        log.error("[Deliver] QoS2 入站路由失败关连接 deviceKey={} topic={}",
                                session.getDeviceKey(), inbound.topic());
                        channel.close();
                    }));
        } else {
            // 重复 PUBREL（首次已完成或会话重启）：直接确认，幂等
            sendPubComp(channel, packetId);
        }
        stats.recordIncoming();
    }

    private void handlePubComp(ChannelHandlerContext ctx, MqttMessage msg) {
        Session session = ctx.channel().attr(SESSION_ATTR).get();
        if (session == null) {
            return;
        }
        int packetId = messageId(msg);
        session.getOutboundInflight().remove(packetId);
        asyncRemoveInflight(session.getDeviceKey(), packetId);
    }

    // ---------------- SUBSCRIBE / UNSUBSCRIBE ----------------

    private void handleSubscribe(ChannelHandlerContext ctx, MqttSubscribeMessage msg) {
        Session session = ctx.channel().attr(SESSION_ATTR).get();
        if (session == null) {
            ctx.close();
            return;
        }
        DeviceCredential cred = attrCredential(session);
        int packetId = msg.variableHeader().messageId();
        List<MqttTopicSubscription> subs = msg.payload().topicSubscriptions();
        List<Integer> returnCodes = new ArrayList<>(subs.size());
        List<String> allowedFilters = new ArrayList<>();

        for (MqttTopicSubscription sub : subs) {
            String filter = sub.topicName();
            int reqQos = sub.qualityOfService().value();
            if (TopicAcl.canSubscribe(cred, filter)) {
                returnCodes.add(reqQos);
                allowedFilters.add(filter);
                session.getSubscriptions().put(filter, new MqttSubscription(filter, reqQos));
                subscriberIndex.add(session, filter, reqQos);
            } else {
                returnCodes.add(0x80); // 拒绝
                log.warn("[ACL] 拒绝越权订阅 deviceKey={} filter={}", session.getDeviceKey(), filter);
            }
        }
        sendSubAck(ctx.channel(), packetId, returnCodes);

        if (!session.isCleanSession()) {
            persistSubscriptions(session);
        }
        // 保留消息投递（SUBACK 之后再发，符合 MQTT 顺序语义）
        for (String filter : allowedFilters) {
            deliverer.deliverRetainedOnSubscribe(session, filter);
        }
    }

    private void handleUnsubscribe(ChannelHandlerContext ctx, MqttUnsubscribeMessage msg) {
        Session session = ctx.channel().attr(SESSION_ATTR).get();
        if (session == null) {
            ctx.close();
            return;
        }
        int packetId = msg.variableHeader().messageId();
        for (String filter : msg.payload().topics()) {
            session.getSubscriptions().remove(filter);
            subscriberIndex.remove(session.getDeviceKey(), filter);
        }
        sendUnsubAck(ctx.channel(), packetId);
        if (!session.isCleanSession()) {
            persistSubscriptions(session);
        }
    }

    private void persistSubscriptions(Session session) {
        executor.execute(() -> {
            try {
                sessionStore.saveSubscriptions(session.getDeviceKey(),
                        new HashSet<>(session.getSubscriptions().values()));
            } catch (Exception e) {
                log.error("[Broker] 订阅持久化失败 deviceKey={}", session.getDeviceKey(), e);
            }
        });
    }

    // ---------------- PINGREQ / DISCONNECT ----------------

    private void handlePingReq(ChannelHandlerContext ctx) {
        Session session = ctx.channel().attr(SESSION_ATTR).get();
        if (session == null) {
            ctx.close();
            return;
        }
        session.touch();
        maybeRenewOnline(session, attrCredential(session));
        ctx.channel().writeAndFlush(new MqttMessage(
                new MqttFixedHeader(MqttMessageType.PINGRESP, false, MqttQoS.AT_MOST_ONCE, false, 0)));
    }

    private void handleDisconnect(ChannelHandlerContext ctx, MqttMessage msg) {
        Session session = ctx.channel().attr(SESSION_ATTR).get();
        if (session != null) {
            session.setDisconnectedGracefully(true);
        }
        ctx.close(); // 优雅断开：channelInactive 执行持久化与离线事件
    }

    // ---------------- 工具 ----------------

    private void maybeRenewOnline(Session session, DeviceCredential cred) {
        if (cred != null && session.shouldRenewOnline()) {
            executor.execute(() -> lifecycleNotifier.renewOnline(cred));
        }
    }

    private void asyncRemoveInflight(String deviceKey, int packetId) {
        executor.execute(() -> sessionStore.removeInflight(deviceKey, packetId));
    }

    private void asyncSaveInflight(String deviceKey, InflightMessage inflight) {
        executor.execute(() -> sessionStore.saveInflight(deviceKey, inflight));
    }

    private void replaceIdleHandler(Channel channel, int keepAliveSeconds) {
        int readerIdle = Math.max(1, (int) Math.ceil(keepAliveSeconds * 1.5));
        ChannelPipeline pipeline = channel.pipeline();
        IdleStateHandler idle = new IdleStateHandler(readerIdle, 0, 0);
        if (pipeline.get(NettyServerConfig.IDLE_HANDLER_NAME) != null) {
            pipeline.replace(NettyServerConfig.IDLE_HANDLER_NAME, NettyServerConfig.IDLE_HANDLER_NAME, idle);
        } else {
            pipeline.addBefore(NettyServerConfig.MQTT_HANDLER_NAME, NettyServerConfig.IDLE_HANDLER_NAME, idle);
        }
        log.debug("[Broker] keepalive={}s → idle 阈值 {}s", keepAliveSeconds, readerIdle);
    }

    private void sendConnAck(Channel channel, MqttConnectReturnCode code, boolean sessionPresent) {
        channel.writeAndFlush(new MqttConnAckMessage(
                new MqttFixedHeader(MqttMessageType.CONNACK, false, MqttQoS.AT_MOST_ONCE, false, 0),
                new MqttConnAckVariableHeader(code, sessionPresent)));
    }

    /**
     * v5 CONNACK：携带服务端能力声明（Maximum QoS=2、Retain Available=1，见 v5 属性协商）
     */
    private void sendConnAckV5(Channel channel, MqttConnectReturnCode code, boolean sessionPresent) {
        MqttProperties props = new MqttProperties();
        props.add(new MqttProperties.IntegerProperty(
                MqttProperties.MqttPropertyType.MAXIMUM_QOS.value(), 2));
        props.add(new MqttProperties.IntegerProperty(
                MqttProperties.MqttPropertyType.RETAIN_AVAILABLE.value(), 1));
        channel.writeAndFlush(new MqttConnAckMessage(
                new MqttFixedHeader(MqttMessageType.CONNACK, false, MqttQoS.AT_MOST_ONCE, false, 0),
                new MqttConnAckVariableHeader(code, sessionPresent, props)));
    }

    private void sendPubAck(Channel channel, int packetId) {
        channel.writeAndFlush(new MqttPubAckMessage(
                new MqttFixedHeader(MqttMessageType.PUBACK, false, MqttQoS.AT_MOST_ONCE, false, 0),
                MqttMessageIdVariableHeader.from(packetId)));
    }

    private void sendPubRec(Channel channel, int packetId) {
        channel.writeAndFlush(new MqttMessage(
                new MqttFixedHeader(MqttMessageType.PUBREC, false, MqttQoS.AT_MOST_ONCE, false, 0),
                MqttMessageIdVariableHeader.from(packetId)));
    }

    private void sendPubRel(Channel channel, int packetId) {
        channel.writeAndFlush(new MqttMessage(
                new MqttFixedHeader(MqttMessageType.PUBREL, false, MqttQoS.AT_LEAST_ONCE, false, 0),
                MqttMessageIdVariableHeader.from(packetId)));
    }

    private void sendPubComp(Channel channel, int packetId) {
        channel.writeAndFlush(new MqttMessage(
                new MqttFixedHeader(MqttMessageType.PUBCOMP, false, MqttQoS.AT_MOST_ONCE, false, 0),
                MqttMessageIdVariableHeader.from(packetId)));
    }

    private void sendSubAck(Channel channel, int packetId, List<Integer> codes) {
        channel.writeAndFlush(new MqttSubAckMessage(
                new MqttFixedHeader(MqttMessageType.SUBACK, false, MqttQoS.AT_MOST_ONCE, false, 0),
                MqttMessageIdVariableHeader.from(packetId),
                new MqttSubAckPayload(codes)));
    }

    private void sendUnsubAck(Channel channel, int packetId) {
        // Netty 无 MqttUnsubAckMessage 类，UNSUBACK = MqttMessage + messageId
        channel.writeAndFlush(new MqttMessage(
                new MqttFixedHeader(MqttMessageType.UNSUBACK, false, MqttQoS.AT_MOST_ONCE, false, 0),
                MqttMessageIdVariableHeader.from(packetId)));
    }

    /**
     * 提取报文 packetId（v3/v5 统一：各 VariableHeader 均继承 MqttMessageIdVariableHeader）
     */
    private int messageId(MqttMessage msg) {
        Object vh = msg.variableHeader();
        if (vh instanceof MqttMessageIdVariableHeader h) {
            return h.messageId();
        }
        throw new IllegalArgumentException("无法解析 packetId: " + msg.fixedHeader().messageType());
    }

    private MqttConnectReturnCode returnCode(int code) {
        return switch (code) {
            case 1 -> MqttConnectReturnCode.CONNECTION_REFUSED_UNACCEPTABLE_PROTOCOL_VERSION;
            case 2 -> MqttConnectReturnCode.CONNECTION_REFUSED_IDENTIFIER_REJECTED;
            case 3 -> MqttConnectReturnCode.CONNECTION_REFUSED_SERVER_UNAVAILABLE;
            case 5 -> MqttConnectReturnCode.CONNECTION_REFUSED_NOT_AUTHORIZED;
            default -> MqttConnectReturnCode.CONNECTION_REFUSED_BAD_USER_NAME_OR_PASSWORD;
        };
    }

    private DeviceCredential attrCredential(Session session) {
        Object deviceId = session.attr(Session.SessionAttr.DEVICE_ID);
        if (deviceId == null) {
            return null;
        }
        return new DeviceCredential(
                session.getDeviceKey(),
                (Long) deviceId,
                (Long) session.attr(Session.SessionAttr.TENANT_ID),
                (String) session.attr(Session.SessionAttr.PRODUCT_KEY),
                (String) session.attr(Session.SessionAttr.DEVICE_NAME),
                (Integer) session.attr(Session.SessionAttr.DEVICE_STATUS),
                1, "", false);
    }

    private String remoteIp(Channel channel) {
        if (channel.remoteAddress() instanceof InetSocketAddress addr) {
            return addr.getAddress().getHostAddress();
        }
        return null;
    }

    /**
     * CONNECT 解析结果（认证回调使用，跨线程传递）
     */
    private record ConnectParams(int version, String clientId, int keepAliveSeconds,
                                 boolean cleanSession, String username, String password,
                                 Session.MqttWill will, String remoteIp,
                                 long sessionExpirySeconds, int receiveMaximum, int maxPacketSize) {
    }
}
