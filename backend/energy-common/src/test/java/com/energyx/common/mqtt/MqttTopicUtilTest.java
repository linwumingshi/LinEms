package com.energyx.common.mqtt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * MQTT Topic 规范单元测试：锁定上下行 Topic 命名与 clientId 规范。
 */
class MqttTopicUtilTest {

	@Test
	void upTopic_shouldFollowConvention() {
		assertEquals("snd_ess_pcs/snd_pcs_001/up/property",
				MqttTopicUtil.upPropertyTopic("snd_ess_pcs", "snd_pcs_001"));
	}

	@Test
	void downCommandTopic_shouldFollowConvention() {
		assertEquals("snd_ess_pcs/snd_pcs_001/down/command",
				MqttTopicUtil.downCommandTopic("snd_ess_pcs", "snd_pcs_001"));
	}

	@Test
	void buildClientId_shouldJoinWithUnderscore() {
		assertEquals("snd_ess_pcs_snd_pcs_001", MqttTopicUtil.buildClientId("snd_ess_pcs", "snd_pcs_001"));
	}

	@Test
	void parseUpTopic_shouldParseAllFourTypes() {
		MqttTopicInfo property = MqttTopicUtil.parseUpTopic("snd_ess_pcs/snd_pcs_001/up/property");
		assertEquals("snd_ess_pcs", property.productKey());
		assertEquals("snd_pcs_001", property.deviceName());
		assertEquals(MqttUpType.PROPERTY, property.upType());

		assertEquals(MqttUpType.EVENT, MqttTopicUtil.parseUpTopic("snd_ess_pcs/snd_pcs_001/up/event").upType());
		assertEquals(MqttUpType.LIFECYCLE, MqttTopicUtil.parseUpTopic("snd_ess_pcs/snd_pcs_001/up/lifecycle").upType());
		assertEquals(MqttUpType.ACK, MqttTopicUtil.parseUpTopic("snd_ess_pcs/snd_pcs_001/up/ack").upType());
	}

	@Test
	void parseUpTopic_shouldRejectDownAndMalformed() {
		assertNull(MqttTopicUtil.parseUpTopic("snd_ess_pcs/snd_pcs_001/down/command"));
		assertNull(MqttTopicUtil.parseUpTopic("snd_ess_pcs/snd_pcs_001/up/unknown"));
		assertNull(MqttTopicUtil.parseUpTopic("snd_ess_pcs/snd_pcs_001/up"));
		assertNull(MqttTopicUtil.parseUpTopic("snd_ess_pcs/up/property"));
		assertNull(MqttTopicUtil.parseUpTopic(null));
		assertNull(MqttTopicUtil.parseUpTopic(""));
	}

}
