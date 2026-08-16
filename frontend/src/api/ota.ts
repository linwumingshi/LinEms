import http from './http'
import type { OtaPackage, OtaPackageSaveReq, OtaTask, OtaTaskCreateReq, OtaTaskDevice, OtaTaskStatistics } from '@/types/models'

/** OTA 固件升级 API（网关路由 /api/ota/** → energy-ota） */
export const otaApi = {
  // ---------------- 升级包 ----------------
  /** 上传升级包（multipart：file + 表单字段） */
  uploadPackage(file: File, body: OtaPackageSaveReq): Promise<string> {
    const fd = new FormData()
    fd.append('file', file)
    Object.entries(body).forEach(([k, v]) => {
      if (v !== undefined && v !== null && v !== '') fd.append(k, String(v))
    })
    return http.post('/api/ota/packages', fd)
  },
  /** 升级包分页 */
  packages(params: { productKey?: string; version?: string; pageNum?: number; pageSize?: number }): Promise<{ records: OtaPackage[]; total: number }> {
    return http.get('/api/ota/packages', { params })
  },
  /** 升级包详情 */
  packageDetail(packageId: string): Promise<OtaPackage> {
    return http.get(`/api/ota/packages/${packageId}`)
  },
  /** 停用/启用（status 1正常 2停用） */
  updatePackageStatus(packageId: string, status: number): Promise<void> {
    return http.put(`/api/ota/packages/${packageId}/status`, null, { params: { status } })
  },
  /** 删除升级包 */
  deletePackage(packageId: string): Promise<void> {
    return http.delete(`/api/ota/packages/${packageId}`)
  },
  /** 下载升级包（浏览器直接下载） */
  downloadUrl(pkg: OtaPackage): string {
    return `/api/ota/files/${pkg.productKey}/${pkg.version}/${pkg.module}/${pkg.fileName}`
  },

  // ---------------- 批次任务 ----------------
  /** 创建任务（创建后立即开始） */
  createTask(body: OtaTaskCreateReq): Promise<string> {
    return http.post('/api/ota/tasks', body)
  },
  /** 任务分页 */
  tasks(params: { taskName?: string; status?: number; pageNum?: number; pageSize?: number }): Promise<{ records: OtaTask[]; total: number }> {
    return http.get('/api/ota/tasks', { params })
  },
  /** 任务详情 */
  taskDetail(taskId: string): Promise<OtaTask> {
    return http.get(`/api/ota/tasks/${taskId}`)
  },
  /** 设备明细分页 */
  taskDevices(taskId: string, params: { state?: number; pageNum?: number; pageSize?: number }): Promise<{ records: OtaTaskDevice[]; total: number }> {
    return http.get(`/api/ota/tasks/${taskId}/devices`, { params })
  },
  /** 立即开始 */
  startTask(taskId: string): Promise<void> {
    return http.post(`/api/ota/tasks/${taskId}/start`)
  },
  /** 暂停任务 */
  pauseTask(taskId: string): Promise<void> {
    return http.post(`/api/ota/tasks/${taskId}/pause`)
  },
  /** 恢复任务 */
  resumeTask(taskId: string): Promise<void> {
    return http.post(`/api/ota/tasks/${taskId}/resume`)
  },
  /** 灰度推进 */
  advanceGray(taskId: string): Promise<string> {
    return http.post(`/api/ota/tasks/${taskId}/gray/advance`)
  },
  /** 取消任务 */
  cancelTask(taskId: string): Promise<void> {
    return http.post(`/api/ota/tasks/${taskId}/cancel`)
  },
  /** 任务统计 */
  taskStatistics(taskId: string): Promise<OtaTaskStatistics> {
    return http.get(`/api/ota/tasks/${taskId}/statistics`)
  },
}
