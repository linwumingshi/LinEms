package com.energyx.command.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.energyx.common.entity.BaseEntity;
import com.energyx.common.enums.CommandState;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * iot_command 行投影（params/result 以 JSON 字符串承载，解析在 service 层）。
 *
 * <p>
 * 继承 {@link BaseEntity}（表含 tenant_id/create_by/create_time/update_time/deleted 列），
 * 审计字段与逻辑删除统一由基类承载。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("iot_command")
public class CommandRow extends BaseEntity {

	/** 指令 ID（雪花字符串，幂等键） */
	@TableId(type = IdType.INPUT)
	private String commandId;

	/** 创建人（用户 ID，系统动作填 0；表含 create_by 列） */
	private Long createBy;

	private Long deviceId;

	private String productKey;

	private String commandName;

	private Integer commandType;

	private String params;

	/**
	 * 指令生命周期状态（CREATED/SENT/DEVICE_RECEIVED/EXECUTING/SUCCESS/FAILED/TIMEOUT，对应 DB 0已创建
	 * 1已发送 2设备已接收 3执行中 4成功 5失败 6超时）
	 */
	private CommandState state;

	private Integer retryCount;

	private Integer maxRetry;

	private Integer timeoutMs;

	private LocalDateTime sentTime;

	private LocalDateTime receivedTime;

	private LocalDateTime executingTime;

	private LocalDateTime finishTime;

	private String result;

	private String errorCode;

	private String errorMsg;

}
