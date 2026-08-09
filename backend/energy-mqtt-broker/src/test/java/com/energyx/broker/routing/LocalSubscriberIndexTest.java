package com.energyx.broker.routing;

import com.energyx.broker.session.Session;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * trie 订阅索引正确性测试（替代原 O(N) 线性扫描索引后的语义回归保障）。
 */
class LocalSubscriberIndexTest {

    private final LocalSubscriberIndex index = new LocalSubscriberIndex();

    private Session session(String deviceKey) {
        return new Session(deviceKey, null, 4, true);
    }

    @Test
    void exactMatch() {
        index.add(session("d1"), "a/b/c", 1);
        assertEquals(1, index.match("a/b/c").size());
        assertTrue(index.match("a/b").isEmpty());
        assertTrue(index.match("a/b/c/d").isEmpty());
        assertTrue(index.match("a/b/x").isEmpty());
    }

    @Test
    void plusWildcard() {
        index.add(session("d1"), "a/+/c", 0);
        assertEquals(1, index.match("a/b/c").size());
        assertTrue(index.match("a/b/x").isEmpty());
        assertTrue(index.match("a/b/c/d").isEmpty());
        // + 不匹配空层级以外的缺失层级
        assertTrue(index.match("a/c").isEmpty());
    }

    @Test
    void hashWildcard() {
        index.add(session("d1"), "a/b/#", 2);
        assertEquals(1, index.match("a/b").size());       // # 匹配零层级
        assertEquals(1, index.match("a/b/c").size());
        assertEquals(1, index.match("a/b/c/d/e").size());
        assertTrue(index.match("a/x").isEmpty());

        index.add(session("d2"), "#", 0);
        assertEquals(1, index.match("any/topic").size()); // 仅 d2 的根 # 命中
        assertEquals(2, index.match("a/b").size());       // d1 的 a/b/# 与 d2 的根 # 同时命中
    }

    @Test
    void dollarTopicsNotMatchedByWildcard() {
        index.add(session("d1"), "#", 1);
        index.add(session("d2"), "+/status", 1);
        assertTrue(index.match("$SYS/broker").isEmpty());
        assertTrue(index.match("$SYS/status").isEmpty());
        index.add(session("d3"), "$SYS/#", 0);
        assertEquals(1, index.match("$SYS/broker").size());
    }

    @Test
    void sharedSubscriptionPrefixStripped() {
        index.add(session("d1"), "$share/g1/a/b", 1);
        assertEquals(1, index.match("a/b").size());
    }

    @Test
    void multiFilterHitTakesMaxQos() {
        Session s = session("d1");
        index.add(s, "a/#", 0);
        index.add(s, "a/b", 2);
        List<LocalSubscriberIndex.SubscriberMatch> matches = index.match("a/b");
        assertEquals(1, matches.size());
        assertEquals(2, matches.get(0).qos());
    }

    @Test
    void resubscribeKeepsMaxQosAndLatestSession() {
        Session old = session("d1");
        Session latest = session("d1");
        index.add(old, "a/b", 0);
        index.add(latest, "a/b", 1);
        List<LocalSubscriberIndex.SubscriberMatch> matches = index.match("a/b");
        assertEquals(1, matches.size());
        assertEquals(1, matches.get(0).qos());
        assertSame(latest, matches.get(0).session());
    }

    @Test
    void removeSingleFilter() {
        index.add(session("d1"), "a/b/#", 1);
        index.add(session("d1"), "a/b/c", 1);
        index.remove("d1", "a/b/#");
        assertTrue(index.match("a/b/x").isEmpty());
        assertEquals(1, index.match("a/b/c").size());
        assertEquals(1, index.size());
    }

    @Test
    void removeAllIsReverseIndexed() {
        index.add(session("d1"), "a/#", 1);
        index.add(session("d1"), "b/c", 1);
        index.add(session("d2"), "a/b", 1);
        index.removeAll("d1");
        assertTrue(index.match("b/c").isEmpty());
        assertEquals(1, index.match("a/b").size()); // d2 不受影响
        assertEquals(1, index.size());
    }

    @Test
    void perDeviceDownTopicModelScales() {
        // 平台真实模型：每设备订阅 {pk}/{dn}/down/#，上行 topic 不应命中任何 down filter
        for (int i = 0; i < 1000; i++) {
            index.add(session("pk_dev" + i), "pk/dev" + i + "/down/#", 1);
        }
        assertTrue(index.match("pk/dev0/up/property").isEmpty());
        assertEquals(1, index.match("pk/dev500/down/command").size());
        assertEquals(1000, index.size());
    }
}
