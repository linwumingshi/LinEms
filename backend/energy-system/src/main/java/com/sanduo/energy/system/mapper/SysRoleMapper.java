package com.sanduo.energy.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanduo.energy.system.entity.SysRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 角色 Mapper。
 */
@Mapper
public interface SysRoleMapper extends BaseMapper<SysRole> {

    /** 查询用户在启用的角色编码集合（登录时装配 LoginUser）。 */
    @Select("SELECT r.role_code FROM sys_role r " +
            "INNER JOIN sys_user_role ur ON ur.role_id = r.role_id " +
            "WHERE ur.user_id = #{userId} AND r.status = 1")
    List<String> selectRoleCodesByUserId(Long userId);
}
