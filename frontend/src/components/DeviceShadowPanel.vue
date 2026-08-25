<script setup lang="ts">
/**
 * 设备影子面板（reported/desired 双端比对 + version + desired 下发）。
 * 从原独立页 Shadow.vue 抽取，供设备详情抽屉与独立入口复用（单一逻辑源）：
 * - props.deviceId 变更自动重新加载（watch immediate）；autoLoad=false 时由父级控制首次加载
 * - 内部自足：查询 / 展示 / desired 编辑下发 / delta 提示 / 错误提示全部内聚
 *
 * desired 编辑器双模式：
 * - 物模型驱动（传入 productKey 且物模型可获取）：属性名下拉（仅列 w/rw 可写属性），
 *   值控件按 dataType 渲染（number/enum/bool/text/struct/array），与后端 M2.2 ENFORCE 校验语义对齐；
 * - 自由输入（未传 productKey 或物模型未发布/拉取失败）：保留历史 key-value 文本行，功能兜底。
 */
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { productApi } from '@/api/product'
import { shadowApi } from '@/api/shadow'
import type { DesiredResult, ShadowView, ThingModelSchema, TsProperty } from '@/types/models'

const props = withDefaults(defineProps<{
  /** 设备 ID（必传；变更时自动重新加载） */
  deviceId: string
  /** 挂载即查询（默认 true；关闭时由父级控制刷新时机） */
  autoLoad?: boolean
  /** 产品 productKey：传入后 desired 编辑器升级为物模型驱动（属性下拉 + 按类型渲染控件） */
  productKey?: string
}>(), { autoLoad: true })

const loading = ref(false)
const view = ref<ShadowView | null>(null)
const lastDelta = ref<DesiredResult | null>(null)

/** desired 编辑器行：key 属性标识；value 控件绑定值；prop 命中物模型属性（模型模式） */
interface EditableRow {
  key: string
  value: string | number | boolean | undefined
  prop?: TsProperty
}
const rows = ref<EditableRow[]>([])

/** 物模型可写属性（accessMode w/rw），加载成功后编辑器按此渲染 */
const modelProps = ref<TsProperty[]>([])
/** 物模型是否已成功加载（false 时编辑器降级为自由 key-value 输入） */
const modelReady = ref(false)

const hasView = computed(() => view.value !== null)
const reportedKeys = computed(() => (view.value ? Object.keys(view.value.reported) : []))
const desiredKeys = computed(() => (view.value ? Object.keys(view.value.desired) : []))

// ---------------- 物模型加载（desired 编辑器数据源） ----------------

const NUMERIC_TYPES = ['int', 'long', 'float', 'double']

/** 子类型守卫：模板 v-else 链按 dataType 精确收窄（避免整类型排除导致 never） */
type NumericProp = TsProperty & { dataType: 'int' | 'long' | 'float' | 'double' }
type EnumProp = TsProperty & { dataType: 'enum' }
type BoolProp = TsProperty & { dataType: 'bool' }

function isNumeric(p?: TsProperty): p is NumericProp {
  return !!p && NUMERIC_TYPES.includes(p.dataType)
}

function isEnum(p?: TsProperty): p is EnumProp {
  return !!p && p.dataType === 'enum'
}

function isBool(p?: TsProperty): p is BoolProp {
  return !!p && p.dataType === 'bool'
}

/** 取物模型 specs 数值约束（min/max/step），兼容顶层冗余写法 */
function numOf(p: TsProperty, key: 'min' | 'max' | 'step'): number | undefined {
  const v = p.specs?.[key] ?? (p as unknown as Record<string, unknown>)[key]
  return typeof v === 'number' ? v : undefined
}

/** enum 枚举选项（specs.enumValues 优先，顶层 enumValues 兜底；兼容原始值与 {value,label} 两种写法） */
interface EnumOption {
  value: string | number | boolean
  label: string
}

function enumOptions(p: TsProperty): EnumOption[] {
  const raw = (p.specs?.enumValues as unknown[] | undefined) ?? (p as unknown as { enumValues?: unknown[] }).enumValues
  if (!Array.isArray(raw)) return []
  return raw.map((e) => {
    const item = (typeof e === 'object' && e !== null ? e : { value: e }) as Record<string, unknown>
    const v = item.value as string | number | boolean | undefined
    return { value: v ?? String(e), label: item.label != null ? String(item.label) : String(v ?? '') }
  })
}

/** 属性下拉展示文案：名称（identifier·单位 · 读写性） */
function propLabel(p: TsProperty): string {
  const unit = p.unit ? ` ${p.unit}` : ''
  return `${p.name}（${p.identifier}${unit} · ${p.accessMode === 'w' ? '只写' : '读写'}）`
}

/** 按类型初始化控件绑定值：数字留空、枚举取首项、布尔 false、其余空串 */
function defaultControlValue(prop: TsProperty): string | number | boolean | undefined {
  if (isBool(prop)) return false
  if (isEnum(prop)) return enumOptions(prop)[0]?.value
  if (isNumeric(prop)) return undefined
  return ''
}

/** 按 productKey 拉取当前生效物模型并提取可写属性；任何失败静默降级（不阻塞影子展示） */
async function loadModel(): Promise<void> {
  modelProps.value = []
  modelReady.value = false
  if (!props.productKey) return
  try {
    const tm = await productApi.thingModelByKey(props.productKey)
    const schema = JSON.parse(tm.schemaJson) as ThingModelSchema
    modelProps.value = (schema.properties ?? []).filter((p) => p.accessMode === 'w' || p.accessMode === 'rw')
    modelReady.value = true
  }
  catch (e) {
    // 物模型未发布 / 获取失败 → desired 编辑器保持自由输入模式（历史行为兜底）
    console.warn(`[DeviceShadowPanel] 物模型加载失败，desired 降级为自由输入 productKey=${props.productKey}`, e)
    modelReady.value = false
  }
}

/** 回填 desired 存量值：模型模式下按控件类型规整（存量可能为字符串），自由模式转字符串 */
function initRowFromDesired(k: string, v: unknown): EditableRow {
  const prop = modelProps.value.find((p) => p.identifier === k)
  if (!modelReady.value || !prop) {
    return { key: k, value: typeof v === 'object' ? JSON.stringify(v) : String(v) }
  }
  let value: string | number | boolean | undefined
  if (isNumeric(prop)) {
    value = typeof v === 'number' ? v : Number(v)
  }
  else if (isBool(prop)) {
    value = typeof v === 'boolean' ? v : String(v) === 'true'
  }
  else if (isEnum(prop)) {
    const hit = enumOptions(prop).find((o) => String(o.value) === String(v))
    value = hit ? hit.value : String(v)
  }
  else {
    value = typeof v === 'object' ? JSON.stringify(v) : String(v)
  }
  return { key: k, value, prop }
}

// ---------------- 影子查询与 desired 下发 ----------------

/** 拉取影子合并视图并初始化 desired 编辑器（影子与物模型并行加载） */
async function query(): Promise<void> {
  if (!props.deviceId) return
  loading.value = true
  lastDelta.value = null
  try {
    const shadow = await Promise.all([shadowApi.getShadow(props.deviceId), loadModel()]).then(([s]) => s)
    view.value = shadow
    rows.value = Object.entries(view.value.desired).map(([k, v]) => initRowFromDesired(k, v))
    if (rows.value.length === 0) addRow()
  }
  catch (e) {
    view.value = null
    ElMessage.error(e instanceof Error ? e.message : String(e))
  }
  finally {
    loading.value = false
  }
}

/** deviceId 变更自动重载（详情抽屉切换设备 / 独立页换设备均生效） */
watch(() => props.deviceId, () => { if (props.autoLoad) void query() }, { immediate: true })

function addRow(): void {
  rows.value.push({ key: '', value: '' })
}

function removeRow(index: number): void {
  rows.value.splice(index, 1)
}

/** 属性下拉选中：命中物模型属性并初始化值控件 */
function onPropSelect(row: EditableRow, identifier: string): void {
  const prop = modelProps.value.find((p) => p.identifier === identifier)
  row.prop = prop
  row.value = prop ? defaultControlValue(prop) : ''
}

/** 值控件 placeholder：struct/array 提示 JSON，其余按 dataType 提示 */
function valuePlaceholder(p?: TsProperty): string {
  if (!p) return '属性值'
  if (p.dataType === 'struct' || p.dataType === 'array') return 'JSON 值，如 {"a":1}'
  return `按 ${p.dataType} 填写`
}

/** 尽力按 JSON 解析值，失败按字符串处理 */
function parseValue(raw: string): unknown {
  const t = raw.trim()
  if (t === '') return ''
  try {
    return JSON.parse(t)
  }
  catch {
    return t
  }
}

/** 模型模式提交：按 dataType 强转为 desired 存储值（对齐后端 ModelValidator 语义） */
function coerceByType(prop: TsProperty, raw: string | number | boolean | undefined): unknown {
  if (isNumeric(prop)) {
    if (raw === undefined || raw === null || raw === '') return null
    const n = Number(raw)
    return Number.isNaN(n) ? null : n
  }
  if (isBool(prop)) return raw === true
  if (isEnum(prop)) return raw
  if (prop.dataType === 'struct' || prop.dataType === 'array') {
    return typeof raw === 'string' ? parseValue(raw) : raw
  }
  return raw === undefined || raw === null ? '' : String(raw)
}

/** 组装 desired：模型模式按类型强转（未填数字/枚举跳过），自由模式 JSON 解析；全部为空则拒绝下发 */
function buildDesired(): Record<string, unknown> | null {
  const desired: Record<string, unknown> = {}
  for (const row of rows.value) {
    const k = row.key.trim()
    if (!k) {
      ElMessage.warning('存在空的属性 key，已忽略（如需删除请整行移除）')
      continue
    }
    if (modelReady.value && row.prop) {
      if (isNumeric(row.prop) && (row.value === undefined || row.value === null || row.value === '')) {
        ElMessage.warning(`属性 ${k} 未填写数值，已忽略`)
        continue
      }
      if (isEnum(row.prop) && (row.value === undefined || row.value === null || row.value === '')) {
        ElMessage.warning(`属性 ${k} 未选择枚举值，已忽略`)
        continue
      }
      desired[k] = coerceByType(row.prop, row.value)
    }
    else {
      desired[k] = parseValue(String(row.value ?? ''))
    }
  }
  if (Object.keys(desired).length === 0) {
    ElMessage.warning('请至少填写一个期望属性')
    return null
  }
  return desired
}

/** 下发 desired：成功后重新拉取合并视图展示最新状态，并提示 delta 属性 */
async function submitDesired(): Promise<void> {
  const desired = buildDesired()
  if (desired === null) return
  loading.value = true
  try {
    lastDelta.value = await shadowApi.setDesired(props.deviceId, desired)
    view.value = await shadowApi.getShadow(props.deviceId)
    ElMessage.success(`desired 下发成功，delta 属性数：${Object.keys(lastDelta.value.delta).length}`)
  }
  catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  }
  finally {
    loading.value = false
  }
}

/** 展示 reported/desired 值（对象序列化为 JSON） */
function display(v: unknown): string {
  if (v === null || v === undefined) return '-'
  return typeof v === 'object' ? JSON.stringify(v) : String(v)
}
</script>

<template>
  <div v-loading="loading" class="dsp">
    <template v-if="hasView">
      <!-- 仪表读数带：影子版本信息 -->
      <section class="ex-readout-band" style="--ro-cols: 4" aria-label="影子版本信息">
        <div class="ex-readout">
          <span class="ex-readout-label">设备 ID</span>
          <span class="ex-readout-value md"><b>{{ view!.deviceId }}</b></span>
        </div>
        <div class="ex-readout">
          <span class="ex-readout-label">乐观锁版本</span>
          <span class="ex-readout-value md steel"><b>{{ view!.version ?? '不存在' }}</b></span>
        </div>
        <div class="ex-readout">
          <span class="ex-readout-label">reported 属性</span>
          <span class="ex-readout-value md"><b>{{ reportedKeys.length }}</b><em>项</em></span>
        </div>
        <div class="ex-readout">
          <span class="ex-readout-label">desired 属性</span>
          <span class="ex-readout-value md discharge"><b>{{ desiredKeys.length }}</b><em>项</em></span>
        </div>
      </section>

      <section class="dual-cols">
        <div class="ex-card">
          <div class="ex-card-head">
            <h2 class="ex-card-title">reported · 设备上报状态</h2>
          </div>
          <el-empty v-if="reportedKeys.length === 0" description="暂无 reported 数据" :image-size="60" />
          <el-table v-else :data="reportedKeys.map((k) => ({ k, v: display(view!.reported[k]) }))" size="small">
            <el-table-column prop="k" label="属性" width="180" />
            <el-table-column prop="v" label="值" show-overflow-tooltip />
          </el-table>
        </div>

        <div class="ex-card">
          <div class="ex-card-head">
            <h2 class="ex-card-title">desired · 期望状态</h2>
          </div>
          <el-empty v-if="desiredKeys.length === 0" description="暂无 desired 数据" :image-size="60" />
          <el-table v-else :data="desiredKeys.map((k) => ({ k, v: display(view!.desired[k]) }))" size="small">
            <el-table-column prop="k" label="属性" width="180" />
            <el-table-column prop="v" label="值" show-overflow-tooltip />
          </el-table>
        </div>
      </section>

      <section class="ex-card editor-card">
        <div class="ex-card-head">
          <h2 class="ex-card-title">设置 desired</h2>
          <span v-if="modelReady" class="editor-note">已按产品物模型渲染 · 仅可写属性（{{ modelProps.length }} 个）</span>
          <span v-else class="editor-note">值可填 JSON 或字符串，例如 5000 / {"level":3}</span>
        </div>
        <el-form label-width="0" class="editor-form">
          <div v-for="(row, index) in rows" :key="index" class="row-line">
            <template v-if="modelReady">
              <!-- 物模型驱动：属性下拉（仅 w/rw）+ 按 dataType 渲染值控件 -->
              <el-select
                v-model="row.key"
                filterable
                class="row-key"
                placeholder="选择属性"
                @change="(id: string) => onPropSelect(row, id)"
              >
                <el-option v-for="p in modelProps" :key="p.identifier" :value="p.identifier" :label="propLabel(p)" />
              </el-select>
              <el-input-number
                v-if="isNumeric(row.prop)"
                :model-value="typeof row.value === 'number' ? row.value : undefined"
                :min="numOf(row.prop, 'min')"
                :max="numOf(row.prop, 'max')"
                :step="numOf(row.prop, 'step') ?? 1"
                controls-position="right"
                class="row-val"
                @update:model-value="row.value = $event"
              />
              <el-select
                v-else-if="isEnum(row.prop)"
                :model-value="row.value"
                filterable
                class="row-val"
                placeholder="选择枚举值"
                @update:model-value="row.value = $event"
              >
                <el-option v-for="opt in enumOptions(row.prop)" :key="String(opt.value)" :value="opt.value"
                  :label="opt.label" />
              </el-select>
              <el-switch
                v-else-if="isBool(row.prop)"
                :model-value="row.value === true"
                class="row-val switch-row"
                @update:model-value="row.value = $event"
              />
              <el-input
                v-else
                :model-value="row.value == null ? '' : String(row.value)"
                :placeholder="valuePlaceholder(row.prop)"
                class="row-val"
                @update:model-value="row.value = $event"
              />
            </template>
            <template v-else>
              <!-- 自由输入兜底：物模型未接入/加载失败时保持历史行为 -->
              <el-input v-model="row.key" placeholder="属性名（如 power）" class="row-key" />
              <el-input
                :model-value="row.value == null ? '' : String(row.value)"
                placeholder="属性值（JSON 或字符串，如 5000）"
                class="row-val"
                @update:model-value="row.value = $event"
              />
            </template>
            <el-button type="danger" :icon="'Delete'" @click="removeRow(index)" circle />
          </div>
        </el-form>
        <div class="row-actions">
          <el-button :icon="'Plus'" @click="addRow">添加属性</el-button>
          <el-button type="primary" :loading="loading" @click="submitDesired">下发 desired</el-button>
        </div>

        <el-alert
          v-if="lastDelta"
          :title="`下发成功：delta（需同步设备）属性 ${Object.keys(lastDelta.delta).length} 个`"
          :description="Object.keys(lastDelta.delta).join('、') || '无差异'"
          type="success"
          show-icon
          :closable="false"
          class="delta-alert"
        />
      </section>
    </template>

    <section v-else class="ex-card empty-card">
      <el-empty description="暂无影子数据" :image-size="72" />
    </section>
  </div>
</template>

<style scoped>
.dual-cols {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 14px;
  align-items: start;
}
.editor-card {
  padding-bottom: 16px;
}
.editor-note {
  font-size: 12px;
  color: var(--ex-ink-3);
  font-variant-numeric: tabular-nums;
}
.editor-form {
  padding: 14px 18px 0;
}
.row-line {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
}
.row-key {
  width: 220px;
}
.row-val {
  flex: 1;
}
.switch-row {
  align-self: center;
}
.row-actions {
  display: flex;
  gap: 8px;
  padding: 0 18px;
  margin-top: 6px;
}
.delta-alert {
  margin: 12px 18px 0;
}
.empty-card {
  padding: 40px 0;
}
@media (max-width: 900px) {
  .dual-cols {
    grid-template-columns: 1fr;
  }
}
</style>
