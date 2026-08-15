package com.energyx.alarm.mapper;

import com.energyx.alarm.model.AlarmRuleRow;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

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

	/** 按主键查规则（详情/编辑回填） */
	@Select("""
			SELECT rule_id, tenant_id, rule_code, rule_name, product_id, device_id, trigger_type,
			       `condition`, severity, silence_seconds, recovery, status, description, create_by,
			       create_time, update_time
			FROM iot_alarm_rule
			WHERE rule_id = #{ruleId}
			""")
	AlarmRuleRow selectById(@Param("ruleId") Long ruleId);

	/** 新增规则（自动回填自增主键） */
	@Insert("""
			INSERT INTO iot_alarm_rule (tenant_id, rule_code, rule_name, product_id, device_id, trigger_type,
			                            `condition`, severity, silence_seconds, recovery, status, description, create_by)
			VALUES (#{tenantId}, #{ruleCode}, #{ruleName}, #{productId}, #{deviceId}, #{triggerType},
			        #{condition}, #{severity}, #{silenceSeconds}, #{recovery}, #{status}, #{description}, #{createBy})
			""")
	@Options(useGeneratedKeys = true, keyProperty = "ruleId")
	int insert(AlarmRuleRow row);

	/** 更新规则（rule_code 不可改） */
	@Update("""
			UPDATE iot_alarm_rule
			SET rule_name = #{ruleName}, product_id = #{productId}, device_id = #{deviceId},
			    trigger_type = #{triggerType}, `condition` = #{condition}, severity = #{severity},
			    silence_seconds = #{silenceSeconds}, recovery = #{recovery}, status = #{status},
			    description = #{description}, update_time = NOW()
			WHERE rule_id = #{ruleId} AND tenant_id = #{tenantId}
			""")
	int update(AlarmRuleRow row);

	/** 删除规则（物理删除；已产生的告警记录不受影响） */
	@Delete("DELETE FROM iot_alarm_rule WHERE rule_id = #{ruleId} AND tenant_id = #{tenantId}")
	int deleteById(@Param("ruleId") Long ruleId, @Param("tenantId") Long tenantId);

}
