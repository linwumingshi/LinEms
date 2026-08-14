<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ruleApi } from '@/api/rule'
import RuleEditor from '@/components/RuleEditor.vue'
import RuleLogDrawer from '@/components/RuleLogDrawer.vue'
import type { RuleView } from '@/types/models'
import { triggerTypeText } from '@/utils/ruleOptions'
import { toLocal } from '@/utils/alarmFormat'

// ---------------- 查询条件 ----------------
const filters = reactive<{
  ruleName: string
  enabled: number | undefined
}>({
  ruleName: '',
  enabled: undefined,
})

const tableData = ref<RuleView[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)

async function load(): Promise<void> {
  loading.value = true
  try {
    const res = await ruleApi.page({
      ruleName: filters.ruleName || undefined,
      enabled: filters.enabled,
      page: page.value,
      size: size.value,
    })
    tableData.value = res.records
    total.value = res.total
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    loading.value = false
  }
}

function resetFilters(): void {
  filters.ruleName = ''
  filters.enabled = undefined
  page.value = 1
  void load()
}

// ---------------- 编辑抽屉 ----------------
const editorVisible = ref(false)
const editing = ref<RuleView | null>(null)

function openCreate(): void {
  editing.value = null
  editorVisible.value = true
}

function openEdit(row: RuleView): void {
  editing.value = row
  editorVisible.value = true
}

// ---------------- 启停 / 删除 / 手动触发 ----------------
async function toggleEnabled(row: RuleView): Promise<void> {
  try {
    if (row.enabled === 1) {
      await ruleApi.disable(row.ruleId)
    } else {
      await ruleApi.enable(row.ruleId)
    }
    ElMessage.success(row.enabled === 1 ? '已停用' : '已启用')
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  }
}

async function remove(row: RuleView): Promise<void> {
  try {
    await ElMessageBox.confirm(`删除规则「${row.ruleName}」？删除后不可恢复。`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await ruleApi.remove(row.ruleId)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  }
}

const triggering = ref<number | null>(null)

/** 手动触发（仅含 MANUAL 触发器规则可触发；无 MANUAL 时后端按普通校验不执行） */
async function trigger(row: RuleView): Promise<void> {
  triggering.value = row.ruleId
  try {
    await ruleApi.trigger(row.ruleId)
    ElMessage.success(`已手动触发「${row.ruleName}」，结果见执行日志`)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    triggering.value = null
  }
}

// ---------------- 执行日志 ----------------
const logVisible = ref(false)
const logRule = ref<RuleView | null>(null)

function openLog(row: RuleView): void {
  logRule.value = row
  logVisible.value = true
}

/** 规则概要（TCA 摘要，供列表展示） */
function summary(row: RuleView): string {
  const d = row.dsl
  const triggers = d.triggers.map((t) => triggerTypeText(t.type)).join(' + ')
  const actions = d.actions.map((a) => a.type).join(' + ')
  return `触发:${triggers} → 动作:${actions}`
}

/** 是否含 MANUAL 触发器（决定是否展示「手动触发」按钮） */
function hasManual(row: RuleView): boolean {
  return row.dsl.triggers.some((t) => t.type === 'MANUAL')
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="ex-page">
    <header class="ex-page-head">
      <div class="head-title">
        <h1 class="ex-title">场景联动</h1>
        <p class="ex-sub">TCA 规则编排 · 五类触发源 × 四类动作 · 动作防抖与执行审计</p>
      </div>
      <el-button type="primary" @click="openCreate">+ 新建规则</el-button>
    </header>

    <!-- 查询区 -->
    <section class="ex-card filter-card">
      <el-form :inline="true" @submit.prevent>
        <el-form-item label="规则名称">
          <el-input v-model="filters.ruleName" placeholder="模糊匹配" clearable style="width: 200px" @keyup.enter="page = 1; void load()" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="filters.enabled" placeholder="全部" clearable style="width: 110px">
            <el-option label="启用" :value="1" />
            <el-option label="停用" :value="0" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="page = 1; void load()">查询</el-button>
          <el-button @click="resetFilters">重置</el-button>
        </el-form-item>
      </el-form>
    </section>

    <!-- 规则表格 -->
    <section class="ex-card table-card">
      <div class="ex-card-head">
        <h2 class="ex-card-title">场景规则</h2>
        <span class="table-total">共 {{ total }} 条</span>
      </div>
      <el-table :data="tableData" v-loading="loading" size="default" empty-text="暂无场景规则，点击右上角「新建规则」创建">
        <el-table-column prop="ruleCode" label="编码" width="150" show-overflow-tooltip />
        <el-table-column prop="ruleName" label="名称" min-width="140" show-overflow-tooltip />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.enabled === 1 ? 'success' : 'info'" size="small">
              {{ row.enabled === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="触发 → 动作" min-width="200" show-overflow-tooltip>
          <template #default="{ row }"><span class="ex-num">{{ summary(row) }}</span></template>
        </el-table-column>
        <el-table-column prop="debounceSeconds" label="防抖(s)" width="80">
          <template #default="{ row }"><span class="ex-num">{{ row.debounceSeconds }}</span></template>
        </el-table-column>
        <el-table-column prop="priority" label="优先级" width="70">
          <template #default="{ row }"><span class="ex-num">{{ row.priority }}</span></template>
        </el-table-column>
        <el-table-column label="更新时间" width="160">
          <template #default="{ row }"><span class="ex-num">{{ toLocal(row.updateTime) }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="210" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button link type="primary" size="small" @click="openLog(row)">日志</el-button>
            <el-button
              v-if="hasManual(row)"
              link
              type="warning"
              size="small"
              :loading="triggering === row.ruleId"
              @click="trigger(row)"
            >
              触发
            </el-button>
            <el-button link type="primary" size="small" @click="toggleEnabled(row)">
              {{ row.enabled === 1 ? '停用' : '启用' }}
            </el-button>
            <el-button link type="danger" size="small" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pager">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          :total="total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          @current-change="load"
          @size-change="page = 1; void load()"
        />
      </div>
    </section>

    <!-- 规则编辑器抽屉 -->
    <RuleEditor v-model="editorVisible" :editing="editing" @saved="load" />

    <!-- 执行日志抽屉 -->
    <RuleLogDrawer v-model="logVisible" :rule-id="logRule?.ruleId ?? null" :rule-code="logRule?.ruleCode" />
  </div>
</template>

<style scoped>
.filter-card {
  padding: 4px 14px 0;
}
.filter-card :deep(.el-form-item) {
  margin-bottom: 12px;
}
.table-card {
  padding-bottom: 10px;
}
.table-total {
  font-size: 12px;
  color: var(--ex-ink-3);
  font-variant-numeric: tabular-nums;
}
.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
  padding: 0 18px;
}
</style>
