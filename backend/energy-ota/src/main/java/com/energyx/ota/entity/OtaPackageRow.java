package com.energyx.ota.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.energyx.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * OTA 升级包（ota_package，全量包 + 差分包同表）。
 *
 * <p>
 * 表含 tenant_id/create_time/update_time/deleted 四列，继承 {@link BaseEntity}； create_by
 * 列存在故自行声明（基类不含）。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ota_package")
public class OtaPackageRow extends BaseEntity {

	/** 升级包 ID（雪花） */
	@TableId(type = IdType.ASSIGN_ID)
	private Long packageId;

	private String productKey;

	/** 固件版本号（目标版本） */
	private String version;

	/** 固件模块（默认 main，预留多模块） */
	private String module;

	/** 1全量包 2差分包 */
	private Integer packageType;

	/** 差分源版本（差分包必填，全量包为 NULL） */
	private String baseVersion;

	/** 原始文件名 */
	private String fileName;

	/** 存储相对路径 */
	private String filePath;

	/** 文件大小（字节） */
	private Long fileSize;

	/** 文件 MD5（传输校验） */
	private String md5;

	/** 文件 SHA256（完整性校验） */
	private String sha256;

	/** RSA 签名（base64，预留验签） */
	private String signature;

	/** 可升级源版本列表，逗号分隔；NULL=任意源版本 */
	private String sourceVersions;

	/** 升级说明/变更日志 */
	private String description;

	/** 1正常 2已停用 */
	private Integer status;

	/** 创建人（用户 ID，系统动作填 0；表含 create_by 列） */
	private Long createBy;

}
