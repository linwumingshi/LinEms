package com.energyx.broker.config;

import com.energyx.broker.stats.BrokerStats;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Broker 业务线程池配置。
 *
 * <p>原则（Phase 1 §4.5）：Netty IO 线程绝不做阻塞操作（认证 Redis/MySQL、会话持久化、
 * Kafka 生产都属于慢路径），必须剥离到独立业务线程池。</p>
 *
 * <p>线程模型：
 * <ul>
 *   <li>IO 线程（Netty EventLoop）：编解码 + 路由分发（纯内存，无阻塞）；</li>
 *   <li>业务线程（本池）：认证、Redis 会话持久化、Kafka 生命周期生产、离线投递；</li>
 *   <li>Router 消费线程：见 {@code RouterConsumer}，独立专用线程。</li>
 * </ul>
 *
 * <p>拒绝策略为「记日志丢弃」而非 CallerRunsPolicy：后者在队满时会让提交任务的
 * IO 线程内联执行阻塞任务，直接卡死整个 EventLoop。超载时丢一次持久化
 * （QoS 续传/离线队列退化为不可恢复）换取连接面不被打挂，是可接受的降级。</p>
 */
@Slf4j
@Configuration
public class BrokerExecutorConfig {

    private final BrokerStats brokerStats;

    public BrokerExecutorConfig(BrokerStats brokerStats) {
        this.brokerStats = brokerStats;
    }

    @Bean(name = "brokerExecutor", destroyMethod = "shutdown")
    public ExecutorService brokerExecutor() {
        int threads = Math.max(4, Runtime.getRuntime().availableProcessors() * 2);
        AtomicInteger seq = new AtomicInteger();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "broker-work-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        RejectedExecutionHandler handler = (r, executor) -> {
            brokerStats.recordExecutorRejected(); // P1-10 拒绝打点，Prometheus 告警可见
            log.error("[Broker] 业务线程池队列已满，丢弃任务 {}", r.getClass().getSimpleName());
        };
        return new ThreadPoolExecutor(
                threads, threads,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(10_000),
                factory,
                handler);
    }

    /**
     * 延迟任务调度线程（P1-11 Will Delay 延迟遗嘱投递等）。
     * 独立单线程，避免延迟任务长时间 sleep 占用 brokerExecutor 工作线程。
     */
    @Bean(name = "brokerScheduler", destroyMethod = "shutdownNow")
    public ScheduledExecutorService brokerScheduler() {
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "broker-scheduler");
            t.setDaemon(true);
            return t;
        };
        return Executors.newSingleThreadScheduledExecutor(factory);
    }
}
