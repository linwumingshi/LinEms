<script setup lang="ts">
/**
 * 物模型嵌套参数编辑器（服务的 input/output、事件的 data 通用）。
 * 表格化编辑：identifier / name / dataType / unit / required / 删除。
 */
import type { TsParam } from '@/types/models'
import { tsDataTypeOptions } from '@/utils/tsl'

defineProps<{ params: TsParam[] }>()
const emit = defineEmits<{
  (e: 'add'): void
  (e: 'remove', i: number): void
}>()

function add(): void { emit('add') }
function remove(i: number): void { emit('remove', i) }
</script>

<template>
  <div class="pe">
    <el-table :data="params" size="small" border>
      <el-table-column label="标识符" min-width="120">
        <template #default="{ row }">
          <el-input v-model="row.identifier" size="small" placeholder="如 power" />
        </template>
      </el-table-column>
      <el-table-column label="名称" min-width="120">
        <template #default="{ row }">
          <el-input v-model="row.name" size="small" placeholder="中文友好名" />
        </template>
      </el-table-column>
      <el-table-column label="类型" width="120">
        <template #default="{ row }">
          <el-select v-model="row.dataType" size="small">
            <el-option v-for="o in tsDataTypeOptions" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="单位" width="100">
        <template #default="{ row }">
          <el-input v-model="row.unit" size="small" placeholder="如 kW" />
        </template>
      </el-table-column>
      <el-table-column label="必填" width="60" align="center">
        <template #default="{ row }">
          <el-switch v-model="row.required" size="small" />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="60" align="center">
        <template #default="{ $index }">
          <el-button link type="danger" size="small" @click="remove($index)">删</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-button v-if="params.length === 0" size="small" type="primary" plain @click="add">+ 新增参数</el-button>
    <el-button v-else size="small" type="primary" plain @click="add" style="margin-top: 6px">+ 新增参数</el-button>
  </div>
</template>

<style scoped>
.pe {
  margin-bottom: 8px;
}
</style>
