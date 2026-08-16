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

	/** 角色 ID（主键，自增）；roleId=1 为内置超级管理员角色，不可删除/禁用 */
	@TableId(type = IdType.AUTO)
	private Long roleId;

	/** 角色编码，同租户内唯一，最大长度 64，以字母开头且仅含字母/数字/下划线，如 SUPER_ADMIN、OPERATOR */
	private String roleCode;

	/** 角色名称（展示用），最大长度 64 */
	private String roleName;

	/**
	 * 数据可见范围。JSON 输出状态码：1 本人 / 2 本企业 / 3 本租户 / 4 全部，枚举常量见
	 * {@link com.energyx.common.enums.DataScope}（SELF/ENTERPRISE/TENANT/ALL）。
	 */
	private DataScope dataScope;

	/**
	 * 角色状态。JSON 输出状态码：0 禁用 / 1 启用，枚举常量见
	 * {@link com.energyx.common.enums.RoleStatus}（DISABLED/ENABLED）。
	 */
	private RoleStatus status;

}
