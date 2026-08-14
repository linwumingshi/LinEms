package com.energyx.rule.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.energyx.common.kafka.KafkaRecordHandler;
import com.energyx.common.message.ThingPropertyMessage;
import com.energyx.common.redis.MessageDedup;
import com.energyx.rule.engine.RuleContext;
import com.energyx.rule.engine.RuleEngine;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;

/**
 * 属性上报消费者：消费 iot-thing-property（key=deviceId）→ 属性触发规则检测。
 *
 * <p>
 * 消息级去重用 rule 边界独立 SETNX（Redis-key 规范 §3.5）；同 deviceId 分区内保序。
 * </p>
 */
@Slf4j
@Component
public class PropertyRuleConsumer implements KafkaRecordHandler {

	private final ObjectMapper objectMapper;

	private final MessageDedup messageDedup;

	private final RuleEngine ruleEngine;

	public PropertyRuleConsumer(ObjectMapper objectMapper, MessageDedup messageDedup, RuleEngine ruleEngine) {
		this.objectMapper = objectMapper;
		this.messageDedup = messageDedup;
		this.ruleEngine = ruleEngine;
	}

	@Override
	public void handle(ConsumerRecord<String, String> record) throws Exception {
		ThingPropertyMessage msg = objectMapper.readValue(record.value(), ThingPropertyMessage.class);
		if (msg.getDeviceId() == null || msg.getMessageId() == null) {
			log.warn("[Rule] 属性消息缺 deviceId/messageId，丢弃 key={}", record.key());
			return;
		}
		// rule 消费边界独立去重（重放不重复触发规则）
		boolean first = messageDedup.tryOnce("rule", msg.getDeviceId(), msg.getMessageId(), 300);
		log.info("[Rule] 属性消息消费 deviceId={} messageId={} productKey={} first={}", msg.getDeviceId(), msg.getMessageId(),
				msg.getProductKey(), first);
		if (!first) {
			return;
		}
		RuleContext ctx = new RuleContext();
		ctx.setTriggerType("PROPERTY");
		ctx.setDeviceId(msg.getDeviceId());
		ctx.setTenantId(msg.getTenantId());
		ctx.setProductKey(msg.getProductKey());
		ctx.setProperties(msg.getProperties() == null ? new java.util.LinkedHashMap<>() : msg.getProperties());
		ctx.setTs(msg.getTs());
		ctx.setRaw(record.value());
		ruleEngine.onProperty(ctx);
	}

}
