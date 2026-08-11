package com.energyx.ems.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.energyx.common.exception.BusinessException;
import com.energyx.common.exception.ErrorCode;
import com.energyx.common.tenant.TenantContext;
import com.energyx.ems.entity.EmsConstraint;
import com.energyx.ems.entity.EmsStrategy;
import com.energyx.ems.mapper.EmsConstraintMapper;
import com.energyx.ems.mapper.EmsStrategyMapper;
import com.energyx.ems.util.StrategyConfigValidator;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 策略 CRUD。租户隔离由条件化租户拦截器自动完成 （HTTP 线程按 {@link TenantContext} 追加 tenant_id），本服务仅写入时读取租户。
 */
@Service
public class EmsStrategyService extends ServiceImpl<EmsStrategyMapper, EmsStrategy> {

	private final EmsConstraintMapper constraintMapper;

	public EmsStrategyService(EmsConstraintMapper constraintMapper) {
		this.constraintMapper = constraintMapper;
	}

	/** 创建策略（草稿）。保存即校验 config（P0-5c）。 */
	public EmsStrategy create(EmsStrategy s) {
		s.setTenantId(requireTenant());
		validateConfig(s);
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
		validateConfig(s);
		updateById(s);
		return s;
	}

	/** P0-5c：保存即校验 config（JSON 结构 + 窗口 start<end + 功率≤包络），问题抛出 BAD_REQUEST。 */
	private void validateConfig(EmsStrategy s) {
		if (s.getStrategyType() == null || s.getConfig() == null)
			return;
		EmsConstraint c = s.getStationId() == null ? null
				: constraintMapper
					.selectOne(new LambdaQueryWrapper<EmsConstraint>().eq(EmsConstraint::getTenantId, requireTenant())
						.eq(EmsConstraint::getStationId, s.getStationId()));
		BigDecimal chargeMax = c == null ? null : c.getChargePowerMax();
		BigDecimal dischargeMax = c == null ? null : c.getDischargePowerMax();
		List<String> issues = StrategyConfigValidator.validate(s.getConfig(), s.getStrategyType(), chargeMax,
				dischargeMax);
		if (!issues.isEmpty()) {
			throw new BusinessException(ErrorCode.BAD_REQUEST, "策略配置校验未通过：" + String.join("；", issues));
		}
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
