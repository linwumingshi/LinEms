package com.sanduo.simdevice;

import com.sanduo.device.DeviceIdentity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        IllegalStateException e = new IllegalStateException(
                "连接被拒绝 code=4 clientId=snd_ess_pcs_sim-dev-000001");
        String s = Connector.describeConnectError(e);
        assertTrue(s.contains("连接被拒绝"));
        assertTrue(s.contains("密码"));
    }
}
