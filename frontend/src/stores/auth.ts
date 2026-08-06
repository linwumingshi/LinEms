import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { authApi } from '@/api/auth'
import type { LoginRequest, LoginResult } from '@/types/models'
import {
  clearAuth,
  getStoredUser,
  getToken,
  setStoredUser,
  setToken,
  type StoredUser,
} from '@/utils/auth-storage'

/**
 * 认证全局状态：JWT + 当前用户 + 权限/角色。
 *
 * <p>token/user 双写 localStorage（auth-storage），应用启动由 main.ts 调
 * restoreFromStorage 恢复；logout 先调后端吊销 Redis 会话再清本地（后端失败也清，
 * 保证前端必然回到未登录态）；HTTP 401 由 http.ts 统一走 expireSession（防呆：
 * 登录失败是 HTTP 200 业务错误码，不会误触清空）。</p>
 */
export const useAuthStore = defineStore('auth', () => {
  /** JWT（null=未登录） */
  const token = ref<string | null>(getToken())
  /** 当前用户（含权限/角色标识，供后续菜单/按钮鉴权） */
  const user = ref<StoredUser | null>(getStoredUser())
  const permissions = ref<string[]>(user.value?.permissions ?? [])
  const roles = ref<string[]>(user.value?.roles ?? [])

  const isAuthenticated = computed(() => token.value !== null && token.value !== '')

  /** 登录：写内存 + 持久化，返回后端响应（供页面跳转 / 提示） */
  async function login(req: LoginRequest): Promise<LoginResult> {
    const result = await authApi.login(req)
    token.value = result.token
    setToken(result.token)
    const stored: StoredUser = {
      userId: result.userId,
      username: result.username,
      realName: result.realName,
      tenantId: result.tenantId,
      enterpriseId: result.enterpriseId,
      permissions: result.permissions ?? [],
      roles: result.roles ?? [],
    }
    user.value = stored
    setStoredUser(stored)
    permissions.value = stored.permissions
    roles.value = stored.roles
    return result
  }

  /** 登出：后端吊销会话 + 清本地（finally 保证本地必清） */
  async function logout(): Promise<void> {
    try {
      await authApi.logout()
    } finally {
      expireSession()
    }
  }

  /** 会话失效（登出 / HTTP 401）：清空内存与本地，不回跳 */
  function expireSession(): void {
    token.value = null
    user.value = null
    permissions.value = []
    roles.value = []
    clearAuth()
  }

  /** 应用启动时从 localStorage 恢复登录态 */
  function restoreFromStorage(): void {
    token.value = getToken()
    const stored = getStoredUser()
    user.value = stored
    permissions.value = stored?.permissions ?? []
    roles.value = stored?.roles ?? []
  }

  return {
    token,
    user,
    permissions,
    roles,
    isAuthenticated,
    login,
    logout,
    expireSession,
    restoreFromStorage,
  }
})
