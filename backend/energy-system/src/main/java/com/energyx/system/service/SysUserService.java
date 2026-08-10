package com.energyx.system.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.energyx.common.model.PageResult;
import com.energyx.system.dto.SysUserQuery;
import com.energyx.system.dto.SysUserSaveReq;
import com.energyx.system.dto.SysUserVO;
import com.energyx.system.entity.SysUser;

import java.util.List;

/**
 * 用户管理服务：CRUD + 角色分配 + 密码重置。
 */
public interface SysUserService extends IService<SysUser> {

	PageResult<SysUserVO> pageQuery(SysUserQuery query);

	/** 用户详情（不含密码，附带角色与单位名）。 */
	SysUserVO detailVO(Long userId);

	Long createUser(SysUserSaveReq req);

	void updateUser(Long userId, SysUserSaveReq req);

	/** 删除用户：超级管理员/当前登录账号不可删。 */
	void deleteUser(Long userId);

	/** 变更状态：0 禁用 1 启用 2 锁定。 */
	void changeStatus(Long userId, Integer status);

	/** 重置密码并吊销该用户全部会话。 */
	void resetPassword(Long userId, String newPassword);

	/** 查询用户已分配角色 ID。 */
	List<Long> roleIds(Long userId);

	/** 分配角色（全量覆盖）并刷新在线会话。 */
	void assignRoles(Long userId, List<Long> roleIds);

}
