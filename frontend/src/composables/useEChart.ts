import { onBeforeUnmount, onMounted, shallowRef, type Ref } from 'vue'
import * as echarts from 'echarts'

/**
 * ECharts 生命周期组合式：挂载后 init、ResizeObserver 自适应、卸载时 dispose。
 *
 * <p>用法：页面声明容器 ref 传入，数据就绪后调用 render(option)；
 * 未挂载前调用会自动缓存，挂载后立即渲染（避免首帧空窗）。</p>
 */
export function useEChart(elRef: Ref<HTMLElement | undefined>) {
  const chart = shallowRef<echarts.ECharts | null>(null)
  let pending: echarts.EChartsOption | null = null
  let observer: ResizeObserver | null = null

  function render(option: echarts.EChartsOption): void {
    if (chart.value) {
      chart.value.setOption(option, { notMerge: true })
    } else {
      pending = option
    }
  }

  onMounted(() => {
    const el = elRef.value
    if (!el) return
    chart.value = echarts.init(el)
    if (pending) {
      chart.value.setOption(pending, { notMerge: true })
      pending = null
    }
    observer = new ResizeObserver(() => chart.value?.resize())
    observer.observe(el)
  })

  onBeforeUnmount(() => {
    observer?.disconnect()
    observer = null
    chart.value?.dispose()
    chart.value = null
  })

  return { chart, render }
}
