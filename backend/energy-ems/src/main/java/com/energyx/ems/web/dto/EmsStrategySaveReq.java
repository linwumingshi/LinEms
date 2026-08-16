package com.energyx.ems.web.dto;

import com.energyx.common.enums.StrategyType;
import com.energyx.ems.entity.EmsStrategy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class EmsStrategySaveReq {

	/** 策略 ID；更新时必填，创建时留空由后端自增生成 */
	private Long strategyId;

	/**
	 * 站点 ID
	 * @required
	 */
	@NotNull
	private Long stationId;

	/**
	 * 策略名称
	 * @required
	 */
	@NotBlank
	private String strategyName;

	/**
	 * 策略类型，取值见 {@link StrategyType}（PEAK_VALLEY/DEMAND/DR/SOC_CTRL/TIME）
	 * @required
	 */
	@NotNull
	private StrategyType strategyType;

	/**
	 * 策略配置 JSON（chargeWindows/dischargeWindows/socRange）
	 * @required
	 */
	@NotBlank
	private String config;

	/** 多策略冲突仲裁优先级；缺省为 0 */
	private Integer priority;

	public EmsStrategy toEntity() {
		EmsStrategy s = new EmsStrategy();
		s.setStrategyId(strategyId);
		s.setStationId(stationId);
		s.setStrategyName(strategyName);
		s.setStrategyType(strategyType);
		s.setConfig(config);
		s.setPriority(priority == null ? 0 : priority);
		return s;
	}

}
