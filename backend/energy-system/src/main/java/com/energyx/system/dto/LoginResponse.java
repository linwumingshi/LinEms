package com.energyx.system.dto;

import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.List;

/**
 * 登录响应：JWT + 基本信息 + 权限/角色标识（前端驱动菜单渲染与按钮级鉴权）。
 */
@Getter
@Setter
public class LoginResponse implements Serializable {

	private static final long serialVersionUID = 1L;

	/** JWT（调用方按 Authorization: Bearer <token> 携带） */
	private String token;

	/** 固定 Bearer */
	private String tokenType;

	/** 有效期（秒） */
	private int expiresIn;

	private Long userId;

	private String username;

	private String realName;

	private Long tenantId;

	private Long enterpriseId;

	/** 权限标识集合（含 *:*:* 表示超级管理员全部权限） */
	private List<String> permissions;

	/** 角色编码集合 */
	private List<String> roles;

}
