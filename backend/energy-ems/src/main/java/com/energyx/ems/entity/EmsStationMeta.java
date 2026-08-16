package com.energyx.ems.entity;

import com.energyx.common.entity.BaseEntity;
import lombok.EqualsAndHashCode;
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
@EqualsAndHashCode(callSuper = true)
@TableName("ems_station_meta")
public class EmsStationMeta extends BaseEntity {

	/** 投资元数据 ID（主键，自增） */
	@TableId(type = IdType.AUTO)
	private Long stationMetaId;

	/** 站点 ID（uk_station_meta_station 唯一） */
	private Long stationId;

	/** 投资额（元） */
	private BigDecimal investmentAmount;

	/** 投运日期 */
	private LocalDate installDate;

}
