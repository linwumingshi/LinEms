package com.energyx.product.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 物模型发布请求。
 */
@Data
public class ThingModelSaveReq {

    @NotBlank(message = "物模型版本不能为空")
    @Size(max = 32, message = "物模型版本长度不能超过 32")
    private String version;

    @NotBlank(message = "物模型 JSON 不能为空")
    private String schemaJson;
}
