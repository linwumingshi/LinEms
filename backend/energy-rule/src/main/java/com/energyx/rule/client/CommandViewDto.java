package com.energyx.rule.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 指令视图响应 DTO（Feign 反序列化 energy-command POST /api/command 返回体）。 本地投影，不依赖 energy-command
 * 模块；仅消费 commandId。对端 data 含 tenantId/deviceId 等多余字段，忽略未知字段容错。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class CommandViewDto {

	private String commandId;

}
