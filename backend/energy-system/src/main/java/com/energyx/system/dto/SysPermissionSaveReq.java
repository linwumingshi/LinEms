package com.energyx.system.dto;

import com.energyx.common.enums.PermissionStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 菜单资源创建/更新请求。
 */
@Data
public class SysPermissionSaveReq {

	/** 父节点 ID（0=顶级） */
	private Long parentId;

	/** 权限标识（非空时全局唯一），如 system:user:add */
	@Size(max = 100, message = "权限标识长度不能超过 100")
	private String permCode;

	@NotBlank(message = "菜单名称不能为空")
	@Size(max = 64, message = "菜单名称长度不能超过 64")
	private String permName;

	/** 1 菜单 2 按钮 3 数据 */
	@NotNull(message = "菜单类型不能为空")
	private Integer permType;

	/** 资源类型：DEVICE/STRATEGY/ALARM/STATION（数据权限用） */
	@Size(max = 32, message = "资源类型长度不能超过 32")
	private String resourceType;

	@Size(max = 255, message = "路由地址长度不能超过 255")
	private String path;

	private Integer sort;

	@Size(max = 100, message = "图标长度不能超过 100")
	private String icon;

	@Size(max = 255, message = "组件路径长度不能超过 255")
	private String component;

	/** 是否显示：0 显示 1 隐藏 */
	private Integer visible;

	/** 权限状态（NORMAL/DISABLED，对应 DB 0正常 1停用） */
	private PermissionStatus status;

	@Size(max = 500, message = "备注长度不能超过 500")
	private String remark;

}
