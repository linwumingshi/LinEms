<script setup lang="ts">
/**
 * 物模型可视化编辑器（参考阿里云 IoT 物联网平台）。
 * - 顶部「可视化 / JSON 高级」双 tab 切换；双向同步 + 校验
 * - 可视化模式：左侧「属性/服务/事件」分类列表 + 右侧选中条目的可视化表单
 * - JSON 模式：textarea + 格式化 + 解析失败提示（容错保留用户编辑）
 */
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import ParamEditor from './ParamEditor.vue'
import type { ThingModelSchema, TsEvent, TsParam, TsProperty, TsService } from '@/types/models'
import {
  accessModeOptions,
  callTypeOptions,
  emptySchema,
  eventTypeOptions,
  newEvent,
  newParam,
  newProperty,
  newService,
  parseSchema,
  serializeSchema,
  specsGet,
  specsHintFor,
  specsSet,
  tsDataTypeOptions,
  validateSchema,
} from '@/utils/tsl'

const props = withDefaults(defineProps<{
  /** 双向绑定：schemaJson 字符串 */
  modelValue?: string
}>(), { modelValue: '' })

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
}>()

const activeTab = ref<'visual' | 'json'>('visual')

/** 当前 schema（内部状态；可视化和 JSON tab 共享） */
const schema = ref<ThingModelSchema>(emptySchema())
/** 顶部三类的当前选中分类 */
const leftTab = ref<'properties' | 'services' | 'events'>('properties')
/** 各分类选中的条目索引（-1 = 未选） */
const selectedIndex = ref<number>(-1)
/** JSON 模式下的本地编辑值（与 schema 双向同步；JSON 解析失败时保留用户原文） */
const jsonText = ref('')
/** JSON 解析错误提示 */
const jsonError = ref('')
/** 整体校验错误（切换到 JSON 时显示） */
const schemaError = ref('')

// ============== props → schema（外部更新时同步；自身 emit 回写不重置编辑态） ==============
watch(() => props.modelValue, (v) => {
  const incoming = v ?? ''
  // 自身 emit 回写导致的值变化：与当前 schema 序列化一致 → 仅刷新 jsonText，保留选中与编辑态
  if (incoming === serializeSchema(schema.value)) {
    return
  }
  // 外部加载（抽屉打开/切换产品）：全量重置
  schema.value = parseSchema(incoming)
  jsonText.value = serializeSchema(schema.value)
  selectedIndex.value = -1
  schemaError.value = ''
  jsonError.value = ''
}, { immediate: true })

// ============== schema → emit（任何 schema 改动都同步出去） ==============
watch(schema, (s) => {
  schemaError.value = validateSchema(s)
  emit('update:modelValue', serializeSchema(s))
  // 同步 JSON tab 文本（用户可能在 JSON tab 看变化）
  jsonText.value = serializeSchema(s)
}, { deep: true })

// ============== 切换到 JSON tab 时刷新文本 ==============
watch(activeTab, (t) => {
  if (t === 'json') {
    jsonText.value = serializeSchema(schema.value)
    jsonError.value = ''
  }
})

// ============== JSON 模式应用（点击「应用 JSON」按钮） ==============
function applyJson(): void {
  try {
    schema.value = parseSchema(jsonText.value)
    jsonError.value = ''
    ElMessage.success('JSON 已应用')
  } catch (e) {
    jsonError.value = e instanceof Error ? e.message : String(e)
    ElMessage.error(`JSON 解析失败：${jsonError.value}`)
  }
}

function formatJson(): void {
  try { jsonText.value = JSON.stringify(JSON.parse(jsonText.value), null, 2); jsonError.value = '' }
  catch (e) { jsonError.value = e instanceof Error ? e.message : String(e) }
}

// ============== 列表操作 ==============
/** 生成不重复的默认标识符：prop_N / service_N / event_N（N 取现有最大序号 +1） */
function nextIdentifier(prefix: string, arr: Array<{ identifier?: string }>): string {
  let max = 0
  const re = new RegExp(`^${prefix}_(\\d+)$`)
  for (const x of arr) {
    const m = x.identifier?.match(re)
    if (m) max = Math.max(max, Number(m[1]))
  }
  return `${prefix}_${max + 1}`
}

function addProperty(): void {
  const p = newProperty()
  p.identifier = nextIdentifier('prop', schema.value.properties)
  schema.value.properties.push(p)
  selectedIndex.value = schema.value.properties.length - 1
  leftTab.value = 'properties'
}
function addService(): void {
  const s = newService()
  s.identifier = nextIdentifier('service', schema.value.services)
  schema.value.services.push(s)
  selectedIndex.value = schema.value.services.length - 1
  leftTab.value = 'services'
}
function addEvent(): void {
  const e = newEvent()
  e.identifier = nextIdentifier('event', schema.value.events)
  schema.value.events.push(e)
  selectedIndex.value = schema.value.events.length - 1
  leftTab.value = 'events'
}

function removeAt(kind: 'properties' | 'services' | 'events', i: number): void {
  const arr = schema.value[kind] as Array<{ identifier: string }>
  arr.splice(i, 1)
  if (selectedIndex.value === i) selectedIndex.value = -1
  else if (selectedIndex.value > i) selectedIndex.value--
}

function selectAt(i: number): void {
  selectedIndex.value = i
}

// ============== 当前选中项（按 leftTab 切换时重置） ==============
watch(leftTab, () => { selectedIndex.value = -1 })

const currentList = computed(() => {
  const list = schema.value[leftTab.value] ?? []
  return list as Array<TsProperty | TsService | TsEvent>
})

const currentItem = computed(() => {
  const list = currentList.value
  if (selectedIndex.value < 0 || selectedIndex.value >= list.length) return null
  return list[selectedIndex.value] as TsProperty | TsService | TsEvent
})

// ============== specs 读写辅助（仅属性含 specs；服务/事件的入参/出参不挂 specs） ==============
function getSpecs(item: TsProperty | TsService | TsEvent | null): Record<string, unknown> {
  if (!item || !('specs' in item)) return {}
  return (item as TsProperty).specs ?? {}
}
function setSpecs(item: TsProperty | TsService | TsEvent | null, key: string, value: unknown): void {
  if (!item || !('specs' in item)) return
  ;(item as TsProperty).specs = specsSet((item as TsProperty).specs, key, value)
}

/** specs 字段的占位提示（按字段名给直观输入提示） */
function specsPlaceholder(k: string): string {
  if (k === 'min') return '最小值（如 0）'
  if (k === 'max') return '最大值（如 100）'
  if (k === 'step') return '步长（如 1）'
  if (k === 'length') return '最大长度（如 256）'
  if (k === 'unit') return '单位'
  if (k === 'elementType') return '数组元素类型（int/text/struct）'
  if (k === 'size') return '数组最大长度'
  return k
}
</script>

<template>
  <div class="tme">
    <!-- 顶部 tab：可视化 / JSON 高级 -->
    <el-tabs v-model="activeTab" class="tme-tabs">
      <el-tab-pane label="可视化" name="visual" />
      <el-tab-pane label="JSON 高级" name="json" />
    </el-tabs>

    <!-- 可视化模式 -->
    <div v-show="activeTab === 'visual'" class="tme-visual">
      <div class="tme-side">
        <el-tabs v-model="leftTab" class="tme-side-tabs">
          <el-tab-pane label="属性" name="properties">
            <div class="tme-add-row">
              <el-button size="small" type="primary" plain @click="addProperty">+ 新增属性</el-button>
              <span class="tme-count">{{ schema.properties.length }}</span>
            </div>
            <ul class="tme-list">
              <li
                v-for="(p, i) in schema.properties" :key="`p-${i}`"
                :class="{ active: leftTab === 'properties' && selectedIndex === i }"
                @click="selectAt(i)"
              >
                <span class="li-name">{{ p.name || '(未命名)' }}</span>
                <span class="li-identifier">{{ p.identifier || '...' }}</span>
                <el-button link type="danger" size="small" title="删除" @click.stop="removeAt('properties', i)">
                  <svg class="tme-del" viewBox="0 0 1024 1024" aria-hidden="true"><path d="M360 184h-8c4.4 0 8-3.6 8-8v8h304v-8c0 4.4 3.6 8 8 8h-8v72h72v-80c0-35.3-28.7-64-64-64H352c-35.3 0-64 28.7-64 64v80h72v-72zm504 72H160c-8.8 0-16 7.2-16 16v32c0 8.8 7.2 16 16 16h16v384c0 35.3 28.7 64 64 64h544c35.3 0 64-28.7 64-64V320h16c8.8 0 16-7.2 16-16v-32c0-8.8-7.2-16-16-16z" fill="currentColor"/></svg>
                </el-button>
              </li>
              <li v-if="schema.properties.length === 0" class="tme-empty">暂无属性</li>
            </ul>
          </el-tab-pane>
          <el-tab-pane label="服务" name="services">
            <div class="tme-add-row">
              <el-button size="small" type="primary" plain @click="addService">+ 新增服务</el-button>
              <span class="tme-count">{{ schema.services.length }}</span>
            </div>
            <ul class="tme-list">
              <li
                v-for="(s, i) in schema.services" :key="`s-${i}`"
                :class="{ active: leftTab === 'services' && selectedIndex === i }"
                @click="selectAt(i)"
              >
                <span class="li-name">{{ s.name || '(未命名)' }}</span>
                <span class="li-identifier">{{ s.identifier || '...' }}</span>
                <el-button link type="danger" size="small" title="删除" @click.stop="removeAt('services', i)">
                  <svg class="tme-del" viewBox="0 0 1024 1024" aria-hidden="true"><path d="M360 184h-8c4.4 0 8-3.6 8-8v8h304v-8c0 4.4 3.6 8 8 8h-8v72h72v-80c0-35.3-28.7-64-64-64H352c-35.3 0-64 28.7-64 64v80h72v-72zm504 72H160c-8.8 0-16 7.2-16 16v32c0 8.8 7.2 16 16 16h16v384c0 35.3 28.7 64 64 64h544c35.3 0 64-28.7 64-64V320h16c8.8 0 16-7.2 16-16v-32c0-8.8-7.2-16-16-16z" fill="currentColor"/></svg>
                </el-button>
              </li>
              <li v-if="schema.services.length === 0" class="tme-empty">暂无服务</li>
            </ul>
          </el-tab-pane>
          <el-tab-pane label="事件" name="events">
            <div class="tme-add-row">
              <el-button size="small" type="primary" plain @click="addEvent">+ 新增事件</el-button>
              <span class="tme-count">{{ schema.events.length }}</span>
            </div>
            <ul class="tme-list">
              <li
                v-for="(e, i) in schema.events" :key="`e-${i}`"
                :class="{ active: leftTab === 'events' && selectedIndex === i }"
                @click="selectAt(i)"
              >
                <span class="li-name">{{ e.name || '(未命名)' }}</span>
                <span class="li-identifier">{{ e.identifier || '...' }}</span>
                <el-button link type="danger" size="small" title="删除" @click.stop="removeAt('events', i)">
                  <svg class="tme-del" viewBox="0 0 1024 1024" aria-hidden="true"><path d="M360 184h-8c4.4 0 8-3.6 8-8v8h304v-8c0 4.4 3.6 8 8 8h-8v72h72v-80c0-35.3-28.7-64-64-64H352c-35.3 0-64 28.7-64 64v80h72v-72zm504 72H160c-8.8 0-16 7.2-16 16v32c0 8.8 7.2 16 16 16h16v384c0 35.3 28.7 64 64 64h544c35.3 0 64-28.7 64-64V320h16c8.8 0 16-7.2 16-16v-32c0-8.8-7.2-16-16-16z" fill="currentColor"/></svg>
                </el-button>
              </li>
              <li v-if="schema.events.length === 0" class="tme-empty">暂无事件</li>
            </ul>
          </el-tab-pane>
        </el-tabs>
      </div>

      <!-- 右侧表单 -->
      <div class="tme-main">
        <div v-if="!currentItem" class="tme-placeholder">
          <p>从左侧选择条目进行编辑，或点击「新增」创建</p>
        </div>

        <!-- 属性表单 -->
        <template v-else-if="leftTab === 'properties'">
          <h4 class="tme-form-title">属性详情</h4>
          <el-form label-width="100px" size="default">
            <el-form-item label="标识符" required>
              <el-input v-model="(currentItem as TsProperty).identifier" placeholder="英文/数字/下划线，如 cellTemp" />
            </el-form-item>
            <el-form-item label="名称" required>
              <el-input v-model="(currentItem as TsProperty).name" placeholder="中文友好名" />
            </el-form-item>
            <el-form-item label="数据类型" required>
              <el-select v-model="(currentItem as TsProperty).dataType" style="width: 200px">
                <el-option v-for="o in tsDataTypeOptions" :key="o.value" :label="o.label" :value="o.value" />
              </el-select>
            </el-form-item>
            <el-form-item label="访问模式">
              <el-select v-model="(currentItem as TsProperty).accessMode" clearable style="width: 160px">
                <el-option v-for="o in accessModeOptions" :key="o.value" :label="o.label" :value="o.value" />
              </el-select>
            </el-form-item>
            <el-form-item label="单位">
              <el-input v-model="(currentItem as TsProperty).unit" placeholder="如 % / V / ℃" style="width: 200px" />
            </el-form-item>
            <el-form-item label="必填">
              <el-switch v-model="(currentItem as TsProperty).required" />
            </el-form-item>
            <el-form-item label="说明">
              <el-input v-model="(currentItem as TsProperty).desc" type="textarea" :rows="2" />
            </el-form-item>

            <!-- specs 扩展字段（按数据类型渲染） -->
            <el-divider content-position="left">数据类型规格（specs）</el-divider>
            <p class="tme-specs-hint">以下字段随所选数据类型动态变化；JSON 高级模式可编辑完整 specs</p>
            <el-form-item v-for="k in specsHintFor((currentItem as TsProperty).dataType)" :key="k" :label="k">
              <el-input
                v-if="k !== 'enumValues' && k !== 'structFields'"
                :model-value="String(specsGet(getSpecs(currentItem), k) ?? '')"
                :placeholder="specsPlaceholder(k)"
                @update:model-value="(v: string) => setSpecs(currentItem, k, v)"
              />
              <span v-else class="tme-specs-tip">复杂结构（数组/对象）请切换到「JSON 高级」编辑</span>
            </el-form-item>
          </el-form>
        </template>

        <!-- 服务表单 -->
        <template v-else-if="leftTab === 'services'">
          <h4 class="tme-form-title">服务详情</h4>
          <el-form label-width="100px" size="default">
            <el-form-item label="标识符" required>
              <el-input v-model="(currentItem as TsService).identifier" placeholder="英文/数字/下划线，如 setPower" />
            </el-form-item>
            <el-form-item label="名称" required>
              <el-input v-model="(currentItem as TsService).name" placeholder="中文友好名" />
            </el-form-item>
            <el-form-item label="调用类型">
              <el-select v-model="(currentItem as TsService).callType" clearable style="width: 160px">
                <el-option v-for="o in callTypeOptions" :key="o.value" :label="o.label" :value="o.value" />
              </el-select>
            </el-form-item>
            <el-form-item label="说明">
              <el-input v-model="(currentItem as TsService).desc" type="textarea" :rows="2" />
            </el-form-item>
            <el-divider content-position="left">入参（input）</el-divider>
            <ParamEditor
              :params="((currentItem as TsService).input ?? []) as TsParam[]"
              @add="(currentItem as TsService).input = [...(((currentItem as TsService).input ?? []) as TsParam[]), newParam()]"
              @remove="(i: number) => { const p = ((currentItem as TsService).input ?? []) as TsParam[]; p.splice(i, 1); (currentItem as TsService).input = [...p] }"
            />
            <el-divider content-position="left">出参（output）</el-divider>
            <ParamEditor
              :params="((currentItem as TsService).output ?? []) as TsParam[]"
              @add="(currentItem as TsService).output = [...(((currentItem as TsService).output ?? []) as TsParam[]), newParam()]"
              @remove="(i: number) => { const p = ((currentItem as TsService).output ?? []) as TsParam[]; p.splice(i, 1); (currentItem as TsService).output = [...p] }"
            />
          </el-form>
        </template>

        <!-- 事件表单 -->
        <template v-else-if="leftTab === 'events'">
          <h4 class="tme-form-title">事件详情</h4>
          <el-form label-width="100px" size="default">
            <el-form-item label="标识符" required>
              <el-input v-model="(currentItem as TsEvent).identifier" placeholder="英文/数字/下划线" />
            </el-form-item>
            <el-form-item label="名称" required>
              <el-input v-model="(currentItem as TsEvent).name" placeholder="中文友好名" />
            </el-form-item>
            <el-form-item label="事件类型">
              <el-select v-model="(currentItem as TsEvent).type" clearable style="width: 160px">
                <el-option v-for="o in eventTypeOptions" :key="o.value" :label="o.label" :value="o.value" />
              </el-select>
            </el-form-item>
            <el-form-item label="说明">
              <el-input v-model="(currentItem as TsEvent).desc" type="textarea" :rows="2" />
            </el-form-item>
            <el-divider content-position="left">事件输出参数（data）</el-divider>
            <ParamEditor
              :params="((currentItem as TsEvent).data ?? []) as TsParam[]"
              @add="(currentItem as TsEvent).data = [...(((currentItem as TsEvent).data ?? []) as TsParam[]), newParam()]"
              @remove="(i: number) => { const p = ((currentItem as TsEvent).data ?? []) as TsParam[]; p.splice(i, 1); (currentItem as TsEvent).data = [...p] }"
            />
          </el-form>
        </template>
      </div>
    </div>

    <!-- JSON 模式 -->
    <div v-show="activeTab === 'json'" class="tme-json">
      <el-input v-model="jsonText" type="textarea" :rows="18" spellcheck="false" class="tme-json-editor" />
      <div v-if="jsonError" class="tme-json-err">JSON 错误：{{ jsonError }}</div>
      <div v-if="schemaError" class="tme-json-warn">Schema 校验：{{ schemaError }}</div>
      <div class="tme-json-actions">
        <el-button @click="formatJson">格式化</el-button>
        <el-button type="primary" @click="applyJson">应用 JSON（覆盖可视化）</el-button>
      </div>
      <p class="tme-json-tip">JSON 模式可编辑完整 specs/structFields/enumValues 等高级字段；点击「应用 JSON」同步到可视化视图</p>
    </div>

    <div v-if="schemaError && activeTab === 'visual'" class="tme-schema-warn">⚠️ {{ schemaError }}</div>
  </div>
</template>

<style scoped>
.tme {
  border: 1px solid var(--ex-hair);
  border-radius: 6px;
  background: #fff;
}
.tme-tabs {
  margin-bottom: 0;
}
.tme-tabs :deep(.el-tabs__header) {
  margin-bottom: 0;
}
.tme-tabs :deep(.el-tabs__nav-wrap::after) {
  height: 1px;
}

.tme-visual {
  display: grid;
  grid-template-columns: 280px 1fr;
  min-height: 420px;
}

.tme-side {
  border-right: 1px solid var(--ex-hair);
  padding: 8px;
  background: #fafbfc;
}
.tme-side-tabs :deep(.el-tabs__header) {
  margin-bottom: 0;
}
.tme-side-tabs :deep(.el-tabs__item) {
  padding: 0 10px;
  font-size: 13px;
}
.tme-add-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 8px 0;
}
.tme-count {
  font-size: 12px;
  color: var(--ex-ink-3);
}
.tme-list {
  list-style: none;
  margin: 0;
  padding: 0;
}
.tme-list li {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border-radius: 4px;
  cursor: pointer;
  font-size: 13px;
}
.tme-list li:hover {
  background: #f0f3f7;
}
.tme-list li.active {
  background: rgba(37, 99, 235, 0.12);
  color: var(--ex-primary, #2563eb);
}
.tme-list li .li-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tme-list li .li-identifier {
  font-family: monospace;
  font-size: 11px;
  color: var(--ex-ink-3);
}
.tme-empty {
  color: var(--ex-ink-3);
  font-size: 12px;
  text-align: center;
  padding: 14px;
  cursor: default;
}
.tme-empty:hover {
  background: transparent;
}
.tme-del {
  width: 13px;
  height: 13px;
  vertical-align: -2px;
}

.tme-main {
  padding: 16px 20px;
  overflow: auto;
  max-height: 540px;
}
.tme-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 200px;
  color: var(--ex-ink-3);
}
.tme-form-title {
  margin: 0 0 12px;
  font-size: 14px;
  font-weight: 600;
}
.tme-specs-hint {
  font-size: 12px;
  color: var(--ex-ink-3);
  margin: -8px 0 12px;
}
.tme-specs-tip {
  font-size: 12px;
  color: var(--ex-ink-3);
}

.tme-schema-warn,
.tme-json-warn {
  padding: 6px 12px;
  background: #fef3c7;
  color: #92400e;
  font-size: 12px;
  border-top: 1px solid #fde68a;
}
.tme-json-err {
  padding: 6px 12px;
  background: #fee2e2;
  color: #991b1b;
  font-size: 12px;
}

.tme-json {
  padding: 12px;
}
.tme-json-editor :deep(textarea) {
  font-family: ui-monospace, 'Cascadia Mono', 'Consolas', monospace;
  font-size: 12px;
  line-height: 1.6;
}
.tme-json-actions {
  display: flex;
  gap: 8px;
  margin-top: 8px;
}
.tme-json-tip {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--ex-ink-3);
}
</style>