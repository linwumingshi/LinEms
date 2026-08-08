/**
 * 认证态本地持久化（localStorage）。
 *
 * <p>独立成纯工具模块：http.ts（读 token 附加请求头、401 清本地）与
 * stores/auth.ts（读写登录态）共用，避免两者互相 import 造成循环依赖。
 * token 与用户信息分键存储，登录/登出/会话失效统一走本模块。</p>
 */
export const TOKEN_KEY = 'energyx_token'
export const USER_KEY = 'energyx_user'

/** 持久化的用户子集（token 独立存 energyx_token） */
export interface StoredUser {
  userId: string
  username: string
  realName: string | null
  tenantId: string
  enterpriseId: string | null
  permissions: string[]
  roles: string[]
}

/** 读 JWT；未登录返回 null */
export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function setToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token)
}

/** 读持久化用户；缺失/损坏返回 null */
export function getStoredUser(): StoredUser | null {
  const raw = localStorage.getItem(USER_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw) as StoredUser
  } catch {
    return null
  }
}

export function setStoredUser(user: StoredUser): void {
  localStorage.setItem(USER_KEY, JSON.stringify(user))
}

/** 清空认证态（登出 / HTTP 401 会话失效） */
export function clearAuth(): void {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
}
