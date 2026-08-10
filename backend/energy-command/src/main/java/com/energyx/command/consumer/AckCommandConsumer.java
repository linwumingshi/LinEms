package com.energyx.command.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.energyx.command.service.CommandService;
import com.energyx.common.kafka.KafkaRecordHandler;
import com.energyx.common.message.CommandAckMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;

/**
 * ACK 消费者：消费 iot-command-ack（key=commandId，分区内保序）→ 状态机收敛。
 *
 * <p>
 * 不设消息级去重：状态迁移用「WHERE state ∈ 合法前驱」条件更新，Kafka 重放/重复 ACK 自然空操作。 终态/非法转移直接忽略（见
 * CommandService.applyAck）。
 * </p>
 */
@Slf4j
@Component
public class AckCommandConsumer implements KafkaRecordHandler {

	private final ObjectMapper objectMapper;

	private final CommandService commandService;

	public AckCommandConsumer(ObjectMapper objectMapper, CommandService commandService) {
		this.objectMapper = objectMapper;
		this.commandService = commandService;
	}

	@Override
	public void handle(ConsumerRecord<String, String> record) throws Exception {
		CommandAckMessage ack = objectMapper.readValue(record.value(), CommandAckMessage.class);
		commandService.applyAck(ack);
	}

}
