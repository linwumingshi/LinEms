package com.energyx.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.energyx.common.exception.BusinessException;
import com.energyx.common.exception.ErrorCode;
import com.energyx.common.enums.EnterpriseLevel;
import com.energyx.system.dto.SysEnterpriseSaveReq;
import com.energyx.system.entity.SysEnterprise;
import com.energyx.system.entity.SysUser;
import com.energyx.system.mapper.SysEnterpriseMapper;
import com.energyx.system.mapper.SysUserMapper;
import com.energyx.system.security.SecurityUtils;
import com.energyx.system.service.SysEnterpriseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 单位服务：组织树维护。邻接表（parent_id）+ 物化路径（path）+ 层级（level）。
 *
 * <p>
 * path 约定：根节点 "/{id}/"，子节点 = 父 path + "{id}/"，如 "/1/3/"； 父级移动时按路径前缀级联修正整棵子树，保证 likeRight
 * 查询始终可用。
 * </p>
 */
@Slf4j
@Service
public class SysEnterpriseServiceImpl extends ServiceImpl<SysEnterpriseMapper, SysEnterprise>
		implements SysEnterpriseService {

	private final SysUserMapper userMapper;

	public SysEnterpriseServiceImpl(SysUserMapper userMapper) {
		this.userMapper = userMapper;
	}

	@Override
	public List<SysEnterprise> tree() {
		List<SysEnterprise> all = orderedList();
		Map<Long, SysEnterprise> byId = all.stream().collect(Collectors.toMap(SysEnterprise::getEnterpriseId, e -> e));
		List<SysEnterprise> roots = new ArrayList<>();
		for (SysEnterprise node : all) {
			Long parentId = node.getParentId() == null ? 0L : node.getParentId();
			SysEnterprise parent = byId.get(parentId);
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

	@Override
	public List<SysEnterprise> listAll() {
		return orderedList();
	}

	@Override
	public Long createEnterprise(SysEnterpriseSaveReq req) {
		long tenantId = currentTenantId();
		long count = count(new LambdaQueryWrapper<SysEnterprise>().eq(SysEnterprise::getTenantId, tenantId)
			.eq(SysEnterprise::getEnterpriseCode, req.getEnterpriseCode()));
		if (count > 0) {
			throw new BusinessException(ErrorCode.CONFLICT, "单位编码已存在：" + req.getEnterpriseCode());
		}

		SysEnterprise parent = resolveParent(req.getParentId());
		SysEnterprise entity = new SysEnterprise();
		BeanUtils.copyProperties(req, entity);
		entity.setTenantId(tenantId);
		if (parent == null) {
			entity.setParentId(0L);
			entity.setLevel(EnterpriseLevel.GROUP);
		}
		else {
			entity.setParentId(parent.getEnterpriseId());
			entity.setLevel(EnterpriseLevel.of(Math.min(parent.getLevel().getCode() + 1, 2)));
		}
		if (entity.getStatus() == null) {
			entity.setStatus(1);
		}
		if (entity.getSort() == null) {
			entity.setSort(0);
		}
		save(entity);

		// 物化路径依赖自增主键，插入后回填
		entity.setPath((parent == null ? "/" : parent.getPath()) + entity.getEnterpriseId() + "/");
		updateById(entity);
		log.info("创建单位 enterpriseId={} code={} path={}", entity.getEnterpriseId(), entity.getEnterpriseCode(),
				entity.getPath());
		return entity.getEnterpriseId();
	}

	@Override
	public void updateEnterprise(Long enterpriseId, SysEnterpriseSaveReq req) {
		SysEnterprise exists = getById(enterpriseId);
		if (exists == null) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "单位不存在：" + enterpriseId);
		}
		if (!Objects.equals(exists.getEnterpriseCode(), req.getEnterpriseCode())) {
			long count = count(
					new LambdaQueryWrapper<SysEnterprise>().eq(SysEnterprise::getTenantId, exists.getTenantId())
						.eq(SysEnterprise::getEnterpriseCode, req.getEnterpriseCode())
						.ne(SysEnterprise::getEnterpriseId, enterpriseId));
			if (count > 0) {
				throw new BusinessException(ErrorCode.CONFLICT, "单位编码已存在：" + req.getEnterpriseCode());
			}
		}

		Long newParentId = req.getParentId() == null || req.getParentId() == 0L ? 0L : req.getParentId();
		if (newParentId.equals(enterpriseId)) {
			throw new BusinessException(ErrorCode.CONFLICT, "父单位不能是自己");
		}
		if (newParentId != 0L) {
			SysEnterprise parent = getById(newParentId);
			if (parent == null) {
				throw new BusinessException(ErrorCode.NOT_FOUND, "父单位不存在：" + newParentId);
			}
			// 防止成环：新父单位不能落在自身子树内
			if (exists.getPath() != null && parent.getPath().startsWith(exists.getPath())) {
				throw new BusinessException(ErrorCode.CONFLICT, "父单位不能是自己或自己的子单位");
			}
		}

		String oldPath = exists.getPath();
		int oldLevel = exists.getLevel() == null ? 1 : exists.getLevel().getCode();
		BeanUtils.copyProperties(req, exists);
		exists.setParentId(newParentId);
		if (newParentId == 0L) {
			exists.setPath("/" + enterpriseId + "/");
			exists.setLevel(EnterpriseLevel.GROUP);
		}
		else {
			SysEnterprise parent = getById(newParentId);
			exists.setPath(parent.getPath() + enterpriseId + "/");
			exists.setLevel(EnterpriseLevel.of(Math.min(parent.getLevel().getCode() + 1, 2)));
		}
		updateById(exists);

		if (!Objects.equals(oldPath, exists.getPath())) {
			cascadeChildren(exists, oldPath, oldLevel);
		}
		log.info("更新单位 enterpriseId={} path={}", enterpriseId, exists.getPath());
	}

	@Override
	public void deleteEnterprise(Long enterpriseId) {
		SysEnterprise exists = getById(enterpriseId);
		if (exists == null) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "单位不存在：" + enterpriseId);
		}
		long children = count(new LambdaQueryWrapper<SysEnterprise>().eq(SysEnterprise::getParentId, enterpriseId));
		if (children > 0) {
			throw new BusinessException(ErrorCode.CONFLICT, "存在子单位，请先删除子单位");
		}
		long users = userMapper
			.selectCount(new LambdaQueryWrapper<SysUser>().eq(SysUser::getEnterpriseId, enterpriseId));
		if (users > 0) {
			throw new BusinessException(ErrorCode.CONFLICT, "单位下存在用户，无法删除");
		}
		removeById(enterpriseId);
		log.info("删除单位 enterpriseId={}", enterpriseId);
	}

	@Override
	public void changeStatus(Long enterpriseId, Integer status) {
		SysEnterprise exists = getById(enterpriseId);
		if (exists == null) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "单位不存在：" + enterpriseId);
		}
		SysEnterprise update = new SysEnterprise();
		update.setEnterpriseId(enterpriseId);
		update.setStatus(status);
		updateById(update);
		log.info("变更单位状态 enterpriseId={} status={}", enterpriseId, status);
	}

	private List<SysEnterprise> orderedList() {
		return list(new LambdaQueryWrapper<SysEnterprise>().orderByAsc(SysEnterprise::getSort)
			.orderByAsc(SysEnterprise::getEnterpriseId));
	}

	/** 解析父单位；null 或 0 表示顶级。 */
	private SysEnterprise resolveParent(Long parentId) {
		if (parentId == null || parentId == 0L) {
			return null;
		}
		SysEnterprise parent = getById(parentId);
		if (parent == null) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "父单位不存在：" + parentId);
		}
		return parent;
	}

	/** 父级移动后按旧路径前缀级联修正全部后代的 path/level。 */
	private void cascadeChildren(SysEnterprise self, String oldPath, int oldLevel) {
		List<SysEnterprise> children = list(
				new LambdaQueryWrapper<SysEnterprise>().likeRight(SysEnterprise::getPath, oldPath)
					.ne(SysEnterprise::getEnterpriseId, self.getEnterpriseId()));
		for (SysEnterprise child : children) {
			int delta = child.getLevel() == null ? 1 : child.getLevel().getCode() - oldLevel;
			child.setPath(self.getPath() + child.getPath().substring(oldPath.length()));
			child.setLevel(EnterpriseLevel.of(Math.max(1, Math.min(self.getLevel().getCode() + delta, 2))));
			updateById(child);
		}
	}

	private long currentTenantId() {
		Long tenantId = SecurityUtils.getTenantId();
		return tenantId == null ? 1L : tenantId;
	}

}
