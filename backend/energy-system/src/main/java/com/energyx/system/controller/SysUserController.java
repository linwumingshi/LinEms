package com.energyx.system.controller;

import com.energyx.common.model.PageResult;
import com.energyx.common.model.Result;
import com.energyx.system.dto.SysPasswordReq;
import com.energyx.system.dto.SysUserQuery;
import com.energyx.system.dto.SysUserSaveReq;
import com.energyx.system.dto.SysUserVO;
import com.energyx.system.service.SysUserService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户管理接口。
 *
 * <p>
 * 权限标识见 V3 种子：system:user:list / add / edit / remove / resetPwd / role。
 * </p>
 *
 * <ul>
 * <li>GET /system/user/page —— 分页查询用户（支持关键字、状态、单位筛选）</li>
 * <li>GET /system/user/{userId} —— 查询用户详情（含单位名与角色信息）</li>
 * <li>POST /system/user —— 新增用户并按需分配角色</li>
 * <li>PUT /system/user/{userId} —— 更新用户资料，密码留空表示不修改</li>
 * <li>DELETE /system/user/{userId} —— 删除用户并清理角色绑定</li>
 * <li>PUT /system/user/{userId}/status —— 变更用户启用/禁用/锁定状态</li>
 * <li>PUT /system/user/{userId}/password —— 管理员重置用户密码</li>
 * <li>GET /system/user/{userId}/roles —— 查询用户已分配角色 ID 集合</li>
 * <li>PUT /system/user/{userId}/roles —— 全量覆盖式分配用户角色</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/system/user")
public class SysUserController {

	private final SysUserService userService;

	public SysUserController(SysUserService userService) {
		this.userService = userService;
	}

	/**
	 * 分页查询用户列表。
	 *
	 * <p>
	 * 按 keyword 对用户名/姓名做模糊匹配，可叠加状态与所属单位过滤，结果按 userId 倒序； 每页大小上限 100，超出按 100
	 * 截断。返回记录会批量回填单位名称与角色 ID/名称。
	 * </p>
	 * @param query 分页/筛选条件，字段说明见 {@link SysUserQuery}
	 * @return {@link Result}<{@link PageResult}<{@link SysUserVO}>> 用户分页结果（不含密码哈希）
	 */
	@GetMapping("/page")
	@PreAuthorize("@ss.hasPermi('system:user:list')")
	public Result<PageResult<SysUserVO>> page(SysUserQuery query) {
		return Result.ok(userService.pageQuery(query));
	}

	/**
	 * 查询单个用户详情，附带所属单位名称与已分配角色 ID/名称。
	 * @param userId 用户 ID（来源：路径变量）
	 * @return {@link Result}<{@link SysUserVO}> 用户展示视图（不含密码哈希）
	 * @throws com.energyx.common.exception.BusinessException 用户不存在（404）
	 */
	@GetMapping("/{userId}")
	@PreAuthorize("@ss.hasPermi('system:user:list')")
	public Result<SysUserVO> detail(@PathVariable Long userId) {
		return Result.ok(userService.detailVO(userId));
	}

	/**
	 * 新增用户。
	 *
	 * <p>
	 * 校验同租户下用户名唯一、密码必填、roleIds 对应角色均存在后落库；密码以 PasswordEncoder 加密存储， status 未传时默认启用，随后按
	 * roleIds 建立角色绑定。
	 * </p>
	 * @param req 请求体，字段说明见 {@link SysUserSaveReq}
	 * @return {@link Result}<{@link Long}> 新建用户的用户 ID
	 * @throws com.energyx.common.exception.BusinessException
	 * 用户名重复（409）、未设置密码（400）、角色不存在（404）
	 */
	@PostMapping
	@PreAuthorize("@ss.hasPermi('system:user:add')")
	public Result<Long> create(@Valid @RequestBody SysUserSaveReq req) {
		return Result.ok(userService.createUser(req));
	}

	/**
	 * 更新用户资料。
	 *
	 * <p>
	 * 用户名变更时重新校验同租户唯一；password 留空表示不修改密码，传值则重新加密并吊销该用户全部在线会话； roleIds 为 null 表示不调整角色，非
	 * null 则按全量覆盖重新分配。
	 * </p>
	 * @param userId 用户 ID（来源：路径变量）
	 * @param req 请求体，字段说明见 {@link SysUserSaveReq}
	 * @return {@link Result}<{@link Void}> 无数据负载
	 * @throws com.energyx.common.exception.BusinessException 用户不存在（404）、用户名重复（409）
	 */
	@PutMapping("/{userId}")
	@PreAuthorize("@ss.hasPermi('system:user:edit')")
	public Result<Void> update(@PathVariable Long userId, @Valid @RequestBody SysUserSaveReq req) {
		userService.updateUser(userId, req);
		return Result.ok();
	}

	/**
	 * 删除用户（逻辑删除），同时清理其角色绑定并吊销在线会话。
	 *
	 * <p>
	 * 安全约束：超级管理员（userId=1）与当前登录账号不允许删除。
	 * </p>
	 * @param userId 用户 ID（来源：路径变量）
	 * @return {@link Result}<{@link Void}> 无数据负载
	 * @throws com.energyx.common.exception.BusinessException
	 * 删除超级管理员或当前登录账号（409）、用户不存在（404）
	 */
	@DeleteMapping("/{userId}")
	@PreAuthorize("@ss.hasPermi('system:user:remove')")
	public Result<Void> delete(@PathVariable Long userId) {
		userService.deleteUser(userId);
		return Result.ok();
	}

	/**
	 * 变更用户状态。
	 *
	 * <p>
	 * 目标状态非「启用」时视为停用操作，会吊销该用户全部在线会话；超级管理员（userId=1）与当前登录账号不允许被停用。
	 * </p>
	 * @param userId 用户 ID（来源：路径变量）
	 * @param status 目标状态码（来源：查询参数），取值 0 禁用 / 1 启用 / 2 锁定，语义见
	 * {@link com.energyx.common.enums.UserStatus}
	 * @return {@link Result}<{@link Void}> 无数据负载
	 * @throws com.energyx.common.exception.BusinessException
	 * 停用超级管理员或当前登录账号（409）、用户不存在（404）
	 */
	@PutMapping("/{userId}/status")
	@PreAuthorize("@ss.hasPermi('system:user:edit')")
	public Result<Void> changeStatus(@PathVariable Long userId, @RequestParam Integer status) {
		userService.changeStatus(userId, status);
		return Result.ok();
	}

	/**
	 * 管理员重置指定用户的登录密码。
	 *
	 * <p>
	 * 新密码必填且长度需在 6~64 位，加密后写库并吊销该用户全部在线会话，强制其重新登录。
	 * </p>
	 * @param userId 用户 ID（来源：路径变量）
	 * @param req 请求体，字段说明见 {@link SysPasswordReq}
	 * @return {@link Result}<{@link Void}> 无数据负载
	 * @throws com.energyx.common.exception.BusinessException 密码长度不合法（400）、用户不存在（404）
	 */
	@PutMapping("/{userId}/password")
	@PreAuthorize("@ss.hasPermi('system:user:resetPwd')")
	public Result<Void> resetPassword(@PathVariable Long userId, @Valid @RequestBody SysPasswordReq req) {
		userService.resetPassword(userId, req.getPassword());
		return Result.ok();
	}

	/**
	 * 查询用户已分配的角色 ID 集合（供分配角色弹窗回显勾选）。
	 * @param userId 用户 ID（来源：路径变量）
	 * @return {@link Result}<{@link List}<{@link Long}>> 已分配角色 ID 列表，未分配时为空列表
	 */
	@GetMapping("/{userId}/roles")
	@PreAuthorize("@ss.hasPermi('system:user:role')")
	public Result<List<Long>> roleIds(@PathVariable Long userId) {
		return Result.ok(userService.roleIds(userId));
	}

	/**
	 * 为用户分配角色（全量覆盖）。
	 *
	 * <p>
	 * 请求体角色 ID 去重后先校验全部存在，再清空该用户原有绑定并重建；完成后刷新该用户在线会话的权限身份， 使角色变更即时生效。传空数组表示清空全部角色。
	 * </p>
	 * @param userId 用户 ID（来源：路径变量）
	 * @param roleIds 请求体，目标角色 ID 集合（全量覆盖，重复项自动去重）
	 * @return {@link Result}<{@link Void}> 无数据负载
	 * @throws com.energyx.common.exception.BusinessException 用户不存在或角色不存在（404）
	 */
	@PutMapping("/{userId}/roles")
	@PreAuthorize("@ss.hasPermi('system:user:role')")
	public Result<Void> assignRoles(@PathVariable Long userId, @RequestBody List<Long> roleIds) {
		userService.assignRoles(userId, roleIds);
		return Result.ok();
	}

}
