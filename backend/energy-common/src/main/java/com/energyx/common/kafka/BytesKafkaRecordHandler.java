package com.energyx.common.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;

/**
 * 字节报文处理器（二进制信封消费边界，如 mqtt.uplink / mqtt.down / mqtt.broadcast）。
 */
@FunctionalInterface
public interface BytesKafkaRecordHandler {

	/** 处理一条字节报文；抛异常由引擎统一记日志 + 进 DLQ（不阻塞分区） */
	void handle(ConsumerRecord<String, byte[]> record) throws Exception;

}
