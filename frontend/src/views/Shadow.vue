<script setup lang="ts">
/**
 * 设备影子独立入口：产品/设备联动选择 + 复用 DeviceShadowPanel。
 * 展示/下发逻辑统一收敛在 DeviceShadowPanel（与设备详情抽屉共用单一逻辑源），本页仅负责设备选择上下文。
 */
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { deviceApi } from '@/api/device'
import { productApi } from '@/api/product'
import DeviceShadowPanel from '@/components/DeviceShadowPanel.vue'
import type { Device, Product } from '@/types/models'

// ---------------- 产品/设备联动选择 ----------------
const selectedProductKey = ref('')
const productOptions = ref<Product[]>([])
const deviceOptions = ref<Device[]>([])
const deviceId = ref('')

async function loadProducts(): Promise<void> {
  try {
    const page = await productApi.page({ pageNum: 1, pageSize: 200 })
    productOptions.value = page.records ?? []
  } catch (e) {
    ElMessage.error(`产品加载失败：${e instanceof Error ? e.message : String(e)}`)
  }
}

async function onProductChange(productKey: string): Promise<void> {
  deviceId.value = ''
  deviceOptions.value = []
  if (!productKey) return
  try {
    const page = await deviceApi.page({ pageNum: 1, pageSize: 200, productKey })
    deviceOptions.value = page.records ?? []
  } catch (e) {
    ElMessage.error(`设备加载失败：${e instanceof Error ? e.message : String(e)}`)
  }
}

onMounted(() => void loadProducts())
</script>

<template>
  <div class="ex-page">
    <header class="ex-page-head">
      <div class="head-title">
        <h1 class="ex-title">设备影子</h1>
        <p class="ex-sub">设备上报状态（reported）与平台期望状态（desired）的双端比对与期望下发</p>
      </div>
      <el-form inline class="query-bar" @submit.prevent>
        <el-form-item label="产品" class="qi">
          <el-select
            v-model="selectedProductKey"
            placeholder="选择产品"
            filterable
            clearable
            style="width: 180px"
            @change="onProductChange"
          >
            <el-option v-for="p in productOptions" :key="p.productKey" :label="p.productName" :value="p.productKey" />
          </el-select>
        </el-form-item>
        <el-form-item label="设备" class="qi">
          <el-select
            v-model="deviceId"
            placeholder="选择设备"
            filterable
            clearable
            style="width: 220px"
            :disabled="!selectedProductKey"
          >
            <el-option v-for="d in deviceOptions" :key="d.deviceId" :label="d.deviceName" :value="d.deviceId" />
          </el-select>
        </el-form-item>
      </el-form>
    </header>

    <!-- 选中设备后自动加载影子（deviceId 变更面板自动重查；productKey 驱动物模型表单） -->
    <DeviceShadowPanel v-if="deviceId" :device-id="deviceId" :product-key="selectedProductKey" />
    <section v-else class="ex-card empty-card">
      <el-empty description="选择产品与设备后查看影子状态" :image-size="72" />
    </section>
  </div>
</template>

<style scoped>
.query-bar {
  display: flex;
  align-items: flex-start;
  gap: 4px;
}
.query-bar .qi {
  margin-bottom: 0;
}
.empty-card {
  padding: 40px 0;
}
</style>
