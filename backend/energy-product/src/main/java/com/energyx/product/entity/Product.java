package com.energyx.product.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.energyx.common.entity.BaseEntity;
import com.energyx.common.enums.ProductStatus;
import lombok.Getter;
import lombok.Setter;

/**
 * 产品（iot_product）。
 *
 * <p>
 * product_key 为全局路由锚点（如 snd_ess_pcs），设备认证时按 {productKey}_{deviceName} 拆分 clientId
 * 定位产品。tenant_id 不显式赋值：插入时由条件化租户拦截器注入。
 * </p>
 */
@Getter
@Setter
@TableName("iot_product")
public class Product extends BaseEntity {

	/** 产品ID，自增主键 */
	@TableId(type = IdType.AUTO)
	private Long productId;

	/** 产品类目ID，可空 */
	private Long categoryId;

	/** 产品标识（全局路由锚点） */
	private String productKey;

	/** 产品名称 */
	private String productName;

	/** 设备类型：ENERGY_CABINET/BATTERY_CLUSTER/PCS/BMS/EMS/EDGE_GW */
	private String deviceType;

	/** 设备认证方式：SECRET 设备密钥 / CERT 证书 */
	private String authType;

	/** 接入协议，默认 MQTT */
	private String protocol;

	/** 当前生效物模型版本 */
	private String modelVersion;

	/** 产品描述 */
	private String description;

	/**
	 * 产品状态，见 {@link com.energyx.common.enums.ProductStatus}（DISABLED/ENABLED，对应 DB 0 禁用 1
	 * 启用）
	 */
	private ProductStatus status;

}
