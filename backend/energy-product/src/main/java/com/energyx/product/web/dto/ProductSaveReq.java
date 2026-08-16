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

	/**
	 * 产品标识（全局路由锚点），最大长度 64。
	 * @required
	 */
	@NotBlank(message = "产品标识不能为空")
	@Size(max = 64, message = "产品标识长度不能超过 64")
	private String productKey;

	/**
	 * 产品名称，最大长度 128。
	 * @required
	 */
	@NotBlank(message = "产品名称不能为空")
	@Size(max = 128, message = "产品名称长度不能超过 128")
	private String productName;

	/**
	 * 设备类型，最大长度 32，如 ENERGY_CABINET/PCS/BMS/EMS/EDGE_GW。
	 * @required
	 */
	@NotBlank(message = "设备类型不能为空")
	@Size(max = 32, message = "设备类型长度不能超过 32")
	private String deviceType;

	/** 产品类目ID，可空 */
	private Long categoryId;

	/** 设备认证方式：SECRET 设备密钥 / CERT 证书，默认 SECRET */
	private String authType;

	/** 接入协议，默认 MQTT */
	private String protocol;

	/** 当前生效物模型版本（由发布物模型自动维护，更新时不传则不覆盖） */
	private String modelVersion;

	/** 产品描述，最大长度 512 */
	@Size(max = 512, message = "产品描述长度不能超过 512")
	private String description;

	/** 产品状态，见 {@link com.energyx.common.enums.ProductStatus}，默认 ENABLED（1 启用） */
	private ProductStatus status;

}
