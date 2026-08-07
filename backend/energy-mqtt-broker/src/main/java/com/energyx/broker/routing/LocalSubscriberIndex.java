package com.energyx.broker.routing;

import com.energyx.broker.session.Session;
import com.energyx.broker.util.TopicMatcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 节点内订阅索引（纯内存）。
 *
 * <p>结构：topicFilter → (deviceKey → SubscriberBinding)。内层按 deviceKey 建键，使：
 * <ul>
 *   <li>重连直接覆盖旧绑定（新 Session 替换幽灵 Session，无需先删旧）；</li>
 *   <li>持久会话离线后其幽灵绑定保留在索引，发布匹配时转入离线队列（MQTT 持久会话语义）。</li>
 * </ul>
 * 幽灵会话依赖 mqtt:session TTL（7d）自然回收；大批量僵尸设备的索引清理列为 Phase 8 治理项。</p>
 *
 * <p>生产优化：Phase 1 §4.6 规划的 trie（topic 分层 + 通配位图）替换线性匹配（O(N)），
 * 本阶段先保证正确性。</p>
 */
@Slf4j
@Component
public class LocalSubscriberIndex {

    private final Map<String, Map<String, SubscriberBinding>> bindings = new ConcurrentHashMap<>();

    /** 订阅/更新（同 deviceKey 重复订阅以最高 QoS 为准） */
    public void add(Session session, String topicFilter, int qos) {
        bindings.computeIfAbsent(topicFilter, k -> new ConcurrentHashMap<>())
                .merge(session.getDeviceKey(), new SubscriberBinding(session, qos),
                        (a, b) -> new SubscriberBinding(b.session, Math.max(a.qos, b.qos)));
    }

    /** 取消单条订阅（按 deviceKey） */
    public void remove(String deviceKey, String topicFilter) {
        Map<String, SubscriberBinding> map = bindings.get(topicFilter);
        if (map != null) {
            map.remove(deviceKey);
            if (map.isEmpty()) {
                bindings.remove(topicFilter, map);
            }
        }
    }

    /** 会话断开：干净会话移除全部绑定；持久会话保留（幽灵）由调用方决定 */
    public void removeAll(String deviceKey) {
        for (Map.Entry<String, Map<String, SubscriberBinding>> e : bindings.entrySet()) {
            e.getValue().remove(deviceKey);
        }
        bindings.entrySet().removeIf(e -> e.getValue().isEmpty());
    }

    /** 匹配 topic，返回去重后的绑定（同一 deviceKey 多条 filter 命中取最高 QoS） */
    public List<SubscriberMatch> match(String topic) {
        Map<String, SubscriberMatch> hits = new HashMap<>();
        for (Map.Entry<String, Map<String, SubscriberBinding>> e : bindings.entrySet()) {
            if (TopicMatcher.matches(topic, e.getKey())) {
                for (Map.Entry<String, SubscriberBinding> s : e.getValue().entrySet()) {
                    SubscriberMatch existing = hits.get(s.getKey());
                    if (existing == null || s.getValue().qos > existing.qos) {
                        hits.put(s.getKey(), new SubscriberMatch(s.getValue().session, s.getValue().qos));
                    }
                }
            }
        }
        return new ArrayList<>(hits.values());
    }

    public int size() {
        int count = 0;
        for (Map<String, SubscriberBinding> m : bindings.values()) {
            count += m.size();
        }
        return count;
    }

    public record SubscriberBinding(Session session, int qos) {
    }

    public record SubscriberMatch(Session session, int qos) {
    }
}
