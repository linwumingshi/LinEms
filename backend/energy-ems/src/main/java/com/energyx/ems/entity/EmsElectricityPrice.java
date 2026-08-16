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

	/** 电价 ID（主键，自增） */
	@TableId(type = IdType.AUTO)
	private Long priceId;

	/** 站点 ID */
	private Long stationId;

	/** 区域编码（如省/市电价分区） */
	private String region;

	/** 电价档位，取值见 {@link PriceType}（DEEP/VALLEY/FLAT/PEAK/PEEK） */
	private PriceType priceType;

	/** 时段起始时间（当天 HH:mm:ss） */
	private LocalTime startTime;

	/** 时段结束时间（当天 HH:mm:ss） */
	private LocalTime endTime;

	/** 电价（元/kWh） */
	private BigDecimal price;

	/** 生效起始日期（含） */
	private LocalDate validFrom;

	/** 生效结束日期（含） */
	private LocalDate validTo;

	/** 电价档案状态，取值见 {@link ElectricityPriceStatus}（DISABLED/ENABLED） */
	private ElectricityPriceStatus status;

}
