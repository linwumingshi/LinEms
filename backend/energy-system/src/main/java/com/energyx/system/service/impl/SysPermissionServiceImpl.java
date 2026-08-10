package com.energyx.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.energyx.common.exception.BusinessException;
import com.energyx.common.exception.ErrorCode;
import com.energyx.system.dto.SysPermissionSaveReq;
import com.energyx.system.entity.SysPermission;
import com.energyx.system.entity.SysRolePermission;
import com.energyx.system.mapper.SysPermissionMapper;
import com.energyx.system.mapper.SysRolePermissionMapper;
import com.energyx.system.security.PermissionResolver;
import com.energyx.system.security.TokenService;
import com.energyx.system.service.SysPermissionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 菜单资源服务：目录/菜单/按钮树维护。
 *
 * <p>
 * 权限标识（perm_code）唯一；写操作后调用 {@link TokenService#refreshAllSessions}
 * 刷新全部在线会话，使菜单/按钮增删改、停用即时生效。
 * </p>
 */
@Slf4j
@Service
public class SysPermissionServiceImpl extends ServiceImpl<SysPermissionMapper, SysPermission>
		implements SysPermissionService {

	private final SysRolePermissionMapper rolePermissionMapper;

	private final TokenService tokenService;

	private final PermissionResolver permissionResolver;

	public SysPermissionServiceImpl(SysRolePermissionMapper rolePermissionMapper, TokenService tokenService,
			PermissionResolver permissionResolver) {
		this.rolePermissionMapper = rolePermissionMapper;
		this.tokenService = tokenService;
		this.permissionResolver = permissionResolver;
	}

	@Override
	public List<SysPermission> tree() {
		List<SysPermission> all = list(new LambdaQueryWrapper<SysPermission>().orderByAsc(SysPermission::getSort)
			.orderByAsc(SysPermission::getPermId));
		Map<Long, SysPermission> byId = all.stream().collect(Collectors.toMap(SysPermission::getPermId, p -> p));
		List<SysPermission> roots = new ArrayList<>();
		for (SysPermission node : all) {
			Long parentId = node.getParentId() == null ? 0L : node.getParentId();
			SysPermission parent = byId.get(parentId);
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
	public Long createPermission(SysPermissionSaveReq req) {
		SysPermission parent = resolveParent(req.getParentId());
		validatePermCodeUnique(req.getPermCode(), null);
		validatePermType(req.getPermType());

		SysPermission entity = new SysPermission();
		BeanUtils.copyProperties(req, entity);
		entity.setParentId(parent == null ? 0L : parent.getPermId());
		if (entity.getSort() == null) {
			entity.setSort(0);
		}
		if (entity.getVisible() == null) {
			entity.setVisible(0);
		}
		if (entity.getStatus() == null) {
			entity.setStatus(0);
		}
		save(entity);
		tokenService.refreshAllSessions(permissionResolver);
		log.info("创建菜单资源 permId={} code={} type={}", entity.getPermId(), entity.getPermCode(), entity.getPermType());
		return entity.getPermId();
	}

	@Override
	public void updatePermission(Long permId, SysPermissionSaveReq req) {
		SysPermission exists = getById(permId);
		if (exists == null) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "菜单不存在：" + permId);
		}
		validatePermCodeUnique(req.getPermCode(), permId);
		validatePermType(req.getPermType());

		Long newParentId = req.getParentId() == null || req.getParentId() == 0L ? 0L : req.getParentId();
		resolveParent(newParentId);
		// 防止成环：沿新父节点祖先链上溯，若遇自身则拒绝
		Long cursor = newParentId;
		while (cursor != null && cursor != 0L) {
			if (cursor.equals(permId)) {
				throw new BusinessException(ErrorCode.CONFLICT, "父节点不能是自己或自己的子节点");
			}
			SysPermission ancestor = getById(cursor);
			cursor = ancestor == null ? 0L : ancestor.getParentId();
		}

		BeanUtils.copyProperties(req, exists);
		exists.setParentId(newParentId);
		updateById(exists);
		tokenService.refreshAllSessions(permissionResolver);
		log.info("更新菜单资源 permId={} code={}", permId, exists.getPermCode());
	}

	@Override
	public void deletePermission(Long permId) {
		SysPermission exists = getById(permId);
		if (exists == null) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "菜单不存在：" + permId);
		}
		long children = count(new LambdaQueryWrapper<SysPermission>().eq(SysPermission::getParentId, permId));
		if (children > 0) {
			throw new BusinessException(ErrorCode.CONFLICT, "存在子菜单，请先删除子菜单");
		}
		rolePermissionMapper
			.delete(new LambdaQueryWrapper<SysRolePermission>().eq(SysRolePermission::getPermId, permId));
		removeById(permId);
		tokenService.refreshAllSessions(permissionResolver);
		log.info("删除菜单资源 permId={} code={}", permId, exists.getPermCode());
	}

	@Override
	public void changeStatus(Long permId, Integer status) {
		SysPermission exists = getById(permId);
		if (exists == null) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "菜单不存在：" + permId);
		}
		SysPermission update = new SysPermission();
		update.setPermId(permId);
		update.setStatus(status);
		updateById(update);
		tokenService.refreshAllSessions(permissionResolver);
		log.info("变更菜单状态 permId={} status={}", permId, status);
	}

	private SysPermission resolveParent(Long parentId) {
		if (parentId == null || parentId == 0L) {
			return null;
		}
		SysPermission parent = getById(parentId);
		if (parent == null) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "父节点不存在：" + parentId);
		}
		return parent;
	}

	private void validatePermCodeUnique(String permCode, Long excludePermId) {
		if (!StringUtils.hasText(permCode)) {
			return;
		}
		LambdaQueryWrapper<SysPermission> wrapper = new LambdaQueryWrapper<SysPermission>()
			.eq(SysPermission::getPermCode, permCode.trim());
		if (excludePermId != null) {
			wrapper.ne(SysPermission::getPermId, excludePermId);
		}
		if (count(wrapper) > 0) {
			throw new BusinessException(ErrorCode.CONFLICT, "权限标识已存在：" + permCode);
		}
	}

	private void validatePermType(Integer permType) {
		if (permType == null || (permType != 1 && permType != 2 && permType != 3)) {
			throw new BusinessException(ErrorCode.PARAM_MISSING, "菜单类型必须为 1（菜单）/ 2（按钮）/ 3（数据）");
		}
	}

}
