package com.energyx.rule.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.energyx.rule.entity.SceneRuleRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * iot_scene_rule 数据访问（规则全量进本地缓存，DB 不承担实时查询热点）。
 *
 * <p>
 * 基础 CRUD 由 MyBatis-Plus BaseMapper 提供（insert 自增主键自动回填、selectById、
 * deleteById、selectList）；乐观锁更新（updateOptimistic）、启停切换、动态分页保留手写 SQL。
 * </p>
 */
@Mapper
public interface SceneRuleMapper extends BaseMapper<SceneRuleRow> {

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
	/** 分页计数 */
			@Param("enabled") Integer enabled, @Param("offset") long offset, @Param("size") long size);

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
