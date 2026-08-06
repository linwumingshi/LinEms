package com.sanduo.energy.broker.retained;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanduo.energy.broker.session.SessionStore;
import com.sanduo.energy.broker.util.BrokerKeys;
import com.sanduo.energy.broker.util.TopicMatcher;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 保留消息存储（MQTT 3.1.1 §3.3.1）。
 *
 * <p>存储：Redis String mqtt:retained:{topic}（JSON）为跨节点权威，内存 ConcurrentHashMap 为
 * 本节点读缓存（发布写入时同步刷新，保证本节点匹配无延迟）。新增 key 已补登 Redis-key规范.md。</p>
 */
@Slf4j
@Component
public class RetainedMessageStore {

    private final SessionStore sessionStore;
    private final ObjectMapper objectMapper;
    private final Map<String, RetainedEntry> cache = new ConcurrentHashMap<>();

    public RetainedMessageStore(SessionStore sessionStore, ObjectMapper objectMapper) {
        this.sessionStore = sessionStore;
        this.objectMapper = objectMapper;
    }

    /** 写入保留消息（retain=1 且 payload 非空）；payload 为空视为删除 */
    public void put(String topic, byte[] payload, int qos) {
        String key = BrokerKeys.retained(topic);
        if (payload == null || payload.length == 0) {
            cache.remove(topic);
            sessionStore.delete(key);
            return;
        }
        RetainedEntry entry = new RetainedEntry(topic, Base64.getEncoder().encodeToString(payload), qos);
        cache.put(topic, entry);
        try {
            sessionStore.redis().opsForValue().set(key, objectMapper.writeValueAsString(entry));
        } catch (Exception e) {
            log.warn("[Retained] Redis 写入失败 topic={}", topic, e);
        }
    }

    /** 删除保留消息 */
    public void remove(String topic) {
        cache.remove(topic);
        sessionStore.delete(BrokerKeys.retained(topic));
    }

    /** 匹配订阅过滤表达式的全部保留消息（新订阅时投递） */
    public List<RetainedEntry> match(String topicFilter) {
        List<RetainedEntry> result = new ArrayList<>();
        for (Map.Entry<String, RetainedEntry> e : cache.entrySet()) {
            if (TopicMatcher.matches(e.getKey(), TopicMatcher.stripSharePrefix(topicFilter))) {
                result.add(e.getValue());
            }
        }
        return result;
    }

    public int size() {
        return cache.size();
    }

    @Data
    public static class RetainedEntry {
        private String topic;
        private String payloadBase64;
        private int qos;

        public RetainedEntry() {
        }

        public RetainedEntry(String topic, String payloadBase64, int qos) {
            this.topic = topic;
            this.payloadBase64 = payloadBase64;
            this.qos = qos;
        }

        public byte[] payload() {
            return Base64.getDecoder().decode(payloadBase64);
        }
    }
}
