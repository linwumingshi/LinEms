package com.energyx.device;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MqttDevice 的 TLS（mqtts over TCP）socket 级联测：信任固定证书、skipVerify 演示路径、
 * 无信任配置（JDK 默认信任库拒自签）三类分支。
 *
 * <p>服务端/客户端证书为仓库内 TEST-ONLY 自签证书（SAN: DNS:localhost, IP:127.0.0.1），
 * 见 src/test/resources/certs/ 文件头注释；不打包进主构件。</p>
 */
class MqttDeviceTlsWireTest {

    private static final String PK = "std-energy-storage";
    private static final String DN = "dev-000001";
    private static final String SECRET = "test-secret-0123456789";

    @TempDir
    Path tempDir;

    private final List<MqttDevice> devices = new CopyOnWriteArrayList<>();
    private MqttTestBroker broker;

    @BeforeEach
    void copyTestCerts() throws Exception {
        copyResource("certs/test-server-cert.pem", tempDir.resolve("test-server-cert.pem"));
        copyResource("certs/test-server-key.pem", tempDir.resolve("test-server-key.pem"));
    }

    @AfterEach
    void tearDown() {
        devices.forEach(d -> {
            try {
                d.close();
            } catch (Exception ignore) {
                // 清理失败不影响断言
            }
        });
        devices.clear();
        if (broker != null) {
            broker.close();
        }
    }

    private static void copyResource(String resource, Path target) throws Exception {
        try (InputStream in = MqttDeviceTlsWireTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("测试证书缺失: " + resource);
            }
            Files.copy(in, target);
        }
    }

    private DeviceListener listener() {
        return new DeviceListener() {
            @Override
            public void onConnected(DeviceIdentity identity) {
            }

            @Override
            public void onCommand(DeviceIdentity identity, CommandMessage command) {
            }

            @Override
            public void onDisconnected(DeviceIdentity identity, String reason) {
            }

            @Override
            public void onError(DeviceIdentity identity, Throwable cause) {
            }
        };
    }

    /** 启动 TLS 测试 Broker 并连接（useTls=true 由调用方在 cfg 上设置）。 */
    private MqttDevice connectTls(MqttTestBroker broker0, MqttClientConfig cfg) throws Exception {
        MqttDevice device = new MqttDevice(new DeviceIdentity(PK, DN, SECRET),
                cfg.host("127.0.0.1").port(broker0.port()), listener());
        devices.add(device);
        device.connect();
        return device;
    }

    private MqttTestBroker tlsBroker() throws Exception {
        File certFile = tempDir.resolve("test-server-cert.pem").toFile();
        File keyFile = tempDir.resolve("test-server-key.pem").toFile();
        broker = new MqttTestBroker(SslContextBuilder.forServer(certFile, keyFile).build());
        return broker;
    }

    // ------------------------------------------------------------------
    // 用例
    // ------------------------------------------------------------------

    @Test
    void connectWithTrustedCert_tlsHandshakeAndUplinkSucceed() throws Exception {
        MqttTestBroker broker0 = tlsBroker();
        File certFile = tempDir.resolve("test-server-cert.pem").toFile();

        MqttDevice device = connectTls(broker0,
                MqttClientConfig.defaults().useTls(true).tlsTrustCertFile(certFile.getPath()));
        assertTrue(device.isConnected(), "固定信任服务端证书后 TLS 连接应成功");

        // 验证 SAN IP:127.0.0.1 + endpoint identification（HTTPS）全链路通过后再上行
        device.publishProperty(Map.of("power", 5000));
        awaitUntil(() -> broker0.publishes().size() == 1, 3000, "TLS 通道收到 1 条上行");
        assertEquals(PK + "/" + DN + "/up/property", broker0.publishes().get(0).topic());
    }

    @Test
    void connectSkipVerify_selfSignedAccepted() throws Exception {
        MqttTestBroker broker0 = tlsBroker();

        MqttDevice device = connectTls(broker0,
                MqttClientConfig.defaults().useTls(true).tlsSkipVerify(true));
        assertTrue(device.isConnected(), "skipVerify 应信任自签证书");

        device.publishProperty(Map.of("switch", true));
        awaitUntil(() -> broker0.publishes().size() == 1, 3000, "TLS 通道收到 1 条上行");
    }

    @Test
    void connectWithoutTrustConfig_rejectsSelfSigned() throws Exception {
        MqttTestBroker broker0 = tlsBroker();

        MqttDevice device = new MqttDevice(new DeviceIdentity(PK, DN, SECRET),
                MqttClientConfig.defaults().useTls(true).host("127.0.0.1").port(broker0.port()), listener());
        devices.add(device);
        IllegalStateException ex = assertThrows(IllegalStateException.class, device::connect);
        assertTrue(ex.getMessage().contains("TLS 握手失败"),
                "JDK 默认信任库拒自签，必须抛 TLS 握手失败，实际: " + ex.getMessage());
        assertFalse(device.isConnected());
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static void awaitUntil(BooleanSupplier condition, long timeoutMs, String what) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("await 被中断: " + what, e);
            }
        }
        throw new AssertionError("等待超时: " + what);
    }
}
