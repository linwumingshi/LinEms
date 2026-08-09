package com.energyx.broker.routing;

import com.energyx.broker.session.Session;
import com.energyx.broker.util.TopicMatcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 节点内订阅索引（topic trie，纯内存）。
 *
 * <p>结构：按 "/" 分层建树；{@code +} 作为特殊子节点；{@code #} 作为锚定在所在层级节点的
 * 终端绑定集合（hashBindings）。匹配复杂度 O(topic 层级数)（典型 ≤5），替代原 O(N) 线性扫描，
 * 支撑 50 万级订阅规模下的高吞吐匹配。</p>
 *
 * <ul>
 *   <li>绑定按 deviceKey 去重：重连直接覆盖（新 Session 替换幽灵 Session）；同设备多 filter
 *       命中时 match 结果取最高 QoS；</li>
 *   <li>持久会话离线后幽灵绑定保留在索引，发布匹配时由投递层转入离线队列；</li>
 *   <li>反向索引 deviceKey → filters 使 removeAll 为 O(单设备订阅数) 而非 O(N)；</li>
 *   <li>并发：match 走读锁（高并发），add/remove 走写锁（低频）；节点只增不删
 *       （空节点内存有界，以 distinct 层级前缀为上界），避免删除竞态。</li>
 * </ul>
 */
@Slf4j
@Component
public class LocalSubscriberIndex {

    private static final String PLUS = "+";
    private static final String HASH = "#";

    private final TrieNode root = new TrieNode();
    /** deviceKey → 其订阅的原始 filter 集合（removeAll 反向索引） */
    private final Map<String, Set<String>> filtersByDevice = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /** 订阅/更新（同 deviceKey 重复订阅以最高 QoS 为准，Session 以最新为准） */
    public void add(Session session, String topicFilter, int qos) {
        String[] levels = TopicMatcher.stripSharePrefix(topicFilter).split("/", -1);
        lock.writeLock().lock();
        try {
            TrieNode node = root;
            boolean anchored = false;
            for (int i = 0; i < levels.length; i++) {
                String level = levels[i];
                if (HASH.equals(level) && i == levels.length - 1) {
                    node.hashBindings.merge(session.getDeviceKey(),
                            new SubscriberBinding(session, qos),
                            (a, b) -> new SubscriberBinding(b.session(), Math.max(a.qos(), b.qos())));
                    anchored = true;
                    break;
                }
                node = node.children.computeIfAbsent(level, k -> new TrieNode());
            }
            if (!anchored) {
                node.exactBindings.merge(session.getDeviceKey(),
                        new SubscriberBinding(session, qos),
                        (a, b) -> new SubscriberBinding(b.session(), Math.max(a.qos(), b.qos())));
            }
            filtersByDevice.computeIfAbsent(session.getDeviceKey(), k -> ConcurrentHashMap.newKeySet())
                    .add(topicFilter);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 取消单条订阅（按 deviceKey） */
    public void remove(String deviceKey, String topicFilter) {
        String[] levels = TopicMatcher.stripSharePrefix(topicFilter).split("/", -1);
        lock.writeLock().lock();
        try {
            TrieNode node = navigate(levels);
            if (node != null) {
                if (HASH.equals(levels[levels.length - 1])) {
                    node.hashBindings.remove(deviceKey);
                } else {
                    node.exactBindings.remove(deviceKey);
                }
            }
            Set<String> filters = filtersByDevice.get(deviceKey);
            if (filters != null) {
                filters.remove(topicFilter);
                if (filters.isEmpty()) {
                    filtersByDevice.remove(deviceKey, filters);
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 会话断开：移除该设备全部绑定（O(单设备订阅数)，持久会话幽灵由调用方决定是否调用） */
    public void removeAll(String deviceKey) {
        lock.writeLock().lock();
        try {
            Set<String> filters = filtersByDevice.remove(deviceKey);
            if (filters == null) {
                return;
            }
            for (String filter : filters) {
                String[] levels = TopicMatcher.stripSharePrefix(filter).split("/", -1);
                TrieNode node = navigate(levels);
                if (node != null) {
                    if (HASH.equals(levels[levels.length - 1])) {
                        node.hashBindings.remove(deviceKey);
                    } else {
                        node.exactBindings.remove(deviceKey);
                    }
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 匹配 topic，返回去重后的绑定（同一 deviceKey 多条 filter 命中取最高 QoS）。O(topic 层级数) */
    public List<SubscriberMatch> match(String topic) {
        if (topic == null || topic.isEmpty()) {
            return List.of();
        }
        String[] levels = topic.split("/", -1);
        Map<String, SubscriberMatch> hits = new HashMap<>();
        lock.readLock().lock();
        try {
            collect(root, levels, 0, !topic.startsWith("$"), hits);
        } finally {
            lock.readLock().unlock();
        }
        return new ArrayList<>(hits.values());
    }

    /**
     * 递归收集：当前节点的 # 锚定绑定恒命中（匹配零个或多个层级）；
     * 层级未耗尽时向「精确子节点」与「+ 子节点」下行；耗尽时收集精确绑定。
     *
     * @param allowWildcardRoot 为 false 时（topic 以 $ 开头）根层不应用 # 与 +（MQTT §4.7.2）
     */
    private void collect(TrieNode node, String[] levels, int depth, boolean allowWildcardHere,
                         Map<String, SubscriberMatch> hits) {
        if (allowWildcardHere) {
            node.hashBindings.forEach((deviceKey, b) -> mergeHit(hits, deviceKey, b));
        }
        if (depth == levels.length) {
            node.exactBindings.forEach((deviceKey, b) -> mergeHit(hits, deviceKey, b));
            return;
        }
        TrieNode exact = node.children.get(levels[depth]);
        if (exact != null) {
            collect(exact, levels, depth + 1, true, hits);
        }
        if (allowWildcardHere) {
            TrieNode plus = node.children.get(PLUS);
            if (plus != null) {
                collect(plus, levels, depth + 1, true, hits);
            }
        }
    }

    private void mergeHit(Map<String, SubscriberMatch> hits, String deviceKey, SubscriberBinding b) {
        SubscriberMatch existing = hits.get(deviceKey);
        if (existing == null || b.qos() > existing.qos()) {
            hits.put(deviceKey, new SubscriberMatch(b.session(), b.qos()));
        }
    }

    /** 按层级下行到目标节点（"#" 锚定在其前一层级节点上） */
    private TrieNode navigate(String[] levels) {
        TrieNode node = root;
        for (int i = 0; i < levels.length; i++) {
            if (HASH.equals(levels[i]) && i == levels.length - 1) {
                return node;
            }
            node = node.children.get(levels[i]);
            if (node == null) {
                return null;
            }
        }
        return node;
    }

    public int size() {
        int count = 0;
        for (Set<String> s : filtersByDevice.values()) {
            count += s.size();
        }
        return count;
    }

    /** trie 节点：children 按层级名索引（含 "+" 特殊键）；# 锚定绑定与精确终止绑定分离存储 */
    private static final class TrieNode {
        private final Map<String, TrieNode> children = new ConcurrentHashMap<>();
        /** 终止于本层级的精确 filter 绑定（deviceKey → binding） */
        private final Map<String, SubscriberBinding> exactBindings = new ConcurrentHashMap<>();
        /** 形如 "本层级/#" 的锚定绑定（deviceKey → binding） */
        private final Map<String, SubscriberBinding> hashBindings = new ConcurrentHashMap<>();
    }

    public record SubscriberBinding(Session session, int qos) {
    }

    public record SubscriberMatch(Session session, int qos) {
    }
}
