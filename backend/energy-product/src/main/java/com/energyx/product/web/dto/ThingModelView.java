package com.energyx.product.web.dto;

import com.energyx.common.enums.ThingModelStatus;
import lombok.Data;

/**
 * 物模型视图。
 */
@Data
public class ThingModelView {

	private Long modelId;

	private Long productId;

	/** 物模型版本 */
	private String version;

	/** 完整物模型 JSON Schema */
	private String schemaJson;

	/** 物模型状态（DRAFT/PUBLISHED/DEPRECATED，对应 DB 0草稿 1已发布 2已废弃） */
	private ThingModelStatus status;

	/** 当前生效版本 */
	private Integer isCurrent;

}
