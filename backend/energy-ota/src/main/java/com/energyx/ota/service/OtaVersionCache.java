package com.energyx.ota.service;

import com.energyx.ota.util.OtaKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 设备固件版本缓存（ota:version:{deviceId}，TTL 7 天）。
 *
 * <p>
 * inform 上报写缓存，升级通知下发/成功判定读取，避免每次回查设备表。
 * </p>
 */
@Slf4j
@Component
public class OtaVersionCache {

	private static final Duration TTL = Duration.ofDays(7);

	private final StringRedisTemplate redis;

	public OtaVersionCache(StringRedisTemplate redis) {
		this.redis = redis;
	}

	/** 写入设备当前版本（JSON：{version, module, ts}） */
	public void setVersion(Long deviceId, String version, String module) {
		try {
			redis.opsForValue()
				.set(OtaKeys.deviceVersion(deviceId), "{\"version\":\"" + version + "\",\"module\":\"" + module
						+ "\",\"ts\":" + System.currentTimeMillis() + "}", TTL);
		}
		catch (Exception e) {
			log.warn("[OTA] 版本缓存写入失败 deviceId={}", deviceId, e);
		}
	}

	/** 读取设备当前版本；无缓存返回 null */
	public String getVersion(Long deviceId) {
		try {
			String json = redis.opsForValue().get(OtaKeys.deviceVersion(deviceId));
			return json == null ? null : extractVersion(json);
		}
		catch (Exception e) {
			log.warn("[OTA] 版本缓存读取失败 deviceId={}", deviceId, e);
			return null;
		}
	}

	/** 从缓存 JSON 提取 version 字段（容错：JSON 解析失败返回 null） */
	private String extractVersion(String json) {
		try {
			int idx = json.indexOf("\"version\":\"");
			if (idx < 0) {
				return null;
			}
			int start = idx + "\"version\":\"".length();
			int end = json.indexOf('"', start);
			return end < 0 ? null : json.substring(start, end);
		}
		catch (Exception e) {
			return null;
		}
	}

}
