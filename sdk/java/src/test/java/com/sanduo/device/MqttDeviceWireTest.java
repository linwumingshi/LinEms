package com.sanduo.device;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MqttDevice 与 MqttTestBroker 的 socket 级联测：认证契约、上行、下行指令自动 ack、
 * keepalive、优雅断开、连接拒绝 / CONNACK 超时分支。
 */
class MqttDeviceWireTest {

    private static final String PK = "std-energy-storage";
    private static final String DN = "dev-000001";
    private static final String SECRET = "test-secret-0123456789";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<MqttDevice> devices = new CopyOnWriteArrayList<>();
    private MqttTestBroker broker;

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

    private MqttDevice connectDevice(MqttClientConfig config) throws Exception {
        broker = new MqttTestBroker();
        MqttClientConfig effective = (config == null ? MqttClientConfig.defaults() : config)
                .host("127.0.0.1").port(broker.port());
        MqttDevice device = new MqttDevice(new DeviceIdentity(PK, DN, SECRET), effective, listener());
        devices.add(device);
        device.connect();
        return device;
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

    // ------------------------------------------------------------------
    // 用例
    // ------------------------------------------------------------------

    @Test
    void connect_succeeds_withValidHmacAuth() throws Exception {
        MqttDevice device = connectDevice(null);
        assertTrue(device.isConnected());

        assertEquals(1, broker.connections().size());
        MqttTestBroker.Connection conn = broker.connections().get(0);
        assertNotNull(conn);
        assertEquals(PK + "_" + DN, conn.clientId());

        // username = clientId&ts&nonce
        String[] parts = conn.username().split("&");
        assertEquals(3, parts.length, "username 必须为 clientId&ts&nonce 三段");
        assertEquals(PK + "_" + DN, parts[0]);

        // timestamp 在 ±2 分钟窗口内
        long ts = Long.parseLong(parts[1]);
        assertTrue(Math.abs(System.currentTimeMillis() - ts) < 120_000, "timestamp 必须在窗口内");

        // password 字段 = 十六进制签名 ASCII 字节，Broker 以 UTF-8 解码后与期望值比较
        String expected = HmacAuth.sign(SECRET, parts[0], parts[1], parts[2]);
        assertEquals(expected, conn.password(), "password 必须等于 HMAC-SHA256 十六进制签名");
        assertTrue(expected.matches("[0-9a-f]{64}"), "签名必须是 64 位小写十六进制");

        // 已订阅下行指令
        awaitUntil(() -> broker.hasClient(PK + "_" + DN), 3000, "客户端注册到 broker");
    }

    @Test
    void publishProperty_uplinkTopicAndPayloadMatchAccessContract() throws Exception {
        MqttDevice device = connectDevice(null);
        device.publishProperty(Map.of("power", 5000, "voltage", 220.5, "switch", true));

        awaitUntil(() -> broker.publishes().size() == 1, 3000, "收到 1 条上行");
        MqttTestBroker.RecordedPublish p = broker.publishes().get(0);
        assertEquals(PK + "/" + DN + "/up/property", p.topic());

        JsonNode root = JSON.readTree(p.payload());
        assertTrue(root.has("messageId"), "必须携带 messageId（去重键）");
        assertEquals("report", root.path("dataType").asText());
        assertEquals(5000, root.path("properties").path("power").asInt());
        assertEquals(220.5, root.path("properties").path("voltage").asDouble());
        assertTrue(root.path("properties").path("switch").asBoolean());
        assertTrue(root.path("ts").asLong() > 0);
    }

    @Test
    void publishEvent_andPublishLifecycle_useCorrectTopics() throws Exception {
        MqttDevice device = connectDevice(null);
        device.publishEvent("alarm", 3, "OVP", Map.of("value", 880));
        device.publishLifecycle("OFFLINE", "127.0.0.1");

        awaitUntil(() -> broker.publishes().size() == 2, 3000, "收到 2 条上行");
        MqttTestBroker.RecordedPublish event = broker.publishes().get(0);
        assertEquals(PK + "/" + DN + "/up/event", event.topic());
        assertEquals("alarm", JSON.readTree(event.payload()).path("eventName").asText());

        MqttTestBroker.RecordedPublish lc = broker.publishes().get(1);
        assertEquals(PK + "/" + DN + "/up/lifecycle", lc.topic());
        assertEquals("OFFLINE", JSON.readTree(lc.payload()).path("eventType").asText());
    }

    @Test
    void downCommand_autoAckRoundTrip() throws Exception {
        final List<CommandMessage> received = new CopyOnWriteArrayList<>();
        MqttTestBroker broker0 = new MqttTestBroker();
        this.broker = broker0;
        MqttDevice device = new MqttDevice(new DeviceIdentity(PK, DN, SECRET),
                MqttClientConfig.defaults().host("127.0.0.1").port(broker0.port()), new DeviceListener() {
                    @Override
                    public void onConnected(DeviceIdentity identity) {
                    }

                    @Override
                    public void onCommand(DeviceIdentity identity, CommandMessage command) {
                        received.add(command);
                    }

                    @Override
                    public void onDisconnected(DeviceIdentity identity, String reason) {
                    }

                    @Override
                    public void onError(DeviceIdentity identity, Throwable cause) {
                    }
                });
        devices.add(device);
        device.connect();
        awaitUntil(() -> broker0.hasClient(PK + "_" + DN), 3000, "客户端注册");

        String commandJson = "{\"commandId\":\"C20260806010001\",\"deviceId\":1,\"tenantId\":1,"
                + "\"productKey\":\"std-energy-storage\",\"deviceName\":\"dev-000001\","
                + "\"command\":\"setPower\",\"params\":{\"power\":5000},\"qos\":1,\"ts\":1723000000000}";
        broker0.publishDown(PK + "_" + DN, PK + "/" + DN + "/down/command", commandJson.getBytes());

        awaitUntil(() -> !received.isEmpty(), 3000, "设备收到下行指令");
        CommandMessage cmd = received.get(0);
        assertEquals("C20260806010001", cmd.commandId());
        assertEquals("setPower", cmd.command());
        assertEquals(5000, cmd.param("power"));
        assertTrue(cmd.receivedAt() > 0);

        // 自动 ack 上行到 up/ack
        awaitUntil(() -> broker0.publishes().stream().anyMatch(p -> p.topic().endsWith("/up/ack")),
                3000, "收到 up/ack");
        MqttTestBroker.RecordedPublish ack = broker0.publishes().stream()
                .filter(p -> p.topic().endsWith("/up/ack"))
                .findFirst().orElseThrow();
        JsonNode ackNode = JSON.readTree(ack.payload());
        assertEquals("C20260806010001", ackNode.path("commandId").asText());
        assertEquals("SUCCESS", ackNode.path("status").asText());
    }

    @Test
    void autoAck_disabled_doesNotPublishAck() throws Exception {
        MqttTestBroker broker0 = new MqttTestBroker();
        this.broker = broker0;
        MqttDevice device = new MqttDevice(new DeviceIdentity(PK, DN, SECRET),
                MqttClientConfig.defaults().host("127.0.0.1").port(broker0.port()).autoAck(false),
                listener());
        devices.add(device);
        device.connect();
        awaitUntil(() -> broker0.hasClient(PK + "_" + DN), 3000, "客户端注册");

        String commandJson = "{\"commandId\":\"C20260806010002\",\"deviceId\":1,\"tenantId\":1,"
                + "\"productKey\":\"std-energy-storage\",\"deviceName\":\"dev-000001\","
                + "\"command\":\"getStatus\",\"params\":{},\"qos\":1,\"ts\":1723000000000}";
        broker0.publishDown(PK + "_" + DN, PK + "/" + DN + "/down/command", commandJson.getBytes());

        // 负向断言：给足时间观察，若有 ack 出现即失败
        long deadline = System.currentTimeMillis() + 1500;
        boolean ackArrived = false;
        while (System.currentTimeMillis() < deadline) {
            if (broker0.publishes().stream().anyMatch(p -> p.topic().endsWith("/up/ack"))) {
                ackArrived = true;
                break;
            }
            Thread.sleep(10);
        }
        assertFalse(ackArrived, "autoAck=false 必须静默，不应回 ack");
    }

    @Test
    void keepAlive_sendsPingreqPeriodically() throws Exception {
        MqttDevice device = connectDevice(MqttClientConfig.defaults().keepAliveSeconds(1));
        awaitUntil(() -> !broker.pings().isEmpty(), 4000, "收到 PINGREQ");
        assertEquals(PK + "_" + DN, broker.pings().get(0));
        assertTrue(device.isConnected());
    }

    @Test
    void gracefulClose_sendsDisconnect() throws Exception {
        MqttDevice device = connectDevice(null);
        device.close();
        awaitUntil(() -> broker.disconnects().contains(PK + "_" + DN), 3000, "收到 DISCONNECT");
        assertFalse(device.isConnected());
    }

    @Test
    void autoReconnect_afterBrokerKick_returnsToConnected() throws Exception {
        MqttTestBroker broker0 = new MqttTestBroker();
        this.broker = broker0;
        MqttDevice device = new MqttDevice(new DeviceIdentity(PK, DN, SECRET),
                MqttClientConfig.defaults().host("127.0.0.1").port(broker0.port())
                        .reconnectBackoffMs(100).reconnectMaxBackoffMs(500),
                listener());
        devices.add(device);
        device.connect();
        awaitUntil(() -> broker0.hasClient(PK + "_" + DN), 3000, "首次注册");

        // Broker 强制踢线 → 客户端应指数退避自动重连。
        // 以“连接记录数 ≥ 2”为信号：kick 后旧通道的注销是异步的，hasClient 可能残留旧注册；
        // 而 connections 只增不减，Broker 在处理重连 CONNECT 后、回 CONNACK 前追加记录，
        // 故记录数达标时客户端必然已恢复连接。
        broker0.kick(PK + "_" + DN);
        awaitUntil(() -> broker0.connections().size() >= 2, 5000, "重连 CONNECT 到达 Broker");
        assertTrue(device.isConnected(), "踢线后应自动恢复连接");
    }

    @Test
    void autoReconnect_disabled_staysDisconnected() throws Exception {
        MqttTestBroker broker0 = new MqttTestBroker();
        this.broker = broker0;
        MqttDevice device = new MqttDevice(new DeviceIdentity(PK, DN, SECRET),
                MqttClientConfig.defaults().host("127.0.0.1").port(broker0.port()).autoReconnect(false),
                listener());
        devices.add(device);
        device.connect();
        awaitUntil(() -> broker0.hasClient(PK + "_" + DN), 3000, "首次注册");

        broker0.kick(PK + "_" + DN);
        // kick 是异步关通道：先等客户端真正感知到断开（channelInactive 触发），
        // 否则首轮轮询会撞上连接尚存活的瞬间，误判为“已重连”
        long observed = System.currentTimeMillis();
        while (device.isConnected()) {
            if (System.currentTimeMillis() - observed > 3000) {
                break;
            }
            Thread.sleep(10);
        }
        assertFalse(device.isConnected(), "踢线后必须先感知到断开");

        // 观察窗口：给足时间，若自动重连发生即失败
        long deadline = System.currentTimeMillis() + 1500;
        boolean reconnected = false;
        while (System.currentTimeMillis() < deadline) {
            if (device.isConnected()) {
                reconnected = true;
                break;
            }
            Thread.sleep(20);
        }
        assertFalse(reconnected, "autoReconnect=false 不应自动重连");
    }

    @Test
    void connect_rejected_byBroker_throws() throws Exception {
        broker = new MqttTestBroker();
        broker.rejectNextConnect(true);
        MqttDevice device = new MqttDevice(new DeviceIdentity(PK, DN, SECRET),
                MqttClientConfig.defaults().host("127.0.0.1").port(broker.port()), listener());
        devices.add(device);
        IllegalStateException ex = assertThrows(IllegalStateException.class, device::connect);
        assertTrue(ex.getMessage().contains("被拒绝"), "必须抛出连接被拒绝异常，实际: " + ex.getMessage());
        assertFalse(device.isConnected());
    }

    @Test
    void connackTimeout_throws() throws Exception {
        broker = new MqttTestBroker();
        broker.silenceNextConnect(true);
        MqttDevice device = new MqttDevice(new DeviceIdentity(PK, DN, SECRET),
                MqttClientConfig.defaults().host("127.0.0.1").port(broker.port()).connectTimeoutMs(600),
                listener());
        devices.add(device);
        IllegalStateException ex = assertThrows(IllegalStateException.class, device::connect);
        assertTrue(ex.getMessage().contains("CONNACK 超时"), "必须抛出 CONNACK 超时异常，实际: " + ex.getMessage());
        assertFalse(device.isConnected());
    }

    @Test
    void connect_whenClosed_rejectsReconnect() throws Exception {
        MqttDevice device = connectDevice(null);
        device.close();
        assertThrows(IllegalStateException.class, device::connect);
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
