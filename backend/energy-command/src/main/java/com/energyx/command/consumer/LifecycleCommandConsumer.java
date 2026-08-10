package com.energyx.command.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.energyx.command.service.CommandService;
import com.energyx.common.kafka.KafkaRecordHandler;
import com.energyx.common.message.LifecycleMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;

/**
 * 生命周期消费者：消费 iot-device-lifecycle（key=deviceId），设备上线时补发离线队列。
 *
 * <p>
 * 补发天然幂等：队列 LPOP 后按 commandId 状态条件更新，重复 lifecycle 空队列无操作； 崩溃窗口（LPOP 后未置
 * SENT）由超时扫描兜底——补发先置 SENT 再下发，即使丢队列， 已置 SENT 的指令会被超时扫描重试拉回。
 * </p>
 */
@Slf4j
@Component
public class LifecycleCommandConsumer implements KafkaRecordHandler {

	private static final String ONLINE = "ONLINE";

	private final ObjectMapper objectMapper;

	private final CommandService commandService;

	public LifecycleCommandConsumer(ObjectMapper objectMapper, CommandService commandService) {
		this.objectMapper = objectMapper;
		this.commandService = commandService;
	}

	@Override
	public void handle(ConsumerRecord<String, String> record) throws Exception {
		LifecycleMessage lifecycle = objectMapper.readValue(record.value(), LifecycleMessage.class);
		if (ONLINE.equals(lifecycle.getEventType()) && lifecycle.getDeviceId() != null) {
			commandService.drainOfflineQueue(lifecycle.getDeviceId());
		}
	}

}
