import { describe, expect, it } from 'vitest'
import { parseThingModel } from '@/utils/thingModel'

describe('parseThingModel', () => {
  it('解析合法 schema 的属性/服务/事件', () => {
    const schema = parseThingModel(JSON.stringify({
      properties: [{ identifier: 'soc', name: '荷电状态', dataType: 'float', unit: '%', accessMode: 'r' }],
      services: [{ identifier: 'setPower' }],
      events: [],
    }))
    expect(schema.properties).toHaveLength(1)
    expect(schema.properties[0].identifier).toBe('soc')
    expect(schema.services).toHaveLength(1)
    expect(schema.events).toEqual([])
  })

  it('缺省数组字段 → 空数组', () => {
    const schema = parseThingModel('{"properties":[]}')
    expect(schema.properties).toEqual([])
    expect(schema.services).toEqual([])
    expect(schema.events).toEqual([])
  })

  it('畸形 JSON → 空结构', () => {
    expect(parseThingModel('{bad json')).toEqual({ properties: [], services: [], events: [] })
  })
})
