import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '@/stores/auth'
import { authApi } from '@/api/auth'
import { getStoredUser, getToken } from '@/utils/auth-storage'

vi.mock('@/api/auth', () => ({
  authApi: {
    login: vi.fn(),
    logout: vi.fn(),
  },
}))

const mockedLogin = vi.mocked(authApi.login)
const mockedLogout = vi.mocked(authApi.logout)

const loginResult = {
  token: 'jwt-token-abc',
  tokenType: 'Bearer',
  expiresIn: 7200,
  userId: 1,
  username: 'admin',
  realName: '系统管理员',
  tenantId: 1,
  enterpriseId: 1,
  permissions: ['*:*:*'],
  roles: ['admin'],
}

describe('useAuthStore', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('初始未登录：isAuthenticated=false', () => {
    const store = useAuthStore()
    expect(store.isAuthenticated).toBe(false)
  })

  it('login 成功：写内存 + 持久化 token/user', async () => {
    mockedLogin.mockResolvedValue(loginResult)
    const store = useAuthStore()

    await store.login({ username: 'admin', password: 'admin123' })

    expect(store.isAuthenticated).toBe(true)
    expect(store.token).toBe('jwt-token-abc')
    expect(store.user?.realName).toBe('系统管理员')
    expect(store.permissions).toEqual(['*:*:*'])
    expect(store.roles).toEqual(['admin'])
    expect(getToken()).toBe('jwt-token-abc')
    expect(getStoredUser()?.username).toBe('admin')
  })

  it('logout：先调后端吊销会话，再清内存与持久化', async () => {
    mockedLogin.mockResolvedValue(loginResult)
    mockedLogout.mockResolvedValue(undefined)
    const store = useAuthStore()
    await store.login({ username: 'admin', password: 'admin123' })

    await store.logout()

    expect(mockedLogout).toHaveBeenCalledTimes(1)
    expect(store.isAuthenticated).toBe(false)
    expect(store.user).toBeNull()
    expect(getToken()).toBeNull()
    expect(getStoredUser()).toBeNull()
  })

  it('logout 后端失败也清本地（保证前端必然回到未登录态）', async () => {
    mockedLogin.mockResolvedValue(loginResult)
    mockedLogout.mockRejectedValue(new Error('网络错误'))
    const store = useAuthStore()
    await store.login({ username: 'admin', password: 'admin123' })

    await expect(store.logout()).rejects.toThrow('网络错误')

    expect(store.isAuthenticated).toBe(false)
    expect(getToken()).toBeNull()
  })

  it('restoreFromStorage：从 localStorage 恢复登录态', () => {
    localStorage.setItem('energyx_token', 'jwt-restored')
    localStorage.setItem(
      'energyx_user',
      JSON.stringify({
        userId: 2,
        username: 'ops',
        realName: '运维值班',
        tenantId: 1,
        enterpriseId: 1,
        permissions: ['alarm:record:query'],
        roles: ['ops'],
      }),
    )
    const store = useAuthStore()

    store.restoreFromStorage()

    expect(store.isAuthenticated).toBe(true)
    expect(store.token).toBe('jwt-restored')
    expect(store.user?.username).toBe('ops')
    expect(store.permissions).toEqual(['alarm:record:query'])
    expect(store.roles).toEqual(['ops'])
  })
})
