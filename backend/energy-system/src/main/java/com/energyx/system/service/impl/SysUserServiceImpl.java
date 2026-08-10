package com.energyx.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.energyx.common.exception.BusinessException;
import com.energyx.common.exception.ErrorCode;
import com.energyx.common.model.PageResult;
import com.energyx.system.dto.SysUserQuery;
import com.energyx.system.dto.SysUserSaveReq;
import com.energyx.system.dto.SysUserVO;
import com.energyx.system.entity.SysEnterprise;
import com.energyx.system.entity.SysRole;
import com.energyx.system.entity.SysUser;
import com.energyx.system.entity.SysUserRole;
import com.energyx.system.mapper.SysEnterpriseMapper;
import com.energyx.system.mapper.SysRoleMapper;
import com.energyx.system.mapper.SysUserMapper;
import com.energyx.system.mapper.SysUserRoleMapper;
import com.energyx.system.security.PermissionResolver;
import com.energyx.system.security.SecurityUtils;
import com.energyx.system.security.TokenService;
import com.energyx.system.service.SysUserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 用户服务：CRUD + 角色分配。
 *
 * <p>
 * 安全约束：超级管理员（userId=1）与当前登录账号不可删除/禁用； 密码变更/禁用/删除吊销在线会话（TokenService.revokeUserSessions）；
 * 角色分配后刷新在线会话身份（TokenService.refreshUserSessions）。
 * </p>
 */
@Slf4j
@Service
public class SysUserServiceImpl extends ServiceImpl<SysUserMapper, SysUser> implements SysUserService {

	private final SysUserRoleMapper userRoleMapper;

	private final SysRoleMapper roleMapper;

	private final SysEnterpriseMapper enterpriseMapper;

	private final PasswordEncoder passwordEncoder;

	private final TokenService tokenService;

	private final PermissionResolver permissionResolver;

	public SysUserServiceImpl(SysUserRoleMapper userRoleMapper, SysRoleMapper roleMapper,
			SysEnterpriseMapper enterpriseMapper, PasswordEncoder passwordEncoder, TokenService tokenService,
			PermissionResolver permissionResolver) {
		this.userRoleMapper = userRoleMapper;
		this.roleMapper = roleMapper;
		this.enterpriseMapper = enterpriseMapper;
		this.passwordEncoder = passwordEncoder;
		this.tokenService = tokenService;
		this.permissionResolver = permissionResolver;
	}

	@Override
	public PageResult<SysUserVO> pageQuery(SysUserQuery query) {
		long size = Math.min(query.getSize() <= 0 ? 10 : query.getSize(), 100);
		long current = Math.max(query.getCurrent(), 1);
		LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
		if (StringUtils.hasText(query.getKeyword())) {
			String keyword = query.getKeyword().trim();
			wrapper.and(w -> w.like(SysUser::getUsername, keyword).or().like(SysUser::getRealName, keyword));
		}
		if (query.getStatus() != null) {
			wrapper.eq(SysUser::getStatus, query.getStatus());
		}
		if (query.getEnterpriseId() != null) {
			wrapper.eq(SysUser::getEnterpriseId, query.getEnterpriseId());
		}
		wrapper.orderByDesc(SysUser::getUserId);
		Page<SysUser> page = page(new Page<>(current, size), wrapper);

		List<SysUserVO> records = page.getRecords().stream().map(this::toVO).toList();
		fillEnterpriseNames(records);
		fillRoles(records);
		return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(), records);
	}

	@Override
	public SysUserVO detailVO(Long userId) {
		SysUser user = getById(userId);
		if (user == null) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在：" + userId);
		}
		SysUserVO vo = toVO(user);
		fillEnterpriseNames(List.of(vo));
		fillRoles(List.of(vo));
		return vo;
	}

	@Override
	public Long createUser(SysUserSaveReq req) {
		long tenantId = currentTenantId();
		long count = count(new LambdaQueryWrapper<SysUser>().eq(SysUser::getTenantId, tenantId)
			.eq(SysUser::getUsername, req.getUsername()));
		if (count > 0) {
			throw new BusinessException(ErrorCode.CONFLICT, "用户名已存在：" + req.getUsername());
		}
		if (!StringUtils.hasText(req.getPassword())) {
			throw new BusinessException(ErrorCode.PARAM_MISSING, "创建用户必须设置密码");
		}
		// save 前先校验角色存在，避免半途失败留下无角色用户
		validateRolesExist(req.getRoleIds());

		SysUser entity = new SysUser();
		BeanUtils.copyProperties(req, entity);
		entity.setTenantId(tenantId);
		entity.setPassword(passwordEncoder.encode(req.getPassword()));
		if (entity.getStatus() == null) {
			entity.setStatus(1);
		}
		save(entity);
		assignRoles(entity.getUserId(), req.getRoleIds());
		log.info("创建用户 userId={} username={}", entity.getUserId(), entity.getUsername());
		return entity.getUserId();
	}

	@Override
	public void updateUser(Long userId, SysUserSaveReq req) {
		SysUser exists = getById(userId);
		if (exists == null) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在：" + userId);
		}
		if (!Objects.equals(exists.getUsername(), req.getUsername())) {
			long count = count(new LambdaQueryWrapper<SysUser>().eq(SysUser::getTenantId, exists.getTenantId())
				.eq(SysUser::getUsername, req.getUsername())
				.ne(SysUser::getUserId, userId));
			if (count > 0) {
				throw new BusinessException(ErrorCode.CONFLICT, "用户名已存在：" + req.getUsername());
			}
		}

		boolean passwordChanged = StringUtils.hasText(req.getPassword());
		BeanUtils.copyProperties(req, exists);
		if (passwordChanged) {
			exists.setPassword(passwordEncoder.encode(req.getPassword()));
		}
		else {
			// 留空不改密码：置 null 使 MyBatis-Plus 默认策略不更新该列
			exists.setPassword(null);
		}
		updateById(exists);

		if (req.getRoleIds() != null) {
			assignRoles(userId, req.getRoleIds());
		}
		if (passwordChanged) {
			tokenService.revokeUserSessions(userId);
		}
		log.info("更新用户 userId={} passwordChanged={}", userId, passwordChanged);
	}

	@Override
	public void deleteUser(Long userId) {
		if (Long.valueOf(1L).equals(userId)) {
			throw new BusinessException(ErrorCode.CONFLICT, "超级管理员不可删除");
		}
		if (userId.equals(SecurityUtils.getUserId())) {
			throw new BusinessException(ErrorCode.CONFLICT, "不能删除当前登录账号");
		}
		SysUser exists = getById(userId);
		if (exists == null) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在：" + userId);
		}
		userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, userId));
		removeById(userId);
		tokenService.revokeUserSessions(userId);
		log.info("删除用户 userId={}", userId);
	}

	@Override
	public void changeStatus(Long userId, Integer status) {
		if (Long.valueOf(1L).equals(userId) && status != null && status != 1) {
			throw new BusinessException(ErrorCode.CONFLICT, "超级管理员不可禁用");
		}
		if (userId.equals(SecurityUtils.getUserId()) && status != null && status != 1) {
			throw new BusinessException(ErrorCode.CONFLICT, "不能禁用当前登录账号");
		}
		SysUser exists = getById(userId);
		if (exists == null) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在：" + userId);
		}
		SysUser update = new SysUser();
		update.setUserId(userId);
		update.setStatus(status);
		updateById(update);
		if (status != null && status != 1) {
			tokenService.revokeUserSessions(userId);
		}
		log.info("变更用户状态 userId={} status={}", userId, status);
	}

	@Override
	public void resetPassword(Long userId, String newPassword) {
		if (!StringUtils.hasText(newPassword) || newPassword.length() < 6 || newPassword.length() > 64) {
			throw new BusinessException(ErrorCode.PARAM_MISSING, "密码长度需在 6~64 位");
		}
		SysUser exists = getById(userId);
		if (exists == null) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在：" + userId);
		}
		SysUser update = new SysUser();
		update.setUserId(userId);
		update.setPassword(passwordEncoder.encode(newPassword));
		updateById(update);
		tokenService.revokeUserSessions(userId);
		log.info("重置密码 userId={}", userId);
	}

	@Override
	public List<Long> roleIds(Long userId) {
		return userRoleMapper.selectList(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, userId))
			.stream()
			.map(SysUserRole::getRoleId)
			.toList();
	}

	@Override
	public void assignRoles(Long userId, List<Long> roleIds) {
		SysUser exists = getById(userId);
		if (exists == null) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在：" + userId);
		}
		List<Long> targets = roleIds == null ? List.of() : roleIds.stream().distinct().toList();
		validateRolesExist(targets);
		userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, userId));
		for (Long roleId : targets) {
			SysUserRole ur = new SysUserRole();
			ur.setUserId(userId);
			ur.setRoleId(roleId);
			userRoleMapper.insert(ur);
		}
		tokenService.refreshUserSessions(userId, permissionResolver);
		log.info("分配用户角色 userId={} roleIds={}", userId, targets);
	}

	private SysUserVO toVO(SysUser user) {
		SysUserVO vo = new SysUserVO();
		vo.setUserId(user.getUserId());
		vo.setTenantId(user.getTenantId());
		vo.setEnterpriseId(user.getEnterpriseId());
		vo.setUsername(user.getUsername());
		vo.setRealName(user.getRealName());
		vo.setPhone(user.getPhone());
		vo.setEmail(user.getEmail());
		vo.setStatus(user.getStatus());
		vo.setLastLoginTime(user.getLastLoginTime());
		vo.setCreateTime(user.getCreateTime());
		return vo;
	}

	private void fillEnterpriseNames(List<SysUserVO> vos) {
		Set<Long> ids = vos.stream()
			.map(SysUserVO::getEnterpriseId)
			.filter(Objects::nonNull)
			.collect(Collectors.toSet());
		if (ids.isEmpty()) {
			return;
		}
		Map<Long, SysEnterprise> map = enterpriseMapper.selectBatchIds(ids)
			.stream()
			.collect(Collectors.toMap(SysEnterprise::getEnterpriseId, e -> e));
		for (SysUserVO vo : vos) {
			if (vo.getEnterpriseId() != null) {
				SysEnterprise enterprise = map.get(vo.getEnterpriseId());
				if (enterprise != null) {
					vo.setEnterpriseName(enterprise.getEnterpriseName());
				}
			}
		}
	}

	private void fillRoles(List<SysUserVO> vos) {
		Set<Long> userIds = vos.stream().map(SysUserVO::getUserId).collect(Collectors.toSet());
		if (userIds.isEmpty()) {
			return;
		}
		Map<Long, List<Long>> userRoleMap = userRoleMapper
			.selectList(new LambdaQueryWrapper<SysUserRole>().in(SysUserRole::getUserId, userIds))
			.stream()
			.collect(Collectors.groupingBy(SysUserRole::getUserId,
					Collectors.mapping(SysUserRole::getRoleId, Collectors.toList())));
		Set<Long> roleIds = userRoleMap.values().stream().flatMap(Collection::stream).collect(Collectors.toSet());
		Map<Long, String> roleNames = roleIds.isEmpty() ? Map.of()
				: roleMapper.selectBatchIds(roleIds)
					.stream()
					.collect(Collectors.toMap(SysRole::getRoleId, SysRole::getRoleName));

		for (SysUserVO vo : vos) {
			List<Long> ids = userRoleMap.getOrDefault(vo.getUserId(), List.of());
			vo.setRoleIds(ids);
			vo.setRoleNames(ids.stream().map(roleNames::get).filter(Objects::nonNull).toList());
		}
	}

	/** 全量校验角色存在（先校验后写库，避免半途失败）。 */
	private void validateRolesExist(List<Long> roleIds) {
		if (roleIds == null) {
			return;
		}
		for (Long roleId : roleIds) {
			if (roleMapper.selectById(roleId) == null) {
				throw new BusinessException(ErrorCode.NOT_FOUND, "角色不存在：" + roleId);
			}
		}
	}

	private long currentTenantId() {
		Long tenantId = SecurityUtils.getTenantId();
		return tenantId == null ? 1L : tenantId;
	}

}
