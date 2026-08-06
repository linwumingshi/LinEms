package com.sanduo.energy.system.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.sanduo.energy.system.dto.SysPermissionSaveReq;
import com.sanduo.energy.system.entity.SysPermission;

import java.util.List;

/**
 * 菜单资源管理服务（目录/菜单/按钮树 CRUD）。
 */
public interface SysPermissionService extends IService<SysPermission> {

    /** 全量菜单树（含按钮节点，供管理页渲染与角色分配勾选）。 */
    List<SysPermission> tree();

    Long createPermission(SysPermissionSaveReq req);

    void updatePermission(Long permId, SysPermissionSaveReq req);

    /** 删除菜单：存在子节点则拒绝；同步清理角色关联并刷新在线会话。 */
    void deletePermission(Long permId);

    /** 变更状态：0 正常 1 停用（变更后刷新在线会话权限）。 */
    void changeStatus(Long permId, Integer status);
}
