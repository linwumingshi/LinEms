package com.energyx.product.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 物模型版本（iot_thing_model）。
 *
 * <p>
 * schema_json 为完整物模型 JSON Schema（整行快照），access 服务按 product_key + is_current=1
 * 读取并标准化上行数据。本表无 deleted 列，不继承 BaseEntity。
 * </p>
 */
@Getter
@Setter
@TableName("iot_thing_model")
public class ThingModel {

	@TableId(type = IdType.AUTO)
	private Long modelId;

	private Long tenantId;

	private Long productId;

	/** 物模型版本，如 V1.0 */
	private String version;

	/** 完整物模型 JSON Schema */
	private String schemaJson;

	/** 0草稿 1已发布 2已废弃 */
	private Integer status;

	/** 当前生效版本 */
	private Integer isCurrent;

	private Long createBy;

	@TableField(value = "create_time", fill = FieldFill.INSERT)
	private LocalDateTime createTime;

}
