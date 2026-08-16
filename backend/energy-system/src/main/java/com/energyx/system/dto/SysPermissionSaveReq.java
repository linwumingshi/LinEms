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

	/**
	 * 父节点 ID。为空或 0 表示顶级节点；非 0 时对应节点必须存在。更新时不允许指向自身或自身子树（防成环）。
	 */
	private Long parentId;

	/**
	 * 权限标识，非空时全局唯一，最大长度 100，如 {@code system:user:add}。 即 {@code @ss.hasPermi(...)}
	 * 校验所用的标识；目录类节点通常留空。
	 */
	@Size(max = 100, message = "权限标识长度不能超过 100")
	private String permCode;

	/**
	 * 菜单/按钮显示名称，最大长度 64。
	 *
	 */
	@NotBlank(message = "菜单名称不能为空")
	@Size(max = 64, message = "菜单名称长度不能超过 64")
	private String permName;

	/**
	 * 资源类型码，仅允许 1 菜单（含目录）/ 2 按钮 / 3 数据；其他取值会被拒绝。
	 *
	 */
	@NotNull(message = "菜单类型不能为空")
	private Integer permType;

	/**
	 * 数据权限对应的业务资源类型，最大长度 32，取值 DEVICE（设备）/ STRATEGY（策略）/ ALARM（告警）/ STATION（电站）。 仅
	 * permType=3（数据）时使用，其余场景留空。
	 */
	@Size(max = 32, message = "资源类型长度不能超过 32")
	private String resourceType;

	/**
	 * 前端路由地址，最大长度 255，如 {@code /system/user}。仅菜单类节点使用，按钮节点留空。
	 */
	@Size(max = 255, message = "路由地址长度不能超过 255")
	private String path;

	/**
	 * 同级排序序号，升序排列，值越小越靠前；为空时默认 0。
	 */
	private Integer sort;

	/**
	 * 菜单图标标识，最大长度 100；可空。
	 */
	@Size(max = 100, message = "图标长度不能超过 100")
	private String icon;

	/**
	 * 前端组件路径，最大长度 255，如 {@code system/user/index}。仅菜单类节点使用。
	 */
	@Size(max = 255, message = "组件路径长度不能超过 255")
	private String component;

	/**
	 * 是否在菜单中显示，取值 0 显示 / 1 隐藏；为空时默认 0（显示）。
	 */
	private Integer visible;

	/**
	 * 资源状态。JSON 传状态码：0 正常 / 1 停用，枚举常量见
	 * {@link com.energyx.common.enums.PermissionStatus}（NORMAL/DISABLED）。创建时为空默认
	 * NORMAL（正常）。
	 */
	private PermissionStatus status;

	/**
	 * 备注说明，最大长度 500；可空。
	 */
	@Size(max = 500, message = "备注长度不能超过 500")
	private String remark;

}
