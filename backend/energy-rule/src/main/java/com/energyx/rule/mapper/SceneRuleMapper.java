package com.energyx.rule.mapper;

import com.energyx.rule.entity.SceneRuleRow;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * iot_scene_rule 数据访问（规则全量进本地缓存，DB 不承担实时查询热点）。
 */
@Mapper
public interface SceneRuleMapper {

	/** 加载全部启用规则（缓存刷新源） */
	@Select("""
			SELECT rule_id, tenant_id, rule_code, rule_name, description, dsl_version,
			       trigger_json, condition_json, action_json, recovery_json,
			       debounce_seconds, priority, enabled, version, create_by, create_time, update_time
			FROM iot_scene_rule
			WHERE enabled = 1
			""")
	List<SceneRuleRow> selectEnabledRules();

	/** 按 ID 查询单条规则 */
	@Select("""
			SELECT rule_id, tenant_id, rule_code, rule_name, description, dsl_version,
			       trigger_json, condition_json, action_json, recovery_json,
			       debounce_seconds, priority, enabled, version, create_by, create_time, update_time
			FROM iot_scene_rule
			WHERE rule_id = #{ruleId}
			""")
	SceneRuleRow selectById(@Param("ruleId") Long ruleId);

	/** 新增规则（useGeneratedKeys 回填自增主键到 row.ruleId） */
	@Insert("""
			INSERT INTO iot_scene_rule (tenant_id, rule_code, rule_name, description, dsl_version,
			                            trigger_json, condition_json, action_json, recovery_json,
			                            debounce_seconds, priority, enabled, version, create_by)
			VALUES (#{tenantId}, #{ruleCode}, #{ruleName}, #{description}, #{dslVersion},
			        #{triggerJson}, #{conditionJson}, #{actionJson}, #{recoveryJson},
			        #{debounceSeconds}, #{priority}, #{enabled}, #{version}, #{createBy})
			""")
	@Options(useGeneratedKeys = true, keyProperty = "ruleId")
	int insert(SceneRuleRow row);

	/** 乐观锁更新（version 条件，返回受影响行数；0=并发冲突） */
	@Update("""
			UPDATE iot_scene_rule
			SET rule_code = #{ruleCode}, rule_name = #{ruleName}, description = #{description},
			    dsl_version = #{dslVersion}, trigger_json = #{triggerJson}, condition_json = #{conditionJson},
			    action_json = #{actionJson}, recovery_json = #{recoveryJson},
			    debounce_seconds = #{debounceSeconds}, priority = #{priority}, enabled = #{enabled},
			    version = version + 1
			WHERE rule_id = #{ruleId} AND version = #{version}
			""")
	int updateOptimistic(SceneRuleRow row);

	/** 启停切换（enabled 0/1） */
	@Update("UPDATE iot_scene_rule SET enabled = #{enabled} WHERE rule_id = #{ruleId}")
	int updateEnabled(@Param("ruleId") Long ruleId, @Param("enabled") int enabled);

	/** 删除规则 */
	@Delete("DELETE FROM iot_scene_rule WHERE rule_id = #{ruleId}")
	int deleteById(@Param("ruleId") Long ruleId);

	/** 分页查询（租户/名称/启停过滤，动态 SQL） */
	@Select("""
			<script>
			SELECT rule_id, tenant_id, rule_code, rule_name, description, dsl_version,
			       trigger_json, condition_json, action_json, recovery_json,
			       debounce_seconds, priority, enabled, version, create_by, create_time, update_time
			FROM iot_scene_rule
			WHERE 1 = 1
			<if test="tenantId != null"> AND tenant_id = #{tenantId}</if>
			<if test="ruleName != null and ruleName != ''"> AND rule_name LIKE CONCAT('%', #{ruleName}, '%')</if>
			<if test="enabled != null"> AND enabled = #{enabled}</if>
			ORDER BY create_time DESC
			LIMIT #{offset}, #{size}
			</script>
			""")
	List<SceneRuleRow> selectPage(@Param("tenantId") Long tenantId, @Param("ruleName") String ruleName,
			@Param("enabled") Integer enabled, @Param("offset") long offset, @Param("size") long size);

	/** 分页计数 */
	@Select("""
			<script>
			SELECT COUNT(*) FROM iot_scene_rule
			WHERE 1 = 1
			<if test="tenantId != null"> AND tenant_id = #{tenantId}</if>
			<if test="ruleName != null and ruleName != ''"> AND rule_name LIKE CONCAT('%', #{ruleName}, '%')</if>
			<if test="enabled != null"> AND enabled = #{enabled}</if>
			</script>
			""")
	long countPage(@Param("tenantId") Long tenantId, @Param("ruleName") String ruleName,
			@Param("enabled") Integer enabled);

}
