import { describe, expect, it } from 'vitest'
import {
  authStatusText, dataScopeText, deviceStatusTag, deviceStatusText,
  deviceTypeOptions, isStrategyGeneratable, permTypeText, productStatusText,
  roleStatusTag, roleStatusText, thingModelStatusText, userStatusTag,
  userStatusText,
} from '@/utils/dicts'

describe('dicts', () => {
  it('产品状态', () => {
    expect(productStatusText(0)).toBe('禁用')
    expect(productStatusText(1)).toBe('启用')
    expect(productStatusText(9)).toBe('未知(9)')
  })

  it('设备状态字典', () => {
    expect(deviceStatusText(0)).toBe('未注册')
    expect(deviceStatusText(3)).toBe('在线')
    expect(deviceStatusText(5)).toBe('封禁')
    expect(deviceStatusTag(3)).toBe('success')
    expect(deviceStatusTag(4)).toBe('danger')
    expect(deviceStatusTag(99)).toBe('info')
  })

  it('用户/角色状态', () => {
    expect(userStatusText(1)).toBe('启用')
    expect(userStatusTag(0)).toBe('danger')
    expect(roleStatusText(1)).toBe('启用')
    expect(roleStatusTag(0)).toBe('danger')
  })

  it('数据范围 / 权限类型 / 物模型状态 / 凭据状态', () => {
    expect(dataScopeText(3)).toBe('本租户')
    expect(dataScopeText(4)).toBe('全部')
    expect(permTypeText(2)).toBe('按钮')
    expect(thingModelStatusText(1)).toBe('已发布')
    expect(authStatusText(2)).toBe('吊销')
  })

  it('设备类型枚举', () => {
    expect(deviceTypeOptions).toContain('PCS')
    expect(deviceTypeOptions).toContain('BATTERY_CLUSTER')
  })

  it('策略可生成性判定（与后端 PlanGenerator.java 支持集合对齐）', () => {
    expect(isStrategyGeneratable('PEAK_VALLEY')).toBe(true)
    expect(isStrategyGeneratable('DEMAND')).toBe(false)
    expect(isStrategyGeneratable('DR')).toBe(false)
    expect(isStrategyGeneratable('SOC_CTRL')).toBe(false)
    expect(isStrategyGeneratable('TIME')).toBe(false)
    expect(isStrategyGeneratable(undefined)).toBe(false)
    expect(isStrategyGeneratable('')).toBe(false)
  })
})
