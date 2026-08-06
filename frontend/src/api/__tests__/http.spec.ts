import { AxiosError } from 'axios'
import { describe, expect, it } from 'vitest'
import { resolveApiBody, toFriendlyError } from '@/api/http'

describe('resolveApiBody', () => {
  it('code=0 解包 data', () => {
    const body = { code: 0, message: 'ok', data: { deviceId: 1 }, traceId: 't', timestamp: 1 }
    expect(resolveApiBody(body)).toEqual({ deviceId: 1 })
  })

  it('code=0 且 data 为 null（Result.ok()）返回 null', () => {
    expect(resolveApiBody({ code: 0, message: 'ok', data: null })).toBeNull()
  })

  it('code≠0 抛业务异常（带 message）', () => {
    const body = { code: 404, message: '指令不存在: x', data: null, traceId: 't', timestamp: 1 }
    expect(() => resolveApiBody(body)).toThrow('指令不存在: x')
  })

  it('code≠0 且无 message 时回退错误码文案', () => {
    expect(() => resolveApiBody({ code: 500, data: null })).toThrow('业务错误码 500')
  })

  it('非 Result 结构透传', () => {
    expect(resolveApiBody({ foo: 1 })).toEqual({ foo: 1 })
  })
})

describe('toFriendlyError', () => {
  function resp(status: number, data: unknown) {
    return { status, data, statusText: '', headers: {}, config: {} } as never
  }

  it('HTTP 错误：优先取响应体 message', () => {
    const err = new AxiosError('Request failed', 'ERR_BAD_RESPONSE', undefined, undefined, resp(500, { message: '服务异常' }))
    expect(toFriendlyError(err).message).toBe('服务异常')
  })

  it('HTTP 错误：无响应体 message 时取状态码', () => {
    const err = new AxiosError('Request failed', 'ERR_BAD_REQUEST', undefined, undefined, resp(400, {}))
    expect(toFriendlyError(err).message).toBe('HTTP 400')
  })

  it('超时错误', () => {
    const err = new AxiosError('timeout of 15000ms exceeded', 'ECONNABORTED')
    expect(toFriendlyError(err).message).toBe('请求超时，请稍后重试')
  })

  it('网络不通（无响应）给出可操作提示', () => {
    const err = new AxiosError('Network Error', 'ERR_NETWORK')
    expect(toFriendlyError(err).message).toContain('网关服务（127.0.0.1:8000）')
  })

  it('非 axios 错误原样返回', () => {
    expect(toFriendlyError(new Error('boo')).message).toBe('boo')
    expect(toFriendlyError('raw').message).toBe('raw')
  })
})
