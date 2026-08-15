package com.energyx.common.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 业务实体公共基类：多租户 + 审计字段 + 逻辑删除。
 *
 * <p>
 * 各模块业务主表实体（Row/Entity）继承本类，统一承载以下横切字段，消除重复定义：
 * <ul>
 * <li>tenantId —— 租户 ID。INSERT 时由 {@code TenantLineInnerInterceptor} 自动填充 tenant_id
 * 列（无该列的表加入插件 ignore 清单）；</li>
 * <li>createTime/updateTime —— 由 {@link com.energyx.common.config.AuditMetaObjectHandler}
 * 自动填充；</li>
 * <li>deleted —— 逻辑删除标记（@TableLogic，0 正常 1 删除）。启用逻辑删除的表必须存在 deleted
 * 列（迁移脚本已统一补充），BaseMapper 删除/查询自动拼 deleted 条件。</li>
 * </ul>
 * </p>
 *
 * <p>
 * 注意：createBy（创建人）不进基类——各表 create_by 列覆盖不全（19 张缺列），强行基类化会导致 BaseMapper 查询 SELECT
 * create_by 报 Unknown column；有该列的表由实体自行声明，Service 按需赋值。 流水表（历史/记录/日志，如
 * iot_shadow_history、iot_alarm_record）不做逻辑删除，实体不继承本类， 保持物理删除。
 * </p>
 */
@Getter
@Setter
public abstract class BaseEntity implements Serializable {

	private static final long serialVersionUID = 1L;

	/** 租户 ID（多租户隔离核心字段，单租户默认 1；由租户插件自动填充） */
	private Long tenantId;

	/** 创建时间（由 AuditMetaObjectHandler 自动填充） */
	@TableField(value = "create_time", fill = FieldFill.INSERT)
	private LocalDateTime createTime;

	/** 更新时间（由 AuditMetaObjectHandler 自动填充） */
	@TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
	private LocalDateTime updateTime;

	/** 逻辑删除：0 正常 1 删除（@TableLogic，BaseMapper 自动处理） */
	@TableLogic
	@TableField(value = "deleted")
	private Integer deleted;

}
