package com.energyx.device.service.impl;

import com.energyx.common.redis.RedisChannelConstant;
import com.energyx.device.entity.Device;
import com.energyx.device.entity.DeviceCredential;
import com.energyx.device.mapper.DeviceCredentialMapper;
import com.energyx.device.web.dto.CredentialView;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DeviceServiceImplTest {

	private DeviceCredentialMapper credentialMapper;

	private StringRedisTemplate redis;

	private DeviceServiceImpl service;

	@BeforeEach
	void setUp() {
		credentialMapper = mock(DeviceCredentialMapper.class);
		redis = mock(StringRedisTemplate.class);
		// spy 真实实现：拦截 getById 返回 mock 设备，其余（requireDevice/发布）走真实逻辑
		service = spy(new DeviceServiceImpl(credentialMapper, redis));
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

}
