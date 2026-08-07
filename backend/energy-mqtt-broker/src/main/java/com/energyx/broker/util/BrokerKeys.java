package com.energyx.broker.util;

/**
 * Broker 相关 Redis key 构建器（与 docs/design/Redis-key规范.md 第 2 节一一对应）。
 *
 * <p>规则：新增 key 必须先补文档再编码；本类为唯一出口，禁止散落字符串拼接。</p>
 */
public final class BrokerKeys {

    private BrokerKeys() {
    }

    /** 持久会话：Hash(node/cleanSession/will) */
    public static String session(String deviceKey) {
        return "mqtt:session:" + deviceKey;
    }

    /** 持久订阅：Set(topic@qos) */
    public static String subs(String deviceKey) {
        return "mqtt:subs:" + deviceKey;
    }

    /** outbound in-flight：Hash(packetId→JSON)（Phase 4 由规范 ZSet 细化为 Hash 以携带 payload） */
    public static String inflight(String deviceKey) {
        return "mqtt:inflight:" + deviceKey;
    }

    /** 离线消息队列：List */
    public static String offline(String deviceKey) {
        return "mqtt:offline:" + deviceKey;
    }

    /** 连接锁：String(nodeId)，同 clientId 单连接 */
    public static String connLock(String deviceKey) {
        return "mqtt:conn:" + deviceKey;
    }

    /** 认证 nonce：SETNX，防重放 */
    public static String nonce(String nonce) {
        return "mqtt:nonce:" + nonce;
    }

    /** 保留消息：String(JSON) */
    public static String retained(String topic) {
        return "mqtt:retained:" + topic;
    }

    /** 设备在线标记：String(brokerNodeId)，TTL 心跳续期 */
    public static String online(long deviceId) {
        return "iot:online:" + deviceId;
    }

    /** 设备凭据缓存：String(JSON)，TTL 30min */
    public static String credentialCache(String deviceKey) {
        return "cache:cred:" + deviceKey;
    }
}
