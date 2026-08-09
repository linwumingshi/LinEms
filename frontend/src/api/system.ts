// frontend/src/api/system.ts
import http from './http'
import type {
  PageResult, SysEnterprise, SysEnterpriseSaveReq, SysPermission, SysPermissionSaveReq,
  SysRole, SysRoleSaveReq, SysUserSaveReq, SysUserVO,
} from '@/types/models'

/** RBAC API（网关 /api/system/** StripPrefix=1 → energy-system；分页参数 current/size） */

export const userApi = {
  page(params?: Record<string, unknown>): Promise<PageResult<SysUserVO>> {
    return http.get('/api/system/user/page', { params })
  },
  create(body: SysUserSaveReq): Promise<string> {
    return http.post('/api/system/user', body)
  },
  update(userId: string, body: SysUserSaveReq): Promise<void> {
    return http.put(`/api/system/user/${userId}`, body)
  },
  remove(userId: string): Promise<void> {
    return http.delete(`/api/system/user/${userId}`)
  },
  switchStatus(userId: string, status: number): Promise<void> {
    return http.put(`/api/system/user/${userId}/status?status=${status}`)
  },
  resetPassword(userId: string, password: string): Promise<void> {
    return http.put(`/api/system/user/${userId}/password`, { password })
  },
  roles(userId: string): Promise<string[]> {
    return http.get(`/api/system/user/${userId}/roles`)
  },
  assignRoles(userId: string, roleIds: string[]): Promise<void> {
    return http.put(`/api/system/user/${userId}/roles`, roleIds)
  },
}

export const roleApi = {
  page(params?: Record<string, unknown>): Promise<PageResult<SysRole>> {
    return http.get('/api/system/role/page', { params })
  },
  /** 全量角色列表（下拉用） */
  list(): Promise<SysRole[]> {
    return http.get('/api/system/role/list')
  },
  create(body: SysRoleSaveReq): Promise<string> {
    return http.post('/api/system/role', body)
  },
  update(roleId: string, body: SysRoleSaveReq): Promise<void> {
    return http.put(`/api/system/role/${roleId}`, body)
  },
  remove(roleId: string): Promise<void> {
    return http.delete(`/api/system/role/${roleId}`)
  },
  switchStatus(roleId: string, status: number): Promise<void> {
    return http.put(`/api/system/role/${roleId}/status?status=${status}`)
  },
  perms(roleId: string): Promise<string[]> {
    return http.get(`/api/system/role/${roleId}/perms`)
  },
  /** 全量覆盖：body 为裸 List<Long>（前端仅传全选节点 permId；半选父节点不落库，避免重开时 setCheckedKeys 级联放大） */
  assignPerms(roleId: string, permIds: string[]): Promise<void> {
    return http.put(`/api/system/role/${roleId}/perms`, permIds)
  },
}

export const permApi = {
  tree(): Promise<SysPermission[]> {
    return http.get('/api/system/perm/tree')
  },
  create(body: SysPermissionSaveReq): Promise<string> {
    return http.post('/api/system/perm', body)
  },
  update(permId: string, body: SysPermissionSaveReq): Promise<void> {
    return http.put(`/api/system/perm/${permId}`, body)
  },
  remove(permId: string): Promise<void> {
    return http.delete(`/api/system/perm/${permId}`)
  },
  switchStatus(permId: string, status: number): Promise<void> {
    return http.put(`/api/system/perm/${permId}/status?status=${status}`)
  },
}

export const enterpriseApi = {
  /** 组织树（单位管理页左树） */
  tree(): Promise<SysEnterprise[]> {
    return http.get('/api/system/enterprise/tree')
  },
  /** 全量企业列表（下拉用） */
  list(): Promise<SysEnterprise[]> {
    return http.get('/api/system/enterprise/list')
  },
  detail(enterpriseId: string): Promise<SysEnterprise> {
    return http.get(`/api/system/enterprise/${enterpriseId}`)
  },
  create(body: SysEnterpriseSaveReq): Promise<string> {
    return http.post('/api/system/enterprise', body)
  },
  update(enterpriseId: string, body: SysEnterpriseSaveReq): Promise<void> {
    return http.put(`/api/system/enterprise/${enterpriseId}`, body)
  },
  remove(enterpriseId: string): Promise<void> {
    return http.delete(`/api/system/enterprise/${enterpriseId}`)
  },
  switchStatus(enterpriseId: string, status: number): Promise<void> {
    return http.put(`/api/system/enterprise/${enterpriseId}/status?status=${status}`)
  },
}
