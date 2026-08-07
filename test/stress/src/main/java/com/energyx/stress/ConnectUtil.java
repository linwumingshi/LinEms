package com.energyx.stress;

import com.energyx.device.DeviceIdentity;
import com.energyx.device.DeviceListener;
import com.energyx.device.MqttClientConfig;
import com.energyx.device.MqttDevice;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.TimeUnit;

/** 连接相关公共工具：共享 EventLoopGroup / 批量接入 / 统一清理。 */
public final class ConnectUtil {

    private static final Logger log = LoggerFactory.getLogger(ConnectUtil.class);

    /** 无操作监听器（压测工具不需要回调日志）。 */
    static final DeviceListener NOOP = new DeviceListener() {
        @Override
        public void onConnected(DeviceIdentity identity) {
        }

        @Override
        public void onCommand(DeviceIdentity identity, com.energyx.device.CommandMessage command) {
        }

        @Override
        public void onDisconnected(DeviceIdentity identity, String reason) {
        }

        @Override
        public void onError(DeviceIdentity identity, Throwable cause) {
        }
    };

    private ConnectUtil() {
    }

    /** 默认 IO 线程数：按 CPU 核数取 2~16。 */
    static int ioThreads() {
        return Math.max(2, Math.min(16, Runtime.getRuntime().availableProcessors()));
    }

    static EventLoopGroup newLoop(int threads) {
        return new NioEventLoopGroup(Math.max(1, threads));
    }

    /** 明文 1883 便捷重载（throughput/control 沿用，TLS 恒关）。 */
    static MqttDevice newDevice(String host, int port, String productKey, String secretBase,
                                int index, int count, boolean subscribe, int connectTimeoutMs,
                                int keepAliveSeconds, EventLoopGroup loop) {
        return newDevice(host, port, productKey, secretBase, index, count, subscribe,
                connectTimeoutMs, keepAliveSeconds, false, false, null, loop);
    }

    /** 构造设备（默认配置 + 共享 loop；useTls=true 时走 mqtts，skipVerify 优先于 tlsTrustCertFile）。 */
    static MqttDevice newDevice(String host, int port, String productKey, String secretBase,
                                int index, int count, boolean subscribe, int connectTimeoutMs,
                                int keepAliveSeconds, boolean useTls, boolean tlsSkipVerify,
                                String tlsTrustCertFile, EventLoopGroup loop) {
        DeviceIdentity id = new DeviceIdentity(productKey,
                Secrets.deviceName(index, count), Secrets.deriveSecret(secretBase, index));
        MqttClientConfig cfg = MqttClientConfig.defaults()
                .host(host).port(port)
                .connectTimeoutMs(connectTimeoutMs)
                .keepAliveSeconds(keepAliveSeconds)
                .subscribeCommand(subscribe)
                .useTls(useTls)
                .tlsSkipVerify(tlsSkipVerify);
        if (useTls && !tlsSkipVerify && tlsTrustCertFile != null && !tlsTrustCertFile.isBlank()) {
            cfg.tlsTrustCertFile(tlsTrustCertFile);
        }
        return new MqttDevice(id, cfg, NOOP, loop);
    }

    /** 逐个优雅关闭并忽略异常。 */
    static void closeAll(List<MqttDevice> devices) {
        for (MqttDevice d : devices) {
            try {
                d.close();
            } catch (Exception e) {
                log.debug("关闭设备异常 clientId={}", d.identity().clientId(), e);
            }
        }
    }

    static void shutdown(EventLoopGroup loop) {
        loop.shutdownGracefully(0, 5, TimeUnit.SECONDS);
    }
}
