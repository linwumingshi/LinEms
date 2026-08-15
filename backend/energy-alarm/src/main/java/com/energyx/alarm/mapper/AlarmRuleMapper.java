package com.energyx.alarm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.energyx.alarm.model.AlarmRuleRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * iot_alarm_rule 数据访问（继承 BaseMapper 获得 insert/updateById/deleteById/selectById；
 * 启用规则列表保留注解查询，作为缓存刷新源）。
 */
@Mapper
public interface AlarmRuleMapper extends BaseMapper<AlarmRuleRow> {

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
