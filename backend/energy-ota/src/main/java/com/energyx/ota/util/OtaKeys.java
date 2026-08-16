package com.energyx.ota.util;

/**
 * OTA 模块 Redis Key 构建唯一出口（遵循 docs/design/Redis-key规范.md：key 构建收敛到 *Keys 工具类）。
 */
public final class OtaKeys {

	private OtaKeys() {
	}

	/** 设备当前固件版本缓存：ota:version:{deviceId}（inform 上报，S2 使用） */
	public static String deviceVersion(Long deviceId) {
		return "ota:version:" + deviceId;
	}

	/** 任务-设备状态快照：ota:task:{taskId}:device:{deviceId}（下发判重，S3 使用） */
	public static String taskDevice(Long taskId, Long deviceId) {
		return "ota:task:" + taskId + ":device:" + deviceId;
	}

	/** 灰度档位：ota:task:{taskId}:gray:state（S4 灰度推进使用） */
	public static String taskGrayState(Long taskId) {
		return "ota:task:" + taskId + ":gray:state";
	}

	/** 任务推进/取消分布式锁：ota:lock:task:{taskId}（SETNX，S4 使用） */
	public static String taskLock(Long taskId) {
		return "ota:lock:task:" + taskId;
	}

}
