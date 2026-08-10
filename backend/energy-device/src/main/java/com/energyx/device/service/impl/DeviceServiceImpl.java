package com.energyx.device.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.energyx.common.constant.Constants;
import com.energyx.common.exception.BusinessException;
import com.energyx.common.exception.ErrorCode;
import com.energyx.common.tenant.TenantContext;
import com.energyx.device.entity.Device;
import com.energyx.device.entity.DeviceCredential;
import com.energyx.device.mapper.DeviceCredentialMapper;
import com.energyx.device.mapper.DeviceMapper;
import com.energyx.device.service.DeviceService;
import com.energyx.device.util.DeviceSecretGenerator;
import com.energyx.device.web.dto.CredentialView;
import com.energyx.device.web.dto.DeviceCreateReq;
import com.energyx.device.web.dto.DeviceQuery;
import com.energyx.device.web.dto.DeviceUpdateReq;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 设备资产服务实现。
 *
 * <p>
 * 租户隔离：查询/更新/删除全部由条件化租户拦截器按 {@link TenantContext} 自动追加 {@code tenant_id} 条件（HTTP
 * 线程），本服务无需显式按租户过滤；仅创建时从上下文读取 租户写入。物化路径 {@code /父ID/子ID/} 与层级随 parentId 计算，供子树前缀查询。
 * </p>
 */
@Slf4j
@Service
public class DeviceServiceImpl extends ServiceImpl<DeviceMapper, Device> implements DeviceService {

	private final DeviceCredentialMapper credentialMapper;

	public DeviceServiceImpl(DeviceCredentialMapper credentialMapper) {
		this.credentialMapper = credentialMapper;
	}

	@Override
	public Long create(DeviceCreateReq req) {
		long tenantId = requireTenant();
		validateDeviceName(req.getDeviceName());

		// 唯一校验：(tenant_id, product_key, device_name)，拦截器自动追加租户条件
		long dup = count(new LambdaQueryWrapper<Device>().eq(Device::getProductKey, req.getProductKey())
			.eq(Device::getDeviceName, req.getDeviceName()));
		if (dup > 0) {
			throw new BusinessException(ErrorCode.DEVICE_DUPLICATE,
					"设备已存在：" + req.getProductKey() + "_" + req.getDeviceName());
		}

		Device parent = resolveParent(req.getParentId());
		Device device = new Device();
		BeanUtils.copyProperties(req, device);
		device.setDeviceId(IdWorker.getId());
		device.setTenantId(tenantId);
		device.setStatus(req.getStatus() == null ? Constants.DEVICE_STATUS_UNREGISTERED : req.getStatus());
		device.setProtocol(req.getProtocol() == null || req.getProtocol().isBlank() ? "MQTT" : req.getProtocol());
		device.setOnlineSeconds(0L);
		if (parent == null) {
			device.setParentId(0L);
			device.setPath("/" + device.getDeviceId() + "/");
			device.setLevel(1);
		}
		else {
			device.setParentId(parent.getDeviceId());
			device.setPath(parent.getPath() + device.getDeviceId() + "/");
			device.setLevel(parent.getLevel() + 1);
		}
		save(device);

		// 创建设备即生成连接凭据（明文仅此处返回）
		DeviceCredential cred = new DeviceCredential();
		cred.setDeviceId(device.getDeviceId());
		cred.setTenantId(tenantId);
		cred.setDeviceSecret(DeviceSecretGenerator.generate());
		cred.setAuthStatus(1);
		cred.setFailCount(0);
		credentialMapper.insert(cred);

		log.info("创建设备 deviceId={} name={} productKey={} type={} path={}", device.getDeviceId(), device.getDeviceName(),
				device.getProductKey(), device.getDeviceType(), device.getPath());
		return device.getDeviceId();
	}

	@Override
	public void update(Long deviceId, DeviceUpdateReq req) {
		Device exists = requireDevice(deviceId);

		if (req.getDeviceName() != null && !req.getDeviceName().equals(exists.getDeviceName())) {
			validateDeviceName(req.getDeviceName());
			long dup = count(new LambdaQueryWrapper<Device>().eq(Device::getProductKey, exists.getProductKey())
				.eq(Device::getDeviceName, req.getDeviceName())
				.ne(Device::getDeviceId, deviceId));
			if (dup > 0) {
				throw new BusinessException(ErrorCode.DEVICE_DUPLICATE,
						"设备已存在：" + exists.getProductKey() + "_" + req.getDeviceName());
			}
		}

		Device update = new Device();
		update.setDeviceId(deviceId);
		update.setDeviceName(req.getDeviceName());
		update.setDeviceType(req.getDeviceType());
		update.setStationId(req.getStationId());
		update.setStatus(req.getStatus());
		update.setFirmwareVersion(req.getFirmwareVersion());
		update.setMac(req.getMac());
		update.setIp(req.getIp());
		update.setSort(req.getSort());
		updateById(update); // 空字段不更新（MP NOT_NULL 策略）
		log.info("更新设备 deviceId={}", deviceId);
	}

	@Override
	public void delete(Long deviceId) {
		requireDevice(deviceId);

		// 收集子树（path 前缀命中，含自身）
		List<Long> ids = list(new LambdaQueryWrapper<Device>().likeRight(Device::getPath, "/" + deviceId + "/")
			.select(Device::getDeviceId)).stream().map(Device::getDeviceId).collect(Collectors.toList());
		if (!ids.contains(deviceId)) {
			ids.add(deviceId);
		}
		removeByIds(ids); // 逻辑删除

		// 吊销子树全部凭据（拦截器自动限定当前租户）
		credentialMapper.update(null, new LambdaUpdateWrapper<DeviceCredential>().in(DeviceCredential::getDeviceId, ids)
			.set(DeviceCredential::getAuthStatus, 2));
		log.info("删除设备子树 rootId={} nodes={}", deviceId, ids.size());
	}

	@Override
	public IPage<Device> page(DeviceQuery query) {
		LambdaQueryWrapper<Device> wrapper = new LambdaQueryWrapper<Device>()
			.eq(query.getStationId() != null, Device::getStationId, query.getStationId())
			.eq(query.getEnterpriseId() != null, Device::getEnterpriseId, query.getEnterpriseId())
			.eq(query.getDeviceType() != null && !query.getDeviceType().isBlank(), Device::getDeviceType,
					query.getDeviceType())
			.eq(query.getParentId() != null, Device::getParentId, query.getParentId())
			.eq(query.getStatus() != null, Device::getStatus, query.getStatus())
			.eq(query.getProductKey() != null && !query.getProductKey().isBlank(), Device::getProductKey,
					query.getProductKey())
			.like(query.getKeyword() != null && !query.getKeyword().isBlank(), Device::getDeviceName,
					query.getKeyword())
			.orderByAsc(Device::getSort)
			.orderByAsc(Device::getDeviceId);
		return page(new Page<>(query.getPageNum(), query.getPageSize()), wrapper);
	}

	@Override
	public Device detail(Long deviceId) {
		return requireDevice(deviceId);
	}

	@Override
	public List<Device> tree(Long rootId) {
		List<Device> all;
		if (rootId != null && rootId > 0) {
			requireDevice(rootId);
			all = list(new LambdaQueryWrapper<Device>().likeRight(Device::getPath, "/" + rootId + "/")
				.orderByAsc(Device::getSort)
				.orderByAsc(Device::getDeviceId));
		}
		else {
			all = list(new LambdaQueryWrapper<Device>().orderByAsc(Device::getSort).orderByAsc(Device::getDeviceId));
		}
		return buildTree(all);
	}

	@Override
	public CredentialView getCredential(Long deviceId) {
		Device device = requireDevice(deviceId);
		DeviceCredential cred = credentialMapper
			.selectOne(new LambdaQueryWrapper<DeviceCredential>().eq(DeviceCredential::getDeviceId, deviceId));
		return new CredentialView(deviceId, device.getDeviceName(),
				cred == null ? "" : maskSecret(cred.getDeviceSecret()), cred == null ? 0 : cred.getAuthStatus());
	}

	@Override
	public CredentialView regenerateSecret(Long deviceId) {
		Device device = requireDevice(deviceId);
		String secret = DeviceSecretGenerator.generate();
		credentialMapper.update(null,
				new LambdaUpdateWrapper<DeviceCredential>().eq(DeviceCredential::getDeviceId, deviceId)
					.set(DeviceCredential::getDeviceSecret, secret)
					.set(DeviceCredential::getAuthStatus, 1)
					.set(DeviceCredential::getFailCount, 0));
		log.info("重新生成设备密钥 deviceId={}", deviceId);
		return new CredentialView(deviceId, device.getDeviceName(), secret, 1);
	}

	// ---- 私有 ----

	private Device requireDevice(Long deviceId) {
		Device device = getById(deviceId);
		if (device == null) {
			throw new BusinessException(ErrorCode.DEVICE_NOT_FOUND, "设备不存在：" + deviceId);
		}
		return device;
	}

	private long requireTenant() {
		Long tenantId = TenantContext.getTenantId();
		if (tenantId == null) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED, "缺少租户上下文");
		}
		return tenantId;
	}

	/** SDK 契约：deviceName 禁 _ 与 &（clientId 按最后一个 _ 拆分，& 为认证 username 分隔符） */
	private void validateDeviceName(String deviceName) {
		if (deviceName == null || deviceName.isBlank()) {
			throw new BusinessException(ErrorCode.PARAM_INVALID, "设备名不能为空");
		}
		if (deviceName.contains("_") || deviceName.contains("&")) {
			throw new BusinessException(ErrorCode.PARAM_INVALID, "设备名不能包含 _ 或 &（clientId 契约）");
		}
	}

	/** 解析父设备；null 或 0 表示根节点。 */
	private Device resolveParent(Long parentId) {
		if (parentId == null || parentId == 0L) {
			return null;
		}
		Device parent = getById(parentId);
		if (parent == null) {
			throw new BusinessException(ErrorCode.DEVICE_NOT_FOUND, "父设备不存在：" + parentId);
		}
		return parent;
	}

	/** 按 parentId 组装内存树（邻接表 → 多叉树）。 */
	private List<Device> buildTree(List<Device> all) {
		Map<Long, Device> byId = all.stream().collect(Collectors.toMap(Device::getDeviceId, d -> d));
		List<Device> roots = new ArrayList<>();
		for (Device node : all) {
			Long parentId = node.getParentId() == null ? 0L : node.getParentId();
			Device parent = byId.get(parentId);
			if (parentId == 0L || parent == null) {
				roots.add(node);
			}
			else {
				if (parent.getChildren() == null) {
					parent.setChildren(new ArrayList<>());
				}
				parent.getChildren().add(node);
			}
		}
		return roots;
	}

	private String maskSecret(String secret) {
		if (secret == null || secret.isEmpty()) {
			return "";
		}
		return secret.length() <= 8 ? "******"
				: secret.substring(0, 4) + "****" + secret.substring(secret.length() - 4);
	}

}
