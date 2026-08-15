package com.energyx.common.model;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 业务实体公共基类（多租户 + 审计字段）。
 *
 * <p>
 * 各模块数据行（Row/Entity）继承本类，消除 tenantId/createBy/createTime/updateTime 的重复定义。 租户 ID
 * 属横切字段统一放基类，与审计字段同级；多租户接入后由 TenantContext 统一赋值， 单租户环境 Service 层默认填 1。
 * </p>
 *
 * <p>
 * 配合 MyBatis-Plus BaseMapper 使用时：createBy/updateBy 等需在 insert/update 前由 Service 赋值；
 * createTime/updateTime 依赖 DB 默认值（CURRENT_TIMESTAMP(3) / ON UPDATE）回显，或 Service 显式设置。
 * </p>
 */
@Data
public abstract class BaseEntity implements Serializable {

	/** 租户 ID（多租户隔离核心字段，单租户默认 1） */
	private Long tenantId;

	/** 创建人（用户 ID，系统动作填 0） */
	private Long createBy;

	/** 创建时间（DB 默认 CURRENT_TIMESTAMP(3)） */
	private LocalDateTime createTime;

	/** 更新时间（DB ON UPDATE CURRENT_TIMESTAMP(3)） */
	private LocalDateTime updateTime;

}
