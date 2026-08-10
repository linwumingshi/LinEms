package com.energyx.access.util;

/**
 * 接入适配域 Redis key 唯一出口（对齐 Redis-key规范.md）。
 *
 * <ul>
 * <li>iot:msg:dedup:{stage}:{device_id}:{message_id} —— 消息幂等（每消费边界独立命名空间）；</li>
 * <li>cache:device:{device_key} —— 设备信息缓存（deviceId/tenant/station/enterprise）；</li>
 * <li>cache:model:current:{product_key} —— 产品当前生效物模型缓存；</li>
 * <li>iot:cmd:q:{device_id} —— 设备离线指令队列（上线补发）。</li>
 * </ul>
 */
public final class AccessKeys {

	private AccessKeys() {
	}

	public static String msgDedup(String stage, long deviceId, String messageId) {
		return "iot:msg:dedup:" + stage + ":" + deviceId + ":" + messageId;
	}

	public static String deviceInfo(String deviceKey) {
		return "cache:device:" + deviceKey;
	}

	public static String modelCurrent(String productKey) {
		return "cache:model:current:" + productKey;
	}

	public static String cmdQueue(long deviceId) {
		return "iot:cmd:q:" + deviceId;
	}

	/** Broker 连接锁（mqtt:conn:{deviceKey}，value=brokerNodeId）：下行定向路由定位（阶段 2） */
	public static String brokerConnLock(String deviceKey) {
		return "mqtt:conn:" + deviceKey;
	}

}
