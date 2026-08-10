package com.energyx.broker.stats;

import com.energyx.broker.routing.LocalSubscriberIndex;
import com.energyx.broker.session.SessionRegistry;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Broker Micrometer 指标注册（Prometheus 出口：/actuator/prometheus，管理端口 8082）。
 *
 * <p>计数器以 {@link BrokerStats} 的 AtomicLong 为数据源（FunctionCounter 弱引用读取），
 * 与 {@code /internal/broker/stats} 端点共享同一份计数，不产生双写不一致。</p>
 *
 * <p>指标清单：
 * <ul>
 *   <li>mqtt_broker_connections / mqtt_broker_subscriptions（Gauge）</li>
 *   <li>mqtt_messages_in/out_total、mqtt_messages_routed_crossnode_total</li>
 *   <li>mqtt_auth_failures_total、mqtt_connections_accepted/rejected_total</li>
 *   <li>mqtt_route_failures_total（路由持久化失败，QoS1/2 上行降级关连接）</li>
 *   <li>mqtt_backpressure_parked/dropped_total、mqtt_inflight_overflow_total</li>
 *   <li>broker_executor_queue_size / threads_active / threads_pool / tasks_completed_total</li>
 *   <li>mqtt_uplink_puback_latency_seconds（直方图：PUBLISH→Kafka 确认→PUBACK 端到端时延）</li>
 * </ul>
 * JVM 指标由 actuator 自动注册（jvm_memory/jvm_gc/process 等）。</p>
 */
@Component
public class BrokerMetrics {

    private final Timer pubAckLatency;

    public BrokerMetrics(MeterRegistry registry,
                         BrokerStats stats,
                         SessionRegistry sessionRegistry,
                         LocalSubscriberIndex subscriberIndex,
                         @Qualifier("brokerExecutor") ExecutorService brokerExecutor) {
        Gauge.builder("mqtt.broker.connections", sessionRegistry, SessionRegistry::connectionCount)
                .description("当前在线 MQTT 连接数").register(registry);
        Gauge.builder("mqtt.broker.subscriptions", subscriberIndex, LocalSubscriberIndex::size)
                .description("当前订阅绑定数").register(registry);

        counter(registry, "mqtt.messages.in", stats.messagesIn, "上行消息总数");
        counter(registry, "mqtt.messages.out", stats.messagesOut, "下行消息总数");
        counter(registry, "mqtt.messages.routed.crossnode", stats.messagesRoutedCrossNode, "跨节点路由消息总数");
        counter(registry, "mqtt.auth.failures", stats.authFailures, "认证失败总数");
        counter(registry, "mqtt.connections.accepted", stats.acceptedConnections, "接入成功总数");
        counter(registry, "mqtt.connections.rejected", stats.rejectedConnections, "接入拒绝总数");
        counter(registry, "mqtt.route.failures", stats.routeFailures, "路由持久化失败总数");
        counter(registry, "mqtt.backpressure.parked", stats.backpressureParked, "背压挂起总数");
        counter(registry, "mqtt.backpressure.dropped", stats.backpressureDropped, "背压丢弃总数");
        counter(registry, "mqtt.inflight.overflow", stats.inflightOverflow, "inflight 超限总数");
        counter(registry, "mqtt.executor.rejected", stats.executorRejected, "业务线程池拒绝任务总数");
        counter(registry, "mqtt.rate.limited", stats.rateLimited, "单设备发布超限拦截总数");
        counter(registry, "mqtt.auth.overload.rejected", stats.authOverloadRejected, "认证并发超限拒绝总数");
        counter(registry, "mqtt.packet.size.exceeded", stats.packetSizeExceeded, "下行超客户端 Maximum Packet Size 总数");

        if (brokerExecutor instanceof ThreadPoolExecutor tpe) {
            Gauge.builder("broker.executor.queue.size", tpe, e -> e.getQueue().size())
                    .description("业务线程池队列深度").register(registry);
            Gauge.builder("broker.executor.threads.active", tpe, ThreadPoolExecutor::getActiveCount)
                    .description("业务线程池活跃线程数").register(registry);
            Gauge.builder("broker.executor.threads.pool", tpe, ThreadPoolExecutor::getPoolSize)
                    .description("业务线程池线程数").register(registry);
            FunctionCounter.builder("broker.executor.tasks.completed", tpe,
                            ThreadPoolExecutor::getCompletedTaskCount)
                    .description("业务线程池累计完成任务数").register(registry);
        }

        this.pubAckLatency = Timer.builder("mqtt.uplink.puback.latency")
                .description("QoS1 上行 PUBLISH → Kafka 持久化确认 → PUBACK 端到端时延")
                .publishPercentileHistogram()
                .register(registry);
    }

    private void counter(MeterRegistry registry, String name, AtomicLong counter, String description) {
        FunctionCounter.builder(name, counter, AtomicLong::get).description(description).register(registry);
    }

    /** 记录一次 QoS1 上行 ACK 时延（PUBLISH 接收到 → 路由持久化确认） */
    public void recordPubAckLatency(long publishStartNanos) {
        pubAckLatency.record(System.nanoTime() - publishStartNanos, TimeUnit.NANOSECONDS);
    }
}
