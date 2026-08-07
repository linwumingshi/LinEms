package com.energyx.alarm.model;

import lombok.Data;

/**
 * 告警条件/恢复条件（iot_alarm_rule.condition / recovery 的 JSON 反序列化对象）。
 *
 * <p>属性规则：{"metric":"temp","op":"GTE","value":60,"windowSec":60}
 * <br>事件规则：{"event":"bmsFault"}
 * <br>恢复条件：{"metric":"temp","op":"LT","value":55}
 * <br>op 取值：GT/GTE/LT/LTE/EQ/NEQ。</p>
 */
@Data
public class AlarmCondition {

    /** 属性标识（属性/恢复规则必填），如 temp / soc / voltage */
    private String metric;

    /** 比较操作符 GT/GTE/LT/LTE/EQ/NEQ */
    private String op;

    /** 阈值（数值或字符串，EQ/NEQ 支持字符串比较） */
    private Object value;

    /** 持续窗口（秒）：属性值需连续超阈这么久才触发；0/缺省=立即触发 */
    private Integer windowSec;

    /** 事件标识（事件规则必填），如 bmsFault */
    private String event;
}
