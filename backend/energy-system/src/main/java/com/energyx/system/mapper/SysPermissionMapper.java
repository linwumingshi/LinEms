package com.energyx.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.energyx.system.entity.SysPermission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 菜单资源 Mapper。
 */
@Mapper
public interface SysPermissionMapper extends BaseMapper<SysPermission> {

    /** 查询用户在启用的权限标识集合（登录时装配 LoginUser，驱动 @ss.hasPermi）。 */
    @Select("SELECT DISTINCT p.perm_code FROM sys_permission p " +
            "INNER JOIN sys_role_permission rp ON rp.perm_id = p.perm_id " +
            "INNER JOIN sys_role r ON r.role_id = rp.role_id AND r.status = 1 " +
            "INNER JOIN sys_user_role ur ON ur.role_id = r.role_id " +
            "WHERE ur.user_id = #{userId} AND p.status = 0")
    List<String> selectPermCodesByUserId(Long userId);
}
