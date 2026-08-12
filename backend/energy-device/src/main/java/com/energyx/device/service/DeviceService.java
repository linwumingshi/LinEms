package com.energyx.device.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.energyx.device.entity.Device;
import com.energyx.device.web.dto.DeviceCreateReq;
import com.energyx.device.web.dto.DeviceQuery;
import com.energyx.device.web.dto.DeviceUpdateReq;
import com.energyx.device.web.dto.CredentialView;

import java.util.List;

/**
 * 设备资产服务：统一设备树 CRUD + 凭据管理。
 */
public interface DeviceService {

	/** 创建设备（生成雪花 ID、物化路径/层级、设备密钥），返回 deviceId */
	Long create(DeviceCreateReq req);

	void update(Long deviceId, DeviceUpdateReq req);

	/** 激活设备：仅未注册(0)/未激活(1) → 已激活离线(2)，状态变更后广播凭据失效 */
	void activate(Long deviceId);

	/** 禁用设备：仅已激活(2)/在线(3) → 禁用(4)，状态变更后广播凭据失效（在线连接会被踢下线） */
	void disable(Long deviceId);

	/** 启用设备：仅禁用(4) → 已激活离线(2)，状态变更后广播凭据失效 */
	void enable(Long deviceId);

	/** 逻辑删除设备子树（path 前缀），并吊销子树全部凭据 */
	void delete(Long deviceId);

	IPage<Device> page(DeviceQuery query);

	Device detail(Long deviceId);

	/** 资产树；rootId 非空时返回该节点子树，否则返回当前租户全量树 */
	List<Device> tree(Long rootId);

	/** 凭据视图（密钥脱敏） */
	CredentialView getCredential(Long deviceId);

	/** 重新生成设备密钥（吊销状态自动恢复为正常） */
	CredentialView regenerateSecret(Long deviceId);

}
