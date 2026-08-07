package com.energyx.command.web.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

/**
 * 创建指令请求体。
 *
 * <p>commandId 可选：缺省由服务端生成；显式传入则作为幂等键（同 commandId 重试返回既有指令）。</p>
 */
@Data
public class CreateCommandRequest {

    /** 幂等键（可选），如客户端生成的 UUID */
    private String commandId;

    @NotBlank(message = "productKey 不能为空")
    private String productKey;

    @NotBlank(message = "deviceName 不能为空")
    private String deviceName;

    /** 物模型服务标识，如 setPower / startCharge */
    @NotBlank(message = "command 不能为空")
    private String command;

    private Map<String, Object> params;

    /** 1读取 2控制，默认 2 */
    private Integer commandType;

    /** 指令超时（毫秒），缺省取默认 15000 */
    private Integer timeoutMs;

    /** 最大重试，缺省取默认 3 */
    private Integer maxRetry;

    /** 发起人（人工）或 0（策略/影子自动） */
    private Long createBy;
}
