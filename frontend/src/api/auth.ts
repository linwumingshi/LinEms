import http from './http'
import type { LoginRequest, LoginResult } from '@/types/models'

/** 认证 API（网关路由 /api/system/auth/** → energy-system AuthController） */
export const authApi = {
  /** POST /api/system/auth/login 登录：成功返回 LoginResult（拦截器已解包 Result.data） */
  login(req: LoginRequest): Promise<LoginResult> {
    return http.post('/api/system/auth/login', req)
  },

  /** POST /api/system/auth/logout 登出：吊销 Redis 会话（幂等） */
  logout(): Promise<void> {
    return http.post('/api/system/auth/logout')
  },
}
