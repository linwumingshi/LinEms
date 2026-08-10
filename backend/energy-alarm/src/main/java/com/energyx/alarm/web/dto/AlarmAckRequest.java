package com.energyx.alarm.web.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 告警确认请求体。
 */
@Data
public class AlarmAckRequest {

	/** 确认人（工号/账号） */
	@NotBlank(message = "ackedBy 不能为空")
	private String ackedBy;

}
