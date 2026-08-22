package com.energyx.access.processor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.energyx.access.lifecycle.LifecycleProcessor;
import com.energyx.common.kafka.KafkaRecordHandler;
import com.energyx.common.message.LifecycleMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;

/**
 * 设备生命周期消费（iot-device-lifecycle，key=deviceId）。 委托 LifecycleProcessor 刷新设备在线态 + 上下线记录 +
 * 离线指令补发。
 */
@Slf4j
@Component
public class LifecycleConsumer implements KafkaRecordHandler {

	private final LifecycleProcessor processor;

	private final ObjectMapper objectMapper;

	public LifecycleConsumer(LifecycleProcessor processor, ObjectMapper objectMapper) {
		this.processor = processor;
		this.objectMapper = objectMapper;
	}

	/**
	 * 消费生命周期消息：反序列化后委托 LifecycleProcessor 落地设备在线态与上下线记录。
	 * @param record Kafka 消费者记录（value 为 LifecycleMessage JSON）
	 */
	@Override
	public void handle(ConsumerRecord<String, String> record) {
		LifecycleMessage msg;
		try {
			msg = objectMapper.readValue(record.value(), LifecycleMessage.class);
		}
		catch (Exception e) {
			log.warn("[Access] lifecycle 反序列化失败 key={} offset={}，进 DLQ", record.key(), record.offset());
			throw new IllegalArgumentException("lifecycle 反序列化失败", e);
		}
		processor.process(msg);
	}

}
