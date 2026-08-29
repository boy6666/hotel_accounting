<script setup>
// 房态·入住率 FW-07：4 统计卡 + 房间×日期矩阵 + 每日入住趋势 + 工作日/周末入住率 + 房态/流水对账
import { ref, computed } from 'vue'
import { usePageData } from '@/composables/usePageData'
import { usePalette } from '@/composables/usePalette'
import StatCard from '@/components/StatCard.vue'
import ChartView from '@/components/ChartView.vue'
import RoomMatrix from '@/components/RoomMatrix.vue'
import DayRoomEditor from '@/components/DayRoomEditor.vue'
import { occApi, roomApi } from '@/api'
import NoticeBar from '@/components/NoticeBar.vue'
import EmptyState from '@/components/EmptyState.vue'
import { fmtPct, monthLabel } from '@/utils/format'

const { month, loading, data, error, errorMsg, reload } = usePageData(async (m) => {
  const [matrix, daily, wd, rec, rooms] = await Promise.all([
    occApi.matrix(m),
    occApi.daily(m),
    occApi.workdayRate(m),
    occApi.reconcile(m),
    roomApi.list({ enabled: '1', pageSize: 100 })
  ])
  const dailyList = daily.list || []
  const occN = dailyList.reduce((a, r) => a + r.occupiedRooms, 0)
  const nonEmpty = dailyList.filter((r) => r.occupiedRooms > 0).length
  const enabled = rooms.list.length || dailyList[0]?.totalRooms || 0
  return { matrix, dailyList, wd, rec, rooms, occN, nonEmpty, enabled }
})

const avgRate = computed(() => {
  const d = data.value
  if (!d || !d.enabled || !d.nonEmpty) return null
  return (d.occN / (d.enabled * d.nonEmpty)) * 100
})
const diffAbs = computed(() => Math.abs(data.value?.rec?.diff || 0))
const recMatched = computed(() => data.value?.rec?.reconcileStatus === 'matched')

// 月度最高入住率（取真实每日数据）
const maxRate = computed(() => {
  const d = data.value
  if (!d) return null
  let best = null
  d.dailyList.forEach((r) => {
    if (best == null || (r.occupancyRate ?? 0) > best.rate) best = { rate: r.occupancyRate, date: r.bizDate }
  })
  return best
})

const { baseOpt, categoryColors, hexA } = usePalette()

const wdOpt = computed(() => {
  const w = data.value?.wd
  if (!w) return baseOpt()
  return {
    ...baseOpt(),
    tooltip: { ...baseOpt().tooltip, valueFormatter: (v) => v + '%' },
    xAxis: { ...baseOpt().xAxis, data: ['工作日', '周末'], axisLabel: { color: baseOpt().xAxis.axisLabel.color } },
    series: [{
      name: '平均入住率', type: 'bar', barWidth: 56,
      itemStyle: { borderRadius: [4, 4, 0, 0] },
      data: [w.workdayRate, w.weekendRate]
    }]
  }
})

const dailyTrendOpt = computed(() => {
  const d = data.value
  if (!d || !d.dailyList.length) return baseOpt()
  const c = categoryColors.value
  return {
    ...baseOpt(),
    tooltip: { ...baseOpt().tooltip, valueFormatter: (v) => v + ' 间' },
    xAxis: { ...baseOpt().xAxis, data: d.dailyList.map((r) => Number(r.bizDate.slice(-2))) },
    series: [{
      name: '入住房间数', type: 'line', symbol: 'circle', symbolSize: 5,
      itemStyle: { color: c[0] }, lineStyle: { width: 2 },
      areaStyle: { color: hexA(c[0], 0.10) },
      data: d.dailyList.map((r) => r.occupiedRooms)
    }]
  }
})

// 本月是否已有房态数据（每日明细 / 矩阵房间）
const hasOccData = computed(() => (data.value?.dailyList?.length || 0) > 0)

// 点击矩阵单元格 → 打开当日编辑
const editingDate = ref(null)
function onCellClick({ date }) { editingDate.value = date }
function onEdited() { reload() }
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
    <NoticeBar v-if="!hasOccData" :tone="'warn'" title="本月暂无房态数据（数据暂未上传）">
      请先到「导入」页上传 {{ monthLabel(month.month) }} 的每日房态，或在下方矩阵点击单元格补录。
    </NoticeBar>
    <div class="row cards">
      <StatCard label="本月入住率":value="avgRate == null ? '—' : fmtPct(avgRate)" :hint="`${data.occN} / ${data.nonEmpty} 天 · ${data.enabled} 间可售`" />
      <StatCard label="可售房间数" :value="data.enabled" unit="间" hint="= 房间表中启用房数（禁售后自动减）" />
      <StatCard
        label="月度最高入住率"
        :value="maxRate ? fmtPct(maxRate.rate) : '—'"
        :hint="maxRate ? monthLabel(maxRate.date.slice(0, 7)) + ' ' + maxRate.date.slice(5) : '—'"
      />
      <StatCard
        label="对账差异（流水−实际）" :value="diffAbs" unit="间夜"
        :tone="recMatched ? 'ok' : 'warn'" :hint="recMatched ? '房态 vs 流水 · 已对齐' : '存在差异，见下方对账表'"
      />
    </div>

    <div class="card">
      <div class="card-head-row">
        <div class="card-title">
          {{ month.month }} · 房间 × 日期 入住矩阵
          <span class="card-sub">每天均分为 ▮ 入住 / · 空房；点击单元格修改该日入住房号</span>
        </div>
      </div>
      <RoomMatrix :month="month.month" :rooms="data.matrix.rooms" :loading="loading" @cell-click="onCellClick" />
    </div>

    <div class="row cards3">
      <div class="card" style="grid-column: span 2">
        <h3>每日入住房间数（{{ monthLabel(month.month) }}）· 趋势</h3>
        <ChartView v-if="hasOccData" class="chart lg" :option="dailyTrendOpt" height="300px" />
        <EmptyState v-else text="本月暂无房态数据" />
      </div>
      <div class="card">
        <h3>工作日 / 周末平均入住率</h3>
        <template v-if="hasOccData && data.wd">
          <ChartView class="chart md" :option="wdOpt" height="250px" />
          <div class="legend-mini">
            <span><i style="background: var(--s1)"></i>工作日 {{ data.wd.workdayNights ?? 0 }} 间夜 / {{ data.wd.workdayDays ?? 0 }} 天</span>
            <span><i style="background: var(--s1)"></i>周末 {{ data.wd.weekendNights ?? 0 }} 间夜 / {{ data.wd.weekendDays ?? 0 }} 天</span>
          </div>
        </template>
        <EmptyState v-else text="本月暂无房态数据" />
      </div>
    </div>

    <div class="card">
      <div class="card-head-row">
        <div class="card-title">
          房态 / 流水对账（{{ monthLabel(month.month) }}）
          <span class="tag" :class="recMatched ? 'ok' : 'warn'">{{ recMatched ? '差异 0 ✓' : '存在差异' }}</span>
        </div>
      </div>
      <table class="data-table">
        <thead><tr><th>数据源</th><th class="num">间夜</th><th class="num">差异</th><th>说明</th></tr></thead>
        <tbody>
          <tr>
            <td>每日房态累计（登记）</td>
            <td class="num">{{ data.rec?.occupancyNights ?? '—' }}</td>
            <td class="num"></td>
            <td>按日登记自动汇总</td>
          </tr>
          <tr>
            <td>流水间夜（销售表）</td>
            <td class="num">{{ data.rec?.channelNights ?? '—' }}</td>
            <td class="num"></td>
            <td>含线下协议 / 中介</td>
          </tr>
          <tr>
            <td><b>差值</b></td>
            <td class="num"><b>{{ recMatched ? 0 : diffAbs }}</b></td>
            <td class="num"><b>{{ recMatched ? 0 : diffAbs }}</b></td>
            <td>{{ recMatched ? '渠道与房态对齐 —— 无需处理' : '请核对每日明细，可点击矩阵单元格补录' }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </template>

  <DayRoomEditor
    :show="!!editingDate"
    :biz-date="editingDate || ''"
    :rooms="data?.rooms.list || []"
    @close="editingDate = null"
    @saved="onEdited"
  />
</template>
