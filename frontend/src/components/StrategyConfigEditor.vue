<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { EmsConstraint } from '@/types/models'
import {
  parseJsonConfig,
  parsePeakValleyConfig,
  serializePeakValley,
  validatePeakValleyConfig,
  type PeakValleyConfig,
} from '@/utils/strategyConfig'

const props = withDefaults(defineProps<{
  modelValue?: string
  strategyType?: string
  envelope?: EmsConstraint | null
}>(), {
  modelValue: '',
  strategyType: '',
  envelope: null,
})

const emit = defineEmits<{ (e: 'update:modelValue', value: string): void }>()

type Mode = 'form' | 'json'
const mode = ref<Mode>('form')
/** 结构化模式的可编辑窗口表（仅 PEAK_VALLEY 使用） */
const form = ref<PeakValleyConfig>({ chargeWindows: [], dischargeWindows: [] })
/** 上次成功解析保留的未知顶层键（socRange 等），序列化时合并回写 */
const rest = ref<Record<string, unknown>>({})
/** 阻断性校验问题（内联红显） */
const issues = ref<string[]>([])
/** JSON 模式实时语法错误（'' = 通过） */
const jsonError = ref('')
/** 非空不可解析 → 强制 JSON 模式的 info 提示开关 */
const forceJson = ref(false)

/** 电价驱动开关与功率（PEAK_VALLEY 结构化模式）；三键从 rest 读写，序列化经 rest 保留 */
const priceDriven = ref(false)
const chargePower = ref<number | undefined>(undefined)
const dischargePower = ref<number | undefined>(undefined)
/** initFromConfig 批量回填三键时临时禁用 watch，防多余 emit */
let initializing = false

const isPeakValley = computed(() => props.strategyType === 'PEAK_VALLEY')

/** JSON 模式示例占位（按策略类型给模板，DEMAND/TIME 走 JSON 编辑，见 P0-4） */
const jsonPlaceholder = computed(() => {
  switch (props.strategyType) {
    case 'DEMAND':
      return '{"chargeWindows":[{"start":"02:00","end":"06:00","powerLimit":100}],"dischargeWindows":[{"start":"08:00","end":"11:00","powerLimit":200}],"demandLimit":500}'
    case 'TIME':
      return '{"schedule":[{"start":"08:00","end":"09:00","action":"CHARGE","power":100},{"start":"14:00","end":"15:00","action":"DISCHARGE","power":80}]}'
    default:
      return '{"chargeWindows":[{"start":"02:00","end":"06:00","powerLimit":100}]}'
  }
})

/** JSON 模式 schema 提示（DEMAND/TIME 首行说明字段语义） */
const jsonHint = computed(() => {
  if (props.strategyType === 'DEMAND') {
    return '需量管理：chargeWindows 谷段充电备能；dischargeWindows 需量时段放电削峰；demandLimit 可选（kW）'
  }
  if (props.strategyType === 'TIME') {
    return '时间策略：schedule 为时间段数组，每段 {start, end, action: CHARGE/DISCHARGE/STANDBY, power}，至少一个充/放时段'
  }
  return ''
})

/** 包络软警告（不阻断）：窗口功率超站点安全约束上限 */
const warnings = computed(() => {
  const list: string[] = []
  if (!isPeakValley.value || !props.envelope) return list
  // 先取局部 const：属性收窄不跨 forEach 闭包（strict 下 "possibly null"），局部 const 收窄可跨闭包
  const chargeMax = props.envelope.chargePowerMax
  if (chargeMax != null) {
    form.value.chargeWindows.forEach((w, i) => {
      if (w.powerLimit > chargeMax) {
        list.push(`充电窗口 ${i + 1} 功率上限 ${w.powerLimit} kW 超过站点充电功率上限 ${chargeMax} kW`)
      }
    })
  }
  const dischargeMax = props.envelope.dischargePowerMax
  if (dischargeMax != null) {
    form.value.dischargeWindows.forEach((w, i) => {
      if (w.powerLimit > dischargeMax) {
        list.push(`放电窗口 ${i + 1} 功率上限 ${w.powerLimit} kW 超过站点放电功率上限 ${dischargeMax} kW`)
      }
    })
  }
  return list
})

/** 最近一次 emit 回写的 config；外部 modelValue === 它 = 自回写，跳过重解析防循环 */
let lastEmitted: string | null = null

function jsonErrorOf(config: string): string {
  if (!config.trim()) return '' // 空配置不是错误（新增/非峰谷初始态）
  const r = parseJsonConfig(config)
  return r.ok ? '' : r.error
}

/** 模式进入规则（spec §3.3 单一权威）：空→结构化空表；非空可解析→结构化；非空不可解析→强制 JSON。 */
function initFromConfig() {
  const raw = props.modelValue ?? ''
  if (!isPeakValley.value) {
    mode.value = 'json'
    jsonError.value = jsonErrorOf(raw)
    forceJson.value = false
    return
  }
  if (raw.trim() === '') {
    mode.value = 'form'
    form.value = { chargeWindows: [], dischargeWindows: [] }
    rest.value = {}
    // 空配置重置三键：组件跨弹窗复用（el-dialog 未 destroy-on-close），
    // 前一个 priceDriven 配置残留会令开关虚亮且 rest 无 priceDriven → 保存丢意图
    initializing = true
    priceDriven.value = false
    chargePower.value = undefined
    dischargePower.value = undefined
    initializing = false
    issues.value = []
    forceJson.value = false
    return
  }
  const parsed = parseJsonConfig(raw)
  if (!parsed.ok) {
    mode.value = 'json'
    jsonError.value = parsed.error
    forceJson.value = true
    return
  }
  const structured = parsePeakValleyConfig(parsed.value)
  if (!structured.ok) {
    mode.value = 'json'
    jsonError.value = structured.error
    forceJson.value = true
    return
  }
  mode.value = 'form'
  form.value = structured.config
  rest.value = structured.rest
  initializing = true
  priceDriven.value = structured.rest.priceDriven === true
  chargePower.value = typeof structured.rest.chargePower === 'number' ? (structured.rest.chargePower as number) : undefined
  dischargePower.value = typeof structured.rest.dischargePower === 'number' ? (structured.rest.dischargePower as number) : undefined
  initializing = false
  issues.value = []
  forceJson.value = false
}

function emitConfig() {
  const s = serializePeakValley(form.value, rest.value)
  lastEmitted = s
  emit('update:modelValue', s)
}

function switchMode(m: Mode) {
  if (m === mode.value) return
  if (m === 'json') {
    if (isPeakValley.value) emitConfig() // 结构化 → JSON：当前表单序列化入文本域
    jsonError.value = jsonErrorOf(props.modelValue)
    mode.value = 'json'
  } else {
    // JSON → 结构化：重新 parse，失败保持 JSON 模式
    const parsed = parseJsonConfig(props.modelValue)
    if (!parsed.ok) {
      ElMessage.error(parsed.error)
      return
    }
    const structured = parsePeakValleyConfig(parsed.value)
    if (!structured.ok) {
      ElMessage.error(structured.error)
      return
    }
    form.value = structured.config
    rest.value = structured.rest
    initializing = true
    priceDriven.value = structured.rest.priceDriven === true
    chargePower.value = typeof structured.rest.chargePower === 'number' ? (structured.rest.chargePower as number) : undefined
    dischargePower.value = typeof structured.rest.dischargePower === 'number' ? (structured.rest.dischargePower as number) : undefined
    initializing = false
    forceJson.value = false
    mode.value = 'form'
  }
}

function onJsonInput(v: string) {
  lastEmitted = v
  emit('update:modelValue', v)
  jsonError.value = jsonErrorOf(v)
}

function formatJson() {
  const r = parseJsonConfig(props.modelValue)
  if (!r.ok) {
    ElMessage.error(r.error)
    return
  }
  const s = JSON.stringify(r.value, null, 2)
  lastEmitted = s
  emit('update:modelValue', s)
  jsonError.value = ''
}

function addWindow(kind: 'chargeWindows' | 'dischargeWindows') {
  form.value[kind].push({ start: '00:00', end: '01:00', powerLimit: 100 })
}

function removeWindow(kind: 'chargeWindows' | 'dischargeWindows', index: number) {
  form.value[kind].splice(index, 1)
}

const windowGroups = [
  { key: 'chargeWindows' as const, label: '充电窗口', addLabel: '添加充电窗口' },
  { key: 'dischargeWindows' as const, label: '放电窗口', addLabel: '添加放电窗口' },
]

watch(
  () => props.modelValue,
  (val) => {
    if (val === lastEmitted) return // 自 emit 回写，避免循环
    lastEmitted = null
    initFromConfig()
  },
)

watch(
  () => props.strategyType,
  () => initFromConfig(),
)

watch(
  form,
  () => {
    issues.value = validatePeakValleyConfig(serializePeakValley(form.value, rest.value))
    emitConfig()
  },
  { deep: true },
)

watch([priceDriven, chargePower, dischargePower], () => {
  if (initializing || !isPeakValley.value) return
  const next: Record<string, unknown> = { ...rest.value, priceDriven: priceDriven.value }
  if (chargePower.value !== undefined) next.chargePower = chargePower.value
  else delete next.chargePower
  if (dischargePower.value !== undefined) next.dischargePower = dischargePower.value
  else delete next.dischargePower
  rest.value = next
  emitConfig()
})

initFromConfig()
</script>

<template>
  <div class="strategy-config-editor">
    <div v-if="isPeakValley" class="mode-bar">
      <el-radio-group :model-value="mode" size="small" @update:model-value="switchMode($event as Mode)">
        <el-radio-button value="form">结构化编辑</el-radio-button>
        <el-radio-button value="json">JSON 模式</el-radio-button>
      </el-radio-group>
    </div>

    <!-- 结构化模式（仅 PEAK_VALLEY 可达） -->
    <template v-if="isPeakValley && mode === 'form'">
      <div class="price-drive-bar">
        <span class="group-label">电价驱动</span>
        <el-switch v-model="priceDriven" size="small" />
        <span class="drive-hint">开启后按分时电价自动推导谷充峰放窗口</span>
      </div>

      <div v-if="priceDriven" class="power-fields">
        <div class="power-row">
          <span class="group-label">充电功率</span>
          <el-input-number v-model="chargePower" :min="0.1" :precision="1" :step="1" :placeholder="'留空回退包络上限'" style="width: 140px" />
          <span class="unit">kW</span>
        </div>
        <div class="power-row">
          <span class="group-label">放电功率</span>
          <el-input-number v-model="dischargePower" :min="0.1" :precision="1" :step="1" :placeholder="'留空回退包络上限'" style="width: 140px" />
          <span class="unit">kW</span>
        </div>
        <el-alert v-if="warnings.length" type="warning" :closable="false" class="warn-alert" :title="warnings.join('；')" />
      </div>

      <template v-else>
        <div v-for="group in windowGroups" :key="group.key" class="window-group">
          <div class="group-head">
            <span class="group-label">{{ group.label }}</span>
            <el-button link type="primary" size="small" @click="addWindow(group.key)">{{ group.addLabel }}</el-button>
          </div>
          <div v-if="form[group.key].length === 0" class="group-empty">暂无窗口</div>
          <div v-for="(w, i) in form[group.key]" :key="i" class="window-row">
            <el-time-picker v-model="w.start" format="HH:mm" value-format="HH:mm" placeholder="开始" :clearable="false" style="width: 100px" />
            <span class="sep">至</span>
            <el-time-picker v-model="w.end" format="HH:mm" value-format="HH:mm" placeholder="结束" :clearable="false" style="width: 100px" />
            <el-input-number v-model="w.powerLimit" :min="0.1" :precision="1" :step="1" style="width: 120px" />
            <span class="unit">kW</span>
            <el-button link type="danger" size="small" @click="removeWindow(group.key, i)">删除</el-button>
          </div>
        </div>
        <el-alert
          v-if="issues.length"
          type="error"
          :closable="false"
          class="block-alert"
          :title="issues.join('；')"
        />
        <el-alert
          v-if="warnings.length"
          type="warning"
          :closable="false"
          class="warn-alert"
          :title="warnings.join('；')"
        />
      </template>
    </template>

    <!-- JSON 模式（非 PEAK_VALLEY 恒为 JSON；PEAK_VALLEY 切出/不可解析时） -->
    <template v-else-if="mode === 'json'">
      <el-alert
        v-if="isPeakValley && forceJson"
        type="info"
        :closable="false"
        class="mode-alert"
        title="配置无法解析，已切换到 JSON 模式（结构化编辑会覆盖原配置）"
      />
      <el-alert v-if="jsonHint" type="info" :closable="false" class="mode-alert" :title="jsonHint" />
      <el-input
        :model-value="props.modelValue"
        type="textarea"
        :rows="6"
        :placeholder="jsonPlaceholder"
        @update:model-value="onJsonInput($event as string)"
      />
      <div class="json-toolbar">
        <span class="json-state" :class="jsonError ? 'err' : 'ok'">
          {{ jsonError || 'JSON 语法正确' }}
        </span>
        <el-button link type="primary" size="small" @click="formatJson">格式化</el-button>
      </div>
    </template>
  </div>
</template>

<style scoped>
.mode-bar {
  margin-bottom: 8px;
}
.mode-alert {
  margin-bottom: 8px;
}
.window-group {
  margin-bottom: 10px;
}
.group-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}
.group-label {
  font-weight: 600;
  font-size: 13px;
}
.group-empty {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  padding: 6px 0;
}
.window-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 6px;
}
.sep {
  color: var(--el-text-color-secondary);
}
.unit {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.block-alert {
  margin-top: 8px;
}
.warn-alert {
  margin-top: 8px;
}
.price-drive-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}
.drive-hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.power-fields {
  margin-bottom: 8px;
}
.power-row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
}
.json-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 6px;
  font-size: 12px;
}
.json-state.ok {
  color: var(--el-color-success);
}
.json-state.err {
  color: var(--el-color-danger);
}
</style>
