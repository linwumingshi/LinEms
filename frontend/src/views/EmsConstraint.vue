<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { emsApi } from '@/api/ems'
import type { EmsConstraint, Station } from '@/types/models'
import { loadStations, stationName } from '@/utils/stationDict'

const route = useRoute()

const stations = ref<Station[]>([])
const stationId = ref('')
const loading = ref(false)
const saving = ref(false)

const form = ref<Partial<EmsConstraint>>({
  socMin: null,
  socMax: null,
  chargePowerMax: null,
  dischargePowerMax: null,
  tempMax: null,
})

/** 当前电站是否已配置约束（决定空态提示） */
const hasConstraint = ref(false)

const selectedStationName = computed(() => stationName(stationId.value, stations.value))

function resetForm(): void {
  form.value = { socMin: null, socMax: null, chargePowerMax: null, dischargePowerMax: null, tempMax: null }
  hasConstraint.value = false
}

async function onStationChange(id: string): Promise<void> {
  resetForm()
  if (!id) return
  loading.value = true
  try {
    const c = await emsApi.constraintGet(id)
    hasConstraint.value = true
    form.value = {
      stationId: id,
      socMin: c.socMin,
      socMax: c.socMax,
      chargePowerMax: c.chargePowerMax,
      dischargePowerMax: c.dischargePowerMax,
      tempMax: c.tempMax,
    }
    ElMessage.success(`已加载 ${stationName(id, stations.value)} 的现有约束`)
  } catch (e) {
    // 未配置约束：后端返回业务错误（NOT_FOUND），置空态允许新建
    hasConstraint.value = false
    form.value.stationId = id
    ElMessage.info(`该电站尚未配置安全约束，填写下方参数保存即可`)
  } finally {
    loading.value = false
  }
}

function validate(): string | null {
  const { socMin, socMax, chargePowerMax, dischargePowerMax } = form.value
  if (socMin == null || socMax == null || chargePowerMax == null || dischargePowerMax == null) {
    return 'SOC 上下限与充放电功率上限均为必填'
  }
  if (Number(socMin) >= Number(socMax)) return 'SOC 下限必须小于上限'
  if (Number(chargePowerMax) <= 0 || Number(dischargePowerMax) <= 0) return '充放电功率上限必须大于 0'
  return null
}

async function save(): Promise<void> {
  if (!stationId.value) {
    ElMessage.warning('请先选择电站')
    return
  }
  const err = validate()
  if (err) {
    ElMessage.warning(err)
    return
  }
  saving.value = true
  try {
    await emsApi.constraintSave({
      stationId: stationId.value,
      socMin: Number(form.value.socMin),
      socMax: Number(form.value.socMax),
      chargePowerMax: Number(form.value.chargePowerMax),
      dischargePowerMax: Number(form.value.dischargePowerMax),
      tempMax: form.value.tempMax == null ? null : Number(form.value.tempMax),
    } as EmsConstraint)
    hasConstraint.value = true
    ElMessage.success(`安全约束已保存：${selectedStationName.value}`)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  try {
    stations.value = await loadStations()
    // 支持 ?station=xxx 直达（电站管理「安全约束」按钮跳转），自动选中并加载
    const preset = route.query.station
    if (preset) {
      stationId.value = String(preset)
      void onStationChange(stationId.value)
    }
  } catch {
    // 电站加载失败由页面空态兜底
  }
})
</script>

<template>
  <div class="ex-page">
    <header class="ex-page-head">
      <div class="head-title">
        <h1 class="ex-title">安全约束</h1>
        <p class="ex-sub">电站 SOC 上下限与充放电功率上限 · 充放电计划生成的安全包络（越界计划将被拒绝）</p>
      </div>
    </header>

    <section class="dual-cols">
      <!-- 电站选择 -->
      <div class="ex-card form-card">
        <div class="ex-card-head">
          <h2 class="ex-card-title">选择电站</h2>
        </div>
        <el-form label-width="90px" size="default">
          <el-form-item label="电站" required>
            <el-select v-model="stationId" placeholder="选择电站" filterable style="width: 100%" @change="onStationChange">
              <el-option v-for="s in stations" :key="s.stationId" :label="s.stationName" :value="s.stationId" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="stationId" label="约束状态">
            <el-tag :type="hasConstraint ? 'success' : 'warning'">
              {{ hasConstraint ? '已配置' : '未配置' }}
            </el-tag>
          </el-form-item>
        </el-form>
      </div>

      <!-- 约束编辑 -->
      <div class="ex-card form-card">
        <div class="ex-card-head">
          <h2 class="ex-card-title">安全包络参数</h2>
        </div>
        <el-form v-if="stationId" label-width="130px" size="default" @submit.prevent>
          <el-alert
            v-if="!hasConstraint"
            :title="`该电站尚未配置安全约束，保存后即可生成充放电计划`"
            type="warning"
            show-icon
            :closable="false"
            style="margin-bottom: 14px"
          />
          <el-form-item label="SOC 下限 (%)" required>
            <el-input-number v-model="form.socMin" :min="0" :max="100" :precision="1" style="width: 100%" placeholder="如 10" />
          </el-form-item>
          <el-form-item label="SOC 上限 (%)" required>
            <el-input-number v-model="form.socMax" :min="0" :max="100" :precision="1" style="width: 100%" placeholder="如 90" />
          </el-form-item>
          <el-form-item label="充电功率上限 (kW)" required>
            <el-input-number v-model="form.chargePowerMax" :min="1" :precision="1" style="width: 100%" placeholder="如 200" />
          </el-form-item>
          <el-form-item label="放电功率上限 (kW)" required>
            <el-input-number v-model="form.dischargePowerMax" :min="1" :precision="1" style="width: 100%" placeholder="如 200" />
          </el-form-item>
          <el-form-item label="温度上限 (℃)">
            <el-input-number v-model="form.tempMax" :min="1" :precision="1" style="width: 100%" placeholder="可选，如 60" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="saving" @click="save">保存安全约束</el-button>
          </el-form-item>
        </el-form>
        <el-empty v-else description="请先选择电站" :image-size="72" />
      </div>
    </section>
  </div>
</template>
