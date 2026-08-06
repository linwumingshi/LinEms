import { describe, expect, it } from 'vitest'
import { resolveAuthRedirect } from '@/router/index'

describe('resolveAuthRedirect 路由守卫判定（P0-2）', () => {
  it('未登录访问受保护页 → /login', () => {
    expect(resolveAuthRedirect({ path: '/dashboard' }, false)).toBe('/login')
  })

  it('未登录访问公开页（/login）→ null 放行', () => {
    expect(resolveAuthRedirect({ path: '/login', meta: { public: true } }, false)).toBeNull()
  })

  it('已登录访问业务页 → null 放行', () => {
    expect(resolveAuthRedirect({ path: '/alarm' }, true)).toBeNull()
  })

  it('已登录访问 /login → 回首页', () => {
    expect(resolveAuthRedirect({ path: '/login', meta: { public: true } }, true)).toBe('/')
  })
})
