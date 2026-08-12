package com.energyx.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.energyx.common.entity.BaseEntity;
import com.energyx.common.enums.TenantStatus;
import lombok.Getter;
import lombok.Setter;

/**
 * 租户（=集团）。 对应表 sys_tenant；quota 为 JSON 资源配额（以 String 承载 JSON 文本）。
 */
@Getter
@Setter
@TableName("sys_tenant")
public class SysTenant extends BaseEntity {

	@TableId(type = IdType.AUTO)
	private Long tenantId;

	private String tenantCode;

	private String tenantName;

	private String contact;

	private String phone;

	/** JSON 资源配额，如 {"deviceLimit":100000,"ingestRate":500} */
	private String quota;

	/** 租户状态（DISABLED/ENABLED，对应 DB 0禁用 1启用） */
	private TenantStatus status;

}
