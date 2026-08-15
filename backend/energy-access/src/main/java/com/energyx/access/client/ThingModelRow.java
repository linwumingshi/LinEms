package com.energyx.access.client;

/**
 * 物模型只读投影（跨服务调 energy-product 的 /thing-model/by-key 返回，替代跨 schema 直查
 * es_product.iot_product/iot_thing_model）。字段与 product 侧 ThingModelView JSON 兼容。
 */
public record ThingModelRow(Long modelId, Long productId, String version, String schemaJson, Integer isCurrent) {
}
