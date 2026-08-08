// frontend/src/api/device.ts
import http from './http'
import type { CredentialView, Device, DeviceCreateReq, DeviceUpdateReq, PageResult } from '@/types/models'

/** 设备 API（网关 /api/device/** StripPrefix=1 → energy-device；分页参数 pageNum/pageSize） */
export const deviceApi = {
  page(params?: Record<string, unknown>): Promise<PageResult<Device>> {
    return http.get('/api/device/page', { params })
  },
  detail(deviceId: string): Promise<Device> {
    return http.get(`/api/device/${deviceId}`)
  },
  /** 创建返回雪花 deviceId（明文密钥仅此一次，随后需凭据接口/重生成查看） */
  create(body: DeviceCreateReq): Promise<string> {
    return http.post('/api/device', body)
  },
  update(deviceId: string, body: DeviceUpdateReq): Promise<void> {
    return http.put(`/api/device/${deviceId}`, body)
  },
  /** 逻辑删除整棵子树 + 吊销凭据 */
  remove(deviceId: string): Promise<void> {
    return http.delete(`/api/device/${deviceId}`)
  },
  /** 凭据查询：密钥脱敏（abcd****wxyz） */
  credential(deviceId: string): Promise<CredentialView> {
    return http.get(`/api/device/${deviceId}/credential`)
  },
  /** 重生成密钥：返回明文（仅本次） */
  regenerateCredential(deviceId: string): Promise<CredentialView> {
    return http.post(`/api/device/${deviceId}/credential/regenerate`)
  },
}
