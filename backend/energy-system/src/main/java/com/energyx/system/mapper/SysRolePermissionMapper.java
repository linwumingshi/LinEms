package com.energyx.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.energyx.system.entity.SysRolePermission;
import org.apache.ibatis.annotations.Mapper;

/**
 * 角色-权限关联 Mapper。
 */
@Mapper
public interface SysRolePermissionMapper extends BaseMapper<SysRolePermission> {

}
