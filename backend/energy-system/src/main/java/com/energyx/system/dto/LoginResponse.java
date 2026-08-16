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

	/**
	 * JWT 令牌。调用方后续请求按 {@code Authorization: Bearer <token>} 携带。
	 */
	private String token;

	/**
	 * 令牌类型，固定为 {@code Bearer}。
	 */
	private String tokenType;

	/**
	 * 令牌剩余有效期，单位秒（由会话过期时间减登录时间换算，最小 1）。
	 */
	private int expiresIn;

	/**
	 * 登录用户 ID。
	 */
	private Long userId;

	/**
	 * 登录用户名。
	 */
	private String username;

	/**
	 * 用户真实姓名（前端顶栏展示）。
	 */
	private String realName;

	/**
	 * 所属租户 ID。
	 */
	private Long tenantId;

	/**
	 * 所属单位（组织）ID，未归属单位时为 {@code null}。
	 */
	private Long enterpriseId;

	/**
	 * 权限标识集合，如 {@code system:user:add}；含 {@code *:*:*} 表示超级管理员拥有全部权限。 已按字典序去重排序，无权限时为空数组。
	 */
	private List<String> permissions;

	/**
	 * 角色编码集合，如 {@code SUPER_ADMIN}、{@code OPERATOR}。已按字典序去重排序，无角色时为空数组。
	 */
	private List<String> roles;

}
