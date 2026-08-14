package com.energyx.rule.mapper;

import com.energyx.rule.entity.SceneExecLogRow;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * iot_scene_exec_log 数据访问（执行日志，按月分区，保留 N 天定时清理）。
 */
@Mapper
public interface SceneExecLogMapper {

	/** 插入执行日志（log_id 雪花主键 + create_time 分区键） */
	@Insert("""
			INSERT INTO iot_scene_exec_log (log_id, rule_id, rule_code, tenant_id, trigger_type, device_id,
			                                matched, action_result, cost_ms, trace_id, create_time)
			VALUES (#{logId}, #{ruleId}, #{ruleCode}, #{tenantId}, #{triggerType}, #{deviceId},
			        #{matched}, #{actionResult}, #{costMs}, #{traceId}, #{createTime})
			""")
	int insert(SceneExecLogRow row);

	/** 分页查询执行日志（组合条件，动态 SQL） */
	@Select("""
			<script>
			SELECT log_id, rule_id, rule_code, tenant_id, trigger_type, device_id,
			       matched, action_result, cost_ms, trace_id, create_time
			FROM iot_scene_exec_log
			WHERE 1 = 1
			<if test="tenantId != null"> AND tenant_id = #{tenantId}</if>
			<if test="ruleId != null"> AND rule_id = #{ruleId}</if>
			<if test="triggerType != null and triggerType != ''"> AND trigger_type = #{triggerType}</if>
			<if test="deviceId != null"> AND device_id = #{deviceId}</if>
			<if test="startTime != null"> AND create_time &gt;= #{startTime}</if>
			<if test="endTime != null"> AND create_time &lt;= #{endTime}</if>
			ORDER BY create_time DESC
			LIMIT #{offset}, #{size}
			</script>
			""")
	List<SceneExecLogRow> selectPage(@Param("tenantId") Long tenantId, @Param("ruleId") Long ruleId,
			@Param("triggerType") String triggerType, @Param("deviceId") Long deviceId,
			@Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime,
			@Param("offset") long offset, @Param("size") long size);

	/** 分页计数 */
	@Select("""
			<script>
			SELECT COUNT(*) FROM iot_scene_exec_log
			WHERE 1 = 1
			<if test="tenantId != null"> AND tenant_id = #{tenantId}</if>
			<if test="ruleId != null"> AND rule_id = #{ruleId}</if>
			<if test="triggerType != null and triggerType != ''"> AND trigger_type = #{triggerType}</if>
			<if test="deviceId != null"> AND device_id = #{deviceId}</if>
			<if test="startTime != null"> AND create_time &gt;= #{startTime}</if>
			<if test="endTime != null"> AND create_time &lt;= #{endTime}</if>
			</script>
			""")
	long countPage(@Param("tenantId") Long tenantId, @Param("ruleId") Long ruleId,
			@Param("triggerType") String triggerType, @Param("deviceId") Long deviceId,
			@Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime);

	/** 清理 N 天前的日志（定时任务，按月分区可直接 DROP 旧分区，此处按行清理兜底） */
	@Delete("DELETE FROM iot_scene_exec_log WHERE create_time < #{cutoff}")
	int deleteBefore(@Param("cutoff") LocalDateTime cutoff);

}
