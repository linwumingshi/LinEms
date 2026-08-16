package com.energyx.ota.web.dto;

import lombok.Data;

/**
 * 升级包上传/编辑请求。
 */
@Data
public class OtaPackageSaveReq {

	/**
	 * 产品标识（必填）
	 * @required
	 */
	private String productKey;

	/**
	 * 固件版本号（必填）
	 * @required
	 */
	private String version;

	/** 固件模块（默认 main） */
	private String module;

	/** 1全量包（默认） 2差分包 */
	private Integer packageType;

	/** 差分源版本（packageType=2 必填） */
	private String baseVersion;

	/** 可升级源版本列表，逗号分隔；NULL=任意源版本 */
	private String sourceVersions;

	/** 升级说明/变更日志 */
	private String description;

	/** 创建人（用户 ID；缺省 0=系统动作） */
	private Long createBy;

}
