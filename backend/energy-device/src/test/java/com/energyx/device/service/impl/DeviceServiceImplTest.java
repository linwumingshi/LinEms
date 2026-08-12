package com.energyx.device.service.impl;

import com.energyx.common.constant.Constants;
import com.energyx.common.exception.BusinessException;
import com.energyx.common.exception.ErrorCode;
import com.energyx.common.redis.RedisChannelConstant;
import com.energyx.common.tenant.TenantContext;
import com.energyx.common.tenant.TenantInfo;
import com.energyx.device.entity.Device;
import com.energyx.device.entity.DeviceCredential;
import com.energyx.device.mapper.DeviceCredentialMapper;
import com.energyx.device.mapper.DeviceMapper;
import com.energyx.device.web.dto.CredentialView;
import com.energyx.device.web.dto.DeviceCreateReq;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

	// ---- 生成密钥即激活（状态机自动联动）----

	@Test
	void regenerateSecret_fromInactive_shouldActivate() {
		// 未激活(1) 生成密钥 → 自动激活为已激活离线(2)：密钥更新 + 状态更新 + 广播
		Device dev = deviceWithStatus(Constants.DEVICE_STATUS_INACTIVE);
		doReturn(dev).when(service).getById(dev.getDeviceId());
		when(credentialMapper.update(any(), any())).thenReturn(1);

		service.regenerateSecret(dev.getDeviceId());

		verify(credentialMapper).update(any(), any());
		verify(deviceMapper).update(any(), any());
		verify(redis).convertAndSend(eq(RedisChannelConstant.CREDENTIAL_REVOKED), eq("testMeter_meter-000001"));
	}

	@Test
	void regenerateSecret_fromUnregistered_shouldActivate() {
		// 未注册(0) 生成密钥 → 同样自动激活（登记+激活合并）
		Device dev = deviceWithStatus(Constants.DEVICE_STATUS_UNREGISTERED);
		doReturn(dev).when(service).getById(dev.getDeviceId());
		when(credentialMapper.update(any(), any())).thenReturn(1);

		service.regenerateSecret(dev.getDeviceId());

		verify(credentialMapper).update(any(), any());
		verify(deviceMapper).update(any(), any());
		verify(redis).convertAndSend(eq(RedisChannelConstant.CREDENTIAL_REVOKED), eq("testMeter_meter-000001"));
	}

	@Test
	void regenerateSecret_fromOnline_shouldKeepStatus() {
		// 在线(3) 换密钥是常规轮换：不触发状态联动（deviceMapper.update 不调用），仅密钥更新+广播
		Device dev = deviceWithStatus(Constants.DEVICE_STATUS_ONLINE);
		doReturn(dev).when(service).getById(dev.getDeviceId());
		when(credentialMapper.update(any(), any())).thenReturn(1);

		service.regenerateSecret(dev.getDeviceId());

		verify(credentialMapper).update(any(), any());
		verify(deviceMapper, never()).update(any(), any());
		verify(redis).convertAndSend(eq(RedisChannelConstant.CREDENTIAL_REVOKED), eq("testMeter_meter-000001"));
	}

	@Test
	void regenerateSecret_fromBanned_shouldKeepBanned() {
		// 封禁(5) 换密钥不自动解禁（解封走 broker TTL/UNBANNED 回写），避免越权恢复
		Device dev = deviceWithStatus(Constants.DEVICE_STATUS_BANNED);
		doReturn(dev).when(service).getById(dev.getDeviceId());
		when(credentialMapper.update(any(), any())).thenReturn(1);

		service.regenerateSecret(dev.getDeviceId());

		verify(credentialMapper).update(any(), any());
		verify(deviceMapper, never()).update(any(), any());
		verify(redis).convertAndSend(eq(RedisChannelConstant.CREDENTIAL_REVOKED), eq("testMeter_meter-000001"));
	}

	@Test
	void create_shouldDefaultToInactive() {
		// 创建固定为已登记未激活(1)：不再接受前端 status（DeviceCreateReq 已删字段）
		TenantContext.set(new TenantInfo(1L, 1L));
		DeviceCreateReq req = new DeviceCreateReq();
		req.setDeviceName("meter-000002");
		req.setProductKey("testMeter");
		req.setDeviceType("METER");
		// 拦截 ServiceImpl 的 count（唯一校验）与 save（落库），私有方法 resolveParent/validateDeviceName
		// 对 null parentId / 合法设备名真实执行
		doReturn(0L).when(service).count(any());
		doReturn(true).when(service).save(any(Device.class));
		when(credentialMapper.insert(any(DeviceCredential.class))).thenReturn(1);

		service.create(req);

		ArgumentCaptor<Device> captor = ArgumentCaptor.forClass(Device.class);
		verify(service).save(captor.capture());
		assertEquals(Constants.DEVICE_STATUS_INACTIVE, captor.getValue().getStatus());
		TenantContext.acquire().close();
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
