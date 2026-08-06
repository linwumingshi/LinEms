package com.sanduo.energy.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.sanduo.energy.common.exception.BusinessException;
import com.sanduo.energy.common.exception.ErrorCode;
import com.sanduo.energy.common.model.PageResult;
import com.sanduo.energy.system.dto.SysRoleQuery;
import com.sanduo.energy.system.dto.SysRoleSaveReq;
import com.sanduo.energy.system.entity.SysPermission;
import com.sanduo.energy.system.entity.SysRole;
import com.sanduo.energy.system.entity.SysRolePermission;
import com.sanduo.energy.system.entity.SysUserRole;
import com.sanduo.energy.system.mapper.SysPermissionMapper;
import com.sanduo.energy.system.mapper.SysRoleMapper;
import com.sanduo.energy.system.mapper.SysRolePermissionMapper;
import com.sanduo.energy.system.mapper.SysUserRoleMapper;
import com.sanduo.energy.system.security.PermissionResolver;
import com.sanduo.energy.system.security.SecurityUtils;
import com.sanduo.energy.system.security.TokenService;
import com.sanduo.energy.system.service.SysRoleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;

/**
 * 角色服务：CRUD + 权限分配。
 *
 * <p>权限分配后调用 {@link TokenService#refreshPermissionByRoleCode} 刷新持有该角色的在线会话，
 * 使权限变更立即生效；内置超级管理员角色（roleId=1）不可删除/禁用。</p>
 */
@Slf4j
@Service
public class SysRoleServiceImpl extends ServiceImpl<SysRoleMapper, SysRole> implements SysRoleService {

    private final SysRolePermissionMapper rolePermissionMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysPermissionMapper permissionMapper;
    private final TokenService tokenService;
    private final PermissionResolver permissionResolver;

    public SysRoleServiceImpl(SysRolePermissionMapper rolePermissionMapper, SysUserRoleMapper userRoleMapper,
                              SysPermissionMapper permissionMapper, TokenService tokenService,
                              PermissionResolver permissionResolver) {
        this.rolePermissionMapper = rolePermissionMapper;
        this.userRoleMapper = userRoleMapper;
        this.permissionMapper = permissionMapper;
        this.tokenService = tokenService;
        this.permissionResolver = permissionResolver;
    }

    @Override
    public PageResult<SysRole> pageQuery(SysRoleQuery query) {
        long size = Math.min(query.getSize() <= 0 ? 10 : query.getSize(), 100);
        long current = Math.max(query.getCurrent(), 1);
        LambdaQueryWrapper<SysRole> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(query.getKeyword())) {
            String keyword = query.getKeyword().trim();
            wrapper.and(w -> w.like(SysRole::getRoleCode, keyword).or().like(SysRole::getRoleName, keyword));
        }
        if (query.getStatus() != null) {
            wrapper.eq(SysRole::getStatus, query.getStatus());
        }
        wrapper.orderByDesc(SysRole::getRoleId);
        Page<SysRole> page = page(new Page<>(current, size), wrapper);
        return PageResult.of(page);
    }

    @Override
    public List<SysRole> listAll() {
        return list(new LambdaQueryWrapper<SysRole>().orderByAsc(SysRole::getRoleId));
    }

    @Override
    public Long createRole(SysRoleSaveReq req) {
        long tenantId = currentTenantId();
        long count = count(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getTenantId, tenantId)
                .eq(SysRole::getRoleCode, req.getRoleCode()));
        if (count > 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "角色编码已存在：" + req.getRoleCode());
        }
        SysRole entity = new SysRole();
        BeanUtils.copyProperties(req, entity);
        entity.setTenantId(tenantId);
        if (entity.getStatus() == null) {
            entity.setStatus(1);
        }
        if (entity.getDataScope() == null) {
            entity.setDataScope(3);
        }
        save(entity);
        log.info("创建角色 roleId={} code={}", entity.getRoleId(), entity.getRoleCode());
        return entity.getRoleId();
    }

    @Override
    public void updateRole(Long roleId, SysRoleSaveReq req) {
        SysRole exists = getById(roleId);
        if (exists == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "角色不存在：" + roleId);
        }
        if (!Objects.equals(exists.getRoleCode(), req.getRoleCode())) {
            long count = count(new LambdaQueryWrapper<SysRole>()
                    .eq(SysRole::getTenantId, exists.getTenantId())
                    .eq(SysRole::getRoleCode, req.getRoleCode())
                    .ne(SysRole::getRoleId, roleId));
            if (count > 0) {
                throw new BusinessException(ErrorCode.CONFLICT, "角色编码已存在：" + req.getRoleCode());
            }
        }
        BeanUtils.copyProperties(req, exists);
        updateById(exists);
        log.info("更新角色 roleId={}", roleId);
    }

    @Override
    public void deleteRole(Long roleId) {
        if (Long.valueOf(1L).equals(roleId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "内置超级管理员角色不可删除");
        }
        SysRole exists = getById(roleId);
        if (exists == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "角色不存在：" + roleId);
        }
        long users = userRoleMapper.selectCount(new LambdaQueryWrapper<SysUserRole>()
                .eq(SysUserRole::getRoleId, roleId));
        if (users > 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "角色已分配给用户，请先取消分配");
        }
        rolePermissionMapper.delete(new LambdaQueryWrapper<SysRolePermission>()
                .eq(SysRolePermission::getRoleId, roleId));
        removeById(roleId);
        log.info("删除角色 roleId={}", roleId);
    }

    @Override
    public void changeStatus(Long roleId, Integer status) {
        if (Long.valueOf(1L).equals(roleId) && status != null && status != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "内置超级管理员角色不可禁用");
        }
        SysRole exists = getById(roleId);
        if (exists == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "角色不存在：" + roleId);
        }
        SysRole update = new SysRole();
        update.setRoleId(roleId);
        update.setStatus(status);
        updateById(update);
        log.info("变更角色状态 roleId={} status={}", roleId, status);
    }

    @Override
    public List<Long> permIds(Long roleId) {
        return rolePermissionMapper.selectList(new LambdaQueryWrapper<SysRolePermission>()
                        .eq(SysRolePermission::getRoleId, roleId))
                .stream().map(SysRolePermission::getPermId).toList();
    }

    @Override
    public void assignPerms(Long roleId, List<Long> permIds) {
        SysRole exists = getById(roleId);
        if (exists == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "角色不存在：" + roleId);
        }
        List<Long> targets = permIds == null ? List.of() : permIds.stream().distinct().toList();
        // 先全量校验权限存在，避免半途失败
        for (Long permId : targets) {
            if (permissionMapper.selectById(permId) == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "权限不存在：" + permId);
            }
        }
        rolePermissionMapper.delete(new LambdaQueryWrapper<SysRolePermission>()
                .eq(SysRolePermission::getRoleId, roleId));
        for (Long permId : targets) {
            SysRolePermission rp = new SysRolePermission();
            rp.setRoleId(roleId);
            rp.setPermId(permId);
            rolePermissionMapper.insert(rp);
        }
        tokenService.refreshPermissionByRoleCode(exists.getRoleCode(), permissionResolver);
        log.info("分配角色权限 roleId={} permIds={}", roleId, targets);
    }

    private long currentTenantId() {
        Long tenantId = SecurityUtils.getTenantId();
        return tenantId == null ? 1L : tenantId;
    }
}
