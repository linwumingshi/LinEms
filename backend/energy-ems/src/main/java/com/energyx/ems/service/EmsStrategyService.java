package com.energyx.ems.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.energyx.common.exception.BusinessException;
import com.energyx.common.exception.ErrorCode;
import com.energyx.common.tenant.TenantContext;
import com.energyx.ems.entity.EmsStrategy;
import com.energyx.ems.mapper.EmsStrategyMapper;
import org.springframework.stereotype.Service;

/**
 * 策略 CRUD。租户隔离由条件化租户拦截器自动完成 （HTTP 线程按 {@link TenantContext} 追加 tenant_id），本服务仅写入时读取租户。
 */
@Service
public class EmsStrategyService extends ServiceImpl<EmsStrategyMapper, EmsStrategy> {

	/** 创建策略（草稿）。 */
	public EmsStrategy create(EmsStrategy s) {
		s.setTenantId(requireTenant());
		s.setStatus(0);
		s.setVersion(1);
		save(s);
		return s;
	}

	public Page<EmsStrategy> page(long pageNo, long pageSize, Long stationId, String type, Integer status) {
		return page(new Page<>(pageNo, pageSize),
				new LambdaQueryWrapper<EmsStrategy>().eq(stationId != null, EmsStrategy::getStationId, stationId)
					.eq(type != null, EmsStrategy::getStrategyType, type)
					.eq(status != null, EmsStrategy::getStatus, status)
					.orderByDesc(EmsStrategy::getPriority));
	}

	public EmsStrategy update(EmsStrategy s) {
		s.setTenantId(null); // 租户不可改
		updateById(s);
		return s;
	}

	public void delete(Long id) {
		EmsStrategy s = getById(id);
		if (s == null)
			throw new BusinessException(ErrorCode.NOT_FOUND, "策略不存在: " + id);
		if (s.getStatus() == 1)
			throw new BusinessException(ErrorCode.CONFLICT, "启用中的策略不能删除，请先停用");
		removeById(id);
	}

	public void switchStatus(Long id, int status) {
		EmsStrategy s = getById(id);
		if (s == null)
			throw new BusinessException(ErrorCode.NOT_FOUND, "策略不存在: " + id);
		s.setStatus(status);
		s.setVersion(s.getVersion() + 1);
		updateById(s);
	}

	private long requireTenant() {
		Long t = TenantContext.getTenantId();
		if (t == null)
			throw new BusinessException(ErrorCode.UNAUTHORIZED, "缺少租户上下文");
		return t;
	}

}
