<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { emsApi } from '@/api/ems'
import type { EmsStrategy } from '@/types/models'

const loading = ref(false)
const list = ref<EmsStrategy[]>([])
const total = ref(0)
const pageNo = ref(1)
const pageSize = ref(10)
const dialogVisible = ref(false)
const editing = ref<Partial<EmsStrategy>>({})
const isEdit = ref(false)

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

function openCreate() { editing.value = {}; isEdit.value = false; dialogVisible.value = true }
function openEdit(row: EmsStrategy) { editing.value = { ...row }; isEdit.value = true; dialogVisible.value = true }

async function save() {
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
  <div class="page">
    <el-card>
      <div class="toolbar">
        <el-button type="primary" @click="openCreate">新增策略</el-button>
      </div>
      <el-table :data="list" v-loading="loading" border>
        <el-table-column prop="strategyName" label="策略名称" />
        <el-table-column prop="strategyType" label="类型" width="140">
          <template #default="{ row }">
            <el-tag :type="row.strategyType === 'PEAK_VALLEY' ? 'success' : 'info'">{{ row.strategyType }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="priority" label="优先级" width="90" />
        <el-table-column prop="status" label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : row.status === 2 ? 'danger' : 'info'">
              {{ { 0: '草稿', 1: '启用', 2: '停用' }[row.status as number] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="180" />
        <el-table-column label="操作" width="280">
          <template #default="{ row }">
            <el-button size="small" @click="openEdit(row)">编辑</el-button>
            <el-button size="small" type="success" @click="generatePlan(row)" v-if="row.status === 1">生成计划</el-button>
            <el-button size="small" :type="row.status === 1 ? 'warning' : 'primary'" @click="switchStatus(row, row.status === 1 ? 2 : 1)">
              {{ row.status === 1 ? '停用' : '启用' }}
            </el-button>
            <el-button size="small" type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination v-model:current-page="pageNo" v-model:page-size="pageSize" :total="total" @change="load" layout="total, prev, pager, next" />
    </el-card>

    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑策略' : '新增策略'" width="560px">
      <el-form label-width="100px">
        <el-form-item label="策略名称"><el-input v-model="editing.strategyName" /></el-form-item>
        <el-form-item label="策略类型">
          <el-select v-model="editing.strategyType">
            <el-option label="峰谷套利" value="PEAK_VALLEY" />
            <el-option label="需量管理" value="DEMAND" />
            <el-option label="需求响应" value="DR" />
            <el-option label="SOC 约束" value="SOC_CTRL" />
            <el-option label="时间策略" value="TIME" />
          </el-select>
        </el-form-item>
        <el-form-item label="电站 ID"><el-input-number v-model="editing.stationId" :min="1" /></el-form-item>
        <el-form-item label="优先级"><el-input-number v-model="editing.priority" :min="0" /></el-form-item>
        <el-form-item label="配置 JSON"><el-input v-model="editing.config" type="textarea" :rows="5" placeholder='{"chargeWindows":[{"start":"02:00","end":"06:00","powerLimit":100}]}' /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
