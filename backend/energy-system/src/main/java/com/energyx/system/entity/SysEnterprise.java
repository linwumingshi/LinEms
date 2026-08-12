package com.energyx.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.energyx.common.entity.BaseEntity;
import com.energyx.common.enums.EnterpriseLevel;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 企业组织树（单位管理，等价若依 sys_dept）。 邻接表（parent_id）+ 物化路径（path）表达层级，供子树查询。
 */
@Getter
@Setter
@TableName("sys_enterprise")
public class SysEnterprise extends BaseEntity {

	@TableId(type = IdType.AUTO)
	private Long enterpriseId;

	private Long tenantId;

	/** 父企业 ID（0=顶级） */
	private Long parentId;

	/** 物化路径，如 /1/3/ 便于子树查询 */
	private String path;

	/** 企业层级（GROUP/SUB，对应 DB 1集团直属 2子企业） */
	private EnterpriseLevel level;

	private String enterpriseCode;

	private String enterpriseName;

	private Integer sort;

	/** 状态：0 禁用 1 启用 */
	private Integer status;

	/** 子节点（仅树形接口填充，非表字段） */
	@TableField(exist = false)
	private List<SysEnterprise> children;

}
