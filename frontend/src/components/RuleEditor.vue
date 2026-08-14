<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { deviceApi } from '@/api/device'
import { productApi } from '@/api/product'
import { ruleApi } from '@/api/rule'
import type { Device, Product, RuleAction, RuleCondition, RuleConfig, RuleOp, RuleRecovery, RuleTrigger, RuleView, TsProperty } from '@/types/models'
import { opOptions, triggerTypeOptions, conditionTypeOptions, actionTypeOptions } from '@/utils/ruleOptions'

const props = withDefaults(defineProps<{
  modelValue: boolean
  /** 编辑模式（null=新建） */
  editing?: RuleView | null
}>(), {
  editing: null,
})

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'saved'): void
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v),
})

// ---------------- 基础信息 ----------------
const base = reactive({
  ruleCode: '',
  ruleName: '',
  description: '',
  debounceSeconds: 300,
  priority: 100,
})

const dsl = reactive<RuleConfig>({
  triggers: [],
  conditions: [],
  actions: [],
  recovery: null,
})

const saving = ref(false)
const version = ref<number | undefined>(undefined)
const ruleId = ref<number | null>(null)

// ---------------- 产品/设备/物模型联动 ----------------
const productOptions = ref<Product[]>([])
const deviceOptions = ref<Device[]>([])
const ruleOptions = ref<RuleView[]>([])

/** 物模型属性缓存：productKey → properties */
const modelCache = new Map<string, TsProperty[]>()

async function loadProducts(): Promise<void> {
  try {
    const page = await productApi.page({ pageNum: 1, pageSize: 200 })
    productOptions.value = page.records ?? []
  } catch (e) {
    ElMessage.error(`产品加载失败：${e instanceof Error ? e.message : String(e)}`)
  }
}

async function loadDevices(productKey: string): Promise<Device[]> {
  if (!productKey) return []
  try {
    const page = await deviceApi.page({ pageNum: 1, pageSize: 200, productKey })
    return page.records ?? []
  } catch {
    return []
  }
}

async function loadThingModel(productKey: string): Promise<TsProperty[]> {
  if (!productKey) return []
  if (modelCache.has(productKey)) return modelCache.get(productKey)!
  try {
    const view = await productApi.thingModelByKey(productKey)
    const schema = JSON.parse(view.schemaJson) as { properties?: TsProperty[] }
    const propsList = schema.properties ?? []
    modelCache.set(productKey, propsList)
    return propsList
  } catch {
    return []
  }
}

function modelPropsFor(productKey: string): TsProperty[] {
  return modelCache.get(productKey) ?? []
}

/** 选择产品后预载设备与物模型（异步，不阻塞保存） */
async function ensureModelLoaded(productKey: string): Promise<void> {
  if (!productKey) return
  deviceOptions.value = await loadDevices(productKey)
  await loadThingModel(productKey)
}

// ---------------- 触发器 ----------------
function addTrigger(): void {
  dsl.triggers.push({ type: 'PROPERTY', device: { productKey: '', deviceName: '' }, op: 'GT' })
}
function removeTrigger(i: number): void {
  dsl.triggers.splice(i, 1)
}

/** 触发器切换类型后初始化默认字段 */
function onTriggerTypeChange(t: RuleTrigger): void {
  t.device = t.type === 'PROPERTY' || t.type === 'LIFECYCLE' ? { productKey: '', deviceName: '' } : undefined
  if (t.type === 'PROPERTY') {
    t.op = 'GT'
    t.property = ''
  } else if (t.type === 'TIMER') {
    t.cron = '0 0 22 * * ?'
  } else if (t.type === 'LIFECYCLE') {
    t.event = 'ONLINE'
  } else if (t.type === 'ALARM') {
    t.state = 'ACTIVE'
  }
}

// ---------------- 条件 ----------------
function addCondition(): void {
  dsl.conditions.push({ type: 'TIME_RANGE', start: '00:00', end: '23:59' })
}
function removeCondition(i: number): void {
  dsl.conditions.splice(i, 1)
}
function onConditionTypeChange(c: RuleCondition): void {
  c.device = c.type === 'DEVICE_STATUS' || c.type === 'PROPERTY' ? { productKey: '', deviceName: '' } : undefined
  if (c.type === 'TIME_RANGE') {
    c.start = '00:00'
    c.end = '23:59'
  } else if (c.type === 'DEVICE_STATUS') {
    c.status = 'ONLINE'
  } else if (c.type === 'PROPERTY') {
    c.op = 'GT'
    c.property = ''
  }
}

// ---------------- 动作 ----------------
function addAction(): void {
  dsl.actions.push({ type: 'DEVICE_COMMAND', device: { productKey: '', deviceName: '' } })
}
function removeAction(i: number): void {
  dsl.actions.splice(i, 1)
}
function onActionTypeChange(a: RuleAction): void {
  a.device = a.type === 'DEVICE_COMMAND' ? { productKey: '', deviceName: '' } : undefined
  if (a.type === 'ALARM') a.severity = 3
  if (a.type === 'NOTIFY') a.channel = 'WEBHOOK'
}

// ---------------- 恢复配置 ----------------
const recoveryEnabled = ref(false)
const recoveryForm = reactive<RuleRecovery>({ property: '', op: 'LTE', value: '', actions: [] })

function addRecoveryAction(): void {
  recoveryForm.actions.push({ type: 'DEVICE_COMMAND', device: { productKey: '', deviceName: '' } })
}
function removeRecoveryAction(i: number): void {
  recoveryForm.actions.splice(i, 1)
}
function onRecoveryActionTypeChange(a: RuleAction): void {
  onActionTypeChange(a)
}

/** 打开抽屉时初始化（编辑回填 / 新建空表单） */
watch(visible, (open) => {
  if (!open) return
  void initEditor()
})

/** 阈值输入（string|number → string） */
function valueText(v: string | number | null | undefined): string {
  return v === null || v === undefined ? '' : String(v)
}

/** 动作参数 JSON 文本化 */
function paramsText(a: RuleAction): string {
  return a.params ? JSON.stringify(a.params) : ''
}

/** 动作参数文本 → 对象（非法 JSON 原样保存，后端容错） */
function setParams(a: RuleAction, s: string): void {
  if (!s.trim()) {
    a.params = null
    return
  }
  try {
    a.params = JSON.parse(s) as Record<string, unknown>
  } catch {
    a.params = s as unknown as Record<string, unknown>
  }
}

async function initEditor(): Promise<void> {
  deviceOptions.value = []
  const editing = props.editing
  if (editing) {
    ruleId.value = editing.ruleId
    version.value = editing.version
    base.ruleCode = editing.ruleCode
    base.ruleName = editing.ruleName
    base.description = editing.description ?? ''
    base.debounceSeconds = editing.debounceSeconds
    base.priority = editing.priority
    const d = editing.dsl
    dsl.triggers = d.triggers.map((t) => ({ ...t, device: t.device ? { ...t.device } : undefined }))
    dsl.conditions = d.conditions.map((c) => ({ ...c, device: c.device ? { ...c.device } : undefined }))
    dsl.actions = d.actions.map((a) => ({ ...a, device: a.device ? { ...a.device } : undefined, params: a.params ? { ...a.params } : undefined }))
    dsl.recovery = d.recovery
      ? { ...d.recovery, actions: d.recovery.actions.map((a) => ({ ...a, device: a.device ? { ...a.device } : undefined })) }
      : null
    recoveryEnabled.value = !!d.recovery
    if (d.recovery) {
      recoveryForm.property = d.recovery.property
      recoveryForm.op = d.recovery.op
      recoveryForm.value = String(d.recovery.value)
      recoveryForm.actions = d.recovery.actions.map((a) => ({ ...a, device: a.device ? { ...a.device } : undefined }))
    }
    // 预载设备与物模型（编辑回填用）
    for (const t of dsl.triggers) {
      if (t.device?.productKey) await ensureModelLoaded(t.device.productKey)
    }
  } else {
    ruleId.value = null
    version.value = undefined
    base.ruleCode = ''
    base.ruleName = ''
    base.description = ''
    base.debounceSeconds = 300
    base.priority = 100
    dsl.triggers = []
    dsl.conditions = []
    dsl.actions = []
    dsl.recovery = null
    recoveryEnabled.value = false
    recoveryForm.property = ''
    recoveryForm.op = 'LTE'
    recoveryForm.value = ''
    recoveryForm.actions = []
  }
}

/** 校验（对齐后端 DslValidator 语义）并提示首个问题 */
function validate(): string {
  if (!base.ruleCode.trim()) return '规则编码不能为空'
  if (!base.ruleName.trim()) return '规则名称不能为空'
  if (dsl.triggers.length === 0) return '至少需要 1 个触发器'
  for (const t of dsl.triggers) {
    if (t.type === 'PROPERTY' && (!t.device?.productKey || !t.property || !t.value)) return 'PROPERTY 触发器需选择产品/属性并填阈值'
    if (t.type === 'TIMER' && !t.cron) return 'TIMER 触发器需填写 cron'
  }
  if (dsl.actions.length === 0) return '至少需要 1 个动作'
  for (const a of dsl.actions) {
    if (a.type === 'DEVICE_COMMAND' && (!a.device?.productKey || !a.command)) return 'DEVICE_COMMAND 动作需选择产品并填命令标识'
    if (a.type === 'ALARM' && !a.ruleCode) return 'ALARM 动作需填告警编码'
    if (a.type === 'NOTIFY' && !a.url) return 'NOTIFY 动作需填 webhook 地址'
    if (a.type === 'RULE' && !a.ruleId) return 'RULE 动作需选择目标规则'
  }
  return ''
}

async function save(): Promise<void> {
  const issue = validate()
  if (issue) {
    ElMessage.warning(issue)
    return
  }
  const config: RuleConfig = {
    dslVersion: 1,
    name: base.ruleName,
    triggers: dsl.triggers.map((t) => normalizeTrigger(t)),
    conditions: dsl.conditions.map((c) => normalizeCondition(c)),
    actions: dsl.actions.map((a) => normalizeAction(a)),
    recovery: recoveryEnabled.value ? normalizeRecovery() : null,
  }
  saving.value = true
  try {
    if (ruleId.value) {
      await ruleApi.update(ruleId.value, {
        ruleCode: base.ruleCode.trim(),
        ruleName: base.ruleName.trim(),
        description: base.description.trim() || null,
        dsl: config,
        debounceSeconds: base.debounceSeconds,
        priority: base.priority,
        version: version.value,
      })
    } else {
      await ruleApi.create({
        ruleCode: base.ruleCode.trim(),
        ruleName: base.ruleName.trim(),
        description: base.description.trim() || null,
        dsl: config,
        debounceSeconds: base.debounceSeconds,
        priority: base.priority,
        enabled: true,
      })
    }
    ElMessage.success(ruleId.value ? '规则已更新' : '规则已创建')
    visible.value = false
    emit('saved')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    saving.value = false
  }
}

function normalizeTrigger(t: RuleTrigger): RuleTrigger {
  const copy: RuleTrigger = { type: t.type }
  if (t.device?.productKey) copy.device = { productKey: t.device.productKey, deviceName: t.device.deviceName || null }
  if (t.property) copy.property = t.property
  if (t.op) copy.op = t.op
  if (t.value !== undefined && t.value !== null && t.value !== '') copy.value = t.value
  if (t.cron) copy.cron = t.cron
  if (t.event) copy.event = t.event
  if (t.alarmCode) copy.alarmCode = t.alarmCode
  if (t.level) copy.level = t.level
  if (t.state) copy.state = t.state
  return copy
}

function normalizeCondition(c: RuleCondition): RuleCondition {
  const copy: RuleCondition = { type: c.type }
  if (c.device?.productKey) copy.device = { productKey: c.device.productKey, deviceName: c.device.deviceName || null }
  if (c.status) copy.status = c.status
  if (c.start) copy.start = c.start
  if (c.end) copy.end = c.end
  if (c.property) copy.property = c.property
  if (c.op) copy.op = c.op
  if (c.value !== undefined && c.value !== null && c.value !== '') copy.value = c.value
  return copy
}

function normalizeAction(a: RuleAction): RuleAction {
  const copy: RuleAction = { type: a.type }
  if (a.device?.productKey) copy.device = { productKey: a.device.productKey, deviceName: a.device.deviceName || null }
  if (a.command) copy.command = a.command
  if (a.params && Object.keys(a.params).length > 0) copy.params = a.params
  if (a.timeoutMs) copy.timeoutMs = a.timeoutMs
  if (a.maxRetry) copy.maxRetry = a.maxRetry
  if (a.ruleCode) copy.ruleCode = a.ruleCode
  if (a.severity) copy.severity = a.severity
  if (a.message) copy.message = a.message
  if (a.channel) copy.channel = a.channel
  if (a.url) copy.url = a.url
  if (a.headers && Object.keys(a.headers).length > 0) copy.headers = a.headers
  if (a.template) copy.template = a.template
  if (a.ruleId) copy.ruleId = a.ruleId
  return copy
}

function normalizeRecovery(): RuleRecovery {
  return {
    property: recoveryForm.property.trim(),
    op: recoveryForm.op as RuleOp,
    value: recoveryForm.value,
    actions: recoveryForm.actions.map((a) => normalizeAction(a)),
  }
}

async function loadRuleOptions(): Promise<void> {
  try {
    const page = await ruleApi.page({ page: 1, size: 200 })
    ruleOptions.value = page.records ?? []
  } catch {
    ruleOptions.value = []
  }
}

onMounted(() => {
  void loadProducts()
  void loadRuleOptions()
})
</script>

<template>
  <el-drawer
    v-model="visible"
    :title="ruleId ? `编辑场景规则 · ${base.ruleName}` : '新建场景规则'"
    size="720px"
    destroy-on-close
  >
    <div class="editor">
      <!-- 基础信息 -->
      <section class="editor-sec">
        <h3 class="sec-title">基础信息</h3>
        <el-form label-width="90px" label-position="right">
          <el-form-item label="规则编码" required>
            <el-input v-model="base.ruleCode" placeholder="如 SCENE_TEMP_HIGH（租户内唯一）" :disabled="!!ruleId" />
          </el-form-item>
          <el-form-item label="规则名称" required>
            <el-input v-model="base.ruleName" placeholder="如 电芯高温降功率" />
          </el-form-item>
          <el-form-item label="描述">
            <el-input v-model="base.description" type="textarea" :rows="2" placeholder="可选" />
          </el-form-item>
          <el-form-item label="防抖(秒)">
            <el-input-number v-model="base.debounceSeconds" :min="0" :max="86400" />
            <span class="field-hint">窗口期内同一规则+设备只执行一次动作</span>
          </el-form-item>
          <el-form-item label="优先级">
            <el-input-number v-model="base.priority" :min="1" :max="1000" />
            <span class="field-hint">数值小优先（当前引擎按候选顺序匹配）</span>
          </el-form-item>
        </el-form>
      </section>

      <!-- 触发器（OR） -->
      <section class="editor-sec">
        <div class="sec-head">
          <h3 class="sec-title">触发器（多触发器 OR，任一命中）</h3>
          <el-button size="small" type="primary" plain @click="addTrigger">+ 添加触发器</el-button>
        </div>
        <div v-for="(t, i) in dsl.triggers" :key="i" class="item-card">
          <div class="item-head">
            <span class="item-index">T{{ i + 1 }}</span>
            <el-select v-model="t.type" size="small" style="width: 130px" @change="onTriggerTypeChange(t)">
              <el-option v-for="o in triggerTypeOptions" :key="o.value" :label="o.label" :value="o.value" />
            </el-select>
            <el-button link type="danger" size="small" @click="removeTrigger(i)">删除</el-button>
          </div>
          <div v-if="t.type === 'PROPERTY'" class="item-body">
            <el-select v-model="t.device!.productKey" placeholder="产品" filterable size="small" style="width: 160px" @change="ensureModelLoaded(t.device!.productKey)">
              <el-option v-for="p in productOptions" :key="p.productKey" :label="p.productName" :value="p.productKey" />
            </el-select>
            <el-select v-model="t.device!.deviceName" placeholder="设备（空=产品级）" clearable filterable size="small" style="width: 150px">
              <el-option v-for="d in deviceOptions.filter((x) => x.productKey === t.device!.productKey)" :key="d.deviceId" :label="d.deviceName" :value="d.deviceName" />
            </el-select>
            <el-select v-model="t.property" placeholder="属性" filterable size="small" style="width: 150px" :disabled="!t.device!.productKey">
              <el-option v-for="p in modelPropsFor(t.device!.productKey)" :key="p.identifier" :label="`${p.name} (${p.identifier})`" :value="p.identifier" />
            </el-select>
            <el-select v-model="t.op" size="small" style="width: 90px">
              <el-option v-for="o in opOptions" :key="o.value" :label="o.label" :value="o.value" />
            </el-select>
            <el-input :model-value="valueText(t.value)" size="small" style="width: 110px" placeholder="阈值" @update:model-value="(v: string) => (t.value = v)" />
          </div>
          <div v-else-if="t.type === 'TIMER'" class="item-body">
            <el-input v-model="t.cron" size="small" style="width: 240px" placeholder="6 位 cron：秒 分 时 日 月 周" />
            <span class="field-hint">如 0 30 22 * * ?（每天 22:30）</span>
          </div>
          <div v-else-if="t.type === 'LIFECYCLE'" class="item-body">
            <el-select v-model="t.device!.productKey" placeholder="产品（空=全设备）" clearable filterable size="small" style="width: 160px" @change="ensureModelLoaded(t.device!.productKey)">
              <el-option v-for="p in productOptions" :key="p.productKey" :label="p.productName" :value="p.productKey" />
            </el-select>
            <el-select v-model="t.device!.deviceName" placeholder="设备（空=全部）" clearable filterable size="small" style="width: 150px">
              <el-option v-for="d in deviceOptions.filter((x) => x.productKey === t.device!.productKey)" :key="d.deviceId" :label="d.deviceName" :value="d.deviceName" />
            </el-select>
            <el-select v-model="t.event" size="small" style="width: 110px">
              <el-option label="上线 ONLINE" value="ONLINE" />
              <el-option label="下线 OFFLINE" value="OFFLINE" />
            </el-select>
          </div>
          <div v-else-if="t.type === 'ALARM'" class="item-body">
            <el-input v-model="t.alarmCode" size="small" style="width: 180px" placeholder="告警码（可空）" />
            <el-select v-model="t.level" size="small" style="width: 100px" clearable placeholder="级别">
              <el-option label="提示" :value="1" />
              <el-option label="一般" :value="2" />
              <el-option label="严重" :value="3" />
              <el-option label="危急" :value="4" />
            </el-select>
            <el-select v-model="t.state" size="small" style="width: 120px">
              <el-option label="触发 ACTIVE" value="ACTIVE" />
              <el-option label="恢复 RECOVER" value="RECOVER" />
            </el-select>
          </div>
          <div v-else class="item-body">
            <span class="field-hint">手动触发：由「手动触发」按钮/API 直接驱动，无附加字段</span>
          </div>
        </div>
      </section>

      <!-- 条件（AND） -->
      <section class="editor-sec">
        <div class="sec-head">
          <h3 class="sec-title">执行条件（多条件 AND，空=恒真）</h3>
          <el-button size="small" type="primary" plain @click="addCondition">+ 添加条件</el-button>
        </div>
        <div v-for="(c, i) in dsl.conditions" :key="i" class="item-card">
          <div class="item-head">
            <span class="item-index">C{{ i + 1 }}</span>
            <el-select v-model="c.type" size="small" style="width: 140px" @change="onConditionTypeChange(c)">
              <el-option v-for="o in conditionTypeOptions" :key="o.value" :label="o.label" :value="o.value" />
            </el-select>
            <el-button link type="danger" size="small" @click="removeCondition(i)">删除</el-button>
          </div>
          <div v-if="c.type === 'DEVICE_STATUS'" class="item-body">
            <el-select v-model="c.device!.productKey" placeholder="产品" filterable size="small" style="width: 160px" @change="ensureModelLoaded(c.device!.productKey)">
              <el-option v-for="p in productOptions" :key="p.productKey" :label="p.productName" :value="p.productKey" />
            </el-select>
            <el-select v-model="c.device!.deviceName" placeholder="设备" filterable size="small" style="width: 150px">
              <el-option v-for="d in deviceOptions.filter((x) => x.productKey === c.device!.productKey)" :key="d.deviceId" :label="d.deviceName" :value="d.deviceName" />
            </el-select>
            <el-select v-model="c.status" size="small" style="width: 120px">
              <el-option label="在线 ONLINE" value="ONLINE" />
              <el-option label="离线 OFFLINE" value="OFFLINE" />
            </el-select>
          </div>
          <div v-else-if="c.type === 'TIME_RANGE'" class="item-body">
            <el-time-select v-model="c.start" start="00:00" step="00:15" end="23:45" size="small" style="width: 120px" placeholder="开始" />
            <span class="field-hint">至</span>
            <el-time-select v-model="c.end" start="00:00" step="00:15" end="23:45" size="small" style="width: 120px" placeholder="结束" />
            <span class="field-hint">支持跨零点（开始 &gt; 结束 视为夜间区间）</span>
          </div>
          <div v-else-if="c.type === 'PROPERTY'" class="item-body">
            <el-select v-model="c.device!.productKey" placeholder="产品" filterable size="small" style="width: 160px" @change="ensureModelLoaded(c.device!.productKey)">
              <el-option v-for="p in productOptions" :key="p.productKey" :label="p.productName" :value="p.productKey" />
            </el-select>
            <el-select v-model="c.device!.deviceName" placeholder="设备" filterable size="small" style="width: 150px">
              <el-option v-for="d in deviceOptions.filter((x) => x.productKey === c.device!.productKey)" :key="d.deviceId" :label="d.deviceName" :value="d.deviceName" />
            </el-select>
            <el-select v-model="c.property" placeholder="属性" filterable size="small" style="width: 150px" :disabled="!c.device!.productKey">
              <el-option v-for="p in modelPropsFor(c.device!.productKey)" :key="p.identifier" :label="`${p.name} (${p.identifier})`" :value="p.identifier" />
            </el-select>
            <el-select v-model="c.op" size="small" style="width: 90px">
              <el-option v-for="o in opOptions" :key="o.value" :label="o.label" :value="o.value" />
            </el-select>
            <el-input :model-value="valueText(c.value)" size="small" style="width: 110px" placeholder="阈值" @update:model-value="(v: string) => (c.value = v)" />
          </div>
        </div>
      </section>

      <!-- 动作 -->
      <section class="editor-sec">
        <div class="sec-head">
          <h3 class="sec-title">执行动作（多动作独立执行）</h3>
          <el-button size="small" type="primary" plain @click="addAction">+ 添加动作</el-button>
        </div>
        <div v-for="(a, i) in dsl.actions" :key="i" class="item-card">
          <div class="item-head">
            <span class="item-index">A{{ i + 1 }}</span>
            <el-select v-model="a.type" size="small" style="width: 150px" @change="onActionTypeChange(a)">
              <el-option v-for="o in actionTypeOptions" :key="o.value" :label="o.label" :value="o.value" />
            </el-select>
            <el-button link type="danger" size="small" @click="removeAction(i)">删除</el-button>
          </div>
          <div v-if="a.type === 'DEVICE_COMMAND'" class="item-body">
            <el-select v-model="a.device!.productKey" placeholder="产品" filterable size="small" style="width: 160px" @change="ensureModelLoaded(a.device!.productKey)">
              <el-option v-for="p in productOptions" :key="p.productKey" :label="p.productName" :value="p.productKey" />
            </el-select>
            <el-select v-model="a.device!.deviceName" placeholder="设备" filterable size="small" style="width: 150px">
              <el-option v-for="d in deviceOptions.filter((x) => x.productKey === a.device!.productKey)" :key="d.deviceId" :label="d.deviceName" :value="d.deviceName" />
            </el-select>
            <el-input v-model="a.command" size="small" style="width: 150px" placeholder="命令标识，如 setPower" />
            <el-input :model-value="paramsText(a)" size="small" style="width: 220px" placeholder='参数 JSON，如 {"power":30}' @update:model-value="(v: string) => setParams(a, v)" />
          </div>
          <div v-else-if="a.type === 'ALARM'" class="item-body">
            <el-input v-model="a.ruleCode" size="small" style="width: 180px" placeholder="告警编码，如 SCENE_TEMP_HIGH" />
            <el-select v-model="a.severity" size="small" style="width: 100px">
              <el-option label="提示" :value="1" />
              <el-option label="一般" :value="2" />
              <el-option label="严重" :value="3" />
              <el-option label="危急" :value="4" />
            </el-select>
            <el-input v-model="a.message" size="small" style="width: 220px" placeholder="告警内容（可空）" />
          </div>
          <div v-else-if="a.type === 'NOTIFY'" class="item-body">
            <el-input v-model="a.url" size="small" style="width: 260px" placeholder="webhook 地址 https://..." />
            <el-input v-model="a.template" size="small" style="width: 300px" placeholder='模板，如 温度 ${property.cellTemp}℃ 超限' />
          </div>
          <div v-else-if="a.type === 'RULE'" class="item-body">
            <el-select v-model="a.ruleId" placeholder="目标规则（嵌套执行，防环+深度≤5）" filterable size="small" style="width: 320px">
              <el-option v-for="r in ruleOptions.filter((x) => x.ruleId !== ruleId)" :key="r.ruleId" :label="`${r.ruleCode} · ${r.ruleName}`" :value="r.ruleId" />
            </el-select>
          </div>
        </div>
      </section>

      <!-- 恢复配置 -->
      <section class="editor-sec">
        <div class="sec-head">
          <h3 class="sec-title">恢复配置（可选）</h3>
          <el-switch v-model="recoveryEnabled" />
        </div>
        <div v-if="recoveryEnabled" class="item-card">
          <div class="item-body">
            <el-select v-model="recoveryForm.property" placeholder="属性" filterable size="small" style="width: 160px" :disabled="dsl.triggers.every((t) => !t.property)">
              <el-option v-for="t in dsl.triggers.filter((x) => x.type === 'PROPERTY' && x.property)" :key="t.property" :label="t.property" :value="t.property" />
            </el-select>
            <el-select v-model="recoveryForm.op" size="small" style="width: 90px">
              <el-option v-for="o in opOptions" :key="o.value" :label="o.label" :value="o.value" />
            </el-select>
            <el-input :model-value="valueText(recoveryForm.value)" size="small" style="width: 110px" placeholder="恢复阈值" @update:model-value="(v: string) => (recoveryForm.value = v)" />
            <span class="field-hint">条件从满足回到不满足时执行恢复动作（不受防抖限制）</span>
          </div>
          <div class="item-sub">
            <span class="sub-label">恢复动作：</span>
            <el-button size="small" type="primary" plain @click="addRecoveryAction">+ 添加</el-button>
          </div>
          <div v-for="(ra, ri) in recoveryForm.actions" :key="ri" class="item-card inner">
            <div class="item-head">
              <span class="item-index">R{{ ri + 1 }}</span>
              <el-select v-model="ra.type" size="small" style="width: 150px" @change="onRecoveryActionTypeChange(ra)">
                <el-option v-for="o in actionTypeOptions" :key="o.value" :label="o.label" :value="o.value" />
              </el-select>
              <el-button link type="danger" size="small" @click="removeRecoveryAction(ri)">删除</el-button>
            </div>
            <div v-if="ra.type === 'DEVICE_COMMAND'" class="item-body">
              <el-select v-model="ra.device!.productKey" placeholder="产品" filterable size="small" style="width: 160px" @change="ensureModelLoaded(ra.device!.productKey)">
                <el-option v-for="p in productOptions" :key="p.productKey" :label="p.productName" :value="p.productKey" />
              </el-select>
              <el-select v-model="ra.device!.deviceName" placeholder="设备" filterable size="small" style="width: 150px">
                <el-option v-for="d in deviceOptions.filter((x) => x.productKey === ra.device!.productKey)" :key="d.deviceId" :label="d.deviceName" :value="d.deviceName" />
              </el-select>
              <el-input v-model="ra.command" size="small" style="width: 150px" placeholder="命令标识" />
              <el-input :model-value="paramsText(ra)" size="small" style="width: 200px" placeholder='参数 JSON' @update:model-value="(v: string) => setParams(ra, v)" />
            </div>
            <div v-else-if="ra.type === 'ALARM'" class="item-body">
              <el-input v-model="ra.ruleCode" size="small" style="width: 180px" placeholder="告警编码" />
              <el-select v-model="ra.severity" size="small" style="width: 100px">
                <el-option label="提示" :value="1" />
                <el-option label="一般" :value="2" />
                <el-option label="严重" :value="3" />
                <el-option label="危急" :value="4" />
              </el-select>
            </div>
            <div v-else-if="ra.type === 'NOTIFY'" class="item-body">
              <el-input v-model="ra.url" size="small" style="width: 260px" placeholder="webhook 地址" />
              <el-input v-model="ra.template" size="small" style="width: 280px" placeholder="消息模板" />
            </div>
            <div v-else-if="ra.type === 'RULE'" class="item-body">
              <el-select v-model="ra.ruleId" placeholder="目标规则" filterable size="small" style="width: 320px">
                <el-option v-for="r in ruleOptions.filter((x) => x.ruleId !== ruleId)" :key="r.ruleId" :label="`${r.ruleCode} · ${r.ruleName}`" :value="r.ruleId" />
              </el-select>
            </div>
          </div>
        </div>
      </section>
    </div>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">{{ ruleId ? '保存' : '创建' }}</el-button>
    </template>
  </el-drawer>
</template>

<style scoped>
.editor {
  padding-right: 6px;
}
.editor-sec {
  margin-bottom: 18px;
}
.sec-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}
.sec-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--ex-ink);
  margin: 0;
}
.item-card {
  border: 1px solid var(--ex-hair);
  border-radius: 8px;
  padding: 8px 10px;
  margin-bottom: 8px;
  background: #fafbfc;
}
.item-card.inner {
  margin-left: 18px;
  margin-top: 8px;
  background: #fff;
}
.item-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.item-index {
  font-size: 12px;
  font-weight: 700;
  color: var(--ex-primary, #2563eb);
  background: rgba(37, 99, 235, 0.08);
  border-radius: 4px;
  padding: 2px 6px;
}
.item-body {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.item-sub {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
}
.sub-label {
  font-size: 12px;
  color: var(--ex-ink-2);
}
.field-hint {
  font-size: 12px;
  color: var(--ex-ink-3);
  margin-left: 4px;
}
</style>
