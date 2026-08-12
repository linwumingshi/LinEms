package com.energyx.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.energyx.common.exception.BusinessException;
import com.energyx.common.exception.ErrorCode;
import com.energyx.common.enums.TenantStatus;
import com.energyx.common.model.PageResult;
import com.energyx.system.dto.SysTenantQuery;
import com.energyx.system.dto.SysTenantSaveReq;
import com.energyx.system.entity.SysTenant;
import com.energyx.system.mapper.SysTenantMapper;
import com.energyx.system.service.SysTenantService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 租户服务：唯一编码校验 + 逻辑删除 + 状态管理。
 */
@Slf4j
@Service
public class SysTenantServiceImpl extends ServiceImpl<SysTenantMapper, SysTenant> implements SysTenantService {

	@Override
	public PageResult<SysTenant> pageQuery(SysTenantQuery query) {
		long size = Math.min(query.getSize() <= 0 ? 10 : query.getSize(), 100);
		long current = Math.max(query.getCurrent(), 1);
		LambdaQueryWrapper<SysTenant> wrapper = new LambdaQueryWrapper<>();
		if (StringUtils.hasText(query.getKeyword())) {
			String keyword = query.getKeyword().trim();
			wrapper.and(w -> w.like(SysTenant::getTenantCode, keyword).or().like(SysTenant::getTenantName, keyword));
		}
		wrapper.orderByDesc(SysTenant::getTenantId);
		Page<SysTenant> page = page(new Page<>(current, size), wrapper);
		return PageResult.of(page);
	}

	@Override
	public Long createTenant(SysTenantSaveReq req) {
		long count = count(new LambdaQueryWrapper<SysTenant>().eq(SysTenant::getTenantCode, req.getTenantCode()));
		if (count > 0) {
			throw new BusinessException(ErrorCode.CONFLICT, "租户编码已存在：" + req.getTenantCode());
		}
		SysTenant entity = new SysTenant();
		BeanUtils.copyProperties(req, entity);
		if (entity.getStatus() == null) {
			entity.setStatus(TenantStatus.ENABLED);
		}
		save(entity);
		log.info("创建租户 tenantId={} code={}", entity.getTenantId(), entity.getTenantCode());
		return entity.getTenantId();
	}

	@Override
	public void updateTenant(Long tenantId, SysTenantSaveReq req) {
		SysTenant exists = getById(tenantId);
		if (exists == null) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "租户不存在：" + tenantId);
		}
		// 编码变更时校验唯一
		if (!exists.getTenantCode().equals(req.getTenantCode())) {
			long count = count(new LambdaQueryWrapper<SysTenant>().eq(SysTenant::getTenantCode, req.getTenantCode())
				.ne(SysTenant::getTenantId, tenantId));
			if (count > 0) {
				throw new BusinessException(ErrorCode.CONFLICT, "租户编码已存在：" + req.getTenantCode());
			}
		}
		BeanUtils.copyProperties(req, exists);
		updateById(exists);
		log.info("更新租户 tenantId={}", tenantId);
	}

	@Override
	public void changeStatus(Long tenantId, Integer status) {
		SysTenant exists = getById(tenantId);
		if (exists == null) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "租户不存在：" + tenantId);
		}
		SysTenant update = new SysTenant();
		update.setTenantId(tenantId);
		update.setStatus(TenantStatus.of(status));
		updateById(update);
		log.info("变更租户状态 tenantId={} status={}", tenantId, status);
	}

}
