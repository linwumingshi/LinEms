<script setup lang="ts">
import { computed } from 'vue'
import { levelTag, levelText } from '@/utils/alarmFormat'

// 注意：Boolean 类型 prop 缺省会被 Vue 归一为 false，
// 故用 hideText（缺省 false=显示文案）而非 showText 表达语义。
const props = defineProps<{
  level: number
  hideText?: boolean
}>()

const tagType = computed(() => levelTag(props.level))
const text = computed(() => levelText(props.level))
</script>

<template>
  <el-tag :type="tagType" size="small" effect="light">
    {{ hideText ? '' : text }}
  </el-tag>
</template>
