package com.sanduo.energy.broker.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanduo.energy.broker.config.BrokerProperties;
import com.sanduo.energy.broker.mapper.DeviceCredentialMapper;
import com.sanduo.energy.broker.mapper.DeviceMapper;
import com.sanduo.energy.broker.session.SessionStore;
import com.sanduo.energy.broker.util.BrokerKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 设备认证服务：HMAC-SHA256 签名 + nonce 防重放 + 时间窗 + 设备/凭据状态 + 失败封禁。
 *
 * <p>凭据链路：Redis cache:cred:{deviceKey}（30min）→ 未命中查 MySQL
 * iot_device + iot_device_credential → 回写缓存。MySQL 不可用时缓存兜底（Broker 不因此下线）。</p>
 *
 * <p>安全契约（设备 SDK 必须遵循）：
 * <pre>
 * clientId = {productKey}_{deviceName}
 * username = {clientId}&{timestamp}&{nonce}
 * password = hex(HMAC-SHA256(deviceSecret, username))
 * </pre>
 * timestamp 为毫秒，nonce 为一次性随机串。</p>
 */
@Slf4j
@Service
public class DeviceAuthService {

    private final SessionStore sessionStore;
    private final ObjectMapper objectMapper;
    private final DeviceMapper deviceMapper;
    private final DeviceCredentialMapper credentialMapper;
    private final BrokerProperties properties;

    /** 本节点认证失败计数（key=clientId → 计数），连续失败达阈值触发短期封禁；跨节点封禁为 Phase 6 增强 */
    private final Map<String, AtomicInteger> failCounters = new ConcurrentHashMap<>();

    public DeviceAuthService(SessionStore sessionStore, ObjectMapper objectMapper,
                             DeviceMapper deviceMapper, DeviceCredentialMapper credentialMapper,
                             BrokerProperties properties) {
        this.sessionStore = sessionStore;
        this.objectMapper = objectMapper;
        this.deviceMapper = deviceMapper;
        this.credentialMapper = credentialMapper;
        this.properties = properties;
    }

    /**
     * 认证入口。
     *
     * @param clientId  连接 clientId
     * @param username  {clientId}&{timestamp}&{nonce}
     * @param password  hex(HMAC-SHA256)
     */
    public AuthResult authenticate(String clientId, String username, String password) {
        // 1. 参数解析
        String[] parts = username == null ? new String[0] : username.split("&");
        if (parts.length != 3 || clientId == null || clientId.isEmpty()) {
            return AuthResult.deny(4, false, "username 必须为 clientId&timestamp&nonce");
        }
        if (!clientId.equals(parts[0])) {
            return AuthResult.deny(4, false, "username 中的 clientId 与连接 clientId 不一致");
        }
        String timestampStr = parts[1];
        String nonce = parts[2];
        long timestamp;
        try {
            timestamp = Long.parseLong(timestampStr);
        } catch (NumberFormatException e) {
            return AuthResult.deny(4, false, "timestamp 非法");
        }

        // 2. 失败封禁检查
        AtomicInteger counter = failCounters.computeIfAbsent(clientId, k -> new AtomicInteger());
        if (counter.get() >= properties.getAuthFailureBanThreshold()) {
            return AuthResult.deny(5, true, "认证失败次数过多，短期封禁中");
        }

        // 3. 时间窗校验（±2min）
        long now = System.currentTimeMillis();
        long windowMs = properties.getAuthTimestampWindowMinutes() * 60_000L;
        if (Math.abs(now - timestamp) > windowMs) {
            return AuthResult.deny(4, false, "timestamp 超出时间窗");
        }

        // 4. nonce 一次性消费（SETNX，防重放）
        if (!sessionStore.consumeNonce(nonce)) {
            return AuthResult.deny(4, false, "nonce 已使用（重放）");
        }

        // 5. 凭据加载 + 状态校验
        DeviceCredential cred = loadCredential(clientId);
        AuthResult statusCheck = checkStatus(cred, clientId);
        if (!statusCheck.isAllowed()) {
            return statusCheck;
        }

        // 6. 签名校验（常数时间比较）
        String expected = HmacSigner.sign(cred.getDeviceSecret(), clientId, timestampStr, nonce);
        if (!HmacSigner.constantTimeEquals(expected, password)) {
            failCounters.get(clientId).incrementAndGet();
            return AuthResult.deny(4, counter.get() >= properties.getAuthFailureBanThreshold(),
                    "签名校验失败");
        }

        // 7. 通过：清零失败计数
        counter.set(0);
        return AuthResult.allow(cred);
    }

    /** 状态校验：设备主状态 + 凭据状态 + 过期 */
    private AuthResult checkStatus(DeviceCredential cred, String clientId) {
        if (cred == null) {
            failCounters.get(clientId).incrementAndGet();
            return AuthResult.deny(4, false, "设备不存在或凭据未配置");
        }
        // 设备主状态：仅 2 已激活 / 3 在线 允许接入
        int status = cred.getDeviceStatus();
        if (status == 4) {
            return AuthResult.deny(5, false, "设备已禁用");
        }
        if (status == 5) {
            return AuthResult.deny(5, false, "设备已封禁");
        }
        if (status != 2 && status != 3) {
            return AuthResult.deny(5, false, "设备未激活（status=" + status + "）");
        }
        if (cred.getAuthStatus() != 1) {
            return AuthResult.deny(5, false, "凭据已吊销");
        }
        if (cred.isExpired()) {
            return AuthResult.deny(5, false, "凭据已过期");
        }
        return AuthResult.allow(cred);
    }

    /** 凭据加载：Redis cache:cred → 兜底 MySQL → 回写缓存 */
    private DeviceCredential loadCredential(String clientId) {
        String cacheKey = BrokerKeys.credentialCache(clientId);
        String cached = sessionStore.getString(cacheKey);
        if (cached != null) {
            try {
                return objectMapper.readValue(cached, DeviceCredential.class);
            } catch (Exception e) {
                log.warn("[Auth] 凭据缓存反序列化失败，回源 MySQL key={}", clientId);
            }
        }

        // 拆分 clientId：{productKey}_{deviceName}；productKey 可含 '_'（平台锚点如 snd_ess_pcs），
        // deviceName 禁止 '_'（SDK DeviceIdentity 约束），故按最后一个 '_' 拆分
        int underscore = clientId.lastIndexOf('_');
        if (underscore <= 0 || underscore == clientId.length() - 1) {
            return null;
        }
        String productKey = clientId.substring(0, underscore);
        String deviceName = clientId.substring(underscore + 1);

        DeviceRow device = deviceMapper.selectByProductKeyAndName(productKey, deviceName);
        if (device == null) {
            return null;
        }
        CredentialRow cred = credentialMapper.selectByDeviceId(device.deviceId());
        if (cred == null) {
            return null;
        }
        DeviceCredential result = new DeviceCredential(
                clientId,
                device.deviceId(),
                device.tenantId(),
                device.productKey(),
                device.deviceName(),
                device.status(),
                cred.authStatus(),
                cred.deviceSecret(),
                cred.expireTime() != null && cred.expireTime().isBefore(LocalDateTime.now()));

        try {
            sessionStore.redis().opsForValue().set(
                    cacheKey,
                    objectMapper.writeValueAsString(result),
                    Duration.ofSeconds(properties.getCredentialCacheTtlSeconds()));
        } catch (Exception e) {
            log.warn("[Auth] 凭据缓存回写失败 clientId={}", clientId, e);
        }
        return result;
    }
}
