package com.sanduo.energy.access.model;

import lombok.Data;

import java.util.List;

/**
 * 物模型属性定义（对应 schema_json.properties[]）。
 */
@Data
public class ThingModelProperty {

    /** 属性标识符（TDengine 宽表列名 / 影子字段名） */
    private String identifier;

    private String name;

    /** float/int/bool/enum/string/struct */
    private String dataType;

    private String unit;

    /** r（只读上报）/ rw（可读可写）/ w（只写） */
    private String accessMode;

    /** required=true 表示应上报（部分上报不强制全量，缺失仅告警不拒绝） */
    private boolean required;

    /** dataType=enum 时的枚举定义 */
    private List<EnumValue> enumValues;
}
