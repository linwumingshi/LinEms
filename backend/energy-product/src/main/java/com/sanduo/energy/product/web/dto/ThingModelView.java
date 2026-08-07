package com.sanduo.energy.product.web.dto;

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

    /** 0草稿 1已发布 2已废弃 */
    private Integer status;

    /** 当前生效版本 */
    private Integer isCurrent;
}
