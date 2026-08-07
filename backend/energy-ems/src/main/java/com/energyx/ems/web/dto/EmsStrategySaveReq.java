package com.energyx.ems.web.dto;

import com.energyx.ems.entity.EmsStrategy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class EmsStrategySaveReq {

    private Long strategyId;

    @NotNull
    private Long stationId;

    @NotBlank
    private String strategyName;

    @NotBlank
    private String strategyType;

    @NotBlank
    private String config;

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
