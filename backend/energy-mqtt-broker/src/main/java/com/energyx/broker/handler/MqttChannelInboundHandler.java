package com.energyx.broker.handler;

import com.energyx.broker.auth.AuthResult;
import com.energyx.broker.auth.DeviceAuthService;
import com.energyx.broker.auth.DeviceCredential;
import com.energyx.broker.auth.TopicAcl;
import com.energyx.broker.config.BrokerProperties;
import com.energyx.broker.config.NettyServerConfig;
import com.energyx.broker.lifecycle.LifecycleNotifier;
import com.energyx.broker.mqtt.KafkaEventProducer;
import com.energyx.broker.routing.LocalSubscriberIndex;
import com.energyx.broker.routing.MessageDeliverer;
import com.energyx.common.mqtt.RouterEnvelope;
import com.energyx.broker.session.InboundPublish;
import com.energyx.broker.session.InflightMessage;
import com.energyx.broker.session.MqttSubscription;
import com.energyx.broker.session.Session;
import com.energyx.broker.session.SessionRegistry;
import com.energyx.broker.session.SessionStore;
import com.energyx.broker.stats.BrokerStats;
import com.energyx.broker.util.BrokerKeys;
import com.energyx.common.constant.KafkaTopicConstant;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.mqtt.MqttConnectMessage;
import io.netty.handler.codec.mqtt.MqttConnectReturnCode;
import io.netty.handler.codec.mqtt.MqttConnectVariableHeader;
import io.netty.handler.codec.mqtt.MqttFixedHeader;
import io.netty.handler.codec.mqtt.MqttMessage;
import io.netty.handler.codec.mqtt.MqttMessageIdVariableHeader;
import io.netty.handler.codec.mqtt.MqttMessageType;
import io.netty.handler.codec.mqtt.MqttPubAckMessage;
import io.netty.handler.codec.mqtt.MqttPublishMessage;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.netty.handler.codec.mqtt.MqttSubAckMessage;
import io.netty.handler.codec.mqtt.MqttSubAckPayload;
import io.netty.handler.codec.mqtt.MqttSubscribeMessage;
import io.netty.handler.codec.mqtt.MqttTopicSubscription;
import io.netty.handler.codec.mqtt.MqttUnsubscribeMessage;
import io.netty.handler.codec.mqtt.MqttConnAckMessage;
import io.netty.handler.codec.mqtt.MqttConnAckVariableHeader;
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
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;
    private final AtomicInteger rawConnections = new AtomicInteger();

    public MqttChannelInboundHandler(DeviceAuthService authService,
                                     SessionRegistry sessionRegistry,
                                     SessionStore sessionStore,
                                     LocalSubscriberIndex subscriberIndex,
                                     MessageDeliverer deliverer,
                                     LifecycleNotifier lifecycleNotifier,
                                     KafkaEventProducer kafkaProducer,
                                     BrokerProperties properties,
                                     BrokerStats stats,
                                     ObjectMapper objectMapper,
                                     @Qualifier("brokerExecutor") ExecutorService executor) {
        this.authService = authService;
        this.sessionRegistry = sessionRegistry;
        this.sessionStore = sessionStore;
        this.subscriberIndex = subscriberIndex;
        this.deliverer = deliverer;
        this.lifecycleNotifier = lifecycleNotifier;
        this.kafkaProducer = kafkaProducer;
        this.properties = properties;
        this.stats = stats;
        this.objectMapper = objectMapper;
        this.executor = executor;
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
        if (session.isCleanSession()) {
            subscriberIndex.removeAll(deviceKey);
            executor.execute(() -> {
                sessionStore.deleteSession(deviceKey);
                sessionStore.deleteSubscriptions(deviceKey);
                sessionStore.deleteInflight(deviceKey);
                sessionStore.releaseConnLockIfOwner(deviceKey, properties.getNodeId());
            });
        } else {
            // 持久会话：幽灵订阅保留（支撑离线队列），持久化会话元数据 + 订阅
            executor.execute(() -> {
                try {
                    sessionStore.saveSession(deviceKey, properties.getNodeId(), false);
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
            // 遗嘱投递（非优雅断开，delay=0 立即投递；延迟遗嘱为 Phase 6 增强）
            // deliver 内部全内存操作 + Kafka 异步发送 + Redis 异步，可在 eventLoop 直接调用
            Session.MqttWill will = session.getWill();
            if (!graceful && will != null && will.getDelaySeconds() == 0) {
                deliverer.deliver(will.getTopic(), will.getPayload(), will.getQos(), will.isRetain(), null);
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

    /** channel 恢复可写：冲刷该会话的背压挂起队列（在 eventLoop 上触发） */
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

        // 遗嘱（v3 从 payload 提取；v5 will delay 读取属性，0 则立即投递）
        Session.MqttWill will = null;
        if (vh.isWillFlag()) {
            String willTopic = msg.payload().willTopic();
            String willMessage = msg.payload().willMessage();
            if (willTopic != null && !willTopic.isEmpty()) {
                will = new Session.MqttWill(willTopic,
                        willMessage == null ? new byte[0] : willMessage.getBytes(StandardCharsets.UTF_8),
                        vh.willQos(), vh.isWillRetain(), 0);
            }
        }

        ConnectParams params = new ConnectParams(version, clientId,
                vh.keepAliveTimeSeconds(), vh.isCleanSession(),
                username, password, will, remoteIp(channel));

        // 慢路径全部在业务线程：认证（Redis/MySQL）→ 连接锁（Redis）→ sessionPresent 判定（Redis），
        // 仅最终会话注册与 CONNACK 回投 eventLoop（IO 线程零阻塞）
        executor.execute(() -> {
            AuthResult result;
            try {
                result = authService.authenticate(clientId, username, password);
            } catch (Exception e) {
                log.error("[Auth] 认证异常 clientId={}", clientId, e);
                result = AuthResult.deny(3, false, "认证服务异常");
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

        sendConnAck(channel, MqttConnectReturnCode.CONNECTION_ACCEPTED, sessionPresent);
        stats.recordAccepted();
        log.info("[Broker] 设备上线 deviceKey={} version={} remote={}", deviceKey, p.version, p.remoteIp);

        // 异步恢复 + 上线通知：Redis/Kafka 阻塞操作全部走业务线程池
        executor.execute(() -> {
            try {
                if (p.cleanSession) {
                    sessionStore.deleteSession(deviceKey);
                    sessionStore.deleteSubscriptions(deviceKey);
                    sessionStore.deleteInflight(deviceKey);
                } else {
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
        });
    }

    private void kickRemote(String ownerNode, String deviceKey) {
        try {
            RouterEnvelope envelope = RouterEnvelope.kick(properties.getNodeId(), deviceKey);
            kafkaProducer.send(KafkaTopicConstant.MQTT_ROUTER, deviceKey,
                    objectMapper.writeValueAsString(envelope));
        } catch (Exception e) {
            log.warn("[Broker] 远程踢线信封发送失败 deviceKey={} owner={}", deviceKey, ownerNode, e);
        }
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
        Channel channel = ctx.channel();
        switch (qos) {
            case 0 -> deliverer.deliver(topic, payload, 0, retain, null);
            case 1 ->
                // PUBACK 推迟到 Kafka 路由持久化确认之后；路由失败关连接，设备重连重传（at-least-once）
                    deliverer.deliver(topic, payload, 1, retain, null,
                            () -> channel.eventLoop().execute(() -> {
                                if (channel.isActive()) {
                                    sendPubAck(channel, packetId);
                                }
                            }),
                            () -> channel.eventLoop().execute(() -> {
                                log.error("[Deliver] 路由失败关连接迫使重传 deviceKey={} topic={}",
                                        session.getDeviceKey(), topic);
                                channel.close();
                            }));
            case 2 -> {
                // 收到 PUBREL 才路由（恰好一次入站）
                session.getInboundQos2().put(packetId, new InboundPublish(topic, payload, qos));
                sendPubRec(channel, packetId);
            }
            default -> log.warn("[Broker] 非法 QoS={} deviceKey={}", qos, session.getDeviceKey());
        }
        stats.recordIncoming();
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

    /** 提取报文 packetId（v3/v5 统一：各 VariableHeader 均继承 MqttMessageIdVariableHeader） */
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

    /** CONNECT 解析结果（认证回调使用，跨线程传递） */
    private record ConnectParams(int version, String clientId, int keepAliveSeconds,
                                 boolean cleanSession, String username, String password,
                                 Session.MqttWill will, String remoteIp) {
    }
}
