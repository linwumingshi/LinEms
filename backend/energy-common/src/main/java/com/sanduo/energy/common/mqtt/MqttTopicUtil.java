package com.sanduo.energy.common.mqtt;

/**
 * MQTT Topic 约定（Phase1 §6）：
 * 上行 {productKey}/{deviceName}/up/{type}   type ∈ property | event | lifecycle | ack
 * 下行 {productKey}/{deviceName}/down/command
 */
public final class MqttTopicUtil {

    private MqttTopicUtil() {
    }

    /** 设备上行 Topic */
    public static String upTopic(String productKey, String deviceName, String type) {
        return productKey + "/" + deviceName + "/up/" + type;
    }

    /** 平台下行指令 Topic */
    public static String downCommandTopic(String productKey, String deviceName) {
        return productKey + "/" + deviceName + "/down/command";
    }

    /** 设备属性上报 Topic */
    public static String upPropertyTopic(String productKey, String deviceName) {
        return upTopic(productKey, deviceName, "property");
    }

    /** 设备事件 Topic */
    public static String upEventTopic(String productKey, String deviceName) {
        return upTopic(productKey, deviceName, "event");
    }

    /** 设备 ACK Topic */
    public static String upAckTopic(String productKey, String deviceName) {
        return upTopic(productKey, deviceName, "ack");
    }

    /** clientId 规范：{productKey}_{deviceName} */
    public static String buildClientId(String productKey, String deviceName) {
        return productKey + "_" + deviceName;
    }

    /** deviceKey 别名：与 clientId 同构，作 Redis/路由实体键 */
    public static String buildDeviceKey(String productKey, String deviceName) {
        return buildClientId(productKey, deviceName);
    }

    /**
     * 解析上行 Topic 为结构化信息。
     *
     * <p>仅识别 `{pk}/{dn}/up/{type}` 四段格式且 type ∈ property|event|lifecycle|ack；
     * 下行（down/*）、非法段数、未知 type 一律返回 null，调用方按「非设备上行」处理。</p>
     */
    public static MqttTopicInfo parseUpTopic(String topic) {
        if (topic == null || topic.isBlank()) {
            return null;
        }
        String[] parts = topic.split("/");
        if (parts.length != 4 || !"up".equals(parts[2])) {
            return null;
        }
        MqttUpType type = MqttUpType.from(parts[3]);
        if (type == null) {
            return null;
        }
        return new MqttTopicInfo(parts[0], parts[1], type);
    }
}
