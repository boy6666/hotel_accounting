<script setup>
// 销售渠道 FW-05：汇总卡(4) + 各渠道间夜 + 线上/线下收入 + 线上/线下月度趋势 + 渠道表
import { computed } from 'vue'
import { usePageData } from '@/composables/usePageData'
import { usePalette } from '@/composables/usePalette'
import StatCard from '@/components/StatCard.vue'
import ChartView from '@/components/ChartView.vue'
import DataTable from '@/components/DataTable.vue'
import EmptyState from '@/components/EmptyState.vue'
import NoticeBar from '@/components/NoticeBar.vue'
import { channelApi } from '@/api'
import { fmtMoney, monthLabel } from '@/utils/format'

const { month, loading, data, error, errorMsg, reload } = usePageData(async (m) => {
  const [mon, tr] = await Promise.all([
    channelApi.monthly(m),
    channelApi.trend(`${new Date().getFullYear()}-01`, m)
  ])
  return { ...mon, months: tr?.months || [], onlineTrend: tr?.onlineNights || [], offlineTrend: tr?.offlineNights || [] }
})

const rows = computed(() => data.value?.list || [])

const cards = computed(() => {
  const r = rows.value
  const onlineN = r.filter((x) => x.type === 'online').reduce((a, x) => a + x.nights, 0)
  const offlineN = r.filter((x) => x.type === 'offline').reduce((a, x) => a + x.nights, 0)
  const onlineRev = r.filter((x) => x.type === 'online').reduce((a, x) => a + Number(x.revenue || 0), 0)
  const offlineRev = r.filter((x) => x.type === 'offline').reduce((a, x) => a + Number(x.revenue || 0), 0)
  const commission = r.reduce((a, x) => a + Number(x.commission || 0), 0)
  return {
    onlineN, offlineN, onlineRev, offlineRev, commission,
    onlineShare: onlineN + offlineN ? onlineN / (onlineN + offlineN) : 0
  }
})

const { baseOpt, categoryColors, hexA } = usePalette()

const barOpt = computed(() => {
  const r = rows.value.slice().sort((a, b) => b.nights - a.nights)
  if (!r.length) return baseOpt()
  return {
    ...baseOpt(),
    tooltip: { ...baseOpt().tooltip, valueFormatter: (v) => v + ' 间夜' },
    xAxis: { ...baseOpt().xAxis, data: r.map((x) => x.channelName), axisLabel: { color: baseOpt().xAxis.axisLabel.color, interval: 0, rotate: 20 } },
    series: [{
      name: '间夜', type: 'bar', barWidth: 26,
      itemStyle: { borderRadius: [4, 4, 0, 0] },
      data: r.map((x) => x.nights)
    }]
  }
})

const donutOpt = computed(() => {
  const r = rows.value.slice().sort((a, b) => b.nights - a.nights)
  if (!r.length) return {}
  return {
    ...baseOpt({ tooltip: 'item' }),
    tooltip: { ...baseOpt().tooltip, trigger: 'item', valueFormatter: (v) => v + ' 间夜' },
    series: [{
      type: 'pie', radius: ['44%', '70%'],
      label: { show: true, formatter: '{b}\n{d}%', color: 'inherit', fontSize: 11 },
      labelLine: { length: 8, length2: 6 },
      data: r.map((x, i) => ({ name: x.channelName, value: x.nights, itemStyle: { borderRadius: 2 } }))
    }]
  }
})

const trendOpt = computed(() => {
  const d = data.value
  if (!d || !d.months?.length) return baseOpt({ legend: true })
  const c = categoryColors.value
  return {
    ...baseOpt({ legend: true }),
    tooltip: { ...baseOpt().tooltip, valueFormatter: (v) => v + ' 间夜' },
    xAxis: { ...baseOpt().xAxis, data: d.months.map((m) => monthLabel(m)) },
    series: [
      { name: '线上间夜', type: 'line', smooth: true, symbolSize: 5, data: d.onlineTrend, areaStyle: { color: hexA(c[0], 0.10) }, lineStyle: { width: 2 } },
      { name: '线下间夜', type: 'line', smooth: true, symbolSize: 5, data: d.offlineTrend, lineStyle: { width: 2 } }
    ]
  }
})

// 挂牌价推算：挂牌 = 到手均价 / (1 − 佣金率)
function listedAvg(row) {
  if (!row.avgPrice || row.commissionRate == null) return null
  if (row.type === 'offline' || row.commissionRate === 0) return row.avgPrice
  return row.avgPrice / (1 - row.commissionRate)
}

const columns = [
  {
    key: 'channelName', label: '渠道',
    tag: (r) => ({ text: r.type === 'online' ? '线上' : '线下', cls: r.type === 'online' ? 'online' : 'offline' }),
    strong: true
  },
  { key: 'nights', label: '间夜', align: 'right' },
  { key: 'revenue', label: '到手收入', align: 'right', format: (r) => '¥' + fmtMoney(r.revenue) },
  { key: 'avgPrice', label: '到手均价', align: 'right', format: (r) => '¥' + fmtMoney(r.avgPrice, { digits: 2 }) },
  {
    key: 'listedAvg', label: '挂牌价推算', align: 'right',
    format: (r) => (r.type === 'online' ? '¥' + fmtMoney(listedAvg(r), { digits: 2 }) : '= 到手'),
    tag: (r) => (r.type === 'online' ? { text: '在线渠道', cls: 'muted' } : null)
  },
  { key: 'commission', label: '佣金', align: 'right', format: (r) => '¥' + fmtMoney(r.commission || 0) },
  { key: 'commissionRate', label: '佣金率', align: 'right', format: (r) => (r.commissionRate ? (r.commissionRate * 100).toFixed(1) + '%' : '0%') },
  { key: 'share', label: '间夜占比', align: 'right', format: (r) => (r.share ? (r.share * 100).toFixed(1) + '%' : '—') }
]
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
      <StatCard label="本月总间夜" :value="cards.onlineN + cards.offlineN" unit="间夜" :hint="`线上 ${cards.onlineN} · 线下 ${cards.offlineN}`" />
      <StatCard label="线上收入" :value="fmtMoney(cards.onlineRev)" unit="元" :hint="`占比 ${(cards.onlineShare * 100).toFixed(1)}%`" />
      <StatCard label="线下收入" :value="fmtMoney(cards.offlineRev)" unit="元" :hint="`占比 ${(100 - cards.onlineShare * 100).toFixed(1)}%`" />
      <StatCard label="渠道佣金合计" :value="fmtMoney(cards.commission)" unit="元" hint="线上佣金 · 到手价口径" />
    </div>

    <div class="row cards3">
      <div class="card">
        <h3>各渠道间夜（{{ monthLabel(month.month) }}）</h3>
        <ChartView v-if="rows.length" class="chart md" :option="barOpt" height="250px" />
        <EmptyState v-else text="本月暂无渠道数据" />
      </div>
      <div class="card">
        <h3>线上 / 线下收入（{{ monthLabel(month.month) }}）</h3>
        <ChartView v-if="rows.length" class="chart md" :option="donutOpt" height="250px" />
        <EmptyState v-else text="本月暂无渠道数据" />
      </div>
      <div class="card">
        <h3>线上 / 线下 · 月度趋势</h3>
        <ChartView v-if="data.months && data.months.length" class="chart md" :option="trendOpt" height="250px" />
        <EmptyState v-else text="本月暂无渠道数据" />
      </div>
    </div>

    <div class="card">
      <h3>渠道明细（{{ monthLabel(month.month) }}，到手价口径）</h3>
      <DataTable v-if="rows.length" :rows="rows" :columns="columns" :loading="loading" />
      <EmptyState v-else text="本月暂无渠道数据" />
      <NoticeBar style="margin-top: 12px">
        <b>佣金口径</b>
        线上收入统一记「到手价」，挂牌价 = 到手价 ÷（1 − 佣金率）自动反算；可对接 OTA 报表导入自动比对。
      </NoticeBar>
    </div>
  </template>
</template>
