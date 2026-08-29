import { computed } from 'vue'
import { useThemeStore } from '@/stores/theme'
import { PAL, hexA } from '@/utils/palette'

// 图表调色：读主题 store 实时取色板，theme 切换后 option 重建即自动重绘。
// baseOpt() 提供与全站 CSS 变量一致的基座（坐标轴/文字/网格色随明暗变化）。
export function usePalette() {
  const themeStore = useThemeStore()
  const pal = computed(() => PAL[themeStore.theme] || PAL.light)

  const categoryColors = computed(() => pal.value.c)

  /** 基于色板的统一 ECharts 基座 option */
  function baseOpt({ grid = {}, legend = false, tooltip = 'axis' } = {}) {
    const c = pal.value
    return {
      color: c.c,
      textStyle: { color: c.ink2, fontSize: 12, fontFamily: 'inherit' },
      grid: {
        top: legend ? 40 : 30, left: 46, right: 18, bottom: 30,
        containLabel: true, ...grid
      },
      legend: legend
        ? { top: 0, right: 0, textStyle: { color: c.muted }, icon: 'roundRect', itemWidth: 10, itemHeight: 6 }
        : undefined,
      tooltip: {
        trigger: tooltip || 'axis',
        confine: true,
        backgroundColor: themeStore.theme === 'dark' ? '#262522' : '#fff',
        borderColor: c.grid,
        textStyle: { color: c.ink, fontSize: 12 }
      },
      xAxis: xAxisOpt('bottom'),
      yAxis: yAxisOpt('left')
    }
  }

  function xAxisOpt(pos) {
    const c = pal.value
    return {
      type: 'category', position: pos, boundaryGap: true,
      axisLine: { lineStyle: { color: c.axis } },
      axisTick: { show: false },
      axisLabel: { color: c.muted }, splitLine: { show: false }
    }
  }

  function yAxisOpt(pos) {
    const c = pal.value
    return {
      type: 'value', position: pos,
      axisLine: { show: false }, axisTick: { show: false },
      axisLabel: { color: c.muted, formatter: compactNum },
      splitLine: { lineStyle: { color: c.grid } }
    }
  }

  return {
    theme: themeStore, pal,
    categoryColors, baseOpt, xAxisOpt, yAxisOpt, hexA
  }
}

/** 坐标刻度缩写：12000 → 1.2万 */
export function compactNum(v) {
  const n = Number(v)
  if (!isFinite(n)) return ''
  if (Math.abs(n) >= 10000) return (n / 10000).toFixed(n % 10000 === 0 ? 0 : 1) + '万'
  if (Math.abs(n) >= 1000) return (n / 1000).toFixed(n % 1000 === 0 ? 0 : 1) + 'k'
  return String(Math.round(n))
}
