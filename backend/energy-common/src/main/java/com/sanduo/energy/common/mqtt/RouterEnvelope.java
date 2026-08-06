package com.sanduo.energy.common.mqtt;

import lombok.Data;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 跨节点路由信封（Kafka mqtt.router 消息体，JSON 序列化）。
 *
 * <p>该信封是「Broker 集群 ↔ 接入适配」之间的跨模块契约，因此沉淀在 common：
 * <ul>
 *   <li>Broker 侧：设备上行 PUBLISH 跨节点 fan-out、同 clientId 远端踢线（KICK）；</li>
 *   <li>接入适配侧：消费 mqtt.router 解析设备上行；平台下行指令也以 PUBLISH 信封
 *       写回 mqtt.router，经 Broker 全节点 fan-out 投递给订阅 down/* 的设备。</li>
 * </ul></p>
 *
 * <p>消费组约定（Phase 1 §4.7）：Broker 每节点唯一消费组 `mqtt-router-{nodeId}` 实现全量
 * fan-out 并按 sourceNode 去重；接入适配以独立消费组（energy-access-uplink）获取「恰好一次」副本。</p>
 */
@Data
public class RouterEnvelope {

    /** PUBLISH | KICK */
    private String type;

    /** 源节点 ID（必填：Broker 用 nodeId 去重；access 用 nodeId 标识下行来源） */
    private String sourceNode;

    private String topic;

    /** 报文 base64（payload 是二进制，JSON 需编码） */
    private String payloadBase64;

    private int qos;

    private boolean retain;

    /** KICK 目标 deviceKey；PUBLISH 时为空 */
    private String deviceKey;

    /** 毫秒时间戳（诊断/排序） */
    private long ts;

    public static final String TYPE_PUBLISH = "PUBLISH";
    public static final String TYPE_KICK = "KICK";

    public static RouterEnvelope publish(String sourceNode, String topic, byte[] payload,
                                         int qos, boolean retain) {
        RouterEnvelope e = new RouterEnvelope();
        e.setType(TYPE_PUBLISH);
        e.setSourceNode(sourceNode);
        e.setTopic(topic);
        e.setPayloadBase64(Base64.getEncoder().encodeToString(payload));
        e.setQos(qos);
        e.setRetain(retain);
        e.setTs(System.currentTimeMillis());
        return e;
    }

    public static RouterEnvelope kick(String sourceNode, String deviceKey) {
        RouterEnvelope e = new RouterEnvelope();
        e.setType(TYPE_KICK);
        e.setSourceNode(sourceNode);
        e.setDeviceKey(deviceKey);
        e.setTs(System.currentTimeMillis());
        return e;
    }

    /** 解码 payload；空/非法 base64 返回空数组 */
    public byte[] decodePayload() {
        if (payloadBase64 == null || payloadBase64.isEmpty()) {
            return new byte[0];
        }
        try {
            return Base64.getDecoder().decode(payloadBase64);
        } catch (IllegalArgumentException e) {
            return new byte[0];
        }
    }

    public String decodePayloadAsText() {
        return new String(decodePayload(), StandardCharsets.UTF_8);
    }
}
