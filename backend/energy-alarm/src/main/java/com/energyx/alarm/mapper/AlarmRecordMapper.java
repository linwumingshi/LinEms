package com.energyx.alarm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.energyx.alarm.model.AlarmRecordRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * iot_alarm_record 数据访问。
 *
 * <p>
 * 基础 CRUD 与简单条件查询由 MyBatis-Plus BaseMapper 提供（insert/selectList）； 恢复/确认保留手写 条件更新（WHERE
 * status 幂等：重复请求空操作）；动态组合分页（selectPage/count）保留手写 SQL。
 * </p>
 */
@Mapper
public interface AlarmRecordMapper extends BaseMapper<AlarmRecordRow> {

	/** 恢复：规则+设备下全部触发中记录置为已恢复（幂等） */
	@Update("""
			UPDATE iot_alarm_record SET status = 1, recovered_time = #{now}
			WHERE rule_id = #{ruleId} AND device_id = #{deviceId} AND status = 0
			""")
	int recoverActive(@Param("ruleId") Long ruleId, @Param("deviceId") Long deviceId, @Param("now") LocalDateTime now);

	/** 人工确认：触发中/已恢复均可确认，终态确认幂等空操作 */
	@Update("""
			UPDATE iot_alarm_record SET status = 2, acked_by = #{ackedBy}, ack_time = #{now}
			WHERE alarm_event_id = #{alarmEventId} AND status != 2
			""")
	int ack(@Param("alarmEventId") String alarmEventId, @Param("ackedBy") String ackedBy,
			@Param("now") LocalDateTime now);

	/** 组合条件计数（与 selectPage 同条件） */
	@Select("""
			<script>
			SELECT alarm_event_id, tenant_id, device_id, product_key, rule_id, rule_code,
			       level, type, status, message, ext, triggered_time, recovered_time, acked_by, ack_time
			FROM iot_alarm_record
			WHERE 1 = 1
			<if test="tenantId != null"> AND tenant_id = #{tenantId}</if>
			<if test="ruleId != null"> AND rule_id = #{ruleId}</if>
			<if test="deviceId != null"> AND device_id = #{deviceId}</if>
			<if test="level != null"> AND level = #{level}</if>
			<if test="status != null"> AND status = #{status}</if>
			<if test="startTime != null"> AND triggered_time &gt;= #{startTime}</if>
			<if test="endTime != null"> AND triggered_time &lt;= #{endTime}</if>
			ORDER BY triggered_time DESC
			LIMIT #{offset}, #{limit}
			</script>
			""")
	List<AlarmRecordRow> selectPage(@Param("tenantId") Long tenantId, @Param("ruleId") Long ruleId,
			@Param("deviceId") Long deviceId, @Param("level") Integer level, @Param("status") Integer status,
			@Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime,
			@Param("offset") long offset, @Param("limit") long limit);

	@Select("""
			<script>
			SELECT COUNT(*) FROM iot_alarm_record
			WHERE 1 = 1
			<if test="tenantId != null"> AND tenant_id = #{tenantId}</if>
			<if test="ruleId != null"> AND rule_id = #{ruleId}</if>
			<if test="deviceId != null"> AND device_id = #{deviceId}</if>
			<if test="level != null"> AND level = #{level}</if>
			<if test="status != null"> AND status = #{status}</if>
			<if test="startTime != null"> AND triggered_time &gt;= #{startTime}</if>
			<if test="endTime != null"> AND triggered_time &lt;= #{endTime}</if>
			</script>
			""")
	long count(@Param("tenantId") Long tenantId, @Param("ruleId") Long ruleId, @Param("deviceId") Long deviceId,
			@Param("level") Integer level, @Param("status") Integer status, @Param("startTime") LocalDateTime startTime,
			@Param("endTime") LocalDateTime endTime);

}
