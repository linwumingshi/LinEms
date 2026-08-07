package com.energyx.alarm.util;

/**
 * 告警域 Redis key 规范（对齐 docs/design/Redis-key规范.md 告警节）。
 *
 * <ul>
 *   <li>sustain：连续超阈窗口首违反时刻（String 毫秒时间戳），未达窗口不算告警；</li>
 *   <li>silence：触发后的静默标记（防抖合并），TTL=规则静默期；</li>
 * </ul>
 */
public final class AlarmRedisKeys {

    private AlarmRedisKeys() {
    }

    /** 持续窗口键：value=首次违反时刻（毫秒），TTL=窗口+缓冲 */
    public static String sustain(long ruleId, long deviceId) {
        return "alarm:sustain:" + ruleId + ":" + deviceId;
    }

    /** 静默键：触发后 SETNX，TTL=静默期（秒） */
    public static String silence(long ruleId, long deviceId) {
        return "alarm:silence:" + ruleId + ":" + deviceId;
    }
}
