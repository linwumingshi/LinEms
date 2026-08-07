package com.energyx.access.publish;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.energyx.access.config.AccessProperties;
import com.energyx.access.mqtt.AccessKafkaProducer;
import com.energyx.common.constant.KafkaTopicConstant;
import com.energyx.common.message.CommandAckMessage;
import com.energyx.common.message.CommandDownMessage;
import com.energyx.common.message.LifecycleMessage;
import com.energyx.common.message.RawMessage;
import com.energyx.common.message.ThingEventMessage;
import com.energyx.common.message.ThingPropertyMessage;
import com.energyx.common.mqtt.MqttTopicUtil;
import com.energyx.common.mqtt.RouterEnvelope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 标准化消息出口（唯一入口点，统一序列化与发送）。
 *
 * <p>Key 策略对齐 Phase 1 §7.2 顺序保证：
 * <ul>
 *   <li>property/event/lifecycle 以 deviceId 为 key ⇒ 单设备有序、按设备分区；</li>
 *   <li>ack 以 commandId 为 key ⇒ 指令 ACK 与指令记录同分区保序；</li>
 *   <li>raw 以 messageId 为 key；下行桥接信封以 topic 为 key（与 Broker 路由分区规则一致）。</li>
 * </ul></p>
 */
@Slf4j
@Component
public class EventPublisher {

    private final AccessKafkaProducer producer;
    private final AccessProperties props;
    private final ObjectMapper objectMapper;

    public EventPublisher(AccessKafkaProducer producer, AccessProperties props, ObjectMapper objectMapper) {
        this.producer = producer;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    public void publishRaw(RawMessage m) {
        send(KafkaTopicConstant.IOT_RAW, m.getMessageId(), m);
    }

    public void publishProperty(ThingPropertyMessage m) {
        send(KafkaTopicConstant.IOT_THING_PROPERTY, String.valueOf(m.getDeviceId()), m);
    }

    public void publishEvent(ThingEventMessage m) {
        send(KafkaTopicConstant.IOT_THING_EVENT, String.valueOf(m.getDeviceId()), m);
    }

    public void publishAck(CommandAckMessage m) {
        send(KafkaTopicConstant.IOT_COMMAND_ACK, m.getCommandId(), m);
    }

    public void publishLifecycle(LifecycleMessage m) {
        send(KafkaTopicConstant.IOT_DEVICE_LIFECYCLE, String.valueOf(m.getDeviceId()), m);
    }

    public void publishCommandDown(CommandDownMessage m) {
        send(KafkaTopicConstant.IOT_COMMAND_DOWN, String.valueOf(m.getDeviceId()), m);
    }

    /**
     * 平台下行：把 CommandDownMessage 桥接为 mqtt.router PUBLISH 信封。
     *
     * <p>Broker 每节点唯一消费组全量 fan-out 该信封，仅设备所在节点存在 down/# 订阅，
     * 由该节点按 QoS 投递给设备——复用既有跨节点路由通道，access 无需直连 Broker。</p>
     */
    public void publishRouterDown(CommandDownMessage m) {
        String topic = MqttTopicUtil.downCommandTopic(m.getProductKey(), m.getDeviceName());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandId", m.getCommandId());
        body.put("command", m.getCommand());
        body.put("params", m.getParams() == null ? Collections.emptyMap() : m.getParams());
        body.put("ts", m.getTs() == null ? System.currentTimeMillis() : m.getTs());
        try {
            byte[] payload = objectMapper.writeValueAsBytes(body);
            RouterEnvelope env = RouterEnvelope.publish(
                    props.getNodeId(), topic, payload, m.getQos() == null ? 1 : m.getQos(), false);
            send(KafkaTopicConstant.MQTT_ROUTER, topic, env);
        } catch (Exception e) {
            log.error("[Access] 下行信封序列化失败 commandId={}", m.getCommandId(), e);
        }
    }

    private void send(String topic, String key, Object value) {
        try {
            producer.send(topic, key, objectMapper.writeValueAsString(value));
        } catch (Exception e) {
            log.error("[Access] 消息序列化失败 topic={} key={}", topic, key, e);
        }
    }
}
