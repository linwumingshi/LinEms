package com.energyx.system.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.energyx.common.enums.TenantStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 租户（=集团）。 对应表 sys_tenant；quota 为 JSON 资源配额（以 String 承载 JSON 文本）。
 *
 * <p>
 * 注意：本类不继承 {@link com.energyx.common.entity.BaseEntity}——tenant_id 是 sys_tenant 的
 * 主键（非横切租户列），且租户表不能被租户插件按当前租户过滤（已加入插件 ignore 清单）， 故自行声明全部字段；审计/逻辑删除用注解补齐自动填充。
 * </p>
 */
@Getter
@Setter
@TableName("sys_tenant")
public class SysTenant {

	/** 租户 ID（主键，自增） */
	@TableId(type = IdType.AUTO)
	private Long tenantId;

	/** 租户编码，全局唯一，最大长度 64 */
	private String tenantCode;

	/** 租户（集团）名称，最大长度 128 */
	private String tenantName;

	/** 租户联系人姓名，最大长度 64，可为 null */
	private String contact;

	/** 租户联系电话，最大长度 32，可为 null */
	private String phone;

	/**
	 * 资源配额，以 JSON 文本承载，最大长度 512。 如
	 * {@code {"deviceLimit":100000,"ingestRate":500}}：deviceLimit 为设备数上限（台）、ingestRate
	 * 为数据上报速率上限（条/秒）。
	 */
	private String quota;

	/**
	 * 租户状态。JSON 输出状态码：0 禁用 / 1 启用，枚举常量见
	 * {@link com.energyx.common.enums.TenantStatus}（DISABLED/ENABLED）。
	 */
	private TenantStatus status;

	/** 创建时间（由 AuditMetaObjectHandler 自动填充） */
	@TableField(value = "create_time", fill = FieldFill.INSERT)
	private LocalDateTime createTime;

	/** 更新时间（由 AuditMetaObjectHandler 自动填充） */
	@TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
	private LocalDateTime updateTime;

	/** 逻辑删除：0 正常 1 删除 */
	@TableLogic
	@TableField(value = "deleted")
	private Integer deleted;

}
