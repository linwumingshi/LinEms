package com.sanduo.energy.shadow.web.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.Map;

/**
 * 设置期望值请求体。
 */
@Data
public class DesiredRequest {

    @NotEmpty(message = "desired 不能为空")
    private Map<String, Object> desired;
}
