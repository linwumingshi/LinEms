package com.energyx.common.kafka;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.CommitFailedException;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

/**
 * 原生 kafka-clients 消费引擎（镜像缺失 spring-boot-starter-kafka，统一走原生客户端）。
 *
 * <p>
 * 设计要点：
 * <ul>
 * <li><b>分区并行 + 分区内保序</b>：N 个线程各持一个同组 KafkaConsumer，分区由组自动分配， 单分区仅被一个线程消费 ⇒ 同 deviceId
 * 消息严格有序，跨分区天然并行；</li>
 * <li><b>手动提交</b>：enable.auto.commit=false，poll 批次处理完成后 commitSync； 记录级失败不阻塞分区（毒丸防护），记日志
 * + 按需进 DLQ 后照常提交；</li>
 * <li><b>容错</b>：Kafka 不可达时线程重试不退出（daemon），服务可先于 Kafka 启动；</li>
 * <li><b>停机</b>：close() 置位 + 中断唤醒 poll，join 有界等待。</li>
 * </ul>
 * </p>
 *
 * <p>
 * 丢失防护（Phase 1 §7.3）：at-least-once 语义，重复靠各消费边界 {@code MessageDedup} 幂等； 提交在整批处理之后 ⇒
 * 处理失败且未进 DLQ 的记录只会随重放重现，不丢失。
 * </p>
 */
@Slf4j
public class KafkaConsumerEngine implements AutoCloseable {

	/** 死信通道写入回调（模块注入各自的 Kafka 生产者） */
	@FunctionalInterface
	public interface DlqSink {

		void send(String topic, String key, String value);

	}

	private final String topic;

	private final String groupId;

	private final KafkaRecordHandler handler;

	private final Properties baseProps;

	private final int threadCount;

	private final long pollMs;

	private final DlqSink dlqSink;

	private final String dlqTopic;

	private final List<Thread> threads = new ArrayList<>();

	private volatile boolean running;

	public KafkaConsumerEngine(String topic, String groupId, KafkaRecordHandler handler, Properties baseProps,
			int threadCount, long pollMs, DlqSink dlqSink, String dlqTopic) {
		this.topic = Objects.requireNonNull(topic, "topic");
		this.groupId = Objects.requireNonNull(groupId, "groupId");
		this.handler = Objects.requireNonNull(handler, "handler");
		this.baseProps = baseProps;
		this.threadCount = Math.max(1, threadCount);
		this.pollMs = Math.max(50, pollMs);
		this.dlqSink = dlqSink;
		this.dlqTopic = dlqTopic;
	}

	/** 启动消费线程（异步，不阻塞调用方） */
	public void start() {
		running = true;
		for (int i = 0; i < threadCount; i++) {
			Thread t = new Thread(this::runLoop, "kafka-cons-" + groupId + "-" + i);
			t.setDaemon(true);
			t.start();
			threads.add(t);
		}
		log.info("[Kafka] 消费引擎启动 topic={} group={} threads={} dlq={}", topic, groupId, threadCount, dlqTopic);
	}

	private void runLoop() {
		Properties props = new Properties();
		props.putAll(baseProps);
		props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
		props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
		try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
			consumer.subscribe(Collections.singletonList(topic));
			while (running) {
				ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(pollMs));
				if (records.isEmpty()) {
					continue;
				}
				for (ConsumerRecord<String, String> record : records) {
					try {
						handler.handle(record);
					}
					catch (Exception e) {
						log.error("[Kafka] 消费处理失败 topic={} group={} key={} partition={} offset={}", topic, groupId,
								record.key(), record.partition(), record.offset(), e);
						if (dlqSink != null && dlqTopic != null && !dlqTopic.isBlank()) {
							try {
								dlqSink.send(dlqTopic, record.key(), record.value());
							}
							catch (Exception dlqEx) {
								log.error("[Kafka] DLQ 写入失败 topic={} dlq={}", topic, dlqTopic, dlqEx);
							}
						}
					}
				}
				try {
					consumer.commitSync();
				}
				catch (CommitFailedException e) {
					log.warn("[Kafka] 提交偏移失败（重平衡/超时） topic={} group={}", topic, groupId, e);
				}
			}
		}
		catch (WakeupException ignore) {
			// 停机中断唤醒
		}
		catch (Exception e) {
			if (running) {
				log.error("[Kafka] 消费线程异常退出 topic={} group={}", topic, groupId, e);
			}
		}
		finally {
			log.info("[Kafka] 消费线程退出 topic={} group={}", topic, groupId);
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
		log.info("[Kafka] 消费引擎关闭 topic={} group={}", topic, groupId);
	}

}
