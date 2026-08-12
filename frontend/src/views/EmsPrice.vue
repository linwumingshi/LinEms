<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { emsApi } from '@/api/ems'
import type { EmsElectricityPrice, Station } from '@/types/models'
import { loadStations } from '@/utils/stationDict'
import { priceTypeTag, priceTypeText } from '@/utils/dicts'
import { ElectricityPriceStatus, PriceType } from '@/utils/enums'

const route = useRoute()

const stations = ref<Station[]>([])
const stationId = ref('')
const loading = ref(false)
const list = ref<EmsElectricityPrice[]>([])

const dialogVisible = ref(false)
const isEdit = ref(false)
const saving = ref(false)
const form = reactive<Partial<EmsElectricityPrice>>({})

const PRICE_TYPES: PriceType[] = Object.values(PriceType)

/** 后端 LocalTime 序列化为 "HH:mm:ss"，页面统一截取 "HH:mm" 展示 */
function fmtTime(t?: string | null): string {
  return t ? t.slice(0, 5) : ''
}

/** 本地日期（非 UTC，避免跨天偏差） */
function todayStr(): string {
  const d = new Date()
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

async function loadPrices(): Promise<void> {
  if (!stationId.value) {
    list.value = []
    return
  }
  loading.value = true
  try {
    const page = await emsApi.pricePage({ pageNo: 1, pageSize: 100, stationId: stationId.value })
    list.value = page.records
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    loading.value = false
  }
}

function onStationChange(): void {
  void loadPrices()
}

function openCreate(): void {
  isEdit.value = false
  Object.assign(form, {
    stationId: stationId.value,
    priceType: 'VALLEY',
    startTime: '00:00',
    endTime: '08:00',
    price: 0.5,
    validFrom: todayStr(),
    validTo: todayStr(),
    status: ElectricityPriceStatus.ENABLED,
  })
  dialogVisible.value = true
}

function openEdit(row: EmsElectricityPrice): void {
  isEdit.value = true
  Object.assign(form, {
    priceId: row.priceId,
    stationId: row.stationId,
    priceType: row.priceType,
    startTime: fmtTime(row.startTime),
    endTime: fmtTime(row.endTime),
    price: row.price,
    validFrom: row.validFrom,
    validTo: row.validTo,
    status: row.status,
  })
  dialogVisible.value = true
}

function validate(): string | null {
  if (!form.priceType) return '请选择时段类型'
  if (!form.startTime || !form.endTime) return '请选择开始/结束时间'
  if (form.startTime >= form.endTime) return '结束时间必须晚于开始时间'
  if (form.price == null || Number(form.price) <= 0) return '电价必须大于 0'
  if (!form.validFrom || !form.validTo) return '请选择生效/失效日期'
  if (form.validFrom > form.validTo) return '失效日期不能早于生效日期'
  return null
}

async function save(): Promise<void> {
  const err = validate()
  if (err) {
    ElMessage.warning(err)
    return
  }
  saving.value = true
  try {
    if (isEdit.value) {
      await emsApi.priceUpdate(form.priceId!, { ...form } as EmsElectricityPrice)
    } else {
      await emsApi.priceSave([{ ...form } as EmsElectricityPrice])
    }
    ElMessage.success('保存成功')
    dialogVisible.value = false
    void loadPrices()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    saving.value = false
  }
}

async function remove(row: EmsElectricityPrice): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定删除「${fmtTime(row.startTime)}-${fmtTime(row.endTime)} ${priceTypeText(row.priceType)} ${Number(row.price).toFixed(4)} 元/kWh」档位吗？`,
      '提示',
      { type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await emsApi.priceDelete(row.priceId)
    ElMessage.success('已删除')
    void loadPrices()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  }
}

onMounted(async () => {
  try {
    stations.value = await loadStations()
    // 支持 ?station=xxx 直达（如策略页「电价配置」入口），自动选中并加载
    const preset = route.query.station
    if (preset) {
      stationId.value = String(preset)
      void loadPrices()
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
        <h1 class="ex-title">分时电价</h1>
        <p class="ex-sub">档位按开始时间唯一（同站同开始时间重复提交为更新）· 电价驱动策略按 DEEP/VALLEY 充电、PEAK/PEEK 放电推导计划</p>
      </div>
      <el-button v-if="stationId" type="primary" @click="openCreate">新增档位</el-button>
    </header>

    <section class="ex-card filter-card">
      <el-form inline @submit.prevent>
        <el-form-item label="电站">
          <el-select v-model="stationId" placeholder="选择电站" filterable clearable style="width: 280px" @change="onStationChange">
            <el-option v-for="s in stations" :key="s.stationId" :label="s.stationName" :value="s.stationId" />
          </el-select>
        </el-form-item>
      </el-form>
    </section>

    <section class="ex-card table-card">
      <el-table :data="list" v-loading="loading" size="small" empty-text="该电站尚未配置分时电价，点击右上角「新增档位」添加">
        <el-table-column label="时段类型" min-width="90">
          <template #default="{ row }">
            <el-tag :type="priceTypeTag(row.priceType)">{{ priceTypeText(row.priceType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="开始时间" width="100" align="center">
          <template #default="{ row }">{{ fmtTime(row.startTime) }}</template>
        </el-table-column>
        <el-table-column label="结束时间" width="100" align="center">
          <template #default="{ row }">{{ fmtTime(row.endTime) }}</template>
        </el-table-column>
        <el-table-column label="电价 (元/kWh)" min-width="110" align="right">
          <template #default="{ row }"><span class="ex-num">{{ Number(row.price).toFixed(4) }}</span></template>
        </el-table-column>
        <el-table-column label="有效期" min-width="190">
          <template #default="{ row }"><span class="ex-num">{{ row.validFrom }} ~ {{ row.validTo }}</span></template>
        </el-table-column>
        <el-table-column label="状态" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === ElectricityPriceStatus.ENABLED ? 'success' : 'info'">{{ row.status === ElectricityPriceStatus.ENABLED ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="130" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑档位' : '新增档位'" width="480px">
      <el-form label-width="100px" size="default" @submit.prevent>
        <el-form-item label="时段类型" required>
          <el-select v-model="form.priceType" style="width: 100%">
            <el-option v-for="t in PRICE_TYPES" :key="t" :label="`${priceTypeText(t)} (${t})`" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="开始时间" required>
          <el-time-picker v-model="form.startTime" value-format="HH:mm" placeholder="如 00:00" style="width: 100%" />
        </el-form-item>
        <el-form-item label="结束时间" required>
          <el-time-picker v-model="form.endTime" value-format="HH:mm" placeholder="如 08:00" style="width: 100%" />
        </el-form-item>
        <el-form-item label="电价 (元/kWh)" required>
          <el-input-number v-model="form.price" :min="0.0001" :max="10" :precision="4" :step="0.01" style="width: 100%" />
        </el-form-item>
        <el-form-item label="生效日期" required>
          <el-date-picker v-model="form.validFrom" type="date" value-format="YYYY-MM-DD" placeholder="生效日期" style="width: 100%" />
        </el-form-item>
        <el-form-item label="失效日期" required>
          <el-date-picker v-model="form.validTo" type="date" value-format="YYYY-MM-DD" placeholder="失效日期" style="width: 100%" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.status" :active-value="ElectricityPriceStatus.ENABLED" :inactive-value="ElectricityPriceStatus.DISABLED" active-text="启用" inactive-text="停用" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
