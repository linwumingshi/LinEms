package com.sanduo.stress;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 模拟设备身份派生：设备名 / 密钥统一算法，保证造数工具与压测工具对同一批设备可复现。
 *
 * <ul>
 *   <li>deviceName = {@code sim-dev-%0{width}d}，width = max(6, 位数(count))；</li>
 *   <li>deviceSecret = hex(SHA-256(secretBase + ":" + index))，64 位十六进制。</li>
 * </ul>
 */
public final class Secrets {

    private Secrets() {
    }

    /** 第 index（1..count）台设备名。 */
    public static String deviceName(int index, int count) {
        int width = Math.max(6, String.valueOf(count).length());
        return String.format("sim-dev-%0" + width + "d", index);
    }

    /** 第 index（1..count）台设备密钥（确定性，由 secretBase 派生）。 */
    public static String deriveSecret(String secretBase, int index) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((secretBase + ":" + index).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
