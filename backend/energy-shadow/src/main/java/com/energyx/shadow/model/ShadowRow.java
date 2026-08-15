package com.energyx.shadow.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.energyx.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * iot_shadow 行投影（reported/desired 以 JSON 字符串承载，解析在 service 层）。
 *
 * <p>
 * 继承 {@link BaseEntity}（表含 tenant_id/create_time/update_time/deleted 列）：审计与逻辑删除 统一由基类承载。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("iot_shadow")
public class ShadowRow extends BaseEntity {

	/** 设备 ID（影子按设备 1:1） */
	@TableId(type = IdType.INPUT)
	private Long deviceId;

	private String reported;

	private String desired;

	private Integer version;

	private LocalDateTime lastReportedTime;

	private LocalDateTime lastDesiredTime;

}
