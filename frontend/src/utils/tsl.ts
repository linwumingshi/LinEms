/**
 * 物模型 TSL 工具：数据类型/访问模式等选项 + schema 兜底解析（容错，缺字段不抛错）。
 * 完整 TSL 标准参考阿里云 IoT 物联网平台 / 华为云 IoT / Linkkit。
 */
import type { ThingModelSchema, TsDataType, TsEvent, TsParam, TsProperty, TsService } from '@/types/models'

/** 数据类型选项（TSL 标准；specs 携带每类扩展字段） */
export const tsDataTypeOptions: Array<{ value: TsDataType; label: string }> = [
  { value: 'int', label: '整数(int)' },
  { value: 'long', label: '长整型(long)' },
  { value: 'float', label: '单精度(float)' },
  { value: 'double', label: '双精度(double)' },
  { value: 'text', label: '文本(text)' },
  { value: 'bool', label: '布尔(bool)' },
  { value: 'date', label: '日期(date)' },
  { value: 'enum', label: '枚举(enum)' },
  { value: 'struct', label: '结构体(struct)' },
  { value: 'array', label: '数组(array)' },
]

/** 访问模式（属性读写权限） */
export const accessModeOptions: Array<{ value: 'r' | 'w' | 'rw'; label: string }> = [
  { value: 'r', label: '只读 r' },
  { value: 'w', label: '只写 w' },
  { value: 'rw', label: '读写 rw' },
]

/** 服务调用类型 */
export const callTypeOptions: Array<{ value: 'SYNC' | 'ASYNC'; label: string }> = [
  { value: 'ASYNC', label: '异步 ASYNC' },
  { value: 'SYNC', label: '同步 SYNC' },
]

/** 事件类型 */
export const eventTypeOptions: Array<{ value: 'INFO' | 'WARN' | 'ERROR'; label: string }> = [
  { value: 'INFO', label: '信息 INFO' },
  { value: 'WARN', label: '告警 WARN' },
  { value: 'ERROR', label: '故障 ERROR' },
]

/** 数组元素类型下拉选项（排除 array 自身，避免数组套数组的无限嵌套） */
export const elementTypeOptions = tsDataTypeOptions.filter((o) => o.value !== 'array')

/** 是否为整型数据类型（int/long 的 min/max/step 输入须为整数） */
export function isIntegerDataType(dt: TsDataType): boolean {
  return dt === 'int' || dt === 'long'
}

/** 枚举项（value 数字或字符串；desc 可选，与后端 ThingModelValidator 契约一致） */
export interface TsEnumValue {
  value: number | string
  desc?: string
}

/** 新建空枚举项（value 初始为空字符串占位，输入时经 coerceEnumValue 智能转换类型） */
export function newEnumValue(): TsEnumValue {
  return { value: '' }
}

/** 新建空结构体字段（TsParam 结构，与 structFields 数组元素契约一致） */
export function newStructField(): TsParam {
  return { identifier: '', name: '', dataType: 'int' }
}

/** 枚举值智能转换：纯数字串（整数/小数）→ number，其余保留 string（trim 后保存，与后端 value 数字/字符串双形态对齐） */
export function coerceEnumValue(raw: string): number | string {
  const t = raw.trim()
  if (t !== '' && /^-?\d+(\.\d+)?$/.test(t)) return Number(t)
  return t
}

/** 数据类型需要的扩展字段（specs 键名提示，UI 渲染依据） */
export function specsHintFor(dt: TsDataType): string[] {
  switch (dt) {
    case 'int':
    case 'long':
    case 'float':
    case 'double':
      return ['min', 'max', 'step', 'unit']
    case 'text':
      return ['length', 'unit']
    case 'enum':
      return ['enumValues']
    case 'array':
      return ['elementType', 'size']
    case 'struct':
      return ['structFields']
    default:
      return []
  }
}

/** 全新空 schema（产品物模型初始化模板） */
export function emptySchema(): ThingModelSchema {
  return { properties: [], services: [], events: [] }
}

/** 容错解析 schema（缺字段补空数组；解析失败返回空 schema 让用户从零编辑） */
export function parseSchema(schemaJson: string): ThingModelSchema {
  try {
    const obj = JSON.parse(schemaJson || '{}') as Partial<ThingModelSchema>
    return {
      properties: Array.isArray(obj.properties) ? obj.properties : [],
      services: Array.isArray(obj.services) ? obj.services : [],
      events: Array.isArray(obj.events) ? obj.events : [],
    }
  } catch {
    return emptySchema()
  }
}

/** schema 序列化为 JSON 字符串（保证 3 字段齐全，无多余字段） */
export function serializeSchema(schema: ThingModelSchema): string {
  const clean: ThingModelSchema = {
    properties: (schema.properties ?? []).map((p: TsProperty) => {
      const specs = normalizePropertySpecs(p)
      return {
        identifier: p.identifier, name: p.name, dataType: p.dataType,
        ...(p.unit ? { unit: p.unit } : {}),
        ...(p.accessMode ? { accessMode: p.accessMode } : {}),
        ...(specs && Object.keys(specs).length > 0 ? { specs } : {}),
        ...(p.required ? { required: p.required } : {}),
        ...(p.desc ? { desc: p.desc } : {}),
      }
    }),
    services: (schema.services ?? []).map((s: TsService) => ({
      identifier: s.identifier, name: s.name,
      ...(s.callType ? { callType: s.callType } : {}),
      ...(s.input && s.input.length > 0 ? { input: s.input } : {}),
      ...(s.output && s.output.length > 0 ? { output: s.output } : {}),
      ...(s.desc ? { desc: s.desc } : {}),
    })),
    events: (schema.events ?? []).map((e: TsEvent) => ({
      identifier: e.identifier, name: e.name,
      ...(e.type ? { type: e.type } : {}),
      ...(e.data && e.data.length > 0 ? { data: e.data } : {}),
      ...(e.desc ? { desc: e.desc } : {}),
    })),
  }
  return JSON.stringify(clean, null, 2)
}

/**
 * 属性 specs 归一化（M1 物模型契约统一）：兼容历史数据的<b>顶层 enumValues</b> 写法（种子数据/手写 JSON），
 * 序列化时归入 specs.enumValues（UI 约定形式），保证 enumValues 不会在保存时丢失。
 *
 * <p>冲突策略：specs.enumValues 已存在（非空）时以 specs 为准（UI 当前编辑值）；仅当 specs 缺失或为空数组时
 * 才用顶层值补入。返回新的 specs 对象，不修改入参。</p>
 */
function normalizePropertySpecs(p: TsProperty): Record<string, unknown> {
  const legacy = (p as TsProperty & { enumValues?: unknown }).enumValues
  const specs: Record<string, unknown> = p.specs ? { ...p.specs } : {}
  if (Array.isArray(legacy) && legacy.length > 0) {
    const current = specs.enumValues
    if (current === undefined || (Array.isArray(current) && current.length === 0)) {
      specs.enumValues = legacy
    }
  }
  return specs
}

/** 校验（identifier 唯一性 + 必填），返回首个问题或空字符串 */
export function validateSchema(schema: ThingModelSchema): string {
  const dup = (arr: Array<{ identifier: string }>, kind: string) => {
    const seen = new Set<string>()
    for (const x of arr) {
      if (!x.identifier?.trim()) return `${kind} 存在空 identifier`
      if (seen.has(x.identifier)) return `${kind} 存在重复 identifier: ${x.identifier}`
      seen.add(x.identifier)
    }
    return ''
  }
  let err = dup(schema.properties ?? [], '属性')
  if (err) return err
  err = dup(schema.services ?? [], '服务')
  if (err) return err
  err = dup(schema.events ?? [], '事件')
  if (err) return err
  // 服务入参 identifier 唯一性
  for (const s of schema.services ?? []) {
    const e = dup(s.input ?? [], `服务 ${s.identifier} 入参`)
    if (e) return e
  }
  return ''
}

/** 取 specs 字段值（带类型守卫） */
export function specsGet(specs: Record<string, unknown> | undefined, key: string): unknown {
  return specs?.[key]
}

/** 写入 specs 字段（返回新对象，不修改原值） */
export function specsSet(
  specs: Record<string, unknown> | undefined,
  key: string,
  value: unknown,
): Record<string, unknown> {
  const next: Record<string, unknown> = { ...(specs ?? {}) }
  if (value === '' || value === null || value === undefined) delete next[key]
  else next[key] = value
  return next
}

/** 新建一个空属性（用于"新增属性"按钮） */
export function newProperty(): TsProperty {
  return { identifier: '', name: '', dataType: 'int', accessMode: 'r' }
}
/** 新建一个空服务 */
export function newService(): TsService {
  return { identifier: '', name: '', callType: 'ASYNC', input: [], output: [] }
}
/** 新建一个空事件 */
export function newEvent(): TsEvent {
  return { identifier: '', name: '', type: 'INFO', data: [] }
}
/** 新建一个空参数 */
export function newParam(): TsParam {
  return { identifier: '', name: '', dataType: 'int' }
}
