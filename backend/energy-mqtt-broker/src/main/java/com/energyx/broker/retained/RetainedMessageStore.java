package com.energyx.broker.retained;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.energyx.broker.session.SessionStore;
import com.energyx.broker.util.BrokerKeys;
import com.energyx.broker.util.TopicMatcher;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * 保留消息存储（MQTT 3.1.1 §3.3.1）。
 *
 * <p>存储：Redis String mqtt:retained:{topic}（JSON）为跨节点权威，内存 ConcurrentHashMap 为
 * 本节点读缓存（发布写入时同步刷新，保证本节点匹配无延迟）。
 * Redis 写一律经 brokerExecutor 异步执行，绝不在 Netty IO 线程阻塞。</p>
 *
 * <p>冷启动：{@link #warmUp()} 在 Broker 启动后由 {@code MqttBrokerServer} 触发，
 * SCAN 全量 retained key 回填内存缓存——节点重启/扩容后新订阅立即可投递既有保留消息。</p>
 */
@Slf4j
@Component
public class RetainedMessageStore {

    private final SessionStore sessionStore;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;
    private final Map<String, RetainedEntry> cache = new ConcurrentHashMap<>();

    public RetainedMessageStore(SessionStore sessionStore, ObjectMapper objectMapper,
                                @Qualifier("brokerExecutor") ExecutorService executor) {
        this.sessionStore = sessionStore;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    /** 写入保留消息（retain=1 且 payload 非空）；payload 为空视为删除。跨节点并发写按时间戳新者胜（P2-9）。 */
    public void put(String topic, byte[] payload, int qos) {
        String key = BrokerKeys.retained(topic);
        if (payload == null || payload.length == 0) {
            cache.remove(topic);
            executor.execute(() -> sessionStore.delete(key));
            return;
        }
        RetainedEntry entry = new RetainedEntry(topic, Base64.getEncoder().encodeToString(payload), qos,
                System.currentTimeMillis());
        cache.put(topic, entry);
        final String json;
        try {
            json = objectMapper.writeValueAsString(entry);
        } catch (Exception e) {
            log.warn("[Retained] 序列化失败 topic={}", topic, e);
            return;
        }
        executor.execute(() -> {
            // Lua 原子比较：仅当 Redis 中无旧值或旧值时间戳不新于本次时写入，防止节点时钟漂移下旧值覆盖新值
            if (!sessionStore.setRetainedIfNewer(key, json, entry.getTs())) {
                log.debug("[Retained] 丢弃更旧的时间戳写入 topic={} ts={}", topic, entry.getTs());
            }
        });
    }

    /** 删除保留消息 */
    public void remove(String topic) {
        cache.remove(topic);
        String key = BrokerKeys.retained(topic);
        executor.execute(() -> sessionStore.delete(key));
    }

    /** 匹配订阅过滤表达式的全部保留消息（新订阅时投递） */
    public List<RetainedEntry> match(String topicFilter) {
        List<RetainedEntry> result = new ArrayList<>();
        String stripped = TopicMatcher.stripSharePrefix(topicFilter);
        for (Map.Entry<String, RetainedEntry> e : cache.entrySet()) {
            if (TopicMatcher.matches(e.getKey(), stripped)) {
                result.add(e.getValue());
            }
        }
        return result;
    }

    /**
     * 冷启动预热：SCAN mqtt:retained:* 全量回填内存缓存（后台线程，不阻塞启动）。
     * 预热完成前到达的新订阅可能投不到历史保留消息，属可接受的短暂窗口。
     */
    public void warmUp() {
        executor.execute(() -> {
            long start = System.currentTimeMillis();
            int loaded = 0;
            try (var cursor = sessionStore.redis().scan(
                    ScanOptions.scanOptions().match("mqtt:retained:*").count(1_000).build())) {
                List<String> batch = new ArrayList<>(1_000);
                while (cursor.hasNext()) {
                    batch.add(cursor.next());
                    if (batch.size() >= 1_000) {
                        loaded += loadBatch(batch);
                        batch.clear();
                    }
                }
                loaded += loadBatch(batch);
            } catch (Exception e) {
                log.error("[Retained] 冷启动预热失败，保留消息以 Redis 为准逐步经路由同步", e);
                return;
            }
            log.info("[Retained] 冷启动预热完成，加载 {} 条，耗时 {}ms", loaded, System.currentTimeMillis() - start);
        });
    }

    private int loadBatch(List<String> keys) {
        if (keys.isEmpty()) {
            return 0;
        }
        List<String> values = sessionStore.redis().opsForValue().multiGet(keys);
        if (values == null) {
            return 0;
        }
        int loaded = 0;
        for (String json : values) {
            if (json == null) {
                continue;
            }
            try {
                RetainedEntry entry = objectMapper.readValue(json, RetainedEntry.class);
                cache.putIfAbsent(entry.getTopic(), entry); // 不覆盖运行期已写入的更新值
                loaded++;
            } catch (Exception e) {
                log.warn("[Retained] 预热反序列化失败，跳过");
            }
        }
        return loaded;
    }

    public int size() {
        return cache.size();
    }

    @Data
    public static class RetainedEntry {
        private String topic;
        private String payloadBase64;
        private int qos;
        /** 发布毫秒时间戳（P2-9：跨节点覆盖比较，旧值不覆盖新值） */
        private long ts;

        public RetainedEntry() {
        }

        public RetainedEntry(String topic, String payloadBase64, int qos, long ts) {
            this.topic = topic;
            this.payloadBase64 = payloadBase64;
            this.qos = qos;
            this.ts = ts;
        }

        public byte[] payload() {
            return Base64.getDecoder().decode(payloadBase64);
        }
    }
}
