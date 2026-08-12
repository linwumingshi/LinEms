package com.energyx.broker.auth;

import com.energyx.broker.config.BrokerProperties;
import com.energyx.broker.mapper.DeviceCredentialMapper;
import com.energyx.broker.mapper.DeviceMapper;
import com.energyx.broker.mqtt.KafkaEventProducer;
import com.energyx.broker.session.SessionStore;
import com.energyx.common.constant.KafkaTopicConstant;
import com.energyx.common.enums.DeviceStatus;
import com.energyx.common.message.LifecycleMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 认证封禁事件测试：连续失败达阈值 → 发 lifecycle BANNED 事件（Kafka iot-device-lifecycle），失败不阻断认证主流程。
 */
class DeviceAuthServiceTest {

	private static final String CLIENT_ID = "testMeter_meter-000001";

	private SessionStore sessionStore;

	private DeviceMapper deviceMapper;

	private DeviceCredentialMapper credentialMapper;

	private BrokerProperties properties;

	private KafkaEventProducer producer;

	private ObjectMapper objectMapper;

	private DeviceAuthService service;

	@BeforeEach
	void setUp() {
		sessionStore = mock(SessionStore.class);
		deviceMapper = mock(DeviceMapper.class);
		credentialMapper = mock(DeviceCredentialMapper.class);
		producer = mock(KafkaEventProducer.class);
		objectMapper = new ObjectMapper();
		properties = new BrokerProperties();
		properties.setAuthFailureBanThreshold(10);
		properties.setAuthFailureBanSeconds(300);
		properties.setAuthTimestampWindowMinutes(2);
		properties.setCredentialCacheTtlSeconds(1800);
		service = new DeviceAuthService(sessionStore, objectMapper, deviceMapper, credentialMapper, properties,
				producer);
		// 凭据链路：Redis 缓存未命中 → MySQL 兜底查得到设备与凭据
		when(sessionStore.isAuthBanned(anyString())).thenReturn(false);
		when(sessionStore.consumeNonce(anyString())).thenReturn(true);
		when(sessionStore.getString(anyString())).thenReturn(null);
		when(deviceMapper.selectByProductKeyAndName("testMeter", "meter-000001"))
			.thenReturn(new DeviceRow(100L, 9L, "testMeter", "meter-000001", DeviceStatus.OFFLINE));
		when(credentialMapper.selectByDeviceId(100L)).thenReturn(new CredentialRow(100L, "secret", 1, null));
	}

	@Test
	void authenticate_failReachThreshold_publishesBanEvent() throws Exception {
		when(sessionStore.incrAuthFail(CLIENT_ID)).thenReturn(10L);

		AuthResult result = authenticateWithWrongPassword();

		assertFalse(result.isAllowed());
		verify(sessionStore).banClient(CLIENT_ID);
		ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
		verify(producer).send(eq(KafkaTopicConstant.IOT_DEVICE_LIFECYCLE), eq(CLIENT_ID), valueCaptor.capture());
		LifecycleMessage msg = objectMapper.readValue(valueCaptor.getValue(), LifecycleMessage.class);
		assertEquals("BANNED", msg.getEventType());
		assertEquals("AUTH_FAIL_EXCEED", msg.getReason());
		assertEquals(100L, msg.getDeviceId());
		assertNotNull(msg.getTs());
	}

	@Test
	void authenticate_failBelowThreshold_noBanEvent() {
		when(sessionStore.incrAuthFail(CLIENT_ID)).thenReturn(5L);

		AuthResult result = authenticateWithWrongPassword();

		assertFalse(result.isAllowed());
		verify(sessionStore, never()).banClient(anyString());
		verify(producer, never()).send(anyString(), anyString(), anyString());
	}

	@Test
	void authenticate_publishFailure_doesNotBlockAuth() {
		when(sessionStore.incrAuthFail(CLIENT_ID)).thenReturn(10L);
		doThrow(new RuntimeException("kafka down")).when(producer).send(anyString(), anyString(), anyString());

		AuthResult result = authenticateWithWrongPassword();

		assertFalse(result.isAllowed());
		verify(sessionStore).banClient(CLIENT_ID);
	}

	@Test
	void authenticate_redisUnbannedButDbStatus5_allowAndPublishUnbanEvent() throws Exception {
		// Critical-2：Redis 封禁 TTL 已自然解封（isAuthBanned=false，见 setUp），但 DB 仍为 5（封禁审计视图残留）。
		// 回源 MySQL 读到 status=5 → 认证应放行，并补发 UNBANNED 事件让 access 回写 status=2。
		when(deviceMapper.selectByProductKeyAndName("testMeter", "meter-000001"))
			.thenReturn(new DeviceRow(100L, 9L, "testMeter", "meter-000001", DeviceStatus.BANNED));

		long ts = System.currentTimeMillis();
		String nonce = "nonce-unban";
		String username = CLIENT_ID + "&" + ts + "&" + nonce;
		String password = HmacSigner.sign("secret", CLIENT_ID, String.valueOf(ts), nonce);

		AuthResult result = service.authenticate(CLIENT_ID, username, password);

		assertTrue(result.isAllowed());
		verify(sessionStore).clearAuthFail(CLIENT_ID);
		ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
		verify(producer).send(eq(KafkaTopicConstant.IOT_DEVICE_LIFECYCLE), eq(CLIENT_ID), valueCaptor.capture());
		LifecycleMessage msg = objectMapper.readValue(valueCaptor.getValue(), LifecycleMessage.class);
		assertEquals("UNBANNED", msg.getEventType());
		assertEquals("AUTH_OK_AFTER_BAN_TTL", msg.getReason());
		assertEquals(100L, msg.getDeviceId());
		assertNotNull(msg.getTs());
	}

	@Test
	void authenticate_redisStillBannedWithDbStatus5_deniedNoUnbanEvent() {
		// 封禁期内（Redis 仍封禁）：认证入口 isBanned 拦截，不允许绕过，且不发 UNBANNED
		when(sessionStore.isAuthBanned(CLIENT_ID)).thenReturn(true);
		when(deviceMapper.selectByProductKeyAndName("testMeter", "meter-000001"))
			.thenReturn(new DeviceRow(100L, 9L, "testMeter", "meter-000001", DeviceStatus.BANNED));

		AuthResult result = authenticateWithWrongPassword();

		assertFalse(result.isAllowed());
		verify(producer, never()).send(eq(KafkaTopicConstant.IOT_DEVICE_LIFECYCLE), eq(CLIENT_ID), anyString());
	}

	@Test
	void authenticate_dbStatus2_unbannedNoSpuriousUnbanEvent() {
		// 正常已激活设备（status=2）认证成功：不得发 UNBANNED
		long ts = System.currentTimeMillis();
		String nonce = "nonce-normal";
		String username = CLIENT_ID + "&" + ts + "&" + nonce;
		String password = HmacSigner.sign("secret", CLIENT_ID, String.valueOf(ts), nonce);

		AuthResult result = service.authenticate(CLIENT_ID, username, password);

		assertTrue(result.isAllowed());
		verify(producer, never()).send(eq(KafkaTopicConstant.IOT_DEVICE_LIFECYCLE), eq(CLIENT_ID), anyString());
	}

	private AuthResult authenticateWithWrongPassword() {
		long ts = System.currentTimeMillis();
		String username = CLIENT_ID + "&" + ts + "&nonce-1";
		return service.authenticate(CLIENT_ID, username, "wrong-password");
	}

}
