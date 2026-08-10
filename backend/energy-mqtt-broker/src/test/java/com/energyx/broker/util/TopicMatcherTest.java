package com.energyx.broker.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MQTT Topic 通配符匹配单元测试。
 */
class TopicMatcherTest {

	@Test
	void exactMatch() {
		assertTrue(TopicMatcher.matches("pk/dn/up/property", "pk/dn/up/property"));
		assertFalse(TopicMatcher.matches("pk/dn/up/property", "pk/dn/up/event"));
	}

	@Test
	void plusMatchesSingleLevel() {
		assertTrue(TopicMatcher.matches("pk/dn/up/property", "pk/+/up/property"));
		assertTrue(TopicMatcher.matches("pk/dn/up/property", "pk/dn/+/+"));
		assertFalse(TopicMatcher.matches("pk/dn/up/property", "pk/+/up/event"));
	}

	@Test
	void hashMatchesZeroOrMoreLevels() {
		assertTrue(TopicMatcher.matches("pk/dn/down/command", "pk/dn/down/#"));
		assertTrue(TopicMatcher.matches("pk/dn/down/command/ack", "pk/dn/down/#"));
		assertTrue(TopicMatcher.matches("pk/dn/down", "pk/dn/down/#"));
		assertFalse(TopicMatcher.matches("pk/dn/up/property", "pk/dn/down/#"));
	}

	@Test
	void hashMustBeLast() {
		assertFalse(TopicMatcher.matches("a/b/c", "a/#/c"));
	}

	@Test
	void dollarTopicsNotMatchedByHash() {
		// 保留语义：$ 开头的 topic 不被 # 捕获
		assertFalse(TopicMatcher.matches("$SYS/broker/load", "#"));
	}

	@Test
	void sharedSubscriptionPrefix() {
		String shared = "$share/group1/pk/dn/down/command";
		assertTrue(TopicMatcher.matches("pk/dn/down/command", shared));
		assertFalse(TopicMatcher.matches("pk/dn/up/property", shared));
	}

	@Test
	void stripSharePrefix() {
		assertTrue(TopicMatcher.stripSharePrefix("$share/g/x/#").equals("x/#"));
		assertTrue(TopicMatcher.stripSharePrefix("plain/filter").equals("plain/filter"));
	}

}
