package com.sanduo.energy.tsdb.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanduo.energy.common.kafka.KafkaRecordHandler;
import com.sanduo.energy.common.message.ThingEventMessage;
import com.sanduo.energy.common.redis.MessageDedup;
import com.sanduo.energy.tsdb.config.TsdbProperties;
import com.sanduo.energy.tsdb.sql.TdengineSqlBuilder;
import com.sanduo.energy.tsdb.sql.TsdbBatchBuffer;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;

/**
 * 事件摄取：消费 iot-thing-event（key=deviceId）→ 边界幂等（stage=tsdb）
 * → st_event 落库（payload JSON 列）。幂等键用 messageId（事件去重/追踪实例级
 * 由 eventId 承担，跨边界消费幂等仍以 messageId 为准）。
 */
@Slf4j
@Component
public class EventTsdbConsumer implements KafkaRecordHandler {

    private final ObjectMapper objectMapper;
    private final MessageDedup dedup;
    private final TsdbBatchBuffer buffer;
    private final TsdbProperties props;

    public EventTsdbConsumer(ObjectMapper objectMapper, MessageDedup dedup,
                             TsdbBatchBuffer buffer, TsdbProperties props) {
        this.objectMapper = objectMapper;
        this.dedup = dedup;
        this.buffer = buffer;
        this.props = props;
    }

    @Override
    public void handle(ConsumerRecord<String, String> record) throws Exception {
        ThingEventMessage msg = objectMapper.readValue(record.value(), ThingEventMessage.class);
        if (msg.getDeviceId() == null) {
            log.warn("[Tsdb] 事件消息缺 deviceId，丢弃 topic={} key={}", record.topic(), record.key());
            return;
        }
        String messageId = msg.getMessageId();
        if (messageId == null || messageId.isBlank()) {
            log.error("[Tsdb] 事件消息缺 messageId，丢弃 deviceId={} eventId={}", msg.getDeviceId(), msg.getEventId());
            return;
        }
        if (!dedup.tryOnce("tsdb", msg.getDeviceId(), messageId, props.getMsgDedupTtlSeconds())) {
            return; // 该边界已处理过，幂等跳过
        }
        buffer.add(TdengineSqlBuilder.buildEventInsert(msg, props.getEventDb(), objectMapper));
    }
}
