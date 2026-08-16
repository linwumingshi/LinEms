package com.energyx.system.dto;

import com.energyx.common.enums.UserStatus;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户展示视图（不携带密码哈希，附带单位名与角色信息）。
 */
@Data
public class SysUserVO implements Serializable {

	private static final long serialVersionUID = 1L;

	/**
	 * 用户 ID（主键）。
	 */
	private Long userId;

	/**
	 * 所属租户 ID。
	 */
	private Long tenantId;

	/**
	 * 所属单位（组织）ID，未归属单位时为 {@code null}。
	 */
	private Long enterpriseId;

	/**
	 * 所属单位名称，按 enterpriseId 批量回填的冗余展示字段；单位不存在或未归属时为 {@code null}。
	 */
	private String enterpriseName;

	/**
	 * 登录用户名（同租户内唯一）。
	 */
	private String username;

	/**
	 * 用户真实姓名。
	 */
	private String realName;

	/**
	 * 联系手机号，可为 {@code null}。
	 */
	private String phone;

	/**
	 * 联系邮箱，可为 {@code null}。
	 */
	private String email;

	/**
	 * 用户状态。JSON 输出状态码：0 禁用 / 1 启用 / 2 锁定，枚举常量见
	 * {@link com.energyx.common.enums.UserStatus}（DISABLED/ENABLED/LOCKED）。
	 */
	private UserStatus status;

	/**
	 * 最后一次登录成功时间，从未登录时为 {@code null}。
	 */
	private LocalDateTime lastLoginTime;

	/**
	 * 账号创建时间。
	 */
	private LocalDateTime createTime;

	/**
	 * 已分配的角色 ID 列表，未分配时为空数组。
	 */
	private List<Long> roleIds;

	/**
	 * 已分配的角色名称列表，与 roleIds 对应；角色被删除等取不到名称的项会被过滤，故长度可能小于 roleIds。
	 */
	private List<String> roleNames;

}
