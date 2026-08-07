package com.sanduo.simdevice;

import com.sanduo.device.DeviceIdentity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectorTest {

    private static final DeviceIdentity ID =
            new DeviceIdentity("snd_ess_pcs", "sim-dev-000001", DeviceSecret.derive("sanduo-stress", 1));

    @Test
    void connackHintCoversCommonCodes() {
        assertTrue(Connector.connackHint(4).contains("密码"));
        assertTrue(Connector.connackHint(5).contains("未授权"));
        assertTrue(Connector.connackHint(2).contains("clientId"));
    }

    @Test
    void disconnectWhenNotConnected() {
        Connector c = new Connector(ID, "127.0.0.1", 1883, new PendingCommands());
        assertEquals("未连接", c.disconnect());
        assertEquals(false, c.isConnected());
    }

    @Test
    void statusBeforeConnect() {
        Connector c = new Connector(ID, "127.0.0.1", 1883, new PendingCommands());
        String s = c.status();
        assertTrue(s.contains("未连接"));
        assertTrue(s.contains("sim-dev-000001"));
        assertTrue(s.contains("127.0.0.1:1883"));
    }

    @Test
    void describeConnectErrorMapsRejection() {
        // 真实 SDK 格式：code= 后是 netty MqttConnectReturnCode 的枚举名（不重写 toString）
        IllegalStateException e = new IllegalStateException(
                "连接被拒绝 code=CONNECTION_REFUSED_BAD_USER_NAME_OR_PASSWORD clientId=snd_ess_pcs_sim-dev-000001");
        String s = Connector.describeConnectError(e);
        assertTrue(s.contains("连接被拒绝"));
        assertTrue(s.contains("密码"));
    }

    @Test
    void describeConnectErrorMapsNumericRejection() {
        // 向后兼容：数字格式 code=4 仍映射到"密码"提示
        IllegalStateException e = new IllegalStateException(
                "连接被拒绝 code=4 clientId=snd_ess_pcs_sim-dev-000001");
        String s = Connector.describeConnectError(e);
        assertTrue(s.contains("连接被拒绝"));
        assertTrue(s.contains("密码"));
    }

    @Test
    void describeConnectErrorHintsTcpFailure() {
        // SDK 死代码分支格式：若其修复透传，我们仍应提示检查 broker
        IllegalStateException e = new IllegalStateException(
                "TCP 连接失败 host=127.0.0.1 port=1 clientId=snd_ess_pcs_sim-dev-000001");
        String s = Connector.describeConnectError(e);
        assertTrue(s.contains("连接失败"));
        assertTrue(s.contains("检查 broker"));
    }

    @Test
    void connectWhenBrokerDownReturnsHint() {
        // 端口 1 本地立即 ConnectException：SDK syncUninterruptibly() 透传原始异常，
        // connect() 的宽捕获必须兜住并返回中文提示，不得抛出。
        Connector c = new Connector(ID, "127.0.0.1", 1, new PendingCommands());
        String s = c.connect();
        assertTrue(s.contains("连接失败"));
        assertFalse(c.isConnected());
    }
}
