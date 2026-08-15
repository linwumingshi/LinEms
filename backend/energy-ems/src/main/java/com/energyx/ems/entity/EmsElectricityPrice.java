package com.energyx.ems.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.energyx.common.enums.ElectricityPriceStatus;
import com.energyx.common.enums.PriceType;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** 分时电价（ems_electricity_price）。表无 update_time 列。 */
@Data
@TableName("ems_electricity_price")
public class EmsElectricityPrice {

	/** 租户 ID */
	private Long tenantId;

	/** 创建时间（DB DEFAULT CURRENT_TIMESTAMP） */
	private LocalDateTime createTime;

	@TableId(type = IdType.AUTO)
	private Long priceId;

	private Long stationId;

	private String region;

	/** 电价档位（DEEP/VALLEY/FLAT/PEAK/PEEK） */
	private PriceType priceType;

	private LocalTime startTime;

	private LocalTime endTime;

	private BigDecimal price;

	private LocalDate validFrom;

	private LocalDate validTo;

	/** 电价档案状态（DISABLED/ENABLED） */
	private ElectricityPriceStatus status;

}
