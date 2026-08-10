package com.energyx.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 菜单资源（对齐若依 sys_menu 语义）。对应表 sys_permission。
 *
 * <p>
 * perm_type：1 菜单（含目录） 2 按钮 3 数据； perm_code 即 {@code @ss.hasPermi('system:user:add')}
 * 中的权限标识； 菜单节点带 path/component/icon 供前端动态路由，按钮节点仅承载权限标识。
 * </p>
 */
@Getter
@Setter
@TableName("sys_permission")
public class SysPermission {

	@TableId(type = IdType.AUTO)
	private Long permId;

	/** 父节点 ID（0=顶级） */
	private Long parentId;

	/** 权限标识，如 system:user:list */
	private String permCode;

	private String permName;

	/** 1 菜单 2 按钮 3 数据 */
	private Integer permType;

	/** 资源类型：DEVICE/STRATEGY/ALARM/STATION（数据权限用） */
	private String resourceType;

	/** 前端路由地址 */
	private String path;

	private Integer sort;

	private String icon;

	/** 前端组件路径 */
	private String component;

	/** 是否显示：0 显示 1 隐藏 */
	private Integer visible;

	/** 状态：0 正常 1 停用 */
	private Integer status;

	private String remark;

	private LocalDateTime createTime;

	private LocalDateTime updateTime;

	/** 子节点（仅树形接口填充，非表字段） */
	@TableField(exist = false)
	private List<SysPermission> children;

}
