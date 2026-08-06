package com.sanduo.energy.access.model;

import lombok.Data;

/**
 * 服务入参/出参定义项。
 */
@Data
public class ThingModelParam {

    private String identifier;
    private String dataType;
    private String unit;
}
