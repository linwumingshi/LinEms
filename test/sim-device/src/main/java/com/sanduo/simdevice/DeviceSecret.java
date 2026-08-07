package com.sanduo.simdevice;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 设备密钥确定性派生，与 test/stress 的 {@code Secrets.deriveSecret} 同一公式，
 * 保证同一批 seed 出的设备在模拟器中密钥可复现：
 * deviceSecret = hex(SHA-256(secretBase + ":" + index))。
 */
final class DeviceSecret {

    private DeviceSecret() {
    }

    static String derive(String secretBase, int index) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((secretBase + ":" + index).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
