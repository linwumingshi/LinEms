package com.sanduo.energy.common.mqtt;

/**
 * 上行 Topic 解析结果：`{productKey}/{deviceName}/up/{type}`。
 *
 * @param productKey 产品标识（认证与路由锚点）
 * @param deviceName 设备名
 * @param upType     上行报文类型
 */
public record MqttTopicInfo(String productKey, String deviceName, MqttUpType upType) {
}
