package com.sanduo.device;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 认证签名与独立实现（openssl HMAC-SHA256）向量互证。
 *
 * <p>向量来源：{@code printf '%s' "std-energy-storage_dev-000001&1722999999999&0123456789abcdef"
 * | openssl dgst -sha256 -hmac "test-secret-0123456789"}。</p>
 */
class HmacAuthTest {

    private static final String SECRET = "test-secret-0123456789";
    private static final String CLIENT_ID = "std-energy-storage_dev-000001";
    private static final String TS = "1722999999999";
    private static final String NONCE = "0123456789abcdef";
    /** openssl 独立计算值。 */
    private static final String EXPECTED =
            "50eabec90f2d841f3da0a58e0525a4e9dc26e35a787b46fcb1cc67f7320f94d6";

    @Test
    void sign_matchesOpensslVector() {
        String actual = HmacAuth.sign(SECRET, CLIENT_ID, TS, NONCE);
        assertEquals(EXPECTED, actual, "签名必须与 openssl 独立计算一致");
    }

    @Test
    void sign_isDeterministic() {
        String a = HmacAuth.sign(SECRET, CLIENT_ID, TS, NONCE);
        String b = HmacAuth.sign(SECRET, CLIENT_ID, TS, NONCE);
        assertEquals(a, b);
    }

    @Test
    void sign_changesWithNonceOrTimestamp() {
        String base = HmacAuth.sign(SECRET, CLIENT_ID, TS, NONCE);
        String otherNonce = HmacAuth.sign(SECRET, CLIENT_ID, TS, "0000000000000000");
        String otherTs = HmacAuth.sign(SECRET, CLIENT_ID, "1722999999000", NONCE);
        assertNotEquals(base, otherNonce, "nonce 不同签名必须不同");
        assertNotEquals(base, otherTs, "timestamp 不同签名必须不同");
    }

    @Test
    void sign_changesWithSecret() {
        String base = HmacAuth.sign(SECRET, CLIENT_ID, TS, NONCE);
        String otherSecret = HmacAuth.sign("another-secret-000000000000", CLIENT_ID, TS, NONCE);
        assertNotEquals(base, otherSecret);
    }

    @Test
    void buildUsername_concatenatesWithAmpersand() {
        assertEquals("std-energy-storage_dev-000001&1722999999999&0123456789abcdef",
                HmacAuth.buildUsername(CLIENT_ID, 1722999999999L, NONCE));
    }

    @Test
    void passwordEqualsHmacOverUsername() {
        // password 契约 = hex(HMAC(secret, username))，即对完整 username 串签名
        String username = HmacAuth.buildUsername(CLIENT_ID, 1722999999999L, NONCE);
        String password = HmacAuth.sign(SECRET, CLIENT_ID, TS, NONCE);
        assertEquals(EXPECTED, password);
        assertTrue(username.endsWith(NONCE));
    }

    @Test
    void randomNonce_is32HexCharsAndUnique() {
        String a = HmacAuth.randomNonce();
        String b = HmacAuth.randomNonce();
        assertEquals(32, a.length());
        assertTrue(a.matches("[0-9a-f]{32}"));
        assertNotEquals(a, b);
    }

    @Test
    void randomDeviceSecret_is32HexChars() {
        assertEquals(32, HmacAuth.randomDeviceSecret().length());
        assertTrue(HmacAuth.randomDeviceSecret().matches("[0-9a-f]{32}"));
    }

    @Test
    void sign_throwsOnNullSecret() {
        assertThrows(NullPointerException.class, () -> HmacAuth.sign(null, CLIENT_ID, TS, NONCE));
    }
}
