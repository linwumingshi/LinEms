package com.sanduo.stress;

import com.sanduo.device.MqttDevice;
import io.netty.channel.EventLoopGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 控制链路 P99 压测：平台指令下发 → 设备收到并 ACK → 平台状态机置 SUCCESS 的全链路时延。
 *
 * <p>链路（Phase 1 §6 / Phase 6b）：</p>
 * <pre>
 * POST /api/command → energy-command → iot-command-down(Kafka) → energy-access
 *   → Broker PUBLISH down/command(QoS1) → 设备 auto-ack → up/ack → iot-command-ack
 *   → energy-command 状态机 → SUCCESS
 * </pre>
 *
 * <p>验收口径：P99 ≤ 500ms。压测前需全栈已启动（nacos + 各服务 + 网关 8000 + broker 1883），
 * 且目标设备已通过 {@code seed} 造数注册。</p>
 */
public final class ControlLatency {

    private static final Logger log = LoggerFactory.getLogger(ControlLatency.class);

    private ControlLatency() {
    }

    public static int run(Args args) throws Exception {
        PlatformClient client = new PlatformClient(args.gateway);
        probeGateway(client, args.gateway);

        // 1. 接入设备（须订阅 down/command 且 auto-ack）
        List<MqttDevice> devices = new CopyOnWriteArrayList<>();
        EventLoopGroup loop = ConnectUtil.newLoop(args.ioThreads);
        ExecutorService connector = Executors.newFixedThreadPool(Math.min(args.count, args.concurrency));
        CountDownLatch connectLatch = new CountDownLatch(args.count);
        AtomicInteger connectFail = new AtomicInteger();
        for (int i = 1; i <= args.count; i++) {
            final int idx = i;
            connector.submit(() -> {
                try {
                    MqttDevice dev = ConnectUtil.newDevice(args.host, args.port, args.productKey,
                            args.secretBase, idx, args.count, true,   // subscribe down/command
                            args.connectTimeoutMs, 120, loop);
                    devices.add(dev);
                    dev.connect();
                } catch (Exception e) {
                    connectFail.incrementAndGet();
                } finally {
                    connectLatch.countDown();
                }
            });
        }
        connectLatch.await();
        connector.shutdown();
        int connected = devices.size();
        if (connected == 0) {
            System.out.println("[Control] 设备全部接入失败，中止。");
            ConnectUtil.shutdown(loop);
            return 1;
        }
        System.out.printf("[Control] 接入成功 %d/%d，开始控制链路压测（P99 目标 ≤ 500ms）%n",
                connected, args.count);

        // 2. 并发下发
        int issuers = Math.max(1, Math.min(args.concurrency, connected));
        ExecutorService pool = Executors.newFixedThreadPool(issuers);
        Percentiles latency = new Percentiles(connected);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger fail = new AtomicInteger();
        List<String> failSamples = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(connected);

        for (int w = 0; w < issuers; w++) {
            final int from = w * (connected / issuers) + 1;
            final int to = (w == issuers - 1) ? connected : (w + 1) * (connected / issuers);
            pool.submit(() -> {
                for (int idx = from; idx <= to; idx++) {
                    String deviceName = Secrets.deviceName(idx, connected);
                    long t0 = System.nanoTime();
                    try {
                        String commandId = client.createCommand(args.productKey, deviceName,
                                "setPower", Map.of("power", 5000), args.timeoutMs);
                        long deadline = t0 + args.timeoutMs * 1_000_000L;
                        String state;
                        while (true) {
                            state = client.getState(commandId);
                            if ("SUCCESS".equals(state) || "FAILED".equals(state) || "TIMEOUT".equals(state)) {
                                break;
                            }
                            if (System.nanoTime() > deadline) {
                                state = "TIMEOUT";
                                break;
                            }
                            Thread.sleep(20);
                        }
                        if ("SUCCESS".equals(state)) {
                            latency.add((System.nanoTime() - t0) / 1_000_000L);
                            ok.incrementAndGet();
                        } else {
                            fail.incrementAndGet();
                            if (failSamples.size() < 10) {
                                failSamples.add(deviceName + " -> " + commandId + " state=" + state);
                            }
                        }
                    } catch (Exception e) {
                        fail.incrementAndGet();
                        if (failSamples.size() < 10) {
                            failSamples.add(deviceName + " -> " + e.getMessage());
                        }
                    } finally {
                        done.countDown();
                    }
                }
            });
        }
        done.await();

        long p50 = latency.percentile(50);
        long p95 = latency.percentile(95);
        long p99 = latency.percentile(99);
        long p999 = latency.percentile(99.9);

        System.out.println("================== 控制链路压测结果 ==================");
        System.out.printf("  下发指令      : %d（成功 %d / 失败 %d）%n", connected, ok.get(), fail.get());
        System.out.printf("  全链路时延    : P50=%dms P95=%dms P99=%dms P999=%dms max=%dms%n",
                p50, p95, p99, p999, latency.max());
        System.out.printf("  验收口径      : P99 ≤ 500ms → %s%n", p99 <= 500 ? "PASS ✅" : "FAIL ❌");
        failSamples.forEach(f -> System.out.println("  失败样例: " + f));
        System.out.println("=====================================================");

        pool.shutdownNow();
        ConnectUtil.closeAll(devices);
        ConnectUtil.shutdown(loop);
        return (ok.get() == connected && p99 <= 500) ? 0 : 1;
    }

    private static void probeGateway(PlatformClient client, String gateway) throws IOException, InterruptedException {
        try {
            client.getState("__probe__");
        } catch (HttpTimeoutException | ConnectException e) {
            throw new IllegalStateException("网关不可达: " + gateway
                    + "（请先启动 nacos / energy-command / energy-gateway）", e);
        } catch (IOException e) {
            // 404 / 业务错误说明网关与业务服务可达，链路正常
            log.info("[Control] 网关探活通过: {}", gateway);
        }
    }

    /** 控制链路压测参数。 */
    public static final class Args {
        String gateway = "http://127.0.0.1:8000";
        String host = "127.0.0.1";
        int port = 1883;
        int count = 100;
        int concurrency = 20;
        int timeoutMs = 10_000;
        int connectTimeoutMs = 10_000;
        int ioThreads = ConnectUtil.ioThreads();
        String productKey = "snd_ess_pcs";
        String secretBase = "sanduo-stress";
    }
}
