package com.energyx.device.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 设备凭据（iot_device_credential）。
 *
 * <p>
 * 注意：本表无 deleted 列，不能继承 {@link com.energyx.common.entity.BaseEntity}； 仅声明
 * create_time/update_time 供审计字段自动填充。
 * </p>
 *
 * <p>
 * device_secret 用于设备连接认证：password = hex(HMAC-SHA256(secret, username))，
 * 明文仅认证链可见，管理接口一律脱敏返回。
 * </p>
 */
@Getter
@Setter
@TableName("iot_device_credential")
public class DeviceCredential {

	@TableId(type = IdType.AUTO)
	private Long id;

	private Long deviceId;

	private Long tenantId;

	/** 设备密钥（HMAC 签名用） */
	private String deviceSecret;

	/** 1正常 2吊销 */
	private Integer authStatus;

	/** 连续认证失败次数（封禁判定） */
	private Integer failCount;

	private LocalDateTime lastAuthTime;

	private LocalDateTime expireTime;

	@TableField(value = "create_time", fill = FieldFill.INSERT)
	private LocalDateTime createTime;

	@TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
	private LocalDateTime updateTime;

}
