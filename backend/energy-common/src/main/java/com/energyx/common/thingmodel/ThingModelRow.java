package com.energyx.common.thingmodel;

/**
 * 物模型只读投影（跨服务调用方从 product 服务 {@code /api/product/thing-model/by-key} 获取当前生效物模型， 替代跨
 * schema 直查 es_product.iot_product/iot_thing_model）。字段与 product 侧 ThingModelView JSON 兼容。
 *
 * @param modelId 物模型记录主键
 * @param productId 所属产品主键
 * @param version 物模型版本号（iot_product.model_version）
 * @param schemaJson 物模型 schema_json 文本（解析为 {@link ThingModel} 的来源）
 * @param isCurrent 是否当前生效（1 生效 / 0 历史版本）
 */
public record ThingModelRow(Long modelId, Long productId, String version, String schemaJson, Integer isCurrent) {
}
