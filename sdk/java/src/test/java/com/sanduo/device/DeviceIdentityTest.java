package com.sanduo.device;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 身份派生（clientId / 主题）与校验规则测试。
 * 主题格式须与 Broker TopicAcl 的 {@code {pk}/{dn}/up/*} 白名单一致。
 */
class DeviceIdentityTest {

    private static final DeviceIdentity ID = new DeviceIdentity("std-energy-storage", "dev-000001", "secret-123");

    @Test
    void clientId_isProductKeyUnderscoreDeviceName() {
        assertEquals("std-energy-storage_dev-000001", ID.clientId());
        assertEquals(ID.clientId(), ID.deviceKey());
    }

    @Test
    void topics_matchBrokerAclPattern() {
        assertEquals("std-energy-storage/dev-000001/up/property", ID.propertyTopic());
        assertEquals("std-energy-storage/dev-000001/up/event", ID.eventTopic());
        assertEquals("std-energy-storage/dev-000001/up/lifecycle", ID.lifecycleTopic());
        assertEquals("std-energy-storage/dev-000001/up/ack", ID.ackTopic());
        assertEquals("std-energy-storage/dev-000001/down/command", ID.downCommandTopic());

        assertTrue(ID.propertyTopic().matches("[^/]+/[^/]+/up/[a-z]+"));
        assertTrue(ID.downCommandTopic().endsWith("/down/command"));
    }

    @Test
    void rejectsEmptyParts() {
        assertThrows(NullPointerException.class, () -> new DeviceIdentity(null, "dev-1", "s"));
        assertThrows(NullPointerException.class, () -> new DeviceIdentity("pk", null, "s"));
        assertThrows(IllegalArgumentException.class, () -> new DeviceIdentity("", "dev-1", "s"));
        assertThrows(IllegalArgumentException.class, () -> new DeviceIdentity("pk", "", "s"));
    }

    @Test
    void productKeyMayContainUnderscore() {
        // 平台锚点 product_key = snd_ess_pcs（含 '_'）；Broker 按最后一个 '_' 拆分 clientId
        DeviceIdentity id = new DeviceIdentity("snd_ess_pcs", "sim-dev-1", "s");
        assertEquals("snd_ess_pcs_sim-dev-1", id.clientId());
        assertEquals("snd_ess_pcs/sim-dev-1/up/property", id.propertyTopic());
    }

    @Test
    void rejectsSeparatorBreakingClientIdOrUsername() {
        // deviceName 含 '_' 破坏 clientId 最后-'_' 拆分
        assertThrows(IllegalArgumentException.class, () -> new DeviceIdentity("pk", "dev_1", "s"));
        // '&' 为 username 分隔符，deviceName 与 productKey 均禁用
        assertThrows(IllegalArgumentException.class, () -> new DeviceIdentity("pk", "dev&1", "s"));
        assertThrows(IllegalArgumentException.class, () -> new DeviceIdentity("pk&1", "dev-1", "s"));
    }
}
