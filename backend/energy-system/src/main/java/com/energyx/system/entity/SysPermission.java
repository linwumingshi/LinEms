package com.energyx.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.energyx.common.enums.PermissionStatus;
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

	/** 菜单资源 ID（主键，自增） */
	@TableId(type = IdType.AUTO)
	private Long permId;

	/** 父节点 ID，0 表示顶级节点 */
	private Long parentId;

	/** 权限标识，非空时全局唯一，最大长度 100，如 system:user:list；目录类节点通常为空 */
	private String permCode;

	/** 菜单/按钮显示名称，最大长度 64 */
	private String permName;

	/** 资源类型码：1 菜单（含目录）/ 2 按钮 / 3 数据 */
	private Integer permType;

	/** 数据权限对应的业务资源类型，最大长度 32，取值 DEVICE/STRATEGY/ALARM/STATION；仅 permType=3 时使用 */
	private String resourceType;

	/** 前端路由地址，最大长度 255，如 /system/user；仅菜单类节点使用 */
	private String path;

	/** 同级排序序号，升序排列，值越小越靠前 */
	private Integer sort;

	/** 菜单图标标识，最大长度 100，可为 null */
	private String icon;

	/** 前端组件路径，最大长度 255，如 system/user/index；仅菜单类节点使用 */
	private String component;

	/** 是否在菜单中显示：0 显示 1 隐藏 */
	private Integer visible;

	/**
	 * 资源状态。JSON 输出状态码：0 正常 / 1 停用，枚举常量见
	 * {@link com.energyx.common.enums.PermissionStatus}（NORMAL/DISABLED）。停用后前端不再渲染该菜单/按钮。
	 */
	private PermissionStatus status;

	/** 备注说明，最大长度 500，可为 null */
	private String remark;

	/** 创建时间 */
	private LocalDateTime createTime;

	/** 更新时间 */
	private LocalDateTime updateTime;

	/** 子节点（仅树形接口填充，非表字段） */
	@TableField(exist = false)
	private List<SysPermission> children;

}
