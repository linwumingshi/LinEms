package com.sanduo.stress;

import com.sanduo.device.MqttDevice;
import io.netty.channel.EventLoopGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 连接压测：N 台模拟设备并发接入 Broker，统计成功/失败、连接速率与延迟分位。
 *
 * <p>典型场景：{@code connect --count 100000 --concurrency 500 --hold-seconds 60}
 * 达到并保持 10 万连接；对单节点 Broker（max-connections=500000）可继续上探。</p>
 *
 * <p>注意：模拟设备共享一个 NioEventLoopGroup，CPU 核数决定 IO 线程上限；
 * 并发度（--concurrency）决定同时进行握手的连接数，即为建连速率。</p>
 */
public final class ConnectLoad {

    private static final Logger log = LoggerFactory.getLogger(ConnectLoad.class);

    private ConnectLoad() {
    }

    public static int run(Args args) throws Exception {
        EventLoopGroup loop = ConnectUtil.newLoop(args.ioThreads);
        ExecutorService pool = Executors.newFixedThreadPool(args.concurrency);
        Percentiles latency = new Percentiles(args.count);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger fail = new AtomicInteger();
        List<MqttDevice> devices = new CopyOnWriteArrayList<>();
        List<String> firstFailures = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(args.count);

        long t0 = System.nanoTime();
        for (int i = 1; i <= args.count; i++) {
            final int idx = i;
            pool.submit(() -> {
                MqttDevice dev = null;
                long s = System.nanoTime();
                try {
                    dev = ConnectUtil.newDevice(args.host, args.port, args.productKey,
                            args.secretBase, idx, args.count, args.subscribe,
                            args.connectTimeoutMs, args.keepAliveSeconds,
                            args.tls, args.tlsSkipVerify, args.tlsTrustCertFile, loop);
                    devices.add(dev);
                    dev.connect();
                    latency.add((System.nanoTime() - s) / 1_000_000L);
                    ok.incrementAndGet();
                } catch (Exception e) {
                    fail.incrementAndGet();
                    if (firstFailures.size() < 10) {
                        firstFailures.add("sim-dev-#" + idx + " -> " + e.getMessage());
                    }
                    if (dev != null) {
                        try {
                            dev.close();
                        } catch (Exception ignore) {
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        boolean completedInTime = latch.await(args.connectTimeoutMs + 120_000L, TimeUnit.MILLISECONDS);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        pool.shutdownNow();

        if (args.holdSeconds > 0 && ok.get() > 0) {
            System.out.printf("[Connect] 保持 %d 条连接 %d 秒（观察并发占用与稳定性）…%n",
                    ok.get(), args.holdSeconds);
            Thread.sleep(args.holdSeconds * 1000L);
        }

        double rate = elapsedMs > 0 ? ok.get() * 1000.0 / elapsedMs : 0.0;
        System.out.println("================== 连接压测结果 ==================");
        System.out.printf("  目标连接数 : %d%n", args.count);
        System.out.printf("  成功/失败  : %d / %d%n", ok.get(), fail.get());
        System.out.printf("  建连耗时   : %d ms%n", elapsedMs);
        System.out.printf("  建连速率   : %.0f 连接/秒%n", rate);
        System.out.printf("  连接延迟   : P50=%dms P95=%dms P99=%dms P999=%dms max=%dms%n",
                latency.percentile(50), latency.percentile(95),
                latency.percentile(99), latency.percentile(99.9), latency.max());
        if (!completedInTime) {
            System.out.println("  警告: 部分连接未在超时窗口完成，可能达到 Broker 连接上限或资源瓶颈");
        }
        if (!firstFailures.isEmpty()) {
            System.out.println("  失败样例:");
            firstFailures.forEach(f -> System.out.println("    " + f));
        }
        System.out.println("=================================================");

        ConnectUtil.closeAll(devices);
        ConnectUtil.shutdown(loop);
        log.info("连接压测结束，保持连接数 {}，失败 {}", ok.get(), fail.get());
        return fail.get() == 0 ? 0 : 1;
    }

    /** 连接压测参数。 */
    public static final class Args {
        String host = "127.0.0.1";
        int port = 1883;
        int count = 1000;
        int concurrency = 200;
        int connectTimeoutMs = 10_000;
        int keepAliveSeconds = 120;
        boolean subscribe;
        int holdSeconds;
        int ioThreads = ConnectUtil.ioThreads();
        String productKey = "snd_ess_pcs";
        String secretBase = "sanduo-stress";
        /** 是否走 mqtts（TLS over TCP）。 */
        boolean tls;
        /** 跳过证书链与主机名校验（仅演示/自签名；生产必须 false）。 */
        boolean tlsSkipVerify;
        /** 信任的服务端证书 PEM 路径（自签名信任锚；skipVerify 优先）。 */
        String tlsTrustCertFile;
    }
}
