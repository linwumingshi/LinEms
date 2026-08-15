package com.energyx.notify.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.energyx.notify.model.NotifyConfigRow;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * iot_notify_config 数据访问（继承 BaseMapper 获得 insert/updateById/deleteById/selectById；
 * 保留租户过滤与编码查询注解）。
 */
@Mapper
public interface NotifyConfigMapper extends BaseMapper<NotifyConfigRow> {

	/** 全量列表（含停用，管理页展示；BaseMapper.selectList 走 wrapper，此处保留注解查询） */
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

	/** 删除（租户隔离；主键删除走 BaseMapper.deleteById） */
	@Delete("DELETE FROM iot_notify_config WHERE config_id = #{configId} AND tenant_id = #{tenantId}")
	int deleteById(@Param("configId") Long configId, @Param("tenantId") Long tenantId);

}
