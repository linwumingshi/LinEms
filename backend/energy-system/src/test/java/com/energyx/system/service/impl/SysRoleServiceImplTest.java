package com.energyx.system.service.impl;

import com.energyx.common.exception.BusinessException;
import com.energyx.common.exception.ErrorCode;
import com.energyx.system.dto.SysRoleSaveReq;
import com.energyx.system.entity.SysPermission;
import com.energyx.system.entity.SysRole;
import com.energyx.system.entity.SysRolePermission;
import com.energyx.system.mapper.SysPermissionMapper;
import com.energyx.system.mapper.SysRoleMapper;
import com.energyx.system.mapper.SysRolePermissionMapper;
import com.energyx.system.mapper.SysUserRoleMapper;
import com.energyx.system.security.PermissionResolver;
import com.energyx.system.security.TokenService;
import com.energyx.system.service.SysRoleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 角色管理服务单元测试：编码唯一、内置角色保护、删除约束、权限分配与会话刷新。
 */
@ExtendWith(MockitoExtension.class)
class SysRoleServiceImplTest {

    @Mock
    private SysRoleMapper roleMapper;
    @Mock
    private SysRolePermissionMapper rolePermissionMapper;
    @Mock
    private SysUserRoleMapper userRoleMapper;
    @Mock
    private SysPermissionMapper permissionMapper;
    @Mock
    private TokenService tokenService;
    @Mock
    private PermissionResolver permissionResolver;

    private SysRoleService service;

    @BeforeEach
    void setUp() {
        SysRoleServiceImpl impl = new SysRoleServiceImpl(rolePermissionMapper, userRoleMapper,
                permissionMapper, tokenService, permissionResolver);
        ReflectionTestUtils.setField(impl, "baseMapper", roleMapper);
        service = impl;
    }

    private SysRole role(Long id, String code) {
        SysRole role = new SysRole();
        role.setRoleId(id);
        role.setTenantId(1L);
        role.setRoleCode(code);
        role.setRoleName("角色" + code);
        role.setStatus(1);
        return role;
    }

    private SysRoleSaveReq req(String code) {
        SysRoleSaveReq req = new SysRoleSaveReq();
        req.setRoleCode(code);
        req.setRoleName("角色" + code);
        return req;
    }

    @Test
    void createRole_success_setsDefaults() {
        when(roleMapper.selectCount(any())).thenReturn(0L);
        when(roleMapper.insert(any(SysRole.class))).thenReturn(1);

        service.createRole(req("OPERATOR"));

        ArgumentCaptor<SysRole> captor = ArgumentCaptor.forClass(SysRole.class);
        verify(roleMapper).insert(captor.capture());
        assertEquals("OPERATOR", captor.getValue().getRoleCode());
        assertEquals(1L, captor.getValue().getTenantId());
        assertEquals(1, captor.getValue().getStatus());
        assertEquals(3, captor.getValue().getDataScope());
    }

    @Test
    void createRole_duplicateCode_conflict() {
        when(roleMapper.selectCount(any())).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.createRole(req("DUP")));
        assertEquals(ErrorCode.CONFLICT.getCode(), ex.getCode());
        verify(roleMapper, never()).insert(any(SysRole.class));
    }

    @Test
    void deleteRole_superAdmin_conflict() {
        BusinessException ex = assertThrows(BusinessException.class, () -> service.deleteRole(1L));
        assertEquals(ErrorCode.CONFLICT.getCode(), ex.getCode());
        verify(roleMapper, never()).deleteById(anyLong());
    }

    @Test
    void deleteRole_assignedUsers_conflict() {
        when(roleMapper.selectById(2L)).thenReturn(role(2L, "OPERATOR"));
        when(userRoleMapper.selectCount(any())).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.deleteRole(2L));
        assertEquals(ErrorCode.CONFLICT.getCode(), ex.getCode());
    }

    @Test
    void deleteRole_empty_cleansAssociations() {
        when(roleMapper.selectById(2L)).thenReturn(role(2L, "OPERATOR"));
        when(userRoleMapper.selectCount(any())).thenReturn(0L);
        when(roleMapper.deleteById(2L)).thenReturn(1);

        service.deleteRole(2L);

        verify(rolePermissionMapper).delete(any());
        verify(roleMapper).deleteById(2L);
    }

    @Test
    void changeStatus_superAdminDisable_conflict() {
        BusinessException ex = assertThrows(BusinessException.class, () -> service.changeStatus(1L, 0));
        assertEquals(ErrorCode.CONFLICT.getCode(), ex.getCode());
    }

    @Test
    void assignPerms_validatesThenRefreshesSessions() {
        when(roleMapper.selectById(2L)).thenReturn(role(2L, "OPERATOR"));
        SysPermission perm = new SysPermission();
        perm.setPermId(10L);
        perm.setPermCode("system:user:list");
        when(permissionMapper.selectById(10L)).thenReturn(perm);

        service.assignPerms(2L, java.util.List.of(10L));

        verify(rolePermissionMapper).delete(any());
        ArgumentCaptor<SysRolePermission> captor = ArgumentCaptor.forClass(SysRolePermission.class);
        verify(rolePermissionMapper).insert(captor.capture());
        assertEquals(2L, captor.getValue().getRoleId());
        assertEquals(10L, captor.getValue().getPermId());
        verify(tokenService).refreshPermissionByRoleCode("OPERATOR", permissionResolver);
    }

    @Test
    void assignPerms_invalidPerm_notFound() {
        when(roleMapper.selectById(2L)).thenReturn(role(2L, "OPERATOR"));
        when(permissionMapper.selectById(99L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.assignPerms(2L, java.util.List.of(99L)));
        assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
        verify(rolePermissionMapper, never()).delete(any());
    }

    @Test
    void updateRole_duplicateCode_conflict() {
        SysRole existing = role(2L, "OPERATOR");
        when(roleMapper.selectById(2L)).thenReturn(existing);
        when(roleMapper.selectCount(any())).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.updateRole(2L, req("NEWCODE")));
        assertEquals(ErrorCode.CONFLICT.getCode(), ex.getCode());
    }
}
