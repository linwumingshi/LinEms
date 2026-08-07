package com.energyx.shadow.util;

/**
 * 影子域 Redis key 唯一出口（对齐 Redis-key规范.md §3.2）。
 */
public final class ShadowRedisKeys {

    private ShadowRedisKeys() {
    }

    /** 影子 reported 热缓存（Hash：属性 identifier → JSON 值） */
    public static String reported(long deviceId) {
        return "iot:shadow:reported:" + deviceId;
    }

    /** 影子 desired 热缓存（Hash：属性 identifier → JSON 值） */
    public static String desired(long deviceId) {
        return "iot:shadow:desired:" + deviceId;
    }

    /** 影子 delta（String JSON，短 TTL，驱动设备同步） */
    public static String delta(long deviceId) {
        return "iot:shadow:delta:" + deviceId;
    }

    /** 变更历史节流（SETNX，TTL=historyThrottleSeconds） */
    public static String historyThrottle(long deviceId) {
        return "shadow:hist:" + deviceId;
    }
}
