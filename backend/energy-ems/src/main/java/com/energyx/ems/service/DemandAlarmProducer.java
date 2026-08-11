package com.energyx.ems.service;

import com.energyx.common.constant.KafkaTopicConstant;
import com.energyx.common.message.ThingEventMessage;
import com.energyx.ems.entity.EmsDemandConfig;
import com.energyx.ems.model.MeterDevice;
import com.energyx.ems.mqtt.EmsKafkaProducer;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** 需量超限事件发布（P1-2）：同一槽位首超时发一次 iot-thing-event(demandOverLimit)。 */
@Slf4j
@Component
public class DemandAlarmProducer {

	private static final ObjectMapper JSON = new ObjectMapper();

	private final EmsKafkaProducer kafkaProducer;

	public DemandAlarmProducer(EmsKafkaProducer kafkaProducer) {
		this.kafkaProducer = kafkaProducer;
	}

	/** 发布需量超限事件（messageId 按站+槽位幂等；发布失败仅 log，不抛）。 */
	public void publishDemandOverLimit(EmsDemandConfig config, MeterDevice meter, double demandKw, double limitKw,
			LocalDateTime windowStart) {
		ThingEventMessage evt = new ThingEventMessage();
		evt.setMessageId("demand-" + config.getStationId() + "-" + windowStart);
		evt.setEventId(evt.getMessageId());
		evt.setDeviceId(meter.deviceId());
		evt.setTenantId(config.getTenantId());
		evt.setStationId(config.getStationId());
		evt.setProductKey(meter.productKey());
		evt.setEventName("demandOverLimit");
		evt.setSeverity(severityOf(demandKw, limitKw));
		evt.setCode("DEMAND_OVER_LIMIT");
		evt.setTs(System.currentTimeMillis());
		evt.getData().put("demandKw", demandKw);
		evt.getData().put("limitKw", limitKw);
		evt.getData().put("stationId", config.getStationId());
		try {
			kafkaProducer.send(KafkaTopicConstant.IOT_THING_EVENT, String.valueOf(meter.deviceId()),
					JSON.writeValueAsString(evt));
		}
		catch (Exception e) {
			log.warn("[DemandAlarm] 需量超限事件发布失败 stationId={} msg={}", config.getStationId(), e.getMessage());
		}
	}

	/** 超限比例 ≥1.2 严重(3)，否则一般(2)。 */
	private static int severityOf(double demandKw, double limitKw) {
		return limitKw > 0 && demandKw / limitKw >= 1.2 ? 3 : 2;
	}

}
