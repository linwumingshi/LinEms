package com.energyx.ems.service;

import com.energyx.common.constant.KafkaTopicConstant;
import com.energyx.ems.entity.EmsDemandConfig;
import com.energyx.ems.model.DeviceInfo;
import com.energyx.ems.mqtt.EmsKafkaProducer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** DemandAlarmProducer 消息形状锁定：messageId 幂等键、severity 阈值、data 键、topic/key、发布失败不抛。 */
class DemandAlarmProducerTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	private static EmsDemandConfig config() {
		EmsDemandConfig cfg = new EmsDemandConfig();
		cfg.setTenantId(7L);
		cfg.setStationId(10L);
		cfg.setDemandLimitKw(new BigDecimal("100.00"));
		cfg.setDemandRate(new BigDecimal("40.0000"));
		return cfg;
	}

	private static DeviceInfo meter() {
		return new DeviceInfo(1L, 7L, "snd_ess_meter", "m1", 3);
	}

	@Test
	void publish_buildsExpectedShapeAndSends() throws Exception {
		EmsKafkaProducer kafka = mock(EmsKafkaProducer.class);
		DemandAlarmProducer producer = new DemandAlarmProducer(kafka);

		producer.publishDemandOverLimit(config(), meter(), 120, 100, LocalDateTime.of(2026, 8, 11, 10, 30));

		ArgumentCaptor<String> topic = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
		verify(kafka).send(topic.capture(), key.capture(), value.capture());
		assertEquals(KafkaTopicConstant.IOT_THING_EVENT, topic.getValue());
		assertEquals("1", key.getValue()); // String.valueOf(deviceId)

		JsonNode evt = JSON.readTree(value.getValue());
		assertEquals("demand-10-2026-08-11T10:30", evt.get("messageId").asText()); // 幂等键
																					// 站+槽位
		assertEquals(evt.get("messageId").asText(), evt.get("eventId").asText());
		assertEquals(1L, evt.get("deviceId").asLong());
		assertEquals(7L, evt.get("tenantId").asLong());
		assertEquals(10L, evt.get("stationId").asLong());
		assertEquals("snd_ess_meter", evt.get("productKey").asText());
		assertEquals("demandOverLimit", evt.get("eventName").asText());
		assertEquals(3, evt.get("severity").asInt()); // 120/100 = 1.2 ≥ 1.2 → 严重
		assertEquals("DEMAND_OVER_LIMIT", evt.get("code").asText());
		assertTrue(evt.get("ts").asLong() > 0);
		assertEquals(120.0, evt.get("data").get("demandKw").asDouble());
		assertEquals(100.0, evt.get("data").get("limitKw").asDouble());
		assertEquals(10L, evt.get("data").get("stationId").asLong());
	}

	@Test
	void publish_lowRatioSeverityIsTwo() throws Exception {
		EmsKafkaProducer kafka = mock(EmsKafkaProducer.class);
		DemandAlarmProducer producer = new DemandAlarmProducer(kafka);

		producer.publishDemandOverLimit(config(), meter(), 110, 100, LocalDateTime.of(2026, 8, 11, 10, 30));

		ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
		verify(kafka).send(anyString(), anyString(), value.capture());
		assertEquals(2, JSON.readTree(value.getValue()).get("severity").asInt()); // 110/100
																					// =
																					// 1.1
																					// →
																					// 一般
	}

	@Test
	void publish_zeroLimitNeverDivides() throws Exception {
		EmsKafkaProducer kafka = mock(EmsKafkaProducer.class);
		DemandAlarmProducer producer = new DemandAlarmProducer(kafka);

		producer.publishDemandOverLimit(config(), meter(), 500, 0, LocalDateTime.of(2026, 8, 11, 10, 30));

		ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
		verify(kafka).send(anyString(), anyString(), value.capture());
		assertEquals(2, JSON.readTree(value.getValue()).get("severity").asInt()); // limit≤0
																					// →
																					// 一般，不除零
	}

	@Test
	void publish_sendFailureIsSwallowed() {
		EmsKafkaProducer kafka = mock(EmsKafkaProducer.class);
		doThrow(new RuntimeException("broker down")).when(kafka).send(anyString(), anyString(), anyString());
		DemandAlarmProducer producer = new DemandAlarmProducer(kafka);

		assertDoesNotThrow(() -> producer.publishDemandOverLimit(config(), meter(), 120, 100,
				LocalDateTime.of(2026, 8, 11, 10, 30)));
	}

}
