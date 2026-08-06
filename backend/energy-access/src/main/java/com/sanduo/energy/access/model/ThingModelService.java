package com.sanduo.energy.access.model;

import lombok.Data;

import java.util.List;

/**
 * 物模型服务定义（对应 schema_json.services[]，即可下发的指令）。
 * Phase 6 Command Center 校验下发指令时使用；Phase 5 仅解析承载。
 */
@Data
public class ThingModelService {

    private String identifier;
    private String name;

    /** 入参定义（identifier/dataType 结构同属性） */
    private List<ThingModelParam> input;
}
