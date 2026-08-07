package com.energyx.device.util;

import java.security.SecureRandom;

/**
 * 设备密钥生成器。
 *
 * <p>生成 32 字节（64 位 hex）随机密钥，作为设备连接认证的 HMAC 密钥材料
 * （契约：password = hex(HMAC-SHA256(secret, username))，见 Broker DeviceAuthService）。</p>
 */
public final class DeviceSecretGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private DeviceSecretGenerator() {
    }

    /** @return 64 位 hex 字符串 */
    public static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        char[] out = new char[64];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }
}
