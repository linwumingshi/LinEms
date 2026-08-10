package com.energyx.common.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;

/**
 * Kafka 记录处理回调。
 *
 * <p>
 * 契约：{@link KafkaConsumerEngine} 保证同一分区内串行调用（顺序性）， 但多线程消费时不同分区可能并发——实现必须线程安全（只依赖线程安全的
 * Bean）。
 * </p>
 */
@FunctionalInterface
public interface KafkaRecordHandler {

	/**
	 * 处理单条记录；抛异常表示处理失败（引擎记录日志并按需进 DLQ，随后照常提交偏移，防毒丸阻塞）。
	 * @param record 已反序列化为 String 的记录（key/value 均为 String）
	 * @throws Exception 处理失败
	 */
	void handle(ConsumerRecord<String, String> record) throws Exception;

}
