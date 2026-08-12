package com.energyx.product.web.dto;

import com.energyx.common.enums.ProductStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 产品创建/更新请求。
 */
@Data
public class ProductSaveReq {

	@NotBlank(message = "产品标识不能为空")
	@Size(max = 64, message = "产品标识长度不能超过 64")
	private String productKey;

	@NotBlank(message = "产品名称不能为空")
	@Size(max = 128, message = "产品名称长度不能超过 128")
	private String productName;

	@NotBlank(message = "设备类型不能为空")
	@Size(max = 32, message = "设备类型长度不能超过 32")
	private String deviceType;

	private Long categoryId;

	/** SECRET/CERT，默认 SECRET */
	private String authType;

	/** 接入协议，默认 MQTT */
	private String protocol;

	/** 当前生效物模型版本（由发布物模型自动维护，更新时不传则不覆盖） */
	private String modelVersion;

	@Size(max = 512, message = "产品描述长度不能超过 512")
	private String description;

	/** 默认 1 启用 */
	private ProductStatus status;

}
