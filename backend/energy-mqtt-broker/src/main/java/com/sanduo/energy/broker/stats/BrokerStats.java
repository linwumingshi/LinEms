package com.sanduo.energy.broker.stats;

import com.sanduo.energy.broker.routing.LocalSubscriberIndex;
import com.sanduo.energy.broker.session.SessionRegistry;
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
        return map;
    }
}
