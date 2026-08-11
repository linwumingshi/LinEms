package com.energyx.stress;

import com.energyx.device.MqttDevice;
import io.netty.channel.EventLoopGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 吞吐压测：count 台设备按 ratePerDevice msg/s 持续上报属性，统计平台接住的有效吞吐。
 *
 * <p>总目标吞吐 = count × ratePerDevice。示例：10000 台 × 20 msg/s = 20 万 msg/s；
 * 20000 台 × 25 msg/s = 50 万 msg/s。压测端只度量「设备 → Broker 受理」，端到端处理
 * 与积压请对照 Broker 统计与 Kafka 消费 lag（见 Phase8 文档）。</p>
 *
 * <p>属性产品感知：PCS 报物模型六字段（soc/voltage/current/power/temp/runMode）；
 * METER（--product snd_ess_meter）只报 importPower。均使用物模型已登记标识，
 * 保证 access 侧 ModelValidator 校验通过、链路真实生效。</p>
 */
public final class ThroughputLoad {

    private static final Logger log = LoggerFactory.getLogger(ThroughputLoad.class);

    private ThroughputLoad() {
    }

    public static int run(Args args) throws Exception {
        // 1. 并行接入
        List<MqttDevice> devices = new CopyOnWriteArrayList<>();
        AtomicInteger connectFail = new AtomicInteger();
        EventLoopGroup loop = ConnectUtil.newLoop(args.ioThreads);
        ExecutorService connector = Executors.newFixedThreadPool(Math.min(args.count, args.concurrency));
        CountDownLatch connectLatch = new CountDownLatch(args.count);
        for (int i = 1; i <= args.count; i++) {
            final int idx = i;
            connector.submit(() -> {
                try {
                    MqttDevice dev = ConnectUtil.newDevice(args.host, args.port, args.productKey,
                            args.secretBase, idx, args.count, false,
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
            System.out.println("[Throughput] 全部设备接入失败，中止。");
            ConnectUtil.shutdown(loop);
            return 1;
        }
        System.out.printf("[Throughput] 接入成功 %d/%d，开始压测 %d 秒%n",
                connected, args.count, args.durationSec);

        // 2. 发布工作线程（每工作线程持有固定设备分片，每 tick 每台发布一次 → 总速率 = count × rate）
        AtomicLong published = new AtomicLong();
        AtomicLong failed = new AtomicLong();
        AtomicReference<String> lastError = new AtomicReference<>();
        int workers = Math.max(1, Math.min(args.workerThreads, connected));
        ExecutorService workersPool = Executors.newFixedThreadPool(workers);
        final boolean[] running = {true};
        for (int w = 0; w < workers; w++) {
            final int sliceStart = w * (connected / workers);
            final int sliceEnd = (w == workers - 1) ? connected : (w + 1) * (connected / workers);
            workersPool.submit(() -> {
                long intervalNs = 1_000_000_000L / Math.max(1, args.ratePerDevice);
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                while (running[0]) {
                    for (int k = sliceStart; k < sliceEnd; k++) {
                        MqttDevice dev = devices.get(k);
                        try {
                            dev.publishProperty(props(args.productKey, rnd));
                            published.incrementAndGet();
                        } catch (Exception e) {
                            failed.incrementAndGet();
                            if (lastError.compareAndSet(null, e.getMessage())) {
                                log.warn("[Throughput] 首次发布失败: {}", e.getMessage());
                            }
                        }
                    }
                    // 按设备速率休眠（分段 50ms 便于及时退出）
                    long remaining = intervalNs;
                    while (remaining > 0 && running[0]) {
                        long chunk = Math.min(remaining, 50_000_000L);
                        try {
                            Thread.sleep(chunk / 1_000_000L);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        remaining -= chunk;
                    }
                }
            });
        }

        // 3. 秒级采样
        Percentiles perSec = new Percentiles(args.durationSec + 1);
        long lastCount = published.get();
        long reportStart = System.nanoTime();
        for (int sec = 0; sec < args.durationSec; sec++) {
            Thread.sleep(1000);
            long now = published.get();
            long delta = now - lastCount;
            lastCount = now;
            perSec.add(delta);
            if (sec % 10 == 9 || sec == args.durationSec - 1) {
                System.out.printf("[Throughput] 第 %d 秒: 累计=%d 本秒=%d msg/s 失败=%d%n",
                        sec + 1, now, delta, failed.get());
            }
        }
        long total = published.get();
        long elapsedMs = (System.nanoTime() - reportStart) / 1_000_000L;
        running[0] = false;
        workersPool.shutdownNow();

        // 4. 汇总
        System.out.println("================== 吞吐压测结果 ==================");
        System.out.printf("  设备数        : %d（接入成功 %d）%n", args.count, connected);
        System.out.printf("  单机速率      : %d msg/s × %d 台%n", args.ratePerDevice, connected);
        System.out.printf("  压测时长      : %d 秒%n", args.durationSec);
        System.out.printf("  累计上报      : %d 条%n", total);
        System.out.printf("  平均吞吐      : %.0f msg/s%n", elapsedMs > 0 ? total * 1000.0 / elapsedMs : 0);
        System.out.printf("  秒级吞吐      : P50=%d P95=%d P99=%d msg/s%n",
                perSec.percentile(50), perSec.percentile(95), perSec.percentile(99));
        System.out.printf("  发布失败      : %d（首个错误: %s）%n", failed.get(),
                lastError.get() == null ? "-" : lastError.get());
        System.out.println("  注: 端到端处理量与积压请对照 Broker 统计 / Kafka 消费 lag");
        System.out.println("=================================================");

        ConnectUtil.closeAll(devices);
        ConnectUtil.shutdown(loop);
        return failed.get() > 0 ? 1 : 0;
    }

    /** 按产品生成随机属性：METER 只报 importPower（用电功率），其余按 PCS 六字段。 */
    private static Map<String, Object> props(String productKey, ThreadLocalRandom rnd) {
        if ("snd_ess_meter".equals(productKey)) {
            return Map.of("importPower", 500 + rnd.nextInt(3000));
        }
        return Map.of(
                "soc", 40 + rnd.nextInt(60),
                "voltage", 200 + rnd.nextInt(50),
                "current", rnd.nextInt(40),
                "power", 500 + rnd.nextInt(3000),
                "temp", 25 + rnd.nextInt(20),
                "runMode", rnd.nextInt(3));
    }

    /** 吞吐压测参数。 */
    public static final class Args {
        String host = "127.0.0.1";
        int port = 1883;
        int count = 1000;
        int ratePerDevice = 10;
        int durationSec = 60;
        int concurrency = 200;
        int workerThreads = 8;
        int ioThreads = ConnectUtil.ioThreads();
        int connectTimeoutMs = 10_000;
        String productKey = "snd_ess_pcs";
        String secretBase = "sanduo-stress";
    }
}
