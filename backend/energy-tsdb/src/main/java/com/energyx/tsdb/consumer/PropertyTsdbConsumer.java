package com.energyx.tsdb.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.energyx.common.kafka.KafkaRecordHandler;
import com.energyx.common.message.ThingPropertyMessage;
import com.energyx.common.redis.MessageDedup;
import com.energyx.tsdb.config.TsdbProperties;
import com.energyx.tsdb.sql.TdengineSqlBuilder;
import com.energyx.tsdb.sql.TsdbBatchBuffer;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;

/**
 * 属性摄取：消费 iot-thing-property（key=deviceId，分区内保序）→ 边界幂等 （stage=tsdb）→ 构造宽表 SQL → 批量缓冲。
 *
 * <p>
 * 写入失败由缓冲上抛 → 引擎进 DLQ 并照常提交（at-least-once + 幂等兜底）。 缺 messageId 的消息无法幂等，宁可丢弃不可重复落库（上游
 * access 保证必填）。
 * </p>
 */
@Slf4j
@Component
public class PropertyTsdbConsumer implements KafkaRecordHandler {

	private final ObjectMapper objectMapper;

	private final MessageDedup dedup;

	private final TsdbBatchBuffer buffer;

	private final TsdbProperties props;

	public PropertyTsdbConsumer(ObjectMapper objectMapper, MessageDedup dedup, TsdbBatchBuffer buffer,
			TsdbProperties props) {
		this.objectMapper = objectMapper;
		this.dedup = dedup;
		this.buffer = buffer;
		this.props = props;
	}

	@Override
	public void handle(ConsumerRecord<String, String> record) throws Exception {
		ThingPropertyMessage msg = objectMapper.readValue(record.value(), ThingPropertyMessage.class);
		if (msg.getDeviceId() == null || msg.getProductKey() == null) {
			// 上游 access 保证必填；缺失属脏数据，记录后丢弃（无法定位设备/模型，落库无意义）
			log.warn("[Tsdb] 属性消息缺 deviceId/productKey，丢弃 topic={} key={}", record.topic(), record.key());
			return;
		}
		String messageId = msg.getMessageId();
		if (messageId == null || messageId.isBlank()) {
			log.error("[Tsdb] 属性消息缺 messageId，丢弃 deviceId={}", msg.getDeviceId());
			return;
		}
		if (!dedup.tryOnce("tsdb", msg.getDeviceId(), messageId, props.getMsgDedupTtlSeconds())) {
			return; // 该边界已处理过，幂等跳过
		}
		buffer.add(TdengineSqlBuilder.buildPropertyInsert(msg, props.getRawDb(), objectMapper));
	}

}
