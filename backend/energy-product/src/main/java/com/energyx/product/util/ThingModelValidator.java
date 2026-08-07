package com.energyx.product.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.energyx.common.exception.BusinessException;
import com.energyx.common.exception.ErrorCode;

import java.util.List;

/**
 * 物模型 JSON Schema 轻量校验（对齐阿里云物模型结构）。
 *
 * <p>校验目标：可解析的 JSON 对象，且 properties/services/events（若存在）为数组；
 * 深度标准化由 access 服务的 {@code ThingModelParser} 在上行链路执行。</p>
 */
public final class ThingModelValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> ARRAY_FIELDS = List.of("properties", "services", "events");

    private ThingModelValidator() {
    }

    public static void validate(String schemaJson) {
        if (schemaJson == null || schemaJson.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "物模型 JSON 不能为空");
        }
        try {
            JsonNode root = MAPPER.readTree(schemaJson);
            if (!root.isObject()) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "物模型 JSON 必须是对象");
            }
            for (String field : ARRAY_FIELDS) {
                JsonNode node = root.get(field);
                if (node != null && !node.isArray()) {
                    throw new BusinessException(ErrorCode.PARAM_INVALID, "物模型字段 " + field + " 必须是数组");
                }
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "物模型 JSON 解析失败：" + e.getMessage());
        }
    }
}
