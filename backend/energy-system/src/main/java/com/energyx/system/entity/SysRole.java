package com.energyx.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.energyx.common.entity.BaseEntity;
import com.energyx.common.enums.DataScope;
import com.energyx.common.enums.RoleStatus;
import lombok.Getter;
import lombok.Setter;

/**
 * 角色。对应表 sys_role（含 tenant_id/create_time/update_time/deleted 列，审计与逻辑删除 由
 * {@link BaseEntity} 承载）。
 */
@Getter
@Setter
@TableName("sys_role")
public class SysRole extends BaseEntity {

	@TableId(type = IdType.AUTO)
	private Long roleId;

	/** 角色编码，如 SUPER_ADMIN / OPERATOR */
	private String roleCode;

	private String roleName;

	/** 数据范围（SELF/ENTERPRISE/TENANT/ALL，对应 DB 1本人 2本企业 3本租户 4全部） */
	private DataScope dataScope;

	/** 角色状态（DISABLED/ENABLED，对应 DB 0禁用 1启用） */
	private RoleStatus status;

}
