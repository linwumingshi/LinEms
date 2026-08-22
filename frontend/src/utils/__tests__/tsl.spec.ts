import { describe, expect, it } from 'vitest'
import { parseSchema, serializeSchema } from '@/utils/tsl'

describe('tsl serializeSchema enumValues 契约（M1）', () => {
  it('顶层 enumValues（历史种子写法）序列化后不丢失，归入 specs.enumValues', () => {
    const schema = parseSchema(JSON.stringify({
      properties: [{
        identifier: 'runMode', name: '运行模式', dataType: 'enum', accessMode: 'rw',
        enumValues: [{ value: 0, desc: '待机' }, { value: 1, desc: '充电' }, { value: 2, desc: '放电' }],
      }],
    }))
    const out = JSON.parse(serializeSchema(schema))
    const prop = out.properties[0]
    expect(prop.specs.enumValues).toHaveLength(3)
    expect(prop.specs.enumValues[2]).toEqual({ value: 2, desc: '放电' })
  })

  it('specs.enumValues（前端约定写法）序列化后保持', () => {
    const schema = parseSchema(JSON.stringify({
      properties: [{
        identifier: 'runMode', name: '运行模式', dataType: 'enum',
        specs: { enumValues: [{ value: 0, desc: '待机' }, { value: 1, desc: '充电' }] },
      }],
    }))
    const out = JSON.parse(serializeSchema(schema))
    expect(out.properties[0].specs.enumValues).toHaveLength(2)
  })

  it('顶层与 specs.enumValues 同时存在时以 specs 为准（UI 编辑值）', () => {
    const schema = parseSchema(JSON.stringify({
      properties: [{
        identifier: 'runMode', dataType: 'enum',
        enumValues: [{ value: 0, desc: '旧值' }],
        specs: { enumValues: [{ value: 1, desc: '新值' }] },
      }],
    }))
    const out = JSON.parse(serializeSchema(schema))
    expect(out.properties[0].specs.enumValues).toEqual([{ value: 1, desc: '新值' }])
  })

  it('种子 schema 往返（parse→serialize→parse）enumValues 不丢失', () => {
    const seed = JSON.stringify({
      properties: [{
        identifier: 'runMode', name: '运行模式', dataType: 'enum',
        enumValues: [{ value: 0, desc: '待机' }, { value: 1, desc: '充电' }, { value: 2, desc: '放电' }],
        accessMode: 'rw',
      }],
      services: [],
      events: [],
    })
    const serialized = serializeSchema(parseSchema(seed))
    const reparsed = parseSchema(serialized)
    const prop = reparsed.properties[0]
    expect((prop.specs as Record<string, unknown>).enumValues).toHaveLength(3)
    // 序列化输出中不再出现顶层 enumValues（统一为 specs 单来源）
    expect(JSON.parse(serialized).properties[0].enumValues).toBeUndefined()
  })

  it('specs 中非 enumValues 字段（min/max/length）序列化后保留', () => {
    const schema = parseSchema(JSON.stringify({
      properties: [{
        identifier: 'soc', name: '荷电状态', dataType: 'float', unit: '%',
        specs: { min: 0, max: 100, step: 0.5 },
      }],
    }))
    const out = JSON.parse(serializeSchema(schema))
    expect(out.properties[0].specs).toEqual({ min: 0, max: 100, step: 0.5 })
    expect(out.properties[0].unit).toBe('%')
  })
})
