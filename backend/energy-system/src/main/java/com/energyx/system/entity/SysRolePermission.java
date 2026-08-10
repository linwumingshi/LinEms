package com.energyx.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 角色-权限关联。对应表 sys_role_permission。
 */
@Getter
@Setter
@TableName("sys_role_permission")
public class SysRolePermission {

	@TableId(type = IdType.AUTO)
	private Long id;

	private Long roleId;

	private Long permId;

}
