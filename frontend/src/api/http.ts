import axios, { AxiosError, type AxiosResponse } from 'axios'
import type { ApiResult } from '@/types/models'
import { clearAuth, getToken } from '@/utils/auth-storage'

/** 成功业务码（与后端 ErrorCode.SUCCESS=0 对齐，见 Result.java） */
export const SUCCESS_CODE = 0

/**
 * 解析统一响应体：成功返回 data 本体，业务失败抛 Error。
 * 拆成纯函数便于单测直接覆盖（见 src/api/__tests__/http.spec.ts）。
 */
export function resolveApiBody<T>(body: unknown): T {
  if (body && typeof body === 'object' && 'code' in body) {
    const result = body as ApiResult<unknown>
    if (result.code === SUCCESS_CODE) {
      return result.data as T
    }
    throw new Error(result.message || `业务错误码 ${result.code}`)
  }
  // 非 Result 结构（网关/兜底返回）直接透传
  return body as T
}

/** 把 axios 错误归一化为可读 message */
export function toFriendlyError(error: unknown): Error {
  if (error instanceof AxiosError) {
    if (error.response) {
      const body = error.response.data as { message?: string } | undefined
      const msg = body?.message || `HTTP ${error.response.status}`
      return new Error(msg)
    }
    if (error.code === 'ECONNABORTED') {
      return new Error('请求超时，请稍后重试')
    }
    return new Error('网络连接失败，请确认网关服务（127.0.0.1:8000）已启动')
  }
  return error instanceof Error ? error : new Error(String(error))
}

/**
 * HTTP 401 回调：由 main.ts 注册（跳登录页并携带回跳地址）。
 * 独立于 http 之外，避免 http ↔ router 循环引用。
 */
let unauthorizedHandler: (() => void) | null = null

/** 注册 401 处理器（main.ts 启动时调用一次） */
export function onUnauthorized(handler: () => void): void {
  unauthorizedHandler = handler
}

const http = axios.create({
  baseURL: '/',
  timeout: 15000,
  headers: { 'Content-Type': 'application/json' },
})

// 请求拦截器：附加 Bearer token（登录/登出等无 token 请求不受影响）
http.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

http.interceptors.response.use(
  (response: AxiosResponse<unknown>) => resolveApiBody(response.data),
  (error: unknown) => {
    // 只认 HTTP 401（网关未认证/已过期）→ 清本地 + 回调跳登录页。
    // 登录失败是 HTTP 200 + 业务码（40100），走不到这里，避免误清空导致死循环。
    if (error instanceof AxiosError && error.response?.status === 401) {
      clearAuth()
      unauthorizedHandler?.()
    }
    return Promise.reject(toFriendlyError(error))
  },
)

export default http
