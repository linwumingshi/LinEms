<script setup lang="ts">
/**
 * 物模型 specs 可视化编辑器（按 dataType 渲染类型专属规格字段）：
 * - int/long：min/max/step 用整数数字输入（float/double 允许小数），与后端数值契约对齐
 * - text：length 用非负整数输入
 * - enum：enumValues 表格（value 智能转换数字/字符串 + desc + 类型标签）
 * - array：elementType 下拉选择 + size 非负整数
 * - struct：structFields 表格（字段行内可继续编辑规格；嵌套 struct 深度 >= 1 时提示 JSON 模式，防无限递归）
 * 写入值类型与后端 ThingModelValidator 契约一致：min/max/step 为数值、length/size 为非负整数、
 * enumValues 项含 value（数字或字符串）+ desc（可选字符串）、elementType 为合法 dataType。
 */
import { computed } from 'vue'
import type { TsDataType, TsParam } from '@/types/models'
import {
  coerceEnumValue,
  elementTypeOptions,
  isIntegerDataType,
  newEnumValue,
  newStructField,
  tsDataTypeOptions,
  type TsEnumValue,
} from '@/utils/tsl'

const props = withDefaults(defineProps<{
  /** 当前数据类型（决定渲染哪些规格字段） */
  dataType: TsDataType
  /** specs 响应式引用（父组件保证非 undefined；内部直接做可变修改以触发父级更新） */
  specs: Record<string, unknown>
  /** 递归深度：structFields 行内嵌套编辑时 +1，depth >= 1 禁止再展开嵌套 struct */
  depth?: number
}>(), { depth: 0 })

/** 数值型规格字段的显示值（历史字符串值兼容：转 number；空值显示 undefined） */
function numValue(key: string): number | undefined {
  const v = props.specs[key]
  if (v === undefined || v === null || v === '') return undefined
  const n = Number(v)
  return Number.isNaN(n) ? undefined : n
}

/** 写入数值型规格字段：空值删除键，否则写 number（与后端 min/max/step/length/size 数值契约一致） */
function setNum(key: string, v: number | undefined | null): void {
  if (v === undefined || v === null || Number.isNaN(v)) delete props.specs[key]
  else props.specs[key] = v
}

/** 数组元素类型（elementType 为字符串时返回，否则 undefined；v-model 类型安全壳） */
const elementTypeModel = computed<string | undefined>({
  get: () => (typeof props.specs.elementType === 'string' ? (props.specs.elementType as string) : undefined),
  set: (v) => {
    if (v) props.specs.elementType = v
    else delete props.specs.elementType
  },
})

// ============== enumValues 可视化 ==============
/** 枚举项列表（缺失时初始化空数组；行内编辑直接改响应式数组元素） */
function enumValues(): TsEnumValue[] {
  const cur = props.specs.enumValues
  if (!Array.isArray(cur)) props.specs.enumValues = []
  return props.specs.enumValues as TsEnumValue[]
}

/** 枚举值输入：纯数字串转 number，其余保留 string（与后端 value 数字/字符串双形态对齐） */
function onEnumValueInput(row: TsEnumValue, raw: string): void {
  row.value = coerceEnumValue(raw)
}

function addEnumValue(): void {
  enumValues().push(newEnumValue())
}

function removeEnumValue(i: number): void {
  enumValues().splice(i, 1)
}

// ============== structFields 可视化 ==============
/** 结构体字段列表（缺失时初始化空数组；行内编辑直接改响应式数组元素） */
function structFields(): TsParam[] {
  const cur = props.specs.structFields
  if (!Array.isArray(cur)) props.specs.structFields = []
  return props.specs.structFields as TsParam[]
}

/** 行内字段规格对象（不存在时初始化空对象，供嵌套 SpecsEditor 直接修改） */
function rowSpecs(row: TsParam): Record<string, unknown> {
  if (!row.specs) row.specs = {}
  return row.specs
}

function addStructField(): void {
  structFields().push(newStructField())
}

function removeStructField(i: number): void {
  structFields().splice(i, 1)
}
</script>

<template>
  <div class="se">
    <!-- 数字类型：min/max/step（int/long 强制整数，float/double 允许小数） -->
    <template v-if="['int', 'long', 'float', 'double'].includes(dataType)">
      <div class="se-grid">
        <div v-for="k in ['min', 'max', 'step']" :key="k" class="se-field">
          <label class="se-label">{{ k }}</label>
          <el-input-number
            :model-value="numValue(k)"
            :precision="isIntegerDataType(dataType) ? 0 : undefined"
            :step="1"
            :controls-position="'right'"
            :placeholder="k === 'step' ? '如 1' : k === 'min' ? '如 0' : '如 100'"
            :title="k === 'step' ? '步长，须大于 0' : k === 'min' ? '最小值' : '最大值'"
            style="width: 170px"
            @update:model-value="setNum(k, $event)"
          />
        </div>
      </div>
    </template>

    <!-- 文本类型：length 非负整数 -->
    <template v-else-if="dataType === 'text'">
      <div class="se-grid">
        <div class="se-field">
          <label class="se-label">length</label>
          <el-input-number
            :model-value="numValue('length')"
            :min="0"
            :precision="0"
            :step="1"
            :controls-position="'right'"
            placeholder="如 256"
            title="最大长度，非负整数"
            style="width: 170px"
            @update:model-value="setNum('length', $event)"
          />
        </div>
      </div>
    </template>

    <!-- 枚举类型：enumValues 表格 -->
    <template v-else-if="dataType === 'enum'">
      <el-table :data="enumValues()" size="small" border class="se-table">
        <el-table-column label="值" min-width="150">
          <template #default="{ row }">
            <el-input
              :model-value="String(row.value)"
              size="small"
              placeholder="0 / 1 / ON"
              @update:model-value="(v: string) => onEnumValueInput(row, v)"
            />
          </template>
        </el-table-column>
        <el-table-column label="类型" width="66" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="typeof row.value === 'number' ? 'primary' : 'info'">
              {{ typeof row.value === 'number' ? '数字' : '文本' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="说明" min-width="130">
          <template #default="{ row }">
            <el-input v-model="row.desc" size="small" placeholder="如 待机" />
          </template>
        </el-table-column>
        <el-table-column label="" width="50" align="center">
          <template #default="{ $index }">
            <el-button link type="danger" size="small" @click="removeEnumValue($index)">删</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-button size="small" type="primary" plain class="se-add" @click="addEnumValue">+ 添加枚举值</el-button>
    </template>

    <!-- 数组类型：elementType 下拉 + size 非负整数 -->
    <template v-else-if="dataType === 'array'">
      <div class="se-grid">
        <div class="se-field">
          <label class="se-label">elementType</label>
          <el-select v-model="elementTypeModel" placeholder="选择类型" clearable style="width: 170px">
            <el-option v-for="o in elementTypeOptions" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
        </div>
        <div class="se-field">
          <label class="se-label">size</label>
          <el-input-number
            :model-value="numValue('size')"
            :min="0"
            :precision="0"
            :step="1"
            :controls-position="'right'"
            placeholder="如 100"
            title="数组最大长度，非负整数"
            style="width: 170px"
            @update:model-value="setNum('size', $event)"
          />
        </div>
      </div>
    </template>

    <!-- 结构体类型：structFields 表格 -->
    <template v-else-if="dataType === 'struct'">
      <el-table :data="structFields()" size="small" border class="se-table">
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="se-expand">
              <!-- 嵌套 struct 防无限递归：depth >= 1 时提示 JSON 模式；其余类型行内继续可视化编辑规格 -->
              <SpecsEditor
                v-if="!(row.dataType === 'struct' && depth >= 1)"
                :data-type="row.dataType"
                :specs="rowSpecs(row)"
                :depth="depth + 1"
              />
              <p v-else class="se-tip">嵌套 struct 请使用「JSON 高级」模式编辑</p>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="标识符" min-width="110">
          <template #default="{ row }">
            <el-input v-model="row.identifier" size="small" placeholder="如 temp" />
          </template>
        </el-table-column>
        <el-table-column label="名称" min-width="100">
          <template #default="{ row }">
            <el-input v-model="row.name" size="small" placeholder="中文友好名" />
          </template>
        </el-table-column>
        <el-table-column label="类型" width="150">
          <template #default="{ row }">
            <el-select v-model="row.dataType" size="small">
              <el-option v-for="o in tsDataTypeOptions" :key="o.value" :label="o.label" :value="o.value" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="必填" width="60" align="center">
          <template #default="{ row }">
            <el-switch v-model="row.required" size="small" />
          </template>
        </el-table-column>
        <el-table-column label="" width="50" align="center">
          <template #default="{ $index }">
            <el-button link type="danger" size="small" @click="removeStructField($index)">删</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-button size="small" type="primary" plain class="se-add" @click="addStructField">+ 添加结构体字段</el-button>
      <p class="se-tip">点击行首箭头展开，可编辑该字段的类型规格（如数值范围）；嵌套 struct 请用 JSON 模式</p>
    </template>

    <!-- bool/date 等无类型专属规格 -->
    <template v-else>
      <p class="se-none">该类型无额外规格字段</p>
    </template>
  </div>
</template>

<style scoped>
.se-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}
.se-field {
  display: flex;
  align-items: center;
  gap: 6px;
}
.se-label {
  font-family: ui-monospace, 'Cascadia Mono', 'Consolas', monospace;
  font-size: 12px;
  color: var(--ex-ink-3);
  min-width: 14px;
}
.se-table {
  margin-bottom: 8px;
}
.se-add {
  margin-bottom: 4px;
}
.se-expand {
  padding: 8px 12px;
}
.se-tip {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--ex-ink-3);
}
.se-none {
  margin: 0;
  font-size: 12px;
  color: var(--ex-ink-3);
}
</style>
