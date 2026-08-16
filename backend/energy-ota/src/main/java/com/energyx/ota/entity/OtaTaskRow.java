package com.energyx.ota.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.energyx.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * OTA 批次升级任务（ota_task）。
 *
 * <p>
 * 表含 tenant_id/create_time/update_time/deleted 四列，继承 {@link BaseEntity}； create_by
 * 列存在故自行声明（基类不含）。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ota_task")
public class OtaTaskRow extends BaseEntity {

	/** 任务 ID（雪花） */
	@TableId(type = IdType.ASSIGN_ID)
	private Long taskId;

	/** 升级包 ID */
	private Long packageId;

	private String taskName;

	/** 1全部设备 2指定设备 3灰度比例 */
	private Integer taskType;

	/** 1差分优先(DIFF_FIRST) 2仅全量(FULL_ONLY) */
	private Integer downloadPolicy;

	/** 灰度比例 1-100（taskType=3） */
	private Integer grayRatio;

	/** 目标设备数（创建时快照） */
	private Integer deviceCount;

	/** 成功数（冗余统计） */
	private Integer successCount;

	/** 失败数 */
	private Integer failCount;

	/** 0待开始 1执行中 2已完成 3已暂停 4已取消 */
	private Integer status;

	/** 失败重试次数 */
	private Integer retryTimes;

	/** 重试间隔（分钟） */
	private Integer retryIntervalMin;

	/** 下载超时（分钟） */
	private Integer downloadTimeoutMin;

	/** 升级超时（分钟） */
	private Integer upgradeTimeoutMin;

	/** 失败率激增自动暂停（1开 0关） */
	private Integer autoPauseOnFail;

	/** 计划开始时间（NULL=立即） */
	private LocalDateTime scheduleTime;

	/** 创建人（用户 ID，系统动作填 0；表含 create_by 列） */
	private Long createBy;

}
