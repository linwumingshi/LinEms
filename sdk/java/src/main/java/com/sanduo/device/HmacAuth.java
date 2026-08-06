package com.sanduo.device;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 三多平台设备接入认证工具（与 Broker DeviceAuthService 同一套公式）。
 *
 * <p>认证契约（对端由 energy-mqtt-broker 校验）：</p>
 * <ul>
 *   <li>clientId = productKey + "_" + deviceName</li>
 *   <li>username = clientId + "&" + timestamp + "&" + nonce</li>
 *   <li>password = hex(HMAC-SHA256(deviceSecret, username))，timestamp 允许 ±2 分钟滑动窗口</li>
 *   <li>nonce 一次性：Broker 侧 Redis SETNX 校验，5 分钟 TTL</li>
 * </ul>
 *
 * <p>本类是纯静态函数，无 IO 依赖，可单测（与 Broker 单测同向量互证）。</p>
 */
public final class HmacAuth {

    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private HmacAuth() {
    }

    /**
     * 计算设备认证密码：hex(HMAC-SHA256(deviceSecret, username))。
     *
     * @param deviceSecret 设备密钥（device_credential.device_secret）
     * @param clientId     设备标识 productKey_deviceName
     * @param timestamp    1970 毫秒时间戳
     * @param nonce        一次性随机串（5 分钟内不重复）
     * @return 64 位小写十六进制签名
     */
    public static String sign(String deviceSecret, String clientId, String timestamp, String nonce) {
        String username = clientId + "&" + timestamp + "&" + nonce;
        return hmacSha256Hex(deviceSecret, username);
    }

    /**
     * 构造完整 username = clientId&amp;ts&amp;nonce（供连接时直接使用）。
     */
    public static String buildUsername(String clientId, long timestamp, String nonce) {
        return clientId + "&" + timestamp + "&" + nonce;
    }

    /**
     * 生成随机 nonce（32 位十六进制，时间戳 + 随机数拼接），每次连接唯一。
     */
    public static String randomNonce() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * 生成随机 16 字节设备密钥（用于测试 / 造数时生成 device_secret）。
     */
    public static String randomDeviceSecret() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String hmacSha256Hex(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] out = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            char[] hex = new char[out.length * 2];
            for (int i = 0; i < out.length; i++) {
                int v = out[i] & 0xFF;
                hex[i * 2] = HEX[v >>> 4];
                hex[i * 2 + 1] = HEX[v & 0x0F];
            }
            return new String(hex);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 不可用", e);
        }
    }
}
