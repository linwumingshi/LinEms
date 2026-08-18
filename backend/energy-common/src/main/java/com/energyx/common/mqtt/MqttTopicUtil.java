package com.energyx.common.mqtt;

import java.util.Set;

/**
 * MQTT Topic 约定（Phase1 §6）： 上行 {productKey}/{deviceName}/up/{type} type ∈ property |
 * event | lifecycle | ack 下行 {productKey}/{deviceName}/down/command
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
	 * <p>
	 * 仅识别 `{pk}/{dn}/up/{type}` 四段格式且 type ∈ property|event|lifecycle|ack；
	 * 下行（down/*）、非法段数、未知 type 一律返回 null，调用方按「非设备上行」处理。
	 * </p>
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

	/** OTA 上行命名空间类型（对应设备 topic {pk}/{dn}/ota/{type}） */
	private static final Set<String> OTA_UP_TYPES = Set.of("inform", "progress", "result", "pull");

	/** 是否为平台下行 topic（`{pk}/{dn}/down/*`，阶段 2 下行定向投递判定） */
	public static boolean isDownTopic(String topic) {
		if (topic == null || topic.isBlank()) {
			return false;
		}
		String[] parts = topic.split("/");
		return parts.length >= 4 && "down".equals(parts[2]);
	}

	/**
	 * 是否为设备 OTA 上行 topic（{pk}/{dn}/ota/{inform|progress|result|pull}）。
	 *
	 * <p>
	 * 与 access {@code UplinkProcessor#processOta} 的命名空间一致：OTA 上行报文不进入物模型链路， broker 需将其与
	 * {@code up/*} 同样转发至 {@code mqtt.uplink}，再由 access 透传到 {@code ota.uplink} 供 OTA 中心消费。
	 * {@code ota/down} 为平台下行，不在此列（其 type=down 不属于 OTA_UP_TYPES）。
	 * </p>
	 */
	public static boolean isOtaUpTopic(String topic) {
		if (topic == null || topic.isBlank()) {
			return false;
		}
		String[] parts = topic.split("/");
		if (parts.length != 4 || !"ota".equals(parts[2])) {
			return false;
		}
		return OTA_UP_TYPES.contains(parts[3]);
	}

	/** 提取下行 topic 对应的 deviceKey（`{pk}_{dn}`）；非 down topic 返回 null */
	public static String deviceKeyOfDownTopic(String topic) {
		if (!isDownTopic(topic)) {
			return null;
		}
		String[] parts = topic.split("/");
		return buildDeviceKey(parts[0], parts[1]);
	}

}
