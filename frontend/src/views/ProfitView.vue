<script setup>
// 利润分析 FW-06：4 统计卡 + 收入/成本/利润趋势 + 月度净利率 + 月度利润表（本月/累计切换）
import { ref, computed } from 'vue'
import { usePageData } from '@/composables/usePageData'
import { usePalette } from '@/composables/usePalette'
import StatCard from '@/components/StatCard.vue'
import ChartView from '@/components/ChartView.vue'
import DataTable from '@/components/DataTable.vue'
import EmptyState from '@/components/EmptyState.vue'
import { profitApi } from '@/api'
import { fmtMoney, monthLabel } from '@/utils/format'

const { month, loading, data, error, errorMsg, reload } = usePageData(async (m) => {
  const [monthly, summary] = await Promise.all([
    profitApi.monthly(`${new Date().getFullYear()}-01`, m),
    profitApi.summary(m)
  ])
  return { monthly: monthly.list || [], summary }
})

const mode = ref('month') // month | cumulative
const yearStart = `${new Date().getFullYear()}-01`

const list = computed(() => data.value?.monthly || [])
const cur = computed(() => list.value.find((r) => r.month === month.month) || {})

// 累计利润：按月份顺序累加
const cumProfits = computed(() => {
  const out = []
  let acc = 0
  list.value.forEach((r) => { acc += r.profit; out.push({ ...r, _cumProfit: acc }) })
  return out
})

const { baseOpt, categoryColors, hexA } = usePalette()

const trendOpt = computed(() => {
  const r = list.value
  if (!r.length) return baseOpt({ legend: true })
  const c = categoryColors.value
  return {
    ...baseOpt({ legend: true }),
    tooltip: { ...baseOpt().tooltip, valueFormatter: (v) => fmtMoney(v) + ' 元' },
    xAxis: { ...baseOpt().xAxis, data: r.map((x) => monthLabel(x.month)) },
    series: [
      { name: '收入', type: 'line', smooth: true, symbolSize: 5, data: r.map((x) => x.revenue), areaStyle: { color: hexA(c[0], 0.10) }, lineStyle: { width: 2 } },
      { name: '成本', type: 'line', smooth: true, symbolSize: 5, data: r.map((x) => x.totalCost), lineStyle: { width: 2 } },
      { name: '利润', type: 'line', smooth: true, symbolSize: 5, data: r.map((x) => x.profit), lineStyle: { width: 2 } }
    ]
  }
})

// 月度净利率：真实数据（同比口径暂缺历史年数据，以净利率替代展示）
const marginOpt = computed(() => {
  const r = list.value.filter((x) => x.hasData !== false)
  if (!r.length) return baseOpt()
  return {
    ...baseOpt(),
    tooltip: { ...baseOpt().tooltip, valueFormatter: (v) => v + '%' },
    xAxis: { ...baseOpt().xAxis, data: r.map((x) => monthLabel(x.month)) },
    yAxis: { ...baseOpt().yAxis, axisLabel: { formatter: '{value}%' } },
    series: [{
      name: '净利率', type: 'bar', barWidth: 22,
      itemStyle: { borderRadius: [4, 4, 0, 0], color: categoryColors.value[2] },
      data: r.map((x) => (x.revenue ? +((x.profit / x.revenue) * 100).toFixed(1) : null))
    }]
  }
})

const fmtP = (v, digits = 0) => (v == null ? '—' : fmtMoney(v))

// 单间成本 = 均价 − 单间净利
const curCostPerNight = computed(() => {
  if (cur.value.adr == null || cur.value.perNightProfit == null) return null
  return cur.value.adr - cur.value.perNightProfit
})

const columns = computed(() => [
  { key: 'month', label: '月份', format: (r) => monthLabel(r.month), strong: true },
  { key: 'nights', label: '间夜', align: 'right', format: (r) => fmtP(r.nights) },
  { key: 'adr', label: '均价', align: 'right', format: (r) => fmtP(r.adr, 2) },
  { key: 'revenue', label: '收入', align: 'right', format: (r) => fmtP(r.revenue) },
  { key: 'totalCost', label: '成本', align: 'right', format: (r) => fmtP(r.totalCost) },
  { key: 'profit', label: '现金净利', align: 'right', tag: (r) => ({ text: fmtMoney(r.profit), cls: r.profit >= 0 ? 'ok' : 'warn' }) },
  { key: 'perNightProfit', label: '单间净利', align: 'right', format: (r) => fmtP(r.perNightProfit, 2) },
  ...(mode.value === 'cumulative' && cumProfits.value.length
    ? [{
        key: '_cumProfit', label: '累计利润', align: 'right',
        tag: (r) => ({ text: fmtMoney(r._cumProfit), cls: r._cumProfit >= 0 ? 'ok' : 'warn' })
      }]
    : [])
])
</script>

<template>
  <div v-if="loading && !data" class="page-loading">数据加载中…</div>
  <template v-else-if="error">
    <div class="card">
      <div class="card-head-row">
        <div class="card-title">加载失败</div>
        <button class="btn btn-primary btn-sm" @click="reload">重试</button>
      </div>
      <EmptyState :text="errorMsg || '数据加载失败'" />
    </div>
  </template>
  <template v-else-if="data">
    <div class="row cards">
      <StatCard label="本月现金净利" :value="fmtP(cur.profit)" unit="元" :tone="(cur.profit || 0) >= 0 ? 'ok' : 'warn'" hint="现金流口径（未含折旧分期）" />
      <StatCard label="净利率" :value="cur.revenue ? ((cur.profit / cur.revenue) * 100).toFixed(1) + '%' : '—'" hint="含 / 未含房租折旧见设定" />
      <StatCard label="单间净利" :value="fmtP(cur.perNightProfit, 2)" unit="元" hint="元 / 间夜" />
      <StatCard label="单间成本" :value="curCostPerNight == null ? '—' : fmtP(curCostPerNight, 2)" unit="元" hint="元 / 间夜" />
    </div>

    <div class="row cards3">
      <div class="card" style="grid-column: span 2">
        <h3>收入 / 成本 / 利润 · 月度</h3>
        <ChartView v-if="list.length" class="chart lg" :option="trendOpt" height="300px" />
        <EmptyState v-else text="暂无月度数据" />
      </div>
      <div class="card">
        <h3>月度净利率（{{ monthLabel(month.month) }}止）</h3>
        <ChartView v-if="list.length" class="chart md" :option="marginOpt" height="250px" />
        <EmptyState v-else text="暂无月度数据" />
      </div>
    </div>

    <div class="card">
      <div class="card-head-row">
        <div class="card-title">
          月度经营关键指标<span class="card-sub">{{ yearStart }} ~ {{ monthLabel(month.month) }} · 同比待历史补齐</span>
        </div>
        <div class="head-actions">
          <button class="btn btn-ghost btn-sm" :class="{ 'is-active': mode === 'month' }" @click="mode = 'month'">本月</button>
          <button class="btn btn-ghost btn-sm" :class="{ 'is-active': mode === 'cumulative' }" @click="mode = 'cumulative'">含累计</button>
        </div>
      </div>
      <DataTable :rows="mode === 'cumulative' ? cumProfits : list" :columns="columns" :loading="loading" empty="暂无月度数据" />
    </div>
  </template>
</template>
