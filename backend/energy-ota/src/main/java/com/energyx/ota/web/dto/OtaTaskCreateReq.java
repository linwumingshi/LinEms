package com.energyx.ota.web.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 创建 OTA 批次任务请求。
 */
@Data
public class OtaTaskCreateReq {

	/**
	 * 升级包 ID（必填）
	 */
	@NotNull(message = "packageId 不能为空")
	private Long packageId;

	/** 任务名称（缺省 OTA-{version}） */
	private String taskName;

	/** 1全部设备 2指定设备 3灰度比例（默认 1） */
	private Integer taskType;

	/** 指定设备列表（taskType=2） */
	private List<Long> deviceIds;

	/** 1差分优先(DIFF_FIRST) 2仅全量(FULL_ONLY)（默认 1） */
	private Integer downloadPolicy;

	/** 灰度比例 1-100（taskType=3，默认 10） */
	private Integer grayRatio;

	/** 失败重试次数（默认 2） */
	private Integer retryTimes;

	/** 重试间隔（分钟，默认 5） */
	private Integer retryIntervalMin;

	/** 下载超时（分钟，默认 60） */
	private Integer downloadTimeoutMin;

	/** 升级超时（分钟，默认 30） */
	private Integer upgradeTimeoutMin;

	/** 失败率激增自动暂停（默认 1开） */
	private Integer autoPauseOnFail;

	/** 计划开始时间（NULL=立即） */
	private LocalDateTime scheduleTime;

	/** 创建人（用户 ID，缺省 0=系统动作） */
	private Long createBy;

}
