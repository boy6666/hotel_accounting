<script setup>
// 首页看板 FW-03：对账提示 + 4 统计卡 + 收入/成本/利润趋势 + 成本结构 + 线上/线下/渠道占比 + 回本进度占位
import { computed } from 'vue'
import { usePageData } from '@/composables/usePageData'
import { usePalette } from '@/composables/usePalette'
import StatCard from '@/components/StatCard.vue'
import ChartView from '@/components/ChartView.vue'
import NoticeBar from '@/components/NoticeBar.vue'
import EmptyState from '@/components/EmptyState.vue'
import { dashboardApi } from '@/api'
import { fmtMoney, fmtYuan, fmtPct, monthLabel } from '@/utils/format'

const { month, loading, data, error, errorMsg, reload } = usePageData(async (m) => {
  const [ov, cost, ratio, rec, trend] = await Promise.all([
    dashboardApi.overview(m),
    dashboardApi.costStructure(m),
    dashboardApi.channelRatio(m),
    dashboardApi.reconcile(m),
    dashboardApi.trend(`${new Date().getFullYear()}-01`, m)
  ])
  return { ov, cost, ratio, rec, trend }
})

// 当月为空：后端 empty 标记，或营收/间夜/成本全 0 且无明细 → 顶部提示未上传
const isEmptyMonth = computed(() => {
  const ov = data.value?.ov
  if (!ov) return false
  if (ov.empty === true) return true
  return !ov.revenue && !ov.nights && !ov.totalCost
})

const { baseOpt, categoryColors, hexA } = usePalette()

const trendOpt = computed(() => {
  const d = data.value?.trend
  if (!d || !d.months?.length) return baseOpt({ legend: true })
  const c = categoryColors.value
  return {
    ...baseOpt({ legend: true }),
    tooltip: { ...baseOpt().tooltip, valueFormatter: (v) => fmtMoney(v) + ' 元' },
    xAxis: { ...baseOpt().xAxis, data: d.months.map((m) => monthLabel(m)) },
    series: [
      { name: '收入', type: 'line', smooth: true, symbolSize: 5, data: d.revenue, areaStyle: { color: hexA(c[0], 0.10) }, lineStyle: { width: 2 } },
      { name: '成本', type: 'line', smooth: true, symbolSize: 5, data: d.cost, lineStyle: { width: 2 } },
      { name: '利润', type: 'line', smooth: true, symbolSize: 5, data: d.profit, lineStyle: { width: 2 } }
    ]
  }
})

const costDonutOpt = computed(() => {
  const c = data.value?.cost
  if (!c) return {}
  const total = c.total || 1
  return {
    ...baseOpt({ tooltip: 'item' }),
    color: categoryColors.value.slice(0, 3),
    tooltip: { ...baseOpt().tooltip, valueFormatter: (v) => fmtMoney(v) + ' 元' },
    series: [{
      type: 'pie', radius: ['52%', '76%'], center: ['50%', '50%'],
      avoidLabelOverlap: true,
      label: { show: true, formatter: '{b}\n{d}%', color: 'inherit', fontSize: 11 },
      labelLine: { length: 8, length2: 6 },
      itemStyle: { borderColor: 'transparent', borderWidth: 2 },
      data: [
        { name: '固定', value: c.fixed },
        { name: '变动', value: c.variable },
        { name: '一次性', value: c.oneTime }
      ],
      emphasis: { label: { fontSize: 13, fontWeight: 600 } }
    }]
  }
})

const channelDonutOpt = computed(() => {
  const r = data.value?.ratio
  if (!r) return {}
  return {
    ...baseOpt({ tooltip: 'item' }),
    color: [categoryColors.value[0], categoryColors.value[4]],
    tooltip: { ...baseOpt().tooltip, valueFormatter: (v) => fmtMoney(v) + ' 元' },
    series: [{
      type: 'pie', radius: ['52%', '76%'],
      label: { show: true, formatter: '{b}\n{d}%', color: 'inherit', fontSize: 11 },
      labelLine: { length: 8, length2: 6 },
      data: [
        { name: '线上', value: r.onlineRevenue },
        { name: '线下', value: r.offlineRevenue }
      ]
    }]
  }
})

// 渠道间夜占比（按真实 channelNights 数据）
const channelShareOpt = computed(() => {
  const top = data.value?.ratio?.top
  if (!top || !top.length) return {}
  return {
    ...baseOpt({ tooltip: 'item' }),
    color: categoryColors.value,
    tooltip: { ...baseOpt().tooltip, valueFormatter: (v) => v + ' 间夜' },
    series: [{
      type: 'pie', radius: ['50%', '72%'],
      label: { show: true, formatter: '{b}\n{d}%', color: 'inherit', fontSize: 10 },
      labelLine: { length: 6, length2: 5 },
      data: top.map((t) => ({ name: t.channel, value: t.nights }))
    }]
  }
})
</script>

<template>
  <div v-if="loading" class="page-loading">数据加载中…</div>
  <template v-else-if="error">
    <div class="card">
      <div class="card-head-row">
        <div class="card-title">加载失败</div>
        <button class="btn btn-primary btn-sm" @click="reload">重试</button>
      </div>
      <EmptyState :text="errorMsg || '数据加载失败'" />
    </div>
  </template>
  <template v-else-if="data && data.ov">
    <NoticeBar v-if="isEmptyMonth" :tone="'warn'" title="本月暂无经营数据（数据暂未上传）">
      请先到「导入」页上传 {{ monthLabel(month.month) }} 月度数据，或到「房态」页补录 —— 上传后看板/对账自动刷新。
    </NoticeBar>
    <NoticeBar v-if="data.rec" :tone="data.rec.reconcileStatus === 'matched' ? 'ok' : 'warn'" :title="data.rec.reconcileStatus === 'matched' ? '对账已对齐' : '渠道流水与房态存在差异'">
      <template v-if="data.rec.reconcileStatus === 'matched'">
        流水间夜 <b>{{ data.rec.channelNights }}</b> = 实际入住 <b>{{ data.rec.occupancyNights }}</b>，diff 0，无需处理。
      </template>
      <template v-else>
        流水间夜 {{ data.rec.channelNights }} vs 实际入住 {{ data.rec.occupancyNights }}，差 <b class="text-warn">{{ Math.abs(data.rec.diff) }} 间夜</b> —— 请到「房态」页核对补录，或检查渠道流水。
      </template>
    </NoticeBar>

    <div class="row cards">
      <StatCard label="本月营收" :value="fmtMoney(data.ov.revenue)" unit="元" :delta="data.ov.revenueDelta" />
      <StatCard label="本月间夜" :value="data.ov.nights" unit="间夜" :delta="data.ov.nightsDelta" :hint="`均价 ${fmtYuan(data.ov.adr)}`" />
      <StatCard label="本月入住率" :value="data.ov.occupancyRate == null ? '—' : fmtPct(data.ov.occupancyRate)" :delta="data.ov.occupancyRateDelta" hint="可售房间×营业日" />
      <StatCard
        label="本月现金净利" :value="fmtMoney(data.ov.profit)" unit="元" :delta="data.ov.profitDelta"
        :hint="`毛利率 ${data.ov.revenue ? fmtPct(data.ov.profit / data.ov.revenue) : '—'}`"
        :tone="data.ov.profit >= 0 ? 'ok' : 'warn'"
      />
    </div>

    <div class="row cards3">
      <div class="card" style="grid-column: span 2">
        <h3>收入 / 成本 / 利润 · 月度趋势</h3>
        <ChartView class="chart lg" :option="trendOpt" height="300px" />
      </div>
      <div class="card">
        <h3>{{ monthLabel(month.month) }}费用构成</h3>
        <ChartView v-if="data.cost && data.cost.total > 0" class="chart md" :option="costDonutOpt" height="250px" />
        <EmptyState v-else text="本月暂无成本数据" />
      </div>
    </div>

    <div class="row cards3">
      <div class="card">
        <h3>线上 vs 线下 · 近期占比</h3>
        <ChartView class="chart md" :option="channelDonutOpt" height="250px" />
      </div>
      <div class="card">
        <h3>渠道间夜占比（{{ monthLabel(month.month) }}）</h3>
        <ChartView class="chart md" :option="channelShareOpt" height="250px" />
      </div>
      <div class="card">
        <h3>回本进度</h3>
        <div class="big num">—</div>
        <div class="delta">投资回本数据未接入月报</div>
        <div class="muted-sm" style="margin-top:10px">回本进度需累计净流入核算，见「回本测算」页新建方案。</div>
      </div>
    </div>
  </template>
  <!-- 后端成功但 ov 缺失等异常：兜底页，不白屏 -->
  <template v-else>
    <div class="card">
      <div class="card-head-row">
        <div class="card-title">数据异常</div>
        <button class="btn btn-primary btn-sm" @click="reload">重试</button>
      </div>
      <EmptyState text="看板数据响应异常，请重试或检查后端服务" />
    </div>
  </template>
</template>
