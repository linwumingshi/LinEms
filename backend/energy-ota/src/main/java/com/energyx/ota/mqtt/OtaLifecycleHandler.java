package com.energyx.ota.mqtt;

import com.energyx.common.kafka.KafkaRecordHandler;
import com.energyx.common.message.LifecycleMessage;
import com.energyx.ota.service.OtaTaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;

/**
 * 设备生命周期事件处理器（消费 iot-device-lifecycle）。
 *
 * <p>
 * 设备上线（ONLINE）→ 触发任务补推：存在待升级任务明细的设备收到升级通知 （离线设备任务创建时无法直推，上线后在此补推）。
 * </p>
 */
@Slf4j
@Component
public class OtaLifecycleHandler implements KafkaRecordHandler {

	private final OtaTaskService taskService;

	private final ObjectMapper objectMapper;

	public OtaLifecycleHandler(OtaTaskService taskService, ObjectMapper objectMapper) {
		this.taskService = taskService;
		this.objectMapper = objectMapper;
	}

	@Override
	public void handle(ConsumerRecord<String, String> record) throws Exception {
		LifecycleMessage msg;
		try {
			msg = objectMapper.readValue(record.value(), LifecycleMessage.class);
		}
		catch (Exception e) {
			return;
		}
		if (msg == null || msg.getDeviceId() == null) {
			return;
		}
		if ("ONLINE".equalsIgnoreCase(msg.getEventType())) {
			log.debug("[OTA] 设备上线触发任务补推 deviceId={}", msg.getDeviceId());
			taskService.onDeviceOnline(msg.getDeviceId());
		}
	}

}
