package com.energyx.broker.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.energyx.broker.config.BrokerProperties;
import com.energyx.broker.mapper.DeviceCredentialMapper;
import com.energyx.broker.mapper.DeviceMapper;
import com.energyx.broker.mqtt.KafkaEventProducer;
import com.energyx.broker.session.SessionStore;
import com.energyx.broker.util.BrokerKeys;
import com.energyx.common.constant.KafkaTopicConstant;
import com.energyx.common.message.LifecycleMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 设备认证服务：HMAC-SHA256 签名 + nonce 防重放 + 时间窗 + 设备/凭据状态 + 失败封禁。
 *
 * <p>
 * 凭据链路：Redis cache:cred:{deviceKey}（30min）→ 未命中查 MySQL iot_device + iot_device_credential
 * → 回写缓存。MySQL 不可用时缓存兜底（Broker 不因此下线）。
 * </p>
 *
 * <p>
 * 安全契约（设备 SDK 必须遵循）： <pre>
 * clientId = {productKey}_{deviceName}
 * username = {clientId}&{timestamp}&{nonce}
 * password = hex(HMAC-SHA256(deviceSecret, username))
 * </pre> timestamp 为毫秒，nonce 为一次性随机串。
 * </p>
 */
@Slf4j
@Service
public class DeviceAuthService {

	private final SessionStore sessionStore;

	private final ObjectMapper objectMapper;

	private final DeviceMapper deviceMapper;

	private final DeviceCredentialMapper credentialMapper;

	private final BrokerProperties properties;

	private final KafkaEventProducer producer;

	/**
	 * 本地封禁快表（L1）：clientId → 封禁截止时间戳（毫秒）。 权威封禁状态在
	 * Redis（mqtt:ban:*，跨节点共享，TTL=authFailureBanSeconds，自然过期解封）； 本地表只用于挡高频重复请求、减少 Redis
	 * RTT。容量封顶防止海量随机 clientId 打爆内存。
	 */
	private final Map<String, Long> localBanUntil = new ConcurrentHashMap<>();

	private static final int MAX_LOCAL_BAN_ENTRIES = 100_000;

	public DeviceAuthService(SessionStore sessionStore, ObjectMapper objectMapper, DeviceMapper deviceMapper,
			DeviceCredentialMapper credentialMapper, BrokerProperties properties, KafkaEventProducer producer) {
		this.sessionStore = sessionStore;
		this.objectMapper = objectMapper;
		this.deviceMapper = deviceMapper;
		this.credentialMapper = credentialMapper;
		this.properties = properties;
		this.producer = producer;
	}

	/**
	 * 认证入口。
	 * @param clientId 连接 clientId
	 * @param username {clientId}&{timestamp}&{nonce}
	 * @param password hex(HMAC-SHA256)
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
		}
		catch (NumberFormatException e) {
			return AuthResult.deny(4, false, "timestamp 非法");
		}

		// 2. 封禁检查：本地快表（L1）→ Redis（权威，跨节点共享，TTL 自然解封）
		if (isBanned(clientId)) {
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
			recordFailureAndMaybeBan(clientId);
			return AuthResult.deny(4, isBanned(clientId), "签名校验失败");
		}

		// 7. 通过：清零失败计数
		sessionStore.clearAuthFail(clientId);
		localBanUntil.remove(clientId);
		return AuthResult.allow(cred);
	}

	/** 封禁判定：本地快表未过期直接命中；否则查 Redis 并回填本地 */
	private boolean isBanned(String clientId) {
		Long until = localBanUntil.get(clientId);
		if (until != null) {
			if (until > System.currentTimeMillis()) {
				return true;
			}
			localBanUntil.remove(clientId);
		}
		if (sessionStore.isAuthBanned(clientId)) {
			rememberBanLocally(clientId);
			return true;
		}
		return false;
	}

	/** 认证失败计数 +1（Redis 跨节点共享窗口计数），达阈值则封禁（TTL 自然解封）并通知 access 回写设备表 */
	private void recordFailureAndMaybeBan(String clientId) {
		long fails = sessionStore.incrAuthFail(clientId);
		if (fails >= properties.getAuthFailureBanThreshold()) {
			sessionStore.banClient(clientId);
			rememberBanLocally(clientId);
			log.warn("[Auth] clientId={} 连续认证失败 {} 次，封禁 {}s", clientId, fails, properties.getAuthFailureBanSeconds());
			publishBanEvent(clientId);
		}
	}

	/** 封禁落库通知：发 lifecycle BANNED 事件（access 消费回写 iot_device.status=5）。发布失败仅告警，不阻断认证主流程。 */
	private void publishBanEvent(String clientId) {
		try {
			LifecycleMessage msg = new LifecycleMessage();
			msg.setEventType("BANNED");
			msg.setReason("AUTH_FAIL_EXCEED");
			msg.setTs(System.currentTimeMillis());
			DeviceRow device = resolveDevice(clientId);
			if (device != null) {
				msg.setDeviceId(device.deviceId());
				msg.setTenantId(device.tenantId());
				msg.setProductKey(device.productKey());
				msg.setDeviceName(device.deviceName());
			}
			producer.send(KafkaTopicConstant.IOT_DEVICE_LIFECYCLE, clientId, objectMapper.writeValueAsString(msg));
		}
		catch (Exception e) {
			log.warn("[Auth] 封禁事件发布失败 clientId={}", clientId, e);
		}
	}

	/**
	 * clientId（{productKey}_{deviceName}）→ 设备查询；解析失败/查不到返回 null，事件退化为 deviceId=null 由
	 * access 忽略
	 */
	private DeviceRow resolveDevice(String clientId) {
		// productKey 可含 '_'（平台锚点如 snd_ess_pcs），deviceName 禁止 '_'，按最后一个 '_' 拆分
		int underscore = clientId.lastIndexOf('_');
		if (underscore <= 0 || underscore == clientId.length() - 1) {
			return null;
		}
		try {
			return deviceMapper.selectByProductKeyAndName(clientId.substring(0, underscore),
					clientId.substring(underscore + 1));
		}
		catch (Exception e) {
			log.warn("[Auth] 封禁事件查询设备失败 clientId={}", clientId, e);
			return null;
		}
	}

	private void rememberBanLocally(String clientId) {
		if (localBanUntil.size() >= MAX_LOCAL_BAN_ENTRIES) {
			localBanUntil.clear(); // 容量封顶：极端攻击下整体降级为只查 Redis，拒绝无界增长
		}
		localBanUntil.put(clientId, System.currentTimeMillis() + properties.getAuthFailureBanSeconds() * 1000L);
	}

	/** 状态校验：设备主状态 + 凭据状态 + 过期 */
	private AuthResult checkStatus(DeviceCredential cred, String clientId) {
		if (cred == null) {
			recordFailureAndMaybeBan(clientId);
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
			}
			catch (Exception e) {
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
		DeviceCredential result = new DeviceCredential(clientId, device.deviceId(), device.tenantId(),
				device.productKey(), device.deviceName(), device.status(), cred.authStatus(), cred.deviceSecret(),
				cred.expireTime() != null && cred.expireTime().isBefore(LocalDateTime.now()));

		try {
			sessionStore.redis()
				.opsForValue()
				.set(cacheKey, objectMapper.writeValueAsString(result),
						Duration.ofSeconds(properties.getCredentialCacheTtlSeconds()));
		}
		catch (Exception e) {
			log.warn("[Auth] 凭据缓存回写失败 clientId={}", clientId, e);
		}
		return result;
	}

}
