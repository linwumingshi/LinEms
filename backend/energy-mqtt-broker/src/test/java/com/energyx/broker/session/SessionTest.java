package com.energyx.broker.session;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Session 核心逻辑单元测试（packetId 分配器）。
 */
class SessionTest {

	private Session newSession() {
		return new Session("pk_dn", null, 4, false);
	}

	@Test
	void allocPacketId_cyclesWithinRange() {
		Session session = newSession();
		Set<Integer> ids = new HashSet<>();
		for (int i = 0; i < 70_000; i++) {
			ids.add(session.allocPacketId());
		}
		// 循环 1..65535，不重复分配（除非被释放）
		assertEquals(65_535, ids.size());
		for (int id : ids) {
			assertTrue(id >= 1 && id <= 65_535);
		}
	}

	@Test
	void allocPacketId_skipsInflightOccupied() {
		Session session = newSession();
		int first = session.allocPacketId();
		InflightMessage occupied = new InflightMessage(first, "t", new byte[] { 1 }, 1, false,
				InflightMessage.STATE_AWAITING_PUBACK, 0L);
		session.getOutboundInflight().put(first, occupied);

		int second = session.allocPacketId();
		assertNotEquals(first, second, "已占用的 packetId 不应再分配");
	}

	@Test
	void allocPacketId_returnsMinusOneWhenFull() {
		Session session = newSession();
		// 塞满 65535 个 in-flight
		for (int i = 1; i <= 65_535; i++) {
			InflightMessage msg = new InflightMessage(i, "t", new byte[] { 1 }, 1, false,
					InflightMessage.STATE_AWAITING_PUBACK, 0L);
			session.getOutboundInflight().put(i, msg);
			session.allocPacketId(); // 推进游标
		}
		assertEquals(-1, session.allocPacketId(), "in-flight 全满时应返回 -1");
	}

	@Test
	void subscriptionEncodeDecode_roundTrip() {
		MqttSubscription sub = new MqttSubscription("pk/dn/down/#", 1);
		MqttSubscription decoded = MqttSubscription.decode(sub.encode());
		assertEquals("pk/dn/down/#", decoded.getTopicFilter());
		assertEquals(1, decoded.getQos());
	}

}
