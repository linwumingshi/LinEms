package com.energyx.rule.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.energyx.common.kafka.KafkaRecordHandler;
import com.energyx.common.message.AlarmMessage;
import com.energyx.rule.engine.RuleContext;
import com.energyx.rule.engine.RuleEngine;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 告警事件消费者：消费 iot-alarm（key=deviceId）→ 告警触发规则。
 *
 * <p>
 * 告警消息自带 ruleCode/level/status，天然去重（告警中心静默窗口兜底），不做消息级去重。
 * </p>
 */
@Slf4j
@Component
public class AlarmRuleConsumer implements KafkaRecordHandler {

	private final ObjectMapper objectMapper;

	private final RuleEngine ruleEngine;

	public AlarmRuleConsumer(ObjectMapper objectMapper, RuleEngine ruleEngine) {
		this.objectMapper = objectMapper;
		this.ruleEngine = ruleEngine;
	}

	@Override
	public void handle(ConsumerRecord<String, String> record) throws Exception {
		AlarmMessage msg = objectMapper.readValue(record.value(), AlarmMessage.class);
		if (msg.getDeviceId() == null) {
			log.warn("[Rule] 告警消息缺 deviceId，丢弃 key={}", record.key());
			return;
		}
		RuleContext ctx = new RuleContext();
		ctx.setTriggerType("ALARM");
		ctx.setDeviceId(msg.getDeviceId());
		ctx.setTenantId(msg.getTenantId());
		ctx.setProductKey(msg.getProductKey());
		ctx.setTs(msg.getTs());
		ctx.setRaw(record.value());
		Map<String, Object> alarm = new LinkedHashMap<>();
		alarm.put("code", msg.getRuleCode());
		alarm.put("level", msg.getLevel());
		alarm.put("state", msg.getStatus());
		alarm.put("alarmEventId", msg.getAlarmEventId());
		alarm.put("message", msg.getMessage());
		ctx.setAlarm(alarm);
		ruleEngine.onAlarm(ctx);
	}

}
