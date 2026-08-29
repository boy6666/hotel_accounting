<script setup>
import { ref, watch, onMounted, onBeforeUnmount } from 'vue'
import * as echarts from 'echarts'

// 图表容器：option 变化即 setOption(notMerge) —— 主题切换等引发 option 重构建后自动重绘。
const props = defineProps({
  option: { type: Object, required: true },
  height: { type: String, default: '300px' }
})

const el = ref(null)
let chart = null

function render() {
  if (!chart && el.value) chart = echarts.init(el.value, null, { renderer: 'canvas' })
  if (chart && props.option) chart.setOption(props.option, { notMerge: true })
}
function onResize() { chart && chart.resize() }

onMounted(() => {
  render()
  window.addEventListener('resize', onResize)
})
watch(() => props.option, render, { deep: true })
watch(() => props.height, () => setTimeout(() => chart && chart.resize(), 0))
onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  chart && chart.dispose()
  chart = null
})
</script>

<template>
  <div ref="el" class="chart-box" :style="{ height }"></div>
</template>
