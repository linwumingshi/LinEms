package com.energyx.ota.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * OTA 任务-设备明细（ota_task_device，流水表）。
 *
 * <p>
 * 复合主键 (task_id, device_id)；流水表无 deleted/create_time 审计列（仅 create_time 由 DB DEFAULT
 * 填充），不继承 BaseEntity。
 * </p>
 */
@Data
@TableName("ota_task_device")
public class OtaTaskDeviceRow {

	/** 任务 ID（复合主键之一） */
	@TableId(type = IdType.INPUT)
	private Long taskId;

	/** 设备 ID（复合主键之二） */
	private Long deviceId;

	private Long tenantId;

	/** 0待升级 1下载中 2升级中 3成功 4失败 5超时 6已取消 */
	private Integer state;

	/** 进度 0-100 */
	private Integer progress;

	/** 升级前版本 */
	private String versionBefore;

	/** 升级后版本（成功回写） */
	private String versionAfter;

	/** 失败码 */
	private String failCode;

	/** 失败描述 */
	private String failMsg;

	/** 已重试次数 */
	private Integer retryCount;

	/** 下次重试时间 */
	private LocalDateTime retryAt;

	private LocalDateTime startTime;

	private LocalDateTime finishTime;

}
