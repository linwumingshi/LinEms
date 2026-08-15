package com.energyx.device.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.energyx.common.entity.BaseEntity;
import com.energyx.common.enums.CredentialAuthStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 设备凭据（iot_device_credential）。
 *
 * <p>
 * 继承 {@link BaseEntity}（表含 tenant_id/create_time/update_time/deleted 列）：审计字段与逻辑删除
 * 统一由基类承载，自动填充见 {@code AuditMetaObjectHandler}。
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
public class DeviceCredential extends BaseEntity {

	@TableId(type = IdType.AUTO)
	private Long id;

	private Long deviceId;

	/** 设备密钥（HMAC 签名用） */
	private String deviceSecret;

	/** 凭据认证状态（NORMAL/REVOKED，对应 DB 1正常 2吊销） */
	private CredentialAuthStatus authStatus;

	/** 连续认证失败次数（封禁判定） */
	private Integer failCount;

	private LocalDateTime lastAuthTime;

	private LocalDateTime expireTime;

}
