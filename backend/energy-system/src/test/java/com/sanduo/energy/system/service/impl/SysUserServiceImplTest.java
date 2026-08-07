package com.sanduo.energy.system.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanduo.energy.common.exception.BusinessException;
import com.sanduo.energy.common.exception.ErrorCode;
import com.sanduo.energy.common.model.PageResult;
import com.sanduo.energy.system.dto.SysUserQuery;
import com.sanduo.energy.system.dto.SysUserSaveReq;
import com.sanduo.energy.system.dto.SysUserVO;
import com.sanduo.energy.system.entity.SysEnterprise;
import com.sanduo.energy.system.entity.SysRole;
import com.sanduo.energy.system.entity.SysUser;
import com.sanduo.energy.system.entity.SysUserRole;
import com.sanduo.energy.system.mapper.SysEnterpriseMapper;
import com.sanduo.energy.system.mapper.SysRoleMapper;
import com.sanduo.energy.system.mapper.SysUserMapper;
import com.sanduo.energy.system.mapper.SysUserRoleMapper;
import com.sanduo.energy.system.security.LoginUser;
import com.sanduo.energy.system.security.PermissionResolver;
import com.sanduo.energy.system.security.TokenService;
import com.sanduo.energy.system.service.SysUserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户管理服务单元测试：密码编码、用户名唯一、自我保护、角色分配与会话刷新。
 */
@ExtendWith(MockitoExtension.class)
class SysUserServiceImplTest {

    @Mock
    private SysUserMapper userMapper;
    @Mock
    private SysUserRoleMapper userRoleMapper;
    @Mock
    private SysRoleMapper roleMapper;
    @Mock
    private SysEnterpriseMapper enterpriseMapper;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private TokenService tokenService;
    @Mock
    private PermissionResolver permissionResolver;

    private SysUserService service;

    @BeforeEach
    void setUp() {
        SysUserServiceImpl impl = new SysUserServiceImpl(userRoleMapper, roleMapper, enterpriseMapper,
                passwordEncoder, tokenService, permissionResolver);
        ReflectionTestUtils.setField(impl, "baseMapper", userMapper);
        service = impl;
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void setCurrentUser(Long userId) {
        LoginUser loginUser = new LoginUser(userId, 1L, 1L, "operator", "op",
                Set.of(), Set.of(), null, 1);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities()));
    }

    private SysUser user(Long id, String username) {
        SysUser user = new SysUser();
        user.setUserId(id);
        user.setTenantId(1L);
        user.setEnterpriseId(1L);
        user.setUsername(username);
        user.setRealName("测试" + username);
        user.setStatus(1);
        return user;
    }

    private SysUserSaveReq req(String username) {
        SysUserSaveReq req = new SysUserSaveReq();
        req.setUsername(username);
        req.setRealName("测试" + username);
        req.setPassword("admin123");
        return req;
    }

    @Test
    void createUser_encodesPasswordAndAssignsRoles() {
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(passwordEncoder.encode("admin123")).thenReturn("{bcrypt}encoded");
        when(userMapper.insert(any(SysUser.class))).thenAnswer(inv -> {
            // 模拟 MyBatis-Plus 插入后回填自增主键
            inv.getArgument(0, SysUser.class).setUserId(100L);
            return 1;
        });
        when(userMapper.selectById(anyLong())).thenReturn(user(100L, "zhangsan"));

        Long id = service.createUser(req("zhangsan"));

        assertEquals(100L, id);
        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        verify(userMapper).insert(captor.capture());
        assertEquals("zhangsan", captor.getValue().getUsername());
        assertEquals("{bcrypt}encoded", captor.getValue().getPassword());
        assertEquals(1L, captor.getValue().getTenantId());
        assertEquals(1, captor.getValue().getStatus());

        // 分配角色（roleIds 为空 → 仅清空旧关联 + 刷新会话）
        verify(userRoleMapper).delete(any());
        verify(tokenService).refreshUserSessions(100L, permissionResolver);
    }

    @Test
    void createUser_duplicateUsername_conflict() {
        when(userMapper.selectCount(any())).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.createUser(req("dup")));
        assertEquals(ErrorCode.CONFLICT.getCode(), ex.getCode());
        verify(userMapper, never()).insert(any(SysUser.class));
    }

    @Test
    void createUser_missingPassword_paramMissing() {
        when(userMapper.selectCount(any())).thenReturn(0L);
        SysUserSaveReq req = req("nopass");
        req.setPassword(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.createUser(req));
        assertEquals(ErrorCode.PARAM_MISSING.getCode(), ex.getCode());
    }

    @Test
    void createUser_invalidRole_notFound_beforeInsert() {
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(roleMapper.selectById(99L)).thenReturn(null);

        SysUserSaveReq req = req("withrole");
        req.setRoleIds(List.of(99L));
        BusinessException ex = assertThrows(BusinessException.class, () -> service.createUser(req));
        assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
        // 角色校验在 save 之前，不产生孤儿用户
        verify(userMapper, never()).insert(any(SysUser.class));
    }

    @Test
    void updateUser_changePassword_revokesSessions() {
        when(userMapper.selectById(5L)).thenReturn(user(5L, "zhangsan"));
        when(passwordEncoder.encode("newpass123")).thenReturn("{bcrypt}new");

        SysUserSaveReq req = req("zhangsan");
        req.setPassword("newpass123");
        service.updateUser(5L, req);

        verify(userMapper).updateById(any(SysUser.class));
        verify(tokenService).revokeUserSessions(5L);
    }

    @Test
    void updateUser_blankPassword_doesNotRevoke() {
        when(userMapper.selectById(5L)).thenReturn(user(5L, "zhangsan"));

        SysUserSaveReq req = req("zhangsan");
        req.setPassword(null);
        service.updateUser(5L, req);

        verify(tokenService, never()).revokeUserSessions(5L);
    }

    @Test
    void deleteUser_superAdmin_conflict() {
        BusinessException ex = assertThrows(BusinessException.class, () -> service.deleteUser(1L));
        assertEquals(ErrorCode.CONFLICT.getCode(), ex.getCode());
        verify(userMapper, never()).deleteById(anyLong());
    }

    @Test
    void deleteUser_self_conflict() {
        setCurrentUser(5L);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.deleteUser(5L));
        assertEquals(ErrorCode.CONFLICT.getCode(), ex.getCode());
    }

    @Test
    void changeStatus_selfDisable_conflict() {
        setCurrentUser(5L);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.changeStatus(5L, 0));
        assertEquals(ErrorCode.CONFLICT.getCode(), ex.getCode());
    }

    @Test
    void changeStatus_disableOther_revokesSessions() {
        setCurrentUser(5L);
        when(userMapper.selectById(6L)).thenReturn(user(6L, "lisi"));

        service.changeStatus(6L, 0);

        verify(userMapper).updateById(any(SysUser.class));
        verify(tokenService).revokeUserSessions(6L);
    }

    @Test
    void assignRoles_validatesThenRefreshesSessions() {
        when(userMapper.selectById(5L)).thenReturn(user(5L, "zhangsan"));
        SysRole role = new SysRole();
        role.setRoleId(10L);
        role.setRoleName("操作员");
        when(roleMapper.selectById(10L)).thenReturn(role);

        service.assignRoles(5L, List.of(10L));

        verify(userRoleMapper).delete(any());
        ArgumentCaptor<SysUserRole> captor = ArgumentCaptor.forClass(SysUserRole.class);
        verify(userRoleMapper).insert(captor.capture());
        assertEquals(5L, captor.getValue().getUserId());
        assertEquals(10L, captor.getValue().getRoleId());
        verify(tokenService).refreshUserSessions(5L, permissionResolver);
    }

    @Test
    void assignRoles_invalidRole_notFound() {
        when(userMapper.selectById(5L)).thenReturn(user(5L, "zhangsan"));
        when(roleMapper.selectById(99L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.assignRoles(5L, List.of(99L)));
        assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
        // 校验失败不清理旧关联
        verify(userRoleMapper, never()).delete(any());
    }

    @Test
    void resetPassword_encodesAndRevokes() {
        when(userMapper.selectById(5L)).thenReturn(user(5L, "zhangsan"));
        when(passwordEncoder.encode("reset123")).thenReturn("{bcrypt}reset");

        service.resetPassword(5L, "reset123");

        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        verify(userMapper).updateById(captor.capture());
        assertEquals("{bcrypt}reset", captor.getValue().getPassword());
        verify(tokenService).revokeUserSessions(5L);
    }

    @Test
    void pageQuery_fillsRolesAndEnterprise() {
        SysUser u = user(5L, "zhangsan");
        Page<SysUser> page = new Page<>(1, 10);
        page.setRecords(List.of(u));
        page.setTotal(1);
        when(userMapper.selectPage(any(), any())).thenReturn(page);

        SysUserRole ur = new SysUserRole();
        ur.setUserId(5L);
        ur.setRoleId(10L);
        when(userRoleMapper.selectList(any())).thenReturn(List.of(ur));

        SysRole role = new SysRole();
        role.setRoleId(10L);
        role.setRoleName("操作员");
        when(roleMapper.selectBatchIds(any())).thenReturn(List.of(role));

        SysEnterprise ent = new SysEnterprise();
        ent.setEnterpriseId(1L);
        ent.setEnterpriseName("EnergyX 集团本部");
        when(enterpriseMapper.selectBatchIds(any())).thenReturn(List.of(ent));

        PageResult<SysUserVO> result = service.pageQuery(new SysUserQuery());

        assertEquals(1, result.getTotal());
        SysUserVO vo = result.getRecords().get(0);
        assertEquals("EnergyX 集团本部", vo.getEnterpriseName());
        assertEquals(List.of(10L), vo.getRoleIds());
        assertEquals(List.of("操作员"), vo.getRoleNames());
    }
}
