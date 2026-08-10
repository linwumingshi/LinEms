package com.energyx.shadow.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.energyx.common.kafka.KafkaRecordHandler;
import com.energyx.common.message.ThingPropertyMessage;
import com.energyx.shadow.service.ShadowService;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;

/**
 * 影子 reported 消费者：消费 iot-thing-property（key=deviceId，分区内保序）→ 影子双写。
 *
 * <p>
 * <b>刻意不做消息级去重</b>：影子合并且有版本乐观锁，天然幂等。若先写 dedup key 再合并， 一旦合并过程中 Redis 成功 / MySQL 失败，Kafka
 * 重放会被去重直接跳过，MySQL 永不收敛； 不设去重则重放重跑合并，自我收敛。缺陷仅是同一设备同值快照的重复合并（结果不变），可接受。
 * </p>
 */
@Slf4j
@Component
public class PropertyShadowConsumer implements KafkaRecordHandler {

	private final ObjectMapper objectMapper;

	private final ShadowService shadowService;

	public PropertyShadowConsumer(ObjectMapper objectMapper, ShadowService shadowService) {
		this.objectMapper = objectMapper;
		this.shadowService = shadowService;
	}

	@Override
	public void handle(ConsumerRecord<String, String> record) throws Exception {
		ThingPropertyMessage msg = objectMapper.readValue(record.value(), ThingPropertyMessage.class);
		if (msg.getDeviceId() == null || msg.getProperties() == null || msg.getProperties().isEmpty()) {
			log.warn("[Shadow] 属性消息缺 deviceId/属性，丢弃 topic={} key={}", record.topic(), record.key());
			return;
		}
		long tenantId = msg.getTenantId() == null ? 0L : msg.getTenantId();
		shadowService.applyReported(msg.getDeviceId(), tenantId, msg.getProperties());
	}

}
