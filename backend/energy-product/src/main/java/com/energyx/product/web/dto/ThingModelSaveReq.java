package com.energyx.product.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 物模型发布请求。
 */
@Data
public class ThingModelSaveReq {

	/**
	 * 物模型版本号，最大长度 32。
	 * @required
	 */
	@NotBlank(message = "物模型版本不能为空")
	@Size(max = 32, message = "物模型版本长度不能超过 32")
	private String version;

	/**
	 * 完整物模型 JSON Schema 字符串，不可为空。
	 * @required
	 */
	@NotBlank(message = "物模型 JSON 不能为空")
	private String schemaJson;

}
