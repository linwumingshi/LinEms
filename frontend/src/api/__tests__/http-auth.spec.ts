import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AxiosError } from 'axios'
import http, { onUnauthorized } from '@/api/http'
import { getToken, setToken } from '@/utils/auth-storage'

/** 造一个带 status/body 的 AxiosError（走 http 响应错误分支） */
function axiosError(status: number): AxiosError {
  return new AxiosError('Request failed', 'ERR_BAD_REQUEST', undefined, undefined, {
    status,
    data: { message: status === 401 ? '未认证或 Token 无效' : '服务异常' },
    statusText: '',
    headers: {},
    config: {},
  } as never)
}

describe('http 认证接线（P0-2）', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('请求拦截器：有 token 时附加 Authorization: Bearer <token>', async () => {
    setToken('jwt-x')
    let seen: unknown = null
    http.defaults.adapter = async (config) => {
      seen = (config.headers as Record<string, unknown>).Authorization
      return { data: { code: 0, message: 'ok', data: null }, status: 200, statusText: '', headers: {}, config }
    }

    await http.get('/api/alarm/records')

    expect(seen).toBe('Bearer jwt-x')
  })

  it('请求拦截器：无 token 时不附加 Authorization', async () => {
    let seen: unknown = 'sentinel'
    http.defaults.adapter = async (config) => {
      seen = (config.headers as Record<string, unknown>).Authorization
      return { data: { code: 0, message: 'ok', data: null }, status: 200, statusText: '', headers: {}, config }
    }

    await http.get('/api/alarm/records')

    expect(seen).toBeUndefined()
  })

  it('HTTP 401：触发 unauthorizedHandler 并清空本地认证态', async () => {
    setToken('jwt-stale')
    const handler = vi.fn()
    onUnauthorized(handler)
    http.defaults.adapter = async () => Promise.reject(axiosError(401))

    await expect(http.get('/api/alarm/records')).rejects.toThrow('未认证或 Token 无效')

    expect(handler).toHaveBeenCalledTimes(1)
    expect(getToken()).toBeNull()
  })

  it('HTTP 500：不触发 unauthorizedHandler，也不清空本地认证态', async () => {
    setToken('jwt-ok')
    const handler = vi.fn()
    onUnauthorized(handler)
    http.defaults.adapter = async () => Promise.reject(axiosError(500))

    await expect(http.get('/api/alarm/records')).rejects.toThrow('服务异常')

    expect(handler).not.toHaveBeenCalled()
    expect(getToken()).toBe('jwt-ok')
  })
})
