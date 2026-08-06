package com.sanduo.energy.alarm.mapper;

import com.sanduo.energy.alarm.model.AlarmRecordRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * iot_alarm_record 数据访问。
 *
 * <p>规则触发的写为单条插入（alarm_event_id 雪花主键，天然唯一）；
 * 恢复/确认用「WHERE status=0 / status!=2」条件更新，重复请求自然空操作（幂等）。</p>
 */
@Mapper
public interface AlarmRecordMapper {

    @Insert("""
            INSERT INTO iot_alarm_record (alarm_event_id, tenant_id, device_id, product_key, rule_id, rule_code,
                                          level, type, status, message, ext, triggered_time)
            VALUES (#{alarmEventId}, #{tenantId}, #{deviceId}, #{productKey}, #{ruleId}, #{ruleCode},
                    #{level}, #{type}, 0, #{message}, #{ext}, #{triggeredTime})
            """)
    int insert(@Param("alarmEventId") String alarmEventId, @Param("tenantId") Long tenantId,
               @Param("deviceId") Long deviceId, @Param("productKey") String productKey,
               @Param("ruleId") Long ruleId, @Param("ruleCode") String ruleCode,
               @Param("level") Integer level, @Param("type") Integer type,
               @Param("message") String message, @Param("ext") String ext,
               @Param("triggeredTime") LocalDateTime triggeredTime);

    /** 恢复：规则+设备下全部触发中记录置为已恢复（幂等） */
    @Update("""
            UPDATE iot_alarm_record SET status = 1, recovered_time = #{now}
            WHERE rule_id = #{ruleId} AND device_id = #{deviceId} AND status = 0
            """)
    int recoverActive(@Param("ruleId") Long ruleId, @Param("deviceId") Long deviceId,
                      @Param("now") LocalDateTime now);

    /** 人工确认：触发中/已恢复均可确认，终态确认幂等空操作 */
    @Update("""
            UPDATE iot_alarm_record SET status = 2, acked_by = #{ackedBy}, ack_time = #{now}
            WHERE alarm_event_id = #{alarmEventId} AND status != 2
            """)
    int ack(@Param("alarmEventId") String alarmEventId, @Param("ackedBy") String ackedBy,
            @Param("now") LocalDateTime now);

    /** 规则+设备下触发中记录（用于恢复广播） */
    @Select("""
            SELECT alarm_event_id, tenant_id, device_id, product_key, rule_id, rule_code,
                   level, type, status, message, ext, triggered_time, recovered_time, acked_by, ack_time
            FROM iot_alarm_record
            WHERE rule_id = #{ruleId} AND device_id = #{deviceId} AND status = 0
            ORDER BY triggered_time ASC
            """)
    List<AlarmRecordRow> selectActiveRecords(@Param("ruleId") Long ruleId, @Param("deviceId") Long deviceId);

    /** 组合条件分页查询（动态 SQL，参数可空） */
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
                                    @Param("deviceId") Long deviceId, @Param("level") Integer level,
                                    @Param("status") Integer status,
                                    @Param("startTime") LocalDateTime startTime,
                                    @Param("endTime") LocalDateTime endTime,
                                    @Param("offset") long offset, @Param("limit") long limit);

    /** 组合条件计数（与 selectPage 同条件） */
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
    long count(@Param("tenantId") Long tenantId, @Param("ruleId") Long ruleId,
               @Param("deviceId") Long deviceId, @Param("level") Integer level,
               @Param("status") Integer status,
               @Param("startTime") LocalDateTime startTime,
               @Param("endTime") LocalDateTime endTime);
}
