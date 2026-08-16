package com.energyx.product.web.dto;

import com.energyx.common.enums.ThingModelStatus;
import lombok.Data;

/**
 * 物模型视图。
 */
@Data
public class ThingModelView {

	/** 物模型记录ID */
	private Long modelId;

	/** 所属产品ID */
	private Long productId;

	/** 物模型版本 */
	private String version;

	/** 完整物模型 JSON Schema */
	private String schemaJson;

	/**
	 * 物模型状态，见
	 * {@link com.energyx.common.enums.ThingModelStatus}（DRAFT/PUBLISHED/DEPRECATED，对应 DB
	 * 0 草稿 1 已发布 2 已废弃）
	 */
	private ThingModelStatus status;

	/** 是否为当前生效版本：1 当前生效，0 历史版本 */
	private Integer isCurrent;

}
