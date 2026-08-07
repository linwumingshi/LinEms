package com.energyx.device;

import java.util.Objects;

/**
 * 设备三元组身份：productKey + deviceName + deviceSecret。
 *
 * <p>派生自平台设备凭据（iot_device + iot_device_credential），与 Broker 端
 * TopicAcl / 凭据缓存的命名规则完全一致：</p>
 * <ul>
 *   <li>clientId = productKey + "_" + deviceName；productKey 允许包含 '_'（如平台锚点 snd_ess_pcs），
 *       deviceName 禁止 '_'（Broker 按最后一个 '_' 拆分，见 DeviceAuthService）</li>
 *   <li>上行属性：{pk}/{dn}/up/property</li>
 *   <li>上行事件：{pk}/{dn}/up/event</li>
 *   <li>上行生命周期：{pk}/{dn}/up/lifecycle</li>
 *   <li>上行指令 ACK：{pk}/{dn}/up/ack</li>
 *   <li>下行指令订阅：{pk}/{dn}/down/command</li>
 * </ul>
 */
public record DeviceIdentity(
        String productKey,
        String deviceName,
        String deviceSecret) {

    public DeviceIdentity {
        Objects.requireNonNull(productKey, "productKey");
        Objects.requireNonNull(deviceName, "deviceName");
        Objects.requireNonNull(deviceSecret, "deviceSecret");
        if (productKey.isEmpty() || deviceName.isEmpty()) {
            throw new IllegalArgumentException("productKey / deviceName 不能为空");
        }
        // clientId={pk}_{dn}，Broker 按最后一个 '_' 拆分 → productKey 可含 '_'（snd_ess_pcs），
        // deviceName 禁 '_' 保证拆分无歧义；'&' 为 username 分隔符，productKey/deviceName 均禁用
        if (productKey.contains("&") || deviceName.contains("_") || deviceName.contains("&")) {
            throw new IllegalArgumentException("productKey 不允许包含 '&'；deviceName 不允许包含 '_' 或 '&'（与 clientId/username 分隔符冲突）");
        }
    }

    /** 连接标识：productKey_deviceName。 */
    public String clientId() {
        return productKey + "_" + deviceName;
    }

    /** 平台侧设备主键（iot_device.id 语义的 deviceKey）。 */
    public String deviceKey() {
        return clientId();
    }

    public String propertyTopic() {
        return productKey + "/" + deviceName + "/up/property";
    }

    public String eventTopic() {
        return productKey + "/" + deviceName + "/up/event";
    }

    public String lifecycleTopic() {
        return productKey + "/" + deviceName + "/up/lifecycle";
    }

    public String ackTopic() {
        return productKey + "/" + deviceName + "/up/ack";
    }

    /** 下行指令订阅主题：{pk}/{dn}/down/command。 */
    public String downCommandTopic() {
        return productKey + "/" + deviceName + "/down/command";
    }
}
