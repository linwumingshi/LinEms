import { onBeforeUnmount, shallowRef, watch, type Ref } from 'vue'
import * as echarts from 'echarts'

/**
 * ECharts 生命周期组合式：容器元素出现后 init（watch 覆盖挂载即存在 / el-drawer 懒渲染晚挂载两种时序）、
 * ResizeObserver 自适应、卸载时 dispose。数据就绪后调用 render(option)；元素未出现前调用会缓存，出现后立即渲染。
 */
export function useEChart(elRef: Ref<HTMLElement | undefined>) {
  const chart = shallowRef<echarts.ECharts | null>(null)
  let pending: echarts.EChartsOption | null = null
  let observer: ResizeObserver | null = null

  function initIfNeeded(el: HTMLElement | undefined): void {
    if (!el || chart.value) return
    chart.value = echarts.init(el)
    if (pending) {
      chart.value.setOption(pending, { notMerge: true })
      pending = null
    }
    observer = new ResizeObserver(() => chart.value?.resize())
    observer.observe(el)
  }

  watch(elRef, initIfNeeded, { immediate: true })

  function render(option: echarts.EChartsOption): void {
    if (chart.value) {
      chart.value.setOption(option, { notMerge: true })
    } else {
      pending = option
    }
  }

  onBeforeUnmount(() => {
    observer?.disconnect()
    observer = null
    chart.value?.dispose()
    chart.value = null
  })

  return { chart, render }
}
