package com.sanduo.energy.command.util;

/**
 * 命令域 Redis key 唯一出口（对齐 Redis-key规范 §3.3）。
 */
public final class CommandRedisKeys {

    private CommandRedisKeys() {
    }

    /** 设备离线指令队列（List，JSON 指令，TTL 7d） */
    public static String offlineQueue(long deviceId) {
        return "iot:cmd:q:" + deviceId;
    }

    /** 在途指令（Hash：commandId → 超时时刻），辅助热查询/诊断，扫描以 DB 为准 */
    public static String inflight(long deviceId) {
        return "iot:cmd:inflight:" + deviceId;
    }

    /** 设备在线标记（Broker 心跳续期），存在即在线 */
    public static String online(long deviceId) {
        return "iot:online:" + deviceId;
    }
}
