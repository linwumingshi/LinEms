import http from './http'
import type { NotifyChannelOption, NotifyConfig, NotifyConfigSaveReq, NotifySendRequest, NotifyTemplate, NotifyTemplateSaveReq } from '@/types/models'

/** 消息通知 API（网关路由 /api/notify/** → energy-notify） */
export const notifyApi = {
  // ---------------- 通知配置 ----------------
  configs(): Promise<NotifyConfig[]> {
    return http.get('/api/notify/configs')
  },
  createConfig(body: NotifyConfigSaveReq): Promise<number> {
    return http.post('/api/notify/config', body)
  },
  updateConfig(configId: string, body: NotifyConfigSaveReq): Promise<void> {
    return http.put(`/api/notify/config/${configId}`, body)
  },
  deleteConfig(configId: string): Promise<void> {
    return http.delete(`/api/notify/config/${configId}`)
  },

  // ---------------- 通知模板 ----------------
  templates(channel?: string): Promise<NotifyTemplate[]> {
    return http.get('/api/notify/templates', { params: channel ? { channel } : {} })
  },
  createTemplate(body: NotifyTemplateSaveReq): Promise<number> {
    return http.post('/api/notify/template', body)
  },
  updateTemplate(templateId: string, body: NotifyTemplateSaveReq): Promise<void> {
    return http.put(`/api/notify/template/${templateId}`, body)
  },
  deleteTemplate(templateId: string): Promise<void> {
    return http.delete(`/api/notify/template/${templateId}`)
  },

  // ---------------- 发送 ----------------
  send(body: NotifySendRequest): Promise<{ success: boolean; message: string }> {
    return http.post('/api/notify/send', body)
  },
  channels(): Promise<NotifyChannelOption[]> {
    return http.get('/api/notify/channels')
  },
}
