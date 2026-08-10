package com.energyx.system.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.energyx.common.model.PageResult;
import com.energyx.system.dto.SysRoleQuery;
import com.energyx.system.dto.SysRoleSaveReq;
import com.energyx.system.entity.SysRole;

import java.util.List;

/**
 * 角色管理服务：CRUD + 权限分配。
 */
public interface SysRoleService extends IService<SysRole> {

	PageResult<SysRole> pageQuery(SysRoleQuery query);

	/** 全量角色（按创建时间倒序，供分配下拉）。 */
	List<SysRole> listAll();

	Long createRole(SysRoleSaveReq req);

	void updateRole(Long roleId, SysRoleSaveReq req);

	/** 删除角色：超级管理员内置角色及已分配用户的角色不可删。 */
	void deleteRole(Long roleId);

	/** 变更状态：0 禁用 1 启用。 */
	void changeStatus(Long roleId, Integer status);

	/** 查询角色已分配权限 ID。 */
	List<Long> permIds(Long roleId);

	/** 分配权限（全量覆盖）并刷新持有该角色的在线会话。 */
	void assignPerms(Long roleId, List<Long> permIds);

}
