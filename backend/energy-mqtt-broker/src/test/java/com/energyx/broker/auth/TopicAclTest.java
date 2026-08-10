package com.energyx.broker.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Topic ACL 单元测试：设备防伪装（只能上/下行自己的 topic）。
 */
class TopicAclTest {

	private final DeviceCredential cred = new DeviceCredential("prodPCS_0101", 1001L, 1L, "prodPCS", "0101", 3, 1,
			"secret", false);

	@Test
	void canPublish_ownUpTopics() {
		assertTrue(TopicAcl.canPublish(cred, "prodPCS/0101/up/property"));
		assertTrue(TopicAcl.canPublish(cred, "prodPCS/0101/up/event"));
		assertTrue(TopicAcl.canPublish(cred, "prodPCS/0101/up/lifecycle"));
		assertTrue(TopicAcl.canPublish(cred, "prodPCS/0101/up/ack"));
	}

	@Test
	void canPublish_rejectsOthers() {
		assertFalse(TopicAcl.canPublish(cred, "prodPCS/0101/down/command"));
		assertFalse(TopicAcl.canPublish(cred, "prodPCS/0101/up/hack")); // 白名单外 type
		assertFalse(TopicAcl.canPublish(cred, "prodPCS/0102/up/property")); // 他设备
		assertFalse(TopicAcl.canPublish(cred, "other/0101/up/property")); // 他产品
	}

	@Test
	void canSubscribe_ownDownTopics() {
		assertTrue(TopicAcl.canSubscribe(cred, "prodPCS/0101/down/command"));
		assertTrue(TopicAcl.canSubscribe(cred, "prodPCS/0101/down/#"));
	}

	@Test
	void canSubscribe_rejectsOthers() {
		assertFalse(TopicAcl.canSubscribe(cred, "prodPCS/0101/up/property"));
		assertFalse(TopicAcl.canSubscribe(cred, "prodPCS/0101/down")); // 裸 down
		assertFalse(TopicAcl.canSubscribe(cred, "prodPCS/0102/down/command"));
		assertFalse(TopicAcl.canSubscribe(cred, "#"));
	}

}
