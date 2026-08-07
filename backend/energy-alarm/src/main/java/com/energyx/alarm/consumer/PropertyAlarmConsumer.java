package com.energyx.alarm.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.energyx.alarm.service.AlarmService;
import com.energyx.common.kafka.KafkaRecordHandler;
import com.energyx.common.message.ThingPropertyMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;

/**
 * 属性规则消费者：消费 iot-thing-property（key=deviceId，分区内保序）→ 阈值比较/持续窗口/恢复。
 *
 * <p>不设消息级去重：触发靠静默 SETNX 原子防抖 + alarm_event_id 雪花唯一主键，
 * Kafka 重放不会产生重复告警记录（见 AlarmService 幂等性说明）。</p>
 */
@Slf4j
@Component
public class PropertyAlarmConsumer implements KafkaRecordHandler {

    private final ObjectMapper objectMapper;
    private final AlarmService alarmService;

    public PropertyAlarmConsumer(ObjectMapper objectMapper, AlarmService alarmService) {
        this.objectMapper = objectMapper;
        this.alarmService = alarmService;
    }

    @Override
    public void handle(ConsumerRecord<String, String> record) throws Exception {
        ThingPropertyMessage msg = objectMapper.readValue(record.value(), ThingPropertyMessage.class);
        alarmService.handlePropertyReport(msg);
    }
}
