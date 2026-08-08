<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { emsApi } from '@/api/ems'
import type { EmsConstraint, EmsStrategy } from '@/types/models'
import { isStrategyGeneratable } from '@/utils/dicts'
import StrategyConfigEditor from '@/components/StrategyConfigEditor.vue'
import { parseJsonConfig, validatePeakValleyConfig } from '@/utils/strategyConfig'

const loading = ref(false)
const list = ref<EmsStrategy[]>([])
const total = ref(0)
const pageNo = ref(1)
const pageSize = ref(10)
const dialogVisible = ref(false)
const editing = ref<Partial<EmsStrategy>>({})
const isEdit = ref(false)
/** 站点安全包络（供配置表单软警告）；缺失/拉取失败静默置 null */
const envelope = ref<EmsConstraint | null>(null)

/** 策略类型语义色（充电/放电/钢蓝/中性） */
const TYPE_TAG: Record<string, 'success' | 'warning' | 'primary' | 'info'> = {
  PEAK_VALLEY: 'success',
  DEMAND: 'primary',
  DR: 'warning',
  SOC_CTRL: 'info',
  TIME: 'info',
}
const TYPE_TEXT: Record<string, string> = {
  PEAK_VALLEY: '峰谷套利',
  DEMAND: '需量管理',
  DR: '需求响应',
  SOC_CTRL: 'SOC 约束',
  TIME: '时间策略',
}

/** 类型下拉：未实现生成的类型追加标注 */
const strategyTypeOptions = computed(() =>
  Object.entries(TYPE_TEXT).map(([value, label]) => ({
    value,
    label: isStrategyGeneratable(value) ? label : `${label}（暂不支持生成）`,
  })),
)
const STATUS_TEXT: Record<number, string> = { 0: '草稿', 1: '启用', 2: '停用' }
const STATUS_TAG: Record<number, 'info' | 'success' | 'danger'> = { 0: 'info', 1: 'success', 2: 'danger' }

async function load() {
  loading.value = true
  try {
    const data = await emsApi.strategyPage({ pageNo: pageNo.value, pageSize: pageSize.value })
    list.value = data.records
    total.value = data.total
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally { loading.value = false }
}

function openCreate() {
  editing.value = {}
  isEdit.value = false
  dialogVisible.value = true
  void loadEnvelope()
}
function openEdit(row: EmsStrategy) {
  editing.value = { ...row }
  isEdit.value = true
  dialogVisible.value = true
  void loadEnvelope()
}
async function loadEnvelope() {
  const stationId = editing.value.stationId
  envelope.value = null
  if (!stationId) return
  try {
    const constraint = await emsApi.constraintGet(stationId)
    if (editing.value.stationId !== stationId) return // 弹窗复用竞态：已切到另一电站，丢弃过期包络
    envelope.value = constraint
  } catch {
    if (editing.value.stationId === stationId) envelope.value = null
  }
}

async function save() {
  const raw = editing.value.config ?? ''
  if (editing.value.strategyType === 'PEAK_VALLEY') {
    const issues = validatePeakValleyConfig(raw)
    if (issues.length) {
      ElMessage.error(issues[0])
      return
    }
  } else {
    const r = parseJsonConfig(raw)
    if (!r.ok) {
      ElMessage.error(r.error)
      return
    }
  }
  if (editing.value.strategyType && !isStrategyGeneratable(editing.value.strategyType)) {
    ElMessage.warning('当前仅峰谷套利可生成计划，其余类型可保存但不可生成')
  }
  try {
    if (isEdit.value) await emsApi.strategyUpdate(editing.value.strategyId!, editing.value)
    else await emsApi.strategyCreate(editing.value)
    ElMessage.success('保存成功')
    dialogVisible.value = false
    load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  }
}

async function remove(row: EmsStrategy) {
  try {
    await ElMessageBox.confirm(`确定删除策略「${row.strategyName}」吗？`, '提示', { type: 'warning' })
  } catch {
    return // 取消
  }
  try {
    await emsApi.strategyDelete(row.strategyId)
    ElMessage.success('已删除')
    load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  }
}

async function switchStatus(row: EmsStrategy, status: number) {
  try {
    await emsApi.strategySwitchStatus(row.strategyId, status)
    ElMessage.success(status === 1 ? '已启用' : '已停用')
    load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  }
}

async function generatePlan(row: EmsStrategy) {
  if (!isStrategyGeneratable(row.strategyType)) {
    ElMessage.warning('该策略类型暂不支持生成计划（后端仅实现峰谷套利）')
    return
  }
  const d = new Date()
  const planDate = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
  try {
    await emsApi.planGenerate({ stationId: row.stationId, strategyId: row.strategyId, planDate })
    ElMessage.success('计划已生成')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  }
}

onMounted(load)
</script>

<template>
  <div class="ex-page">
    <header class="ex-page-head">
      <div class="head-title">
        <h1 class="ex-title">能量管理策略</h1>
        <p class="ex-sub">峰谷套利 / 需量管理 / 需求响应 / SOC 约束 / 时间策略 · 按优先级裁决</p>
      </div>
      <el-button type="primary" @click="openCreate">新增策略</el-button>
    </header>

    <section class="ex-card table-card">
      <el-table :data="list" v-loading="loading" empty-text="暂无策略，点击右上角新增">
        <el-table-column prop="strategyName" label="策略名称" min-width="160" show-overflow-tooltip />
        <el-table-column label="类型" width="120">
          <template #default="{ row }">
            <el-tag :type="TYPE_TAG[row.strategyType] ?? 'info'" size="small">
              {{ TYPE_TEXT[row.strategyType] ?? row.strategyType }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="priority" label="优先级" width="90">
          <template #default="{ row }"><span class="ex-num">{{ row.priority }}</span></template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="STATUS_TAG[row.status as number] ?? 'info'" size="small">
              {{ STATUS_TEXT[row.status as number] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="170">
          <template #default="{ row }"><span class="ex-num">{{ row.createTime }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="280" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-tooltip :disabled="isStrategyGeneratable(row.strategyType)" content="该策略类型暂不支持生成计划（后端仅实现峰谷套利）">
              <span>
                <el-button v-if="row.status === 1" link type="success" :disabled="!isStrategyGeneratable(row.strategyType)" @click="generatePlan(row)">生成计划</el-button>
              </span>
            </el-tooltip>
            <el-button link :type="row.status === 1 ? 'warning' : 'success'" @click="switchStatus(row, row.status === 1 ? 2 : 1)">
              {{ row.status === 1 ? '停用' : '启用' }}
            </el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pager">
        <el-pagination
          v-model:current-page="pageNo"
          v-model:page-size="pageSize"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          @size-change="pageNo = 1; void load()"
          @current-change="load"
        />
      </div>
    </section>

    <!-- 策略编辑弹窗 -->
    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑策略' : '新增策略'" width="560px">
      <el-form label-width="100px">
        <el-form-item label="策略名称">
          <el-input v-model="editing.strategyName" placeholder="如：工作日峰谷套利" />
        </el-form-item>
        <el-form-item label="策略类型">
          <el-select v-model="editing.strategyType" style="width: 100%">
            <el-option v-for="opt in strategyTypeOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="电站 ID">
          <el-input v-model="editing.stationId" placeholder="电站 ID" clearable style="width: 200px" />
        </el-form-item>
        <el-form-item label="优先级">
          <el-input-number v-model="editing.priority" :min="0" style="width: 200px" />
        </el-form-item>
        <el-form-item label="配置 JSON">
          <StrategyConfigEditor v-model="editing.config" :strategy-type="editing.strategyType" :envelope="envelope" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="loading" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.table-card {
  padding-bottom: 10px;
}
.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
  padding: 0 18px;
}
</style>
