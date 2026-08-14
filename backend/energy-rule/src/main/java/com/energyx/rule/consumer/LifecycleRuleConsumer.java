package com.energyx.rule.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.energyx.common.kafka.KafkaRecordHandler;
import com.energyx.common.message.LifecycleMessage;
import com.energyx.rule.engine.RuleContext;
import com.energyx.rule.engine.RuleEngine;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 设备生命周期消费者：消费 iot-device-lifecycle（key=deviceId）→ 上下线触发规则。
 *
 * <p>
 * ONLINE/OFFLINE 事件天然低频率，不做消息级去重（规则侧用状态机兜底）； 事件消息自带
 * deviceName（LifecycleMessage），可直接构造上下文。
 * </p>
 */
@Slf4j
@Component
public class LifecycleRuleConsumer implements KafkaRecordHandler {

	private final ObjectMapper objectMapper;

	private final RuleEngine ruleEngine;

	public LifecycleRuleConsumer(ObjectMapper objectMapper, RuleEngine ruleEngine) {
		this.objectMapper = objectMapper;
		this.ruleEngine = ruleEngine;
	}

	@Override
	public void handle(ConsumerRecord<String, String> record) throws Exception {
		LifecycleMessage msg = objectMapper.readValue(record.value(), LifecycleMessage.class);
		if (msg.getDeviceId() == null || msg.getEventType() == null) {
			log.warn("[Rule] 生命周期消息缺 deviceId/eventType，丢弃 key={}", record.key());
			return;
		}
		RuleContext ctx = new RuleContext();
		ctx.setTriggerType("LIFECYCLE");
		ctx.setDeviceId(msg.getDeviceId());
		ctx.setTenantId(msg.getTenantId());
		ctx.setProductKey(msg.getProductKey());
		ctx.setDeviceName(msg.getDeviceName());
		ctx.setLifecycleEvent(msg.getEventType());
		ctx.setTs(msg.getTs());
		ctx.setRaw(record.value());
		Map<String, Object> extra = new LinkedHashMap<>();
		extra.put("brokerNode", msg.getBrokerNode());
		extra.put("reason", msg.getReason());
		ctx.setPayload(extra);
		ruleEngine.onLifecycle(ctx);
	}

}
