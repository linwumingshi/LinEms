package com.sanduo.energy.access.model;

import lombok.Data;

/**
 * 枚举属性取值项（如 runMode：0待机 1充电 2放电）。
 */
@Data
public class EnumValue {

    private Object value;
    private String desc;
}
