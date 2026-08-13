package com.energyx.common.kafka;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.CommitFailedException;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.CooperativeStickyAssignor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

/**
 * 字节消费引擎（二进制信封专用，如 mqtt.uplink / mqtt.down.{nodeId} / mqtt.broadcast）。
 *
 * <p>
 * 与 {@link KafkaConsumerEngine} 同构：分区并行 + 分区内保序、手动提交（整批处理完 commitSync）、单条失败不阻塞分区（记日志 + 进
 * DLQ 后照常提交）、Kafka 不可达重试不退出、 close() 唤醒 join 有界等待。差异仅在 value 反序列化为 byte[]（免 String↔bytes
 * 往返）。
 * </p>
 *
 * <p>
 * 支持多 topic 订阅（同组）：mqtt.down.{nodeId} 定向 + mqtt.broadcast 广播可共组或分实例， 由调用方决定。
 * </p>
 */
@Slf4j
public class BytesKafkaConsumerEngine implements AutoCloseable {

	@FunctionalInterface
	public interface DlqSink {

		void send(String topic, String key, byte[] value);

	}

	private final List<String> topics;

	private final String groupId;

	private final BytesKafkaRecordHandler handler;

	private final Properties baseProps;

	private final int threadCount;

	private final long pollMs;

	private final DlqSink dlqSink;

	private final String dlqTopic;

	private final List<Thread> threads = new ArrayList<>();

	private volatile boolean running;

	public BytesKafkaConsumerEngine(List<String> topics, String groupId, BytesKafkaRecordHandler handler,
			Properties baseProps, int threadCount, long pollMs, DlqSink dlqSink, String dlqTopic) {
		this.topics = List.copyOf(Objects.requireNonNull(topics, "topics"));
		this.groupId = Objects.requireNonNull(groupId, "groupId");
		this.handler = Objects.requireNonNull(handler, "handler");
		this.baseProps = baseProps;
		this.threadCount = Math.max(1, threadCount);
		this.pollMs = Math.max(50, pollMs);
		this.dlqSink = dlqSink;
		this.dlqTopic = dlqTopic;
	}

	public void start() {
		running = true;
		for (int i = 0; i < threadCount; i++) {
			Thread t = new Thread(this::runLoop, "kafka-bcons-" + groupId + "-" + i);
			t.setDaemon(true);
			t.start();
			threads.add(t);
		}
		log.info("[Kafka] 字节消费引擎启动 topics={} group={} threads={} dlq={}", topics, groupId, threadCount, dlqTopic);
	}

	private void runLoop() {
		Properties props = new Properties();
		props.putAll(baseProps);
		props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
		props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
		// 统一采用协作式再平衡（增量再平衡）：扩缩容 / 故障接管 / 连接锁接管时仅迁移被重分配的分区，
		// 其余分区不中断，避免停世界式（RangeAssignor）重平衡对下行实时性的冲击。
		props.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, CooperativeStickyAssignor.class.getName());
		props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
		props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
		try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(props)) {
			consumer.subscribe(topics);
			while (running) {
				ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(pollMs));
				if (records.isEmpty()) {
					continue;
				}
				for (ConsumerRecord<String, byte[]> record : records) {
					try {
						handler.handle(record);
					}
					catch (Exception e) {
						log.error("[Kafka] 字节消费处理失败 topic={} group={} key={} partition={} offset={}", record.topic(),
								groupId, record.key(), record.partition(), record.offset(), e);
						if (dlqSink != null && dlqTopic != null && !dlqTopic.isBlank()) {
							try {
								dlqSink.send(dlqTopic, record.key(), record.value());
							}
							catch (Exception dlqEx) {
								log.error("[Kafka] DLQ 写入失败 topic={} dlq={}", record.topic(), dlqTopic, dlqEx);
							}
						}
					}
				}
				try {
					consumer.commitSync();
				}
				catch (CommitFailedException e) {
					log.warn("[Kafka] 提交偏移失败（重平衡/超时） topics={} group={}", topics, groupId, e);
				}
			}
		}
		catch (WakeupException ignore) {
			// 停机中断唤醒
		}
		catch (Exception e) {
			if (running) {
				log.error("[Kafka] 字节消费线程异常退出 topics={} group={}", topics, groupId, e);
			}
		}
		finally {
			log.info("[Kafka] 字节消费线程退出 topics={} group={}", topics, groupId);
		}
	}

	@Override
	public void close() {
		running = false;
		threads.forEach(Thread::interrupt);
		threads.forEach(t -> {
			try {
				t.join(3000);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		log.info("[Kafka] 字节消费引擎关闭 topics={} group={}", topics, groupId);
	}

}
