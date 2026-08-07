package com.sanduo.simdevice;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliArgsTest {

    @Test
    void defaults() {
        CliArgs a = CliArgs.parse(new String[]{});
        assertEquals("snd_ess_pcs", a.product());
        assertEquals("sim-dev-000001", a.deviceName());
        assertEquals("127.0.0.1", a.host());
        assertEquals(1883, a.port());
        assertEquals(false, a.autoAck());
        assertEquals(DeviceSecret.derive("sanduo-stress", 1), a.deviceSecret());
    }

    @Test
    void deriveFromSecretBase() {
        CliArgs a = CliArgs.parse(new String[]{"--device", "sim-dev-000007", "--secret-base", "foo"});
        assertEquals(DeviceSecret.derive("foo", 7), a.deviceSecret());
    }

    @Test
    void explicitSecretWins() {
        CliArgs a = CliArgs.parse(new String[]{"--secret", "aabbccddeeff00112233445566778899", "--secret-base", "other"});
        assertEquals("aabbccddeeff00112233445566778899", a.deviceSecret());
    }

    @Test
    void invalidDeviceNameWithUnderscoreRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> CliArgs.parse(new String[]{"--device", "sim_dev_1"}));
        assertTrue(e.getMessage().contains("_"));
    }

    @Test
    void parseBroker() {
        CliArgs a = CliArgs.parse(new String[]{"--broker", "10.0.0.5:2883"});
        assertEquals("10.0.0.5", a.host());
        assertEquals(2883, a.port());
    }

    @Test
    void invalidBrokerRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> CliArgs.parse(new String[]{"--broker", "localhost"}));
        assertThrows(IllegalArgumentException.class,
                () -> CliArgs.parse(new String[]{"--broker", "localhost:0"}));
        assertThrows(IllegalArgumentException.class,
                () -> CliArgs.parse(new String[]{"--broker", "localhost:notaport"}));
    }

    @Test
    void unknownFlagRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> CliArgs.parse(new String[]{"--nope"}));
    }

    @Test
    void missingValueRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> CliArgs.parse(new String[]{"--product"}));
    }

    @Test
    void autoackFlag() {
        CliArgs a = CliArgs.parse(new String[]{"--autoack"});
        assertTrue(a.autoAck());
    }
}
