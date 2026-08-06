package com.sanduo.energy.alarm.service;

import com.sanduo.energy.alarm.config.AlarmProperties;
import com.sanduo.energy.alarm.es.AlarmEsWriter;
import com.sanduo.energy.alarm.mqtt.AlarmKafkaProducer;
import com.sanduo.energy.alarm.ws.AlarmWebSocketHandler;
import com.sanduo.energy.common.message.AlarmMessage;
import com.sanduo.energy.common.web.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 告警事件多路发布门面：Kafka（iot-alarm）+ WebSocket（/ws/alarm）+ ES（es-alarm-log-{yyyyMM}）。
 *
 * <p>AlarmService 只依赖本门面，Kafka/WS/ES 三路下游各自失败互不牵连、且不影响告警主链路
 * （权威源是 MySQL iot_alarm_record）。</p>
 */
@Slf4j
@Component
public class AlarmKafkaPublisher {

    private final AlarmKafkaProducer producer;
    private final AlarmWebSocketHandler webSocketHandler;
    private final AlarmEsWriter esWriter;
    private final AlarmProperties props;

    public AlarmKafkaPublisher(AlarmKafkaProducer producer, AlarmWebSocketHandler webSocketHandler,
                               AlarmEsWriter esWriter, AlarmProperties props) {
        this.producer = producer;
        this.webSocketHandler = webSocketHandler;
        this.esWriter = esWriter;
        this.props = props;
    }

    public void send(String topic, String key, String json) {
        producer.send(topic, key, json);
    }

    public void broadcast(String json) {
        webSocketHandler.broadcast(json);
    }

    /** ES 落库（尽力而为，字段对齐 alarm_log.mapping.json） */
    public void writeEs(AlarmMessage m) {
        if (!props.isEsEnabled()) {
            return;
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("@timestamp", Instant.now().toString());
        doc.put("trace_id", TraceContext.getTraceId());
        doc.put("tenant_id", m.getTenantId());
        doc.put("alarm_event_id", m.getAlarmEventId());
        doc.put("device_id", String.valueOf(m.getDeviceId()));
        doc.put("product_key", m.getProductKey());
        doc.put("rule_id", m.getRuleId());
        doc.put("rule_code", m.getRuleCode());
        doc.put("level", String.valueOf(m.getLevel()));
        doc.put("type", String.valueOf(m.getType()));
        doc.put("status", m.getStatus());
        doc.put("message", m.getMessage());
        doc.put("ext", m.getExt());
        esWriter.writeAsync(m.getAlarmEventId(), doc);
    }
}
