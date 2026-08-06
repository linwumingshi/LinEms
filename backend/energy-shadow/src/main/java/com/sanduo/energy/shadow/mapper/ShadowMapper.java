package com.sanduo.energy.shadow.mapper;

import com.sanduo.energy.shadow.model.ShadowRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * iot_shadow 数据访问（版本乐观锁：UPDATE ... SET version=version+1 WHERE version=#{expect}）。
 */
@Mapper
public interface ShadowMapper {

    @Select("""
            SELECT device_id, tenant_id, reported, desired, version,
                   last_reported_time, last_desired_time
            FROM iot_shadow WHERE device_id = #{deviceId}
            """)
    ShadowRow selectByDeviceId(@Param("deviceId") long deviceId);

    /** 首条上报：reported 初始化，desired='{}'，version=1 */
    @Insert("""
            INSERT INTO iot_shadow (device_id, tenant_id, reported, desired, version, last_reported_time)
            VALUES (#{deviceId}, #{tenantId}, #{reported}, '{}', 1, #{now})
            """)
    int insertReported(@Param("deviceId") long deviceId, @Param("tenantId") long tenantId,
                       @Param("reported") String reported, @Param("now") LocalDateTime now);

    /** 首条期望：reported='{}' 初始化 */
    @Insert("""
            INSERT INTO iot_shadow (device_id, tenant_id, reported, desired, version, last_desired_time)
            VALUES (#{deviceId}, #{tenantId}, '{}', #{desired}, 1, #{now})
            """)
    int insertDesired(@Param("deviceId") long deviceId, @Param("tenantId") long tenantId,
                      @Param("desired") String desired, @Param("now") LocalDateTime now);

    /** reported 乐观锁更新（返回 0 = 版本冲突，调用方重试） */
    @Update("""
            UPDATE iot_shadow
            SET reported = #{reported}, version = version + 1, last_reported_time = #{now}
            WHERE device_id = #{deviceId} AND version = #{expectVersion}
            """)
    int updateReported(@Param("deviceId") long deviceId, @Param("reported") String reported,
                       @Param("expectVersion") int expectVersion, @Param("now") LocalDateTime now);

    /** desired 乐观锁更新（返回 0 = 版本冲突，调用方重试） */
    @Update("""
            UPDATE iot_shadow
            SET desired = #{desired}, version = version + 1, last_desired_time = #{now}
            WHERE device_id = #{deviceId} AND version = #{expectVersion}
            """)
    int updateDesired(@Param("deviceId") long deviceId, @Param("desired") String desired,
                      @Param("expectVersion") int expectVersion, @Param("now") LocalDateTime now);
}
