import { describe, expect, it } from 'vitest'
import {
  coerceEnumValue,
  elementTypeOptions,
  isIntegerDataType,
  newEnumValue,
  newStructField,
  parseSchema,
  serializeSchema,
} from '@/utils/tsl'

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

describe('specs 可视化编辑辅助（tsl）', () => {
  it('isIntegerDataType：int/long 为整数类型，float/double 不是', () => {
    expect(isIntegerDataType('int')).toBe(true)
    expect(isIntegerDataType('long')).toBe(true)
    expect(isIntegerDataType('float')).toBe(false)
    expect(isIntegerDataType('double')).toBe(false)
    expect(isIntegerDataType('text')).toBe(false)
  })

  it('elementTypeOptions：排除 array 自身，保留其余合法类型', () => {
    const values = elementTypeOptions.map((o) => o.value)
    expect(values).not.toContain('array')
    expect(values).toContain('int')
    expect(values).toContain('float')
    expect(values).toContain('text')
    expect(values).toContain('struct')
  })

  it('coerceEnumValue：纯数字串转 number，其余保留 string，空串不转换', () => {
    expect(coerceEnumValue('0')).toBe(0)
    expect(coerceEnumValue('1.5')).toBe(1.5)
    expect(coerceEnumValue('-2')).toBe(-2)
    expect(coerceEnumValue('ON')).toBe('ON')
    expect(coerceEnumValue('  ON  ')).toBe('ON')
    expect(coerceEnumValue('')).toBe('')
  })

  it('newEnumValue / newStructField 工厂返回契约结构', () => {
    expect(newEnumValue()).toEqual({ value: '' })
    expect(newStructField()).toEqual({ identifier: '', name: '', dataType: 'int' })
  })

  it('serializeSchema 原样透传 specs 的 enumValues/structFields/elementType（数值类型保持 number）', () => {
    const schema = parseSchema(JSON.stringify({
      properties: [{
        identifier: 'runMode',
        name: '运行模式',
        dataType: 'enum',
        specs: {
          enumValues: [{ value: 0, desc: '待机' }, { value: 1, desc: '充电' }],
        },
      }, {
        identifier: 'env',
        name: '环境',
        dataType: 'struct',
        specs: {
          structFields: [{ identifier: 'temp', name: '温度', dataType: 'float', specs: { min: -40, max: 120 } }],
        },
      }, {
        identifier: 'cells',
        name: '电芯电压',
        dataType: 'array',
        specs: { elementType: 'float', size: 512 },
      }],
      services: [],
      events: [],
    }))
    const parsed = JSON.parse(serializeSchema(schema))
    expect(parsed.properties[0].specs.enumValues).toEqual([{ value: 0, desc: '待机' }, { value: 1, desc: '充电' }])
    expect(parsed.properties[1].specs.structFields[0].specs.min).toBe(-40)
    expect(parsed.properties[2].specs.elementType).toBe('float')
    expect(parsed.properties[2].specs.size).toBe(512)
  })
})
