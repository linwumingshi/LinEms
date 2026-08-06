package com.sanduo.energy.broker.routing;

import com.sanduo.energy.broker.config.BrokerProperties;
import com.sanduo.energy.broker.mqtt.KafkaEventProducer;
import com.sanduo.energy.broker.retained.RetainedMessageStore;
import com.sanduo.energy.broker.session.InflightMessage;
import com.sanduo.energy.broker.session.MqttSubscription;
import com.sanduo.energy.broker.session.OfflineMessage;
import com.sanduo.energy.broker.session.Session;
import com.sanduo.energy.broker.session.SessionStore;
import com.sanduo.energy.broker.stats.BrokerStats;
import com.sanduo.energy.common.constant.KafkaTopicConstant;
import com.sanduo.energy.common.mqtt.RouterEnvelope;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import com.sanduo.energy.broker.session.MqttSubscription;
import io.netty.handler.codec.mqtt.MqttFixedHeader;
import io.netty.handler.codec.mqtt.MqttMessage;
import io.netty.handler.codec.mqtt.MqttMessageIdVariableHeader;
import io.netty.handler.codec.mqtt.MqttMessageType;
import io.netty.handler.codec.mqtt.MqttProperties;
import io.netty.handler.codec.mqtt.MqttPublishMessage;
import io.netty.handler.codec.mqtt.MqttPublishVariableHeader;
import io.netty.handler.codec.mqtt.MqttQoS;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * 消息投递核心：本地投递 + 跨节点路由 + QoS 状态机 + 保留消息 + 离线队列。
 *
 * <p>投递路径：
 * <pre>
 * 设备 PUBLISH(ACL 通过) ──▶ deliverLocal（本节点订阅者）
 *                        └─▶ mqtt.router（key=topic）──▶ 其他节点 RouterConsumer ──▶ deliverLocal
 * </pre>
 * 跨节点投递为「至少一次」（QoS0 无重试，QoS1/2 依赖订阅端会话状态机续传），
 * 上游去重由 Phase 5 按 deviceId+上报序号处理。</p>
 *
 * <p>线程安全：deliverToSession 可被 IO 线程与 Router 消费线程并发调用，
 * Netty channel.writeAndFlush 线程安全；Redis in-flight 持久化经 brokerExecutor 异步执行。</p>
 */
@Slf4j
@Component
public class MessageDeliverer {

    private final LocalSubscriberIndex subscriberIndex;
    private final SessionStore sessionStore;
    private final KafkaEventProducer kafkaProducer;
    private final RetainedMessageStore retainedStore;
    private final BrokerProperties properties;
    private final BrokerStats stats;
    private final ExecutorService executor;

    public MessageDeliverer(LocalSubscriberIndex subscriberIndex,
                            SessionStore sessionStore,
                            KafkaEventProducer kafkaProducer,
                            RetainedMessageStore retainedStore,
                            BrokerProperties properties,
                            BrokerStats stats,
                            ExecutorService brokerExecutor) {
        this.subscriberIndex = subscriberIndex;
        this.sessionStore = sessionStore;
        this.kafkaProducer = kafkaProducer;
        this.retainedStore = retainedStore;
        this.properties = properties;
        this.stats = stats;
        this.executor = brokerExecutor;
    }

    /**
     * 设备上行报文入口（ACL 已在 handler 校验）。
     *
     * @param sourceNode 路由源节点；null 表示本节点发起（需要发路由），否则为远端已路由消息
     */
    public void deliver(String topic, byte[] payload, int qos, boolean retain, String sourceNode) {
        // 保留消息更新（无论 QoS，retain 位生效）
        if (retain) {
            retainedStore.put(topic, payload, qos);
        }
        // 本地投递
        deliverLocal(topic, payload, qos, retain);
        // 跨节点路由（仅本节点发起时）
        if (sourceNode == null && properties.isEnableRouter() && kafkaProducer.isEnabled()) {
            String envelope = toJson(RouterEnvelope.publish(properties.getNodeId(), topic, payload, qos, retain));
            kafkaProducer.send(KafkaTopicConstant.MQTT_ROUTER, topic, envelope);
            stats.recordCrossNode();
        }
    }

    /** 本地投递：在线会话即时下发，持久离线会话进离线队列 */
    private void deliverLocal(String topic, byte[] payload, int qos, boolean retain) {
        List<LocalSubscriberIndex.SubscriberMatch> matches = subscriberIndex.match(topic);
        for (LocalSubscriberIndex.SubscriberMatch m : matches) {
            Session session = m.session();
            int effQos = Math.min(qos, m.qos());
            if (session.isOnline()) {
                deliverToSession(session, topic, payload, effQos, retain);
            } else if (!session.isCleanSession()) {
                // 持久会话离线：进离线队列（容量/ TTL 由 SessionStore 兜底）
                sessionStore.pushOffline(session.getDeviceKey(), new OfflineMessage(topic, payload, qos));
                log.debug("[Deliver] 持久会话离线入队 deviceKey={} topic={}", session.getDeviceKey(), topic);
            }
        }
    }

    /** 向单会话投递（QoS 状态机 + Redis in-flight 异步持久化） */
    public void deliverToSession(Session session, String topic, byte[] payload, int effQos, boolean retain) {
        if (!session.isOnline()) {
            return;
        }
        Channel channel = session.getChannel();
        if (effQos == MqttQoS.AT_MOST_ONCE.value()) {
            writeToChannel(channel, buildPublish(topic, payload, effQos, retain, 0));
            stats.recordOutgoing();
            return;
        }
        // QoS1/2：分配 packetId + 记录 in-flight（重连续传依据）
        int packetId = session.allocPacketId();
        if (packetId < 0) {
            log.warn("[Deliver] 会话 in-flight 已满，丢弃 QoS>0 消息 deviceKey={} topic={}",
                    session.getDeviceKey(), topic);
            return;
        }
        int state = effQos == 1
                ? InflightMessage.STATE_AWAITING_PUBACK
                : InflightMessage.STATE_AWAITING_PUBREC;
        InflightMessage inflight = new InflightMessage(packetId, topic, payload, effQos, retain, state,
                System.nanoTime());
        session.getOutboundInflight().put(packetId, inflight);
        // 异步持久化 in-flight（不阻塞 IO 线程）
        String deviceKey = session.getDeviceKey();
        executor.execute(() -> {
            try {
                sessionStore.saveInflight(deviceKey, inflight);
            } catch (Exception e) {
                log.warn("[Deliver] in-flight 持久化失败 deviceKey={}", deviceKey, e);
            }
        });
        writeToChannel(channel, buildPublish(topic, payload, effQos, retain, packetId));
        stats.recordOutgoing();
    }

    /**
     * 统一写出入口：已在 event loop 直接写，否则提交到 event loop，保证同连接写序。
     * Netty 跨线程 writeAndFlush 线程安全但顺序不保证，QoS 状态机依赖 ACK 顺序，必须收敛到单线程。
     */
    private void writeToChannel(Channel channel, io.netty.handler.codec.mqtt.MqttMessage message) {
        if (channel.eventLoop().inEventLoop()) {
            channel.writeAndFlush(message);
        } else {
            channel.eventLoop().execute(() -> channel.writeAndFlush(message));
        }
    }

    private MqttPublishMessage buildPublish(String topic, byte[] payload, int qos, boolean retain, int packetId) {
        MqttQoS mqttQos = switch (qos) {
            case 1 -> MqttQoS.AT_LEAST_ONCE;
            case 2 -> MqttQoS.EXACTLY_ONCE;
            default -> MqttQoS.AT_MOST_ONCE;
        };
        MqttFixedHeader header = new MqttFixedHeader(MqttMessageType.PUBLISH, false, mqttQos, retain, 0);
        // 空 MqttProperties 对 v3.1.1 与 v5 均合法
        MqttPublishVariableHeader variableHeader =
                new MqttPublishVariableHeader(topic, packetId, MqttProperties.NO_PROPERTIES);
        return new MqttPublishMessage(header, variableHeader, Unpooled.wrappedBuffer(payload));
    }

    /** 新订阅：投递匹配的保留消息 */
    public void deliverRetainedOnSubscribe(Session session, String topicFilter) {
        List<RetainedMessageStore.RetainedEntry> entries = retainedStore.match(topicFilter);
        for (RetainedMessageStore.RetainedEntry entry : entries) {
            int effQos = Math.min(entry.getQos(), sessionSubscriptionQos(session, topicFilter));
            deliverToSession(session, entry.getTopic(), entry.payload(), effQos, true);
        }
    }

    private int sessionSubscriptionQos(Session session, String topicFilter) {
        MqttSubscription sub = session.getSubscriptions().get(topicFilter);
        return sub == null ? 0 : sub.getQos();
    }

    /** 平台→设备下行指令（Phase 6 command 模块调用；对指定设备单发，不走通配订阅） */
    public void sendCommandToDevice(Session session, String topic, byte[] payload, int qos) {
        deliverToSession(session, topic, payload, qos, false);
    }

    /** 重连续传：恢复 outbound in-flight（QoS1 重发 dup PUBLISH，QoS2 发 PUBREL） */
    public void resendInflight(Session session) {
        List<InflightMessage> pending = sessionStore.loadInflight(session.getDeviceKey());
        for (InflightMessage msg : pending) {
            if (msg.getState() == InflightMessage.STATE_AWAITING_PUBACK
                    || msg.getState() == InflightMessage.STATE_AWAITING_PUBREC) {
                // 重新分配 packetId（原 id 已失效），QoS2 重发 PUBLISH dup
                int newId = session.allocPacketId();
                msg.setPacketId(newId);
                msg.setState(InflightMessage.STATE_AWAITING_PUBACK);
                session.getOutboundInflight().put(newId, msg);
                MqttQoS mqttQos = msg.getQos() == 2 ? MqttQoS.EXACTLY_ONCE : MqttQoS.AT_LEAST_ONCE;
                MqttFixedHeader header = new MqttFixedHeader(MqttMessageType.PUBLISH, true, mqttQos, msg.isRetain(), 0);
                MqttPublishVariableHeader vh = new MqttPublishVariableHeader(msg.getTopic(), newId, MqttProperties.NO_PROPERTIES);
                writeToChannel(session.getChannel(),
                        new MqttPublishMessage(header, vh, Unpooled.wrappedBuffer(msg.getPayload())));
            } else {
                // STATE_AWAITING_PUBCOMP：重发 PUBREL
                writeToChannel(session.getChannel(),
                        new MqttMessage(
                                new MqttFixedHeader(MqttMessageType.PUBREL, false, MqttQoS.AT_LEAST_ONCE, false, 0),
                                MqttMessageIdVariableHeader.from(msg.getPacketId())));
            }
        }
        sessionStore.deleteInflight(session.getDeviceKey());
    }

    /** 重连续传后的离线队列补发（持久会话上线时调用） */
    public void deliverOfflineQueue(Session session) {
        List<OfflineMessage> queue = sessionStore.popOffline(session.getDeviceKey());
        for (OfflineMessage m : queue) {
            MqttSubscription sub = session.getSubscriptions().get(m.getTopic());
            if (sub == null) {
                continue; // 订阅已变化，跳过
            }
            int effQos = Math.min(m.getQos(), sub.getQos());
            deliverToSession(session, m.getTopic(), m.payload(), effQos, false);
        }
    }

    private String toJson(Object value) {
        try {
            return sessionStore.objectMapper().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("信封序列化失败", e);
        }
    }
}
