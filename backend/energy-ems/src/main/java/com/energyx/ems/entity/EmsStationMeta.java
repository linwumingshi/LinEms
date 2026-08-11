package com.energyx.ems.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 收益核算电站投资元数据（ems_station_meta）。station_id 唯一（uk_station_meta_station）。 */
@Data
@TableName("ems_station_meta")
public class EmsStationMeta {

	@TableId(type = IdType.AUTO)
	private Long stationMetaId;

	private Long tenantId;

	private Long stationId;

	/** 投资额 元 */
	private BigDecimal investmentAmount;

	/** 投运日期 */
	private LocalDate installDate;

	@TableField(value = "create_time", fill = FieldFill.INSERT)
	private LocalDateTime createTime;

	@TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
	private LocalDateTime updateTime;

}
