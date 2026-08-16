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

	/** 单位（企业）ID，主键，自增 */
	@TableId(type = IdType.AUTO)
	private Long enterpriseId;

	/** 父单位 ID，0 表示顶级单位 */
	private Long parentId;

	/** 物化路径，根节点为 /{id}/、子节点为父 path + {id}/，如 /1/3/，便于按前缀做子树查询 */
	private String path;

	/**
	 * 企业层级。JSON 输出状态码：1 集团直属 / 2 子企业，枚举常量见
	 * {@link com.energyx.common.enums.EnterpriseLevel}（GROUP/SUB）。由父级层级 +1 推导，最大 2。
	 */
	private EnterpriseLevel level;

	/** 单位编码，同租户内唯一，最大长度 64 */
	private String enterpriseCode;

	/** 单位名称，最大长度 128 */
	private String enterpriseName;

	/** 同级排序序号，升序排列，值越小越靠前 */
	private Integer sort;

	/** 单位状态：0 禁用 1 启用 */
	private Integer status;

	/** 子节点（仅树形接口填充，非表字段） */
	@TableField(exist = false)
	private List<SysEnterprise> children;

}
