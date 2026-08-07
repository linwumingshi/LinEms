package com.energyx.broker.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.energyx.broker.config.BrokerProperties;
import com.energyx.broker.util.BrokerKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 会话持久化（Redis mqtt:* keys）——仅存「可重建的持久化态」，Session 本体只在内存。
 *
 * <p>故障接管链路（Phase 1 §4.4）：节点宕机 → 设备重连任意节点 → 依据本 Store 恢复
 * 订阅/inflight/离线队列，实现「会话跟随设备」，无需跨节点迁移。</p>
 *
 * <p>所有方法都是慢路径（Redis 网络 IO），调用方必须从 Netty IO 线程剥离（经 brokerExecutor）。</p>
 */
@Slf4j
@Component
public class SessionStore {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final BrokerProperties properties;

    public SessionStore(StringRedisTemplate redis, ObjectMapper objectMapper, BrokerProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    // ---------------- session ----------------

    /** 保存持久会话元数据；返回是否成功（EXISTS=0 且 SET=1 才成功，防止被同 clientId 新连接覆盖） */
    public boolean saveSession(String deviceKey, String nodeId, boolean cleanSession) {
        String key = BrokerKeys.session(deviceKey);
        Map<String, String> hash = Map.of(
                "node", nodeId,
                "clean", String.valueOf(cleanSession),
                "ts", String.valueOf(System.currentTimeMillis()));
        boolean ok = Boolean.TRUE.equals(redis.opsForHash().putIfAbsent(key, "node", nodeId));
        // putIfAbsent 只保证首字段；其余字段 upsert
        redis.opsForHash().putAll(key, hash);
        redis.expire(key, Duration.ofSeconds(properties.getSessionTtlSeconds()));
        return ok;
    }

    public Map<Object, Object> loadSession(String deviceKey) {
        return redis.opsForHash().entries(BrokerKeys.session(deviceKey));
    }

    public void deleteSession(String deviceKey) {
        redis.delete(BrokerKeys.session(deviceKey));
    }

    // ---------------- subscriptions ----------------

    /** 全量覆盖持久订阅集（干净会话重连时用） */
    public void saveSubscriptions(String deviceKey, Set<MqttSubscription> subs) {
        String key = BrokerKeys.subs(deviceKey);
        redis.delete(key);
        if (!subs.isEmpty()) {
            Set<String> members = new HashSet<>(subs.size());
            for (MqttSubscription s : subs) {
                members.add(s.encode());
            }
            redis.opsForSet().add(key, members.toArray(new String[0]));
            redis.expire(key, Duration.ofSeconds(properties.getSessionTtlSeconds()));
        }
    }

    public Set<MqttSubscription> loadSubscriptions(String deviceKey) {
        Set<String> members = redis.opsForSet().members(BrokerKeys.subs(deviceKey));
        Set<MqttSubscription> result = new HashSet<>();
        if (members != null) {
            for (String m : members) {
                try {
                    result.add(MqttSubscription.decode(m));
                } catch (IllegalArgumentException e) {
                    log.warn("[SessionStore] 丢弃非法订阅成员 key={} member={}", deviceKey, m);
                }
            }
        }
        return result;
    }

    public void deleteSubscriptions(String deviceKey) {
        redis.delete(BrokerKeys.subs(deviceKey));
    }

    // ---------------- inflight (QoS1/2 状态机续传) ----------------

    /**
     * 保存一条 outbound in-flight。序列化契约：
     * packetId|qos|state|retain|base64(payload)|topic（定长字段，ZREM 可用精确成员）。
     */
    public void saveInflight(String deviceKey, InflightMessage msg) {
        redis.opsForHash().put(BrokerKeys.inflight(deviceKey),
                String.valueOf(msg.getPacketId()), encodeInflight(msg));
        redis.expire(BrokerKeys.inflight(deviceKey), Duration.ofSeconds(properties.getSessionTtlSeconds()));
    }

    public void removeInflight(String deviceKey, int packetId) {
        redis.opsForHash().delete(BrokerKeys.inflight(deviceKey), String.valueOf(packetId));
    }

    public List<InflightMessage> loadInflight(String deviceKey) {
        Map<Object, Object> entries = redis.opsForHash().entries(BrokerKeys.inflight(deviceKey));
        List<InflightMessage> result = new ArrayList<>();
        for (Object value : entries.values()) {
            try {
                result.add(decodeInflight(String.valueOf(value)));
            } catch (IllegalArgumentException e) {
                log.warn("[SessionStore] 丢弃非法 inflight 成员 key={}", deviceKey);
            }
        }
        return result;
    }

    public void deleteInflight(String deviceKey) {
        redis.delete(BrokerKeys.inflight(deviceKey));
    }

    private String encodeInflight(InflightMessage msg) {
        return msg.getPacketId() + "|" + msg.getQos() + "|" + msg.getState() + "|"
                + msg.isRetain() + "|" + Base64.getEncoder().encodeToString(msg.getPayload()) + "|"
                + msg.getTopic();
    }

    private InflightMessage decodeInflight(String s) {
        int i1 = s.indexOf('|');
        int i2 = s.indexOf('|', i1 + 1);
        int i3 = s.indexOf('|', i2 + 1);
        int i4 = s.indexOf('|', i3 + 1);
        int i5 = s.indexOf('|', i4 + 1);
        if (i5 < 0) {
            throw new IllegalArgumentException("非法 inflight 编码: " + s);
        }
        int packetId = Integer.parseInt(s.substring(0, i1));
        int qos = Integer.parseInt(s.substring(i1 + 1, i2));
        int state = Integer.parseInt(s.substring(i2 + 1, i3));
        boolean retain = Boolean.parseBoolean(s.substring(i3 + 1, i4));
        byte[] payload = Base64.getDecoder().decode(s.substring(i4 + 1, i5));
        String topic = s.substring(i5 + 1);
        return new InflightMessage(packetId, topic, payload, qos, retain, state, 0L);
    }

    // ---------------- offline queue ----------------

    /** 推送离线消息（持久会话离线期间）；超容量从队首丢弃最旧 */
    public void pushOffline(String deviceKey, OfflineMessage message) {
        String key = BrokerKeys.offline(deviceKey);
        try {
            redis.opsForList().rightPush(key, objectMapper.writeValueAsString(message));
        } catch (Exception e) {
            log.warn("[SessionStore] 离线消息序列化失败 deviceKey={}", deviceKey, e);
            return;
        }
        redis.expire(key, Duration.ofSeconds(properties.getOfflineQueueTtlSeconds()));
        Long len = redis.opsForList().size(key);
        if (len != null && len > properties.getOfflineQueueCapacity()) {
            redis.opsForList().leftPop(key);
        }
    }

    /** 弹出全部离线消息（FIFO），拉空即清 key */
    public List<OfflineMessage> popOffline(String deviceKey) {
        String key = BrokerKeys.offline(deviceKey);
        List<String> values = redis.opsForList().range(key, 0, -1);
        redis.delete(key);
        if (values == null) {
            return List.of();
        }
        List<OfflineMessage> result = new ArrayList<>(values.size());
        for (String v : values) {
            try {
                result.add(objectMapper.readValue(v, OfflineMessage.class));
            } catch (Exception e) {
                log.warn("[SessionStore] 丢弃非法离线消息 key={}", deviceKey);
            }
        }
        return result;
    }

    /** 会话元数据是否存在（CONNACK sessionPresent 判定） */
    public boolean existsSession(String deviceKey) {
        return Boolean.TRUE.equals(redis.hasKey(BrokerKeys.session(deviceKey)));
    }

    // ---------------- conn lock ----------------

    /** 抢占连接锁：返回 true 表示抢到（同 clientId 仅此节点可建会话） */
    public boolean tryAcquireConnLock(String deviceKey, String nodeId) {
        Boolean ok = redis.opsForValue()
                .setIfAbsent(BrokerKeys.connLock(deviceKey), nodeId,
                        Duration.ofSeconds(properties.getSessionTtlSeconds()));
        return Boolean.TRUE.equals(ok);
    }

    public String getConnLockOwner(String deviceKey) {
        return redis.opsForValue().get(BrokerKeys.connLock(deviceKey));
    }

    /** 仅当归属为本节点时释放（防止误删新连接的锁） */
    public void releaseConnLockIfOwner(String deviceKey, String nodeId) {
        String current = getConnLockOwner(deviceKey);
        if (nodeId.equals(current)) {
            redis.delete(BrokerKeys.connLock(deviceKey));
        }
    }

    // ---------------- nonce（防重放） ----------------

    /** SETNX 一次性 nonce，返回 true 表示首次使用 */
    public boolean consumeNonce(String nonce) {
        Boolean ok = redis.opsForValue().setIfAbsent(BrokerKeys.nonce(nonce), "1", Duration.ofMinutes(5));
        return Boolean.TRUE.equals(ok);
    }

    public ObjectMapper objectMapper() {
        return objectMapper;
    }

    public StringRedisTemplate redis() {
        return redis;
    }

    /** 供 LifecycleNotifier 使用：设置带 TTL 的字符串 */
    public void setString(String key, String value, long ttlSeconds) {
        redis.opsForValue().set(key, value, ttlSeconds, TimeUnit.SECONDS);
    }

    public void delete(String key) {
        redis.delete(key);
    }

    /** 供 DeviceAuthService 使用 */
    public String getString(String key) {
        return redis.opsForValue().get(key);
    }
}
