import http from './http'
import type { CommandView, CreateCommandPayload } from '@/types/models'

/** 指令 API（网关路由 /api/command/** → energy-command） */
export const commandApi = {
  /** POST /api/command 创建指令（在线直发 / 离线入队） */
  create(payload: CreateCommandPayload): Promise<CommandView> {
    return http.post('/api/command', payload)
  },

  /** GET /api/command/{commandId} 指令状态查询 */
  detail(commandId: string): Promise<CommandView> {
    return http.get(`/api/command/${commandId}`)
  },
}
