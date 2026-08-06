package com.sanduo.energy.shadow.web.dto;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 影子合并视图（reported/desired 快照，供查询 API 返回）。
 */
@Data
public class ShadowView {

    private long deviceId;
    private Map<String, Object> reported = new LinkedHashMap<>();
    private Map<String, Object> desired = new LinkedHashMap<>();
    /** 乐观锁版本；行不存在时为 null */
    private Integer version;
}
