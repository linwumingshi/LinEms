<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { ruleApi } from '@/api/rule'
import type { RuleLogView } from '@/types/models'
import { triggerTypeText } from '@/utils/ruleOptions'
import { toLocal } from '@/utils/alarmFormat'

const props = withDefaults(defineProps<{
  modelValue: boolean
  ruleId?: number | null
  ruleCode?: string
}>(), {
  ruleId: null,
  ruleCode: '',
})

const emit = defineEmits<{ (e: 'update:modelValue', value: boolean): void }>()

const visible = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v),
})

const filters = reactive<{
  triggerType: string
  matched: number | undefined
  timeRange: [string, string] | null
}>({
  triggerType: '',
  matched: undefined,
  timeRange: null,
})

const tableData = ref<RuleLogView[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const expandedRows = ref<string[]>([])

async function load(): Promise<void> {
  loading.value = true
  try {
    const res = await ruleApi.logPage({
      ruleId: props.ruleId ? String(props.ruleId) : undefined,
      triggerType: filters.triggerType || undefined,
      deviceId: undefined,
      startTime: filters.timeRange ? filters.timeRange[0] : undefined,
      endTime: filters.timeRange ? filters.timeRange[1] : undefined,
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
  filters.triggerType = ''
  filters.matched = undefined
  filters.timeRange = null
  page.value = 1
  void load()
}

/** action_result JSON 美化展示 */
function actionResultText(r: RuleLogView): string {
  if (!r.actionResult) return '-'
  try {
    return JSON.stringify(JSON.parse(r.actionResult), null, 2)
  } catch {
    return r.actionResult
  }
}

watch(visible, (open) => {
  if (open) {
    page.value = 1
    void load()
  }
})

onMounted(() => {
  if (visible.value) void load()
})
</script>

<template>
  <el-drawer v-model="visible" :title="`执行日志${ruleCode ? ` · ${ruleCode}` : ''}`" size="720px">
    <div class="log-filter">
      <el-select v-model="filters.triggerType" placeholder="触发类型" clearable style="width: 130px" @change="page = 1; void load()">
        <el-option label="属性" value="PROPERTY" />
        <el-option label="定时" value="TIMER" />
        <el-option label="上下线" value="LIFECYCLE" />
        <el-option label="告警" value="ALARM" />
        <el-option label="手动" value="MANUAL" />
        <el-option label="嵌套" value="RULE" />
      </el-select>
      <el-select v-model="filters.matched" placeholder="结果" clearable style="width: 110px" @change="page = 1; void load()">
        <el-option label="条件满足" :value="1" />
        <el-option label="未过条件" :value="0" />
      </el-select>
      <el-date-picker
        v-model="filters.timeRange"
        type="datetimerange"
        value-format="YYYY-MM-DDTHH:mm:ss"
        start-placeholder="开始时间"
        end-placeholder="结束时间"
        :default-time="[new Date(2000, 0, 1, 0, 0, 0), new Date(2000, 0, 1, 23, 59, 59)]"
        @change="page = 1; void load()"
      />
      <el-button @click="resetFilters">重置</el-button>
    </div>

    <el-table
      :data="tableData"
      v-loading="loading"
      size="small"
      empty-text="暂无执行日志"
      :expand-row-keys="expandedRows"
      row-key="logId"
      @expand-change="(_row: RuleLogView, rows: RuleLogView[]) => { expandedRows = rows.map((r) => String(r.logId)) }"
    >
      <el-table-column type="expand">
        <template #default="{ row }">
          <pre class="action-result">{{ actionResultText(row) }}</pre>
        </template>
      </el-table-column>
      <el-table-column label="触发" width="80">
        <template #default="{ row }">{{ triggerTypeText(row.triggerType) }}</template>
      </el-table-column>
      <el-table-column label="结果" width="90">
        <template #default="{ row }">
          <el-tag :type="row.matched === 1 ? 'success' : 'info'" size="small">
            {{ row.matched === 1 ? '满足' : '未过' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="deviceId" label="设备ID" width="90">
        <template #default="{ row }">{{ row.deviceId ?? '-' }}</template>
      </el-table-column>
      <el-table-column label="耗时" width="80">
        <template #default="{ row }">
          <span class="ex-num">{{ row.costMs }}ms</span>
        </template>
      </el-table-column>
      <el-table-column prop="traceId" label="TraceID" show-overflow-tooltip min-width="120">
        <template #default="{ row }">{{ row.traceId ?? '-' }}</template>
      </el-table-column>
      <el-table-column label="时间" width="160">
        <template #default="{ row }"><span class="ex-num">{{ toLocal(row.createTime) }}</span></template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        @current-change="load"
        @size-change="page = 1; void load()"
      />
    </div>
  </el-drawer>
</template>

<style scoped>
.log-filter {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}
.action-result {
  background: #f6f8fa;
  border-radius: 6px;
  padding: 10px 14px;
  font-size: 12px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  white-space: pre-wrap;
  word-break: break-all;
  margin: 0;
  color: var(--ex-ink);
}
.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}
</style>
