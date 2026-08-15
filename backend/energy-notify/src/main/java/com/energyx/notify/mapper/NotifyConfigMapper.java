package com.energyx.notify.mapper;

import com.energyx.notify.model.NotifyConfigRow;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * iot_notify_config 数据访问。
 */
@Mapper
public interface NotifyConfigMapper {

	/** 全量列表（含停用，管理页展示） */
	@Select("""
			SELECT config_id, tenant_id, config_code, config_name, channel, channel_config, status,
			       description, create_by, create_time, update_time
			FROM iot_notify_config
			WHERE tenant_id = #{tenantId}
			ORDER BY create_time DESC
			""")
	List<NotifyConfigRow> selectList(@Param("tenantId") Long tenantId);

	/** 按编码查（发送入口用） */
	@Select("""
			SELECT config_id, tenant_id, config_code, config_name, channel, channel_config, status,
			       description, create_by, create_time, update_time
			FROM iot_notify_config
			WHERE tenant_id = #{tenantId} AND config_code = #{configCode}
			""")
	NotifyConfigRow selectByCode(@Param("tenantId") Long tenantId, @Param("configCode") String configCode);

	/** 按主键查 */
	@Select("""
			SELECT config_id, tenant_id, config_code, config_name, channel, channel_config, status,
			       description, create_by, create_time, update_time
			FROM iot_notify_config
			WHERE config_id = #{configId}
			""")
	NotifyConfigRow selectById(@Param("configId") Long configId);

	/** 新增 */
	@Insert("""
			INSERT INTO iot_notify_config (config_id, tenant_id, config_code, config_name, channel,
			                               channel_config, status, description, create_by)
			VALUES (#{configId}, #{tenantId}, #{configCode}, #{configName}, #{channel},
			        #{channelConfig}, #{status}, #{description}, #{createBy})
			""")
	int insert(NotifyConfigRow row);

	/** 更新（config_code 不可改） */
	@Update("""
			UPDATE iot_notify_config
			SET config_name = #{configName}, channel = #{channel}, channel_config = #{channelConfig},
			    status = #{status}, description = #{description}, update_time = NOW()
			WHERE config_id = #{configId} AND tenant_id = #{tenantId}
			""")
	int update(NotifyConfigRow row);

	/** 删除 */
	@Delete("DELETE FROM iot_notify_config WHERE config_id = #{configId} AND tenant_id = #{tenantId}")
	int deleteById(@Param("configId") Long configId, @Param("tenantId") Long tenantId);

}
