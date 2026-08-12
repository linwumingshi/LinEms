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

	@NotBlank(message = "租户编码不能为空")
	@Size(max = 64, message = "租户编码长度不能超过 64")
	private String tenantCode;

	@NotBlank(message = "租户名称不能为空")
	@Size(max = 128, message = "租户名称长度不能超过 128")
	private String tenantName;

	@Size(max = 64, message = "联系人长度不能超过 64")
	private String contact;

	@Size(max = 32, message = "联系电话长度不能超过 32")
	private String phone;

	@Size(max = 512, message = "资源配额 JSON 长度不能超过 512")
	private String quota;

	/** 默认启用 */
	private TenantStatus status;

}
