package com.energyx.notify.mapper;

import com.energyx.notify.model.NotifyTemplateRow;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * iot_notify_template 数据访问。
 */
@Mapper
public interface NotifyTemplateMapper {

	/** 全量列表（管理页展示） */
	@Select("""
			SELECT template_id, tenant_id, template_code, template_name, message_type, channel,
			       title_template, content_template, variables, status, description, create_by, create_time, update_time
			FROM iot_notify_template
			WHERE tenant_id = #{tenantId}
			ORDER BY create_time DESC
			""")
	List<NotifyTemplateRow> selectList(@Param("tenantId") Long tenantId);

	/** 按渠道筛选（场景联动选模板用） */
	@Select("""
			SELECT template_id, tenant_id, template_code, template_name, message_type, channel,
			       title_template, content_template, variables, status, description, create_by, create_time, update_time
			FROM iot_notify_template
			WHERE tenant_id = #{tenantId} AND channel = #{channel} AND status = 1
			ORDER BY create_time DESC
			""")
	List<NotifyTemplateRow> selectByChannel(@Param("tenantId") Long tenantId, @Param("channel") String channel);

	/** 按编码查（发送入口用） */
	@Select("""
			SELECT template_id, tenant_id, template_code, template_name, message_type, channel,
			       title_template, content_template, variables, status, description, create_by, create_time, update_time
			FROM iot_notify_template
			WHERE tenant_id = #{tenantId} AND template_code = #{templateCode}
			""")
	NotifyTemplateRow selectByCode(@Param("tenantId") Long tenantId, @Param("templateCode") String templateCode);

	/** 按主键查 */
	@Select("""
			SELECT template_id, tenant_id, template_code, template_name, message_type, channel,
			       title_template, content_template, variables, status, description, create_by, create_time, update_time
			FROM iot_notify_template
			WHERE template_id = #{templateId}
			""")
	NotifyTemplateRow selectById(@Param("templateId") Long templateId);

	/** 新增 */
	@Insert("""
			INSERT INTO iot_notify_template (template_id, tenant_id, template_code, template_name, message_type,
			                                 channel, title_template, content_template, variables, status, description, create_by)
			VALUES (#{templateId}, #{tenantId}, #{templateCode}, #{templateName}, #{messageType},
			        #{channel}, #{titleTemplate}, #{contentTemplate}, #{variables}, #{status}, #{description}, #{createBy})
			""")
	int insert(NotifyTemplateRow row);

	/** 更新（template_code 不可改） */
	@Update("""
			UPDATE iot_notify_template
			SET template_name = #{templateName}, message_type = #{messageType}, channel = #{channel},
			    title_template = #{titleTemplate}, content_template = #{contentTemplate},
			    variables = #{variables}, status = #{status}, description = #{description}, update_time = NOW()
			WHERE template_id = #{templateId} AND tenant_id = #{tenantId}
			""")
	int update(NotifyTemplateRow row);

	/** 删除 */
	@Delete("DELETE FROM iot_notify_template WHERE template_id = #{templateId} AND tenant_id = #{tenantId}")
	int deleteById(@Param("templateId") Long templateId, @Param("tenantId") Long tenantId);

}
