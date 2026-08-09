/**
 * 计划生成前置：安全约束就绪预检。
 * 后端契约：EmsPlanService 仅要求 constraint 行存在（缺失抛「未配置安全约束」）；
 * SafetyEnvelopeValidator 对 socMin/socMax/chargePowerMax/dischargePowerMax 取 doubleValue()（null→NPE）。
 * 前端预检与后端闸对齐：行存在 + 4 字段非空。
 */
import { emsApi } from '@/api/ems'
import type { EmsConstraint } from '@/types/models'

/** 约束是否满足生成计划的必要条件（行存在 + 4 字段非空） */
export function hasFullConstraint(c: EmsConstraint | null | undefined): boolean {
  if (!c) return false
  return c.socMin != null && c.socMax != null && c.chargePowerMax != null && c.dischargePowerMax != null
}

/** 查询该站约束；查询失败/缺行 → null（不抛，由调用方决定提示） */
export async function ensureConstraint(stationId: string): Promise<EmsConstraint | null> {
  try {
    return await emsApi.constraintGet(stationId)
  } catch {
    return null
  }
}

/** 预检便捷入口：true = 就绪 */
export async function constraintReady(stationId: string): Promise<boolean> {
  return hasFullConstraint(await ensureConstraint(stationId))
}
