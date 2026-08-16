package com.energyx.system.dto;

import com.energyx.common.enums.TenantStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 租户创建/更新请求。
 */
@Data
public class SysTenantSaveReq {

	/**
	 * 租户编码，全局唯一，最大长度 64。变更时会重新做唯一性校验。
	 *
	 */
	@NotBlank(message = "租户编码不能为空")
	@Size(max = 64, message = "租户编码长度不能超过 64")
	private String tenantCode;

	/**
	 * 租户（集团）名称，最大长度 128。
	 *
	 */
	@NotBlank(message = "租户名称不能为空")
	@Size(max = 128, message = "租户名称长度不能超过 128")
	private String tenantName;

	/**
	 * 租户联系人姓名，最大长度 64；可空。
	 */
	@Size(max = 64, message = "联系人长度不能超过 64")
	private String contact;

	/**
	 * 租户联系电话，最大长度 32；可空。
	 */
	@Size(max = 32, message = "联系电话长度不能超过 32")
	private String phone;

	/**
	 * 资源配额，以 JSON 文本承载，最大长度 512；可空。 例如 {@code {"deviceLimit":100000,"ingestRate":500}}，其中
	 * deviceLimit 为设备数上限（台）、ingestRate 为数据上报速率上限（条/秒）。
	 */
	@Size(max = 512, message = "资源配额 JSON 长度不能超过 512")
	private String quota;

	/**
	 * 租户状态。JSON 传状态码：0 禁用 / 1 启用，枚举常量见
	 * {@link com.energyx.common.enums.TenantStatus}（DISABLED/ENABLED）。创建时为空默认启用。
	 */
	private TenantStatus status;

}
