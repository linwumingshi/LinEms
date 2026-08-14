package com.energyx.rule.client;

import lombok.Data;

/**
 * 指令视图响应 DTO（Feign 反序列化 energy-command POST /api/command 返回体）。 本地投影，不依赖 energy-command
 * 模块；仅消费 commandId。
 */
@Data
public class CommandViewDto {

	private String commandId;

}
