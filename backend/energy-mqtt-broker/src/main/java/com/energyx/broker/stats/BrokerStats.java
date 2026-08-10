package com.energyx.broker.stats;

import com.energyx.broker.routing.LocalSubscriberIndex;
import com.energyx.broker.session.SessionRegistry;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Broker 运行指标（轻量计数器，Phase 8 对接 Prometheus 前先自持）。
 */
@Component
public class BrokerStats {

    public final AtomicLong messagesIn = new AtomicLong();
    public final AtomicLong messagesOut = new AtomicLong();
    public final AtomicLong messagesRoutedCrossNode = new AtomicLong();
    public final AtomicLong authFailures = new AtomicLong();
    public final AtomicLong acceptedConnections = new AtomicLong();
    public final AtomicLong rejectedConnections = new AtomicLong();
    /** 路由（Kafka）持久化失败次数：QoS1/2 上行因此关连接迫使设备重传 */
    public final AtomicLong routeFailures = new AtomicLong();
    /** 背压挂起次数（channel 不可写，报文转入 pending 队列） */
    public final AtomicLong backpressureParked = new AtomicLong();
    /** 背压丢弃次数（pending 超限：QoS0 丢弃 / QoS1/2 依赖 inflight 重连续传） */
    public final AtomicLong backpressureDropped = new AtomicLong();
    /** inflight 超限次数（max-inflight-per-session 生效） */
    public final AtomicLong inflightOverflow = new AtomicLong();
    /** brokerExecutor 业务线程池拒绝次数（任务被丢弃，认证/持久化降级） */
    public final AtomicLong executorRejected = new AtomicLong();
    /** 速率限制拦截次数（P2-7 单设备发布超限） */
    public final AtomicLong rateLimited = new AtomicLong();
    /** 认证并发超限拒绝次数（P2-8 认证风暴防护） */
    public final AtomicLong authOverloadRejected = new AtomicLong();

    private final SessionRegistry sessionRegistry;
    private final LocalSubscriberIndex subscriberIndex;

    public BrokerStats(SessionRegistry sessionRegistry, LocalSubscriberIndex subscriberIndex) {
        this.sessionRegistry = sessionRegistry;
        this.subscriberIndex = subscriberIndex;
    }

    public void recordIncoming() {
        messagesIn.incrementAndGet();
    }

    public void recordOutgoing() {
        messagesOut.incrementAndGet();
    }

    public void recordCrossNode() {
        messagesRoutedCrossNode.incrementAndGet();
    }

    public void recordAuthFailure() {
        authFailures.incrementAndGet();
    }

    public void recordAccepted() {
        acceptedConnections.incrementAndGet();
    }

    public void recordRejected() {
        rejectedConnections.incrementAndGet();
    }

    public void recordRouteFailure() {
        routeFailures.incrementAndGet();
    }

    public void recordBackpressureParked() {
        backpressureParked.incrementAndGet();
    }

    public void recordBackpressureDrop() {
        backpressureDropped.incrementAndGet();
    }

    public void recordInflightOverflow() {
        inflightOverflow.incrementAndGet();
    }

    /** 业务线程池拒绝任务（P1-10 可观测性：认证风暴/持久化洪峰直接可见） */
    public void recordExecutorRejected() {
        executorRejected.incrementAndGet();
    }

    /** 单设备发布超限被拦截（P2-7） */
    public void recordRateLimited() {
        rateLimited.incrementAndGet();
    }

    /** 认证并发超限拒绝（P2-8） */
    public void recordAuthOverloadRejected() {
        authOverloadRejected.incrementAndGet();
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("connections", sessionRegistry.connectionCount());
        map.put("subscriptions", subscriberIndex.size());
        map.put("messagesIn", messagesIn.get());
        map.put("messagesOut", messagesOut.get());
        map.put("messagesRoutedCrossNode", messagesRoutedCrossNode.get());
        map.put("authFailures", authFailures.get());
        map.put("acceptedConnections", acceptedConnections.get());
        map.put("rejectedConnections", rejectedConnections.get());
        map.put("routeFailures", routeFailures.get());
        map.put("backpressureParked", backpressureParked.get());
        map.put("backpressureDropped", backpressureDropped.get());
        map.put("inflightOverflow", inflightOverflow.get());
        map.put("executorRejected", executorRejected.get());
        map.put("rateLimited", rateLimited.get());
        map.put("authOverloadRejected", authOverloadRejected.get());
        return map;
    }
}
