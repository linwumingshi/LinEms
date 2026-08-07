package com.energyx.system.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.energyx.system.entity.SysUser;
import com.energyx.system.mapper.SysUserMapper;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * 用户详情加载（Spring Security DaoAuthenticationProvider 的 UserDetailsService）。
 *
 * <p>多租户限定登录名：sys_user 唯一键为 (tenant_id, username)，故登录主体采用
 * 复合主键格式 {@code tenantId:username}，由 {@code AuthServiceImpl} 构造、本类解析。
 * 账号不存在抛 {@link UsernameNotFoundException}（Provider 统一转 BadCredentialsException，
 * 防账号枚举）；禁用/锁定不在此抛异常，交由 Provider 校验 isEnabled/isAccountNonLocked。</p>
 */
@Service
public class UserDetailsServiceImpl implements UserDetailsService {

    private final SysUserMapper userMapper;
    private final PermissionResolver permissionResolver;

    public UserDetailsServiceImpl(SysUserMapper userMapper, PermissionResolver permissionResolver) {
        this.userMapper = userMapper;
        this.permissionResolver = permissionResolver;
    }

    @Override
    public LoginUser loadUserByUsername(String principal) throws UsernameNotFoundException {
        String[] tenantAndName = splitPrincipal(principal);

        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getTenantId, Long.parseLong(tenantAndName[0]))
                .eq(SysUser::getUsername, tenantAndName[1]));
        if (user == null) {
            throw new UsernameNotFoundException("用户不存在: " + tenantAndName[1]);
        }

        PermissionResolver.ResolvedAuth auth = permissionResolver.resolveUser(user.getUserId());

        return new LoginUser(user.getUserId(), user.getTenantId(), user.getEnterpriseId(),
                user.getRealName(), user.getUsername(), auth.permissions(), auth.roleCodes(),
                user.getPassword(), user.getStatus());
    }

    /**
     * 解析复合主键 {@code tenantId:username}；不合法抛 UsernameNotFoundException（不泄露格式细节）。
     */
    private String[] splitPrincipal(String principal) {
        int idx = principal == null ? -1 : principal.indexOf(':');
        if (idx <= 0 || idx >= principal.length() - 1) {
            throw new UsernameNotFoundException("非法的登录主体");
        }
        String tenantId = principal.substring(0, idx);
        try {
            Long.parseLong(tenantId);
        } catch (NumberFormatException e) {
            throw new UsernameNotFoundException("非法的租户标识");
        }
        return new String[]{tenantId, principal.substring(idx + 1)};
    }
}
