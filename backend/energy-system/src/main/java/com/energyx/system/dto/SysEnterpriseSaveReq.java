package com.energyx.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 单位创建/更新请求。
 */
@Data
public class SysEnterpriseSaveReq {

	/**
	 * 单位编码，同租户内唯一，最大长度 64。变更时会重新做唯一性校验。
	 *
	 */
	@NotBlank(message = "单位编码不能为空")
	@Size(max = 64, message = "单位编码长度不能超过 64")
	private String enterpriseCode;

	/**
	 * 单位名称，最大长度 128。
	 *
	 */
	@NotBlank(message = "单位名称不能为空")
	@Size(max = 128, message = "单位名称长度不能超过 128")
	private String enterpriseName;

	/**
	 * 父单位 ID。为空或 0 表示顶级单位（层级置为集团直属）；非 0 时对应单位必须存在，层级取父级 +1（上限 2，即子企业）。
	 * <p>
	 * 更新时不允许指向自身或自身子树内的单位（按物化路径前缀判定，防成环）。
	 * </p>
	 */
	private Long parentId;

	/**
	 * 同级排序序号，升序排列，值越小越靠前；为空时默认 0。
	 */
	private Integer sort;

	/**
	 * 单位状态，取值 0 禁用 / 1 启用；为空时默认 1（启用）。
	 */
	private Integer status;

}
