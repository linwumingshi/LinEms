package com.energyx.device.service.impl;

import com.energyx.common.constant.Constants;
import com.energyx.common.exception.BusinessException;
import com.energyx.common.exception.ErrorCode;
import com.energyx.common.redis.RedisChannelConstant;
import com.energyx.device.entity.Device;
import com.energyx.device.entity.DeviceCredential;
import com.energyx.device.mapper.DeviceCredentialMapper;
import com.energyx.device.mapper.DeviceMapper;
import com.energyx.device.web.dto.CredentialView;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DeviceServiceImplTest {

	private DeviceCredentialMapper credentialMapper;

	private DeviceMapper deviceMapper;

	private StringRedisTemplate redis;

	private DeviceServiceImpl service;

	@BeforeEach
	void setUp() {
		credentialMapper = mock(DeviceCredentialMapper.class);
		deviceMapper = mock(DeviceMapper.class);
		redis = mock(StringRedisTemplate.class);
		// spy 真实实现：拦截 getById 返回 mock 设备，其余（requireDevice/发布）走真实逻辑
		service = spy(new DeviceServiceImpl(credentialMapper, redis));
		// lambdaUpdate() 底层走 baseMapper.update，反射注入 mock 供状态机动作 stub（baseMapper
		// 声明在父类且跨包，protected 不可直接访问）
		ReflectionTestUtils.setField(service, "baseMapper", deviceMapper);
		when(deviceMapper.update(any(), any())).thenReturn(1);
	}

	/** 单测环境无 Spring/MyBatis：预初始化 MP 实体 lambda 缓存（LambdaUpdateWrapper 依赖） */
	@BeforeAll
	static void initMybatisPlus() {
		org.apache.ibatis.session.Configuration config = new org.apache.ibatis.session.Configuration();
		org.apache.ibatis.builder.MapperBuilderAssistant assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(
				config, "");
		com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, DeviceCredential.class);
		com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, Device.class);
	}

	@Test
	void regenerateSecret_shouldPublishCredentialRevoked() {
		// 设备存在：productKey+deviceName 拼出 clientId
		Device dev = new Device();
		dev.setDeviceId(2087348751559163905L);
		dev.setProductKey("testMeter");
		dev.setDeviceName("meter-000001");
		doReturn(dev).when(service).getById(dev.getDeviceId());
		// 更新凭据成功
		when(credentialMapper.update(any(), any())).thenReturn(1);

		CredentialView view = service.regenerateSecret(dev.getDeviceId());

		// 密钥已生成并返回（64 位 hex）
		assertNotNull(view);
		assertEquals(64, view.getDeviceSecret().length());
		// 凭据失效广播：clientId = productKey_deviceName
		verify(redis).convertAndSend(eq(RedisChannelConstant.CREDENTIAL_REVOKED), eq("testMeter_meter-000001"));
	}

	@Test
	void regenerateSecret_publishFailure_shouldNotThrow() {
		// 广播失败不阻断主流程（缓存残留到 TTL 属可容忍降级）
		Device dev = new Device();
		dev.setDeviceId(2087348751559163905L);
		dev.setProductKey("testMeter");
		dev.setDeviceName("meter-000001");
		doReturn(dev).when(service).getById(dev.getDeviceId());
		when(credentialMapper.update(any(), any())).thenReturn(1);
		doThrow(new RuntimeException("redis down")).when(redis).convertAndSend(any(), any());

		CredentialView view = service.regenerateSecret(dev.getDeviceId());

		assertNotNull(view);
		assertEquals(64, view.getDeviceSecret().length());
	}

	// ---- 管理态状态机 ----

	private Device deviceWithStatus(int status) {
		Device dev = new Device();
		dev.setDeviceId(2087348751559163905L);
		dev.setProductKey("testMeter");
		dev.setDeviceName("meter-000001");
		dev.setStatus(status);
		return dev;
	}

	@Test
	void activate_fromInactive_shouldSucceed() {
		// 未激活(1) → 已激活(2)：更新落库 + 凭据失效广播
		Device dev = deviceWithStatus(Constants.DEVICE_STATUS_INACTIVE);
		doReturn(dev).when(service).getById(dev.getDeviceId());

		service.activate(dev.getDeviceId());

		verify(deviceMapper).update(any(), any());
		verify(redis).convertAndSend(eq(RedisChannelConstant.CREDENTIAL_REVOKED), eq("testMeter_meter-000001"));
	}

	@Test
	void activate_fromUnregistered_shouldSucceed() {
		// 未注册(0) 可直接激活（登记+激活合并，见状态机设计"0→先登记"）
		Device dev = deviceWithStatus(Constants.DEVICE_STATUS_UNREGISTERED);
		doReturn(dev).when(service).getById(dev.getDeviceId());

		service.activate(dev.getDeviceId());

		verify(deviceMapper).update(any(), any());
		verify(redis).convertAndSend(eq(RedisChannelConstant.CREDENTIAL_REVOKED), eq("testMeter_meter-000001"));
	}

	@Test
	void activate_fromOffline_shouldReject() {
		// 已激活(2) 重复激活为非法流转：抛错且不落库不广播
		Device dev = deviceWithStatus(Constants.DEVICE_STATUS_OFFLINE);
		doReturn(dev).when(service).getById(dev.getDeviceId());

		BusinessException ex = assertThrows(BusinessException.class, () -> service.activate(dev.getDeviceId()));

		assertEquals(ErrorCode.DEVICE_STATUS_INVALID.getCode(), ex.getCode());
		verify(deviceMapper, never()).update(any(), any());
		verify(redis, never()).convertAndSend(any(), any());
	}

	@Test
	void disable_fromActiveOrOnline_shouldSucceed() {
		// 已激活(2)/在线(3) → 禁用(4)
		for (int status : new int[] { Constants.DEVICE_STATUS_OFFLINE, Constants.DEVICE_STATUS_ONLINE }) {
			Device dev = deviceWithStatus(status);
			doReturn(dev).when(service).getById(dev.getDeviceId());

			service.disable(dev.getDeviceId());
		}

		verify(deviceMapper, times(2)).update(any(), any());
		verify(redis, times(2)).convertAndSend(eq(RedisChannelConstant.CREDENTIAL_REVOKED),
				eq("testMeter_meter-000001"));
	}

	@Test
	void disable_fromUnregistered_shouldReject() {
		// 未注册(0) 不可禁用：非法流转
		Device dev = deviceWithStatus(Constants.DEVICE_STATUS_UNREGISTERED);
		doReturn(dev).when(service).getById(dev.getDeviceId());

		BusinessException ex = assertThrows(BusinessException.class, () -> service.disable(dev.getDeviceId()));

		assertEquals(ErrorCode.DEVICE_STATUS_INVALID.getCode(), ex.getCode());
		verify(deviceMapper, never()).update(any(), any());
		verify(redis, never()).convertAndSend(any(), any());
	}

	@Test
	void enable_fromDisabled_shouldSucceed() {
		// 禁用(4) → 已激活(2)
		Device dev = deviceWithStatus(Constants.DEVICE_STATUS_DISABLED);
		doReturn(dev).when(service).getById(dev.getDeviceId());

		service.enable(dev.getDeviceId());

		verify(deviceMapper).update(any(), any());
		verify(redis).convertAndSend(eq(RedisChannelConstant.CREDENTIAL_REVOKED), eq("testMeter_meter-000001"));
	}

	@Test
	void enable_fromBanned_shouldReject() {
		// 封禁(5) 不可直接启用（解封由 broker TTL/管理员另走，非本动作）：非法流转
		Device dev = deviceWithStatus(Constants.DEVICE_STATUS_BANNED);
		doReturn(dev).when(service).getById(dev.getDeviceId());

		BusinessException ex = assertThrows(BusinessException.class, () -> service.enable(dev.getDeviceId()));

		assertEquals(ErrorCode.DEVICE_STATUS_INVALID.getCode(), ex.getCode());
		verify(deviceMapper, never()).update(any(), any());
		verify(redis, never()).convertAndSend(any(), any());
	}

}
