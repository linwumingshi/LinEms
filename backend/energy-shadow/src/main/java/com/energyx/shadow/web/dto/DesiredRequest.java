package com.energyx.shadow.web.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.Map;

/**
 * 设置期望值请求体。
 */
@Data
public class DesiredRequest {

	/**
	 * 期望属性集合，键为属性标识、值为期望值（必填，不可为空）
	 * @required
	 */
	@NotEmpty(message = "desired 不能为空")
	private Map<String, Object> desired;

}
