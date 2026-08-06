package com.sanduo.energy.alarm.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanduo.energy.alarm.service.AlarmService;
import com.sanduo.energy.common.kafka.KafkaRecordHandler;
import com.sanduo.energy.common.message.ThingEventMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;

/**
 * 事件规则消费者：消费 iot-thing-event（key=deviceId）→ 事件标识匹配 + 静默防抖。
 *
 * <p>事件为一次性上报，命中即触发（级别取事件携带 severity）；静默期防抖合并，避免设备
 * 连续上报同类事件造成告警风暴。幂等性同属性规则。</p>
 */
@Slf4j
@Component
public class EventAlarmConsumer implements KafkaRecordHandler {

    private final ObjectMapper objectMapper;
    private final AlarmService alarmService;

    public EventAlarmConsumer(ObjectMapper objectMapper, AlarmService alarmService) {
        this.objectMapper = objectMapper;
        this.alarmService = alarmService;
    }

    @Override
    public void handle(ConsumerRecord<String, String> record) throws Exception {
        ThingEventMessage msg = objectMapper.readValue(record.value(), ThingEventMessage.class);
        alarmService.handleEventReport(msg);
    }
}
