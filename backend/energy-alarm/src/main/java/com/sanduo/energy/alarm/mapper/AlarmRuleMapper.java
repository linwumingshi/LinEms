package com.sanduo.energy.alarm.mapper;

import com.sanduo.energy.alarm.model.AlarmRuleRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * iot_alarm_rule 数据访问（规则全量进本地缓存，DB 不承担实时查询热点）。
 */
@Mapper
public interface AlarmRuleMapper {

    /** 加载全部启用规则（缓存刷新源） */
    @Select("""
            SELECT rule_id, tenant_id, rule_code, rule_name, product_id, device_id, trigger_type,
                   `condition`, severity, silence_seconds, recovery, status, description, create_by,
                   create_time, update_time
            FROM iot_alarm_rule
            WHERE status = 1
            """)
    List<AlarmRuleRow> selectEnabledRules();
}
