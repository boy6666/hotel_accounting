<script setup>
// AI 定价 · 预测 FW-10（三合一）：建议价 / 档位 / 目标倒推 / 预测+LLM 解读
// 旁车/LLM 不可用 → 页内降级提示（纯统计结果保底），页面不白屏。
import { ref, computed, watch, onMounted } from 'vue'
import { pricingApi, predictionApi, profitApi, roomApi } from '@/api'
import { useMonthStore } from '@/stores/month'
import { usePalette } from '@/composables/usePalette'
import { useToastStore } from '@/stores/toast'
import { addMonths, fmtMoney, monthLabel } from '@/utils/format'
import StatCard from '@/components/StatCard.vue'
import ChartView from '@/components/ChartView.vue'
import AiBox from '@/components/AiBox.vue'
import TierTable from '@/components/TierTable.vue'
import DataTable from '@/components/DataTable.vue'
import EmptyState from '@/components/EmptyState.vue'

const monthStore = useMonthStore()
const toast = useToastStore()
const { baseOpt, categoryColors } = usePalette()

const METRIC_CN = { revenue: '月收入', nights: '间夜', occupancy_rate: '入住率', adr: '平均房价', price: '建议均价' }

// ---------- 工具 ----------
const toArr = (d) => (Array.isArray(d) ? d : (d && Array.isArray(d.list) ? d.list : (d && Array.isArray(d.items) ? d.items : [])))
const pad = (n) => String(n).padStart(2, '0')
const todayStr = () => {
  const d = new Date()
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}
function addDays(date, n) {
  const [y, m, d] = date.split('-').map(Number)
  const nd = new Date(y, m - 1, d + n)
  return `${nd.getFullYear()}-${pad(nd.getMonth() + 1)}-${pad(nd.getDate())}`
}

// ---------- 顶卡 1：本月实际均价 ----------
const summary = ref(null)
const adrValue = computed(() => (summary.value && Number.isFinite(Number(summary.value.adr)) ? fmtMoney(summary.value.adr) : '—'))

// ---------- 顶卡 3：下月预测入住率 ----------
const nextMonth = computed(() => addMonths(monthStore.month, 1))
const nextOcc = ref(null)
const nextOccLabel = computed(() => (nextOcc.value ? Number(nextOcc.value.predictedValue).toFixed(1) : '—'))

// ---------- 建议价卡 ----------
const sugFrom = ref(todayStr())
const sugTo = ref(addDays(todayStr(), 13))
const suggestions = ref([])
const sugLoading = ref(false)
const sugArr = computed(() => suggestions.value)
const editingPrice = ref({})

const lockN = computed(() => sugArr.value.filter((r) => r.source === 'manual').length)
const holidayN = computed(() => sugArr.value.filter((r) => r.isHoliday).length)
const sugAvg = computed(() => {
  if (!sugArr.value.length) return null
  return sugArr.value.reduce((a, r) => a + Number(r.suggestedPrice || 0), 0) / sugArr.value.length
})
const sugAvgLabel = computed(() => (sugAvg.value == null ? '未生成' : fmtMoney(sugAvg.value)))

async function loadSuggestions() {
  try {
    const d = await pricingApi.suggestions(sugFrom.value, sugTo.value)
    editingPrice.value = {}
    suggestions.value = toArr(d)
  } catch (e) { suggestions.value = [] }
}
async function generate() {
  if (sugFrom.value > sugTo.value) { toast.warn('请检查建议价区间'); return }
  sugLoading.value = true
  try {
    await pricingApi.generateSuggestions(sugFrom.value, sugTo.value)
    await loadSuggestions()
    toast.success(`已生成 ${sugArr.value.length} 天建议价`)
  } catch (e) { /* toast 已提示 */ } finally { sugLoading.value = false }
}

function priceColor(r) {
  const c = categoryColors.value
  if (r.source === 'manual') return c[7]
  if (r.isHoliday || (r.tierName || '').includes('节假日')) return c[6]
  if (r.isWeekend || (r.tierName || '').includes('周末')) return c[3]
  return c[0]
}
function sugTooltip(params) {
  const p = Array.isArray(params) ? params[0] : params
  const r = sugArr.value[(p && p.dataIndex) || 0]
  if (!r) return ''
  const src = r.source === 'manual' ? '手动锁定' : (r.source === 'llm' ? 'LLM 建议' : '引擎自动')
  return [
    `<b>${r.bizDate}</b>${r.isWeekend ? ' · 周末' : ''}${r.isHoliday ? ' · 节假日' : ''}`,
    `建议价：<b>¥${r.suggestedPrice}</b>（${r.tierName}）`,
    `预测入住率：${r.occupancyForecast == null ? '—' : r.occupancyForecast + '%'}`,
    `来源：${src}`
  ].join('<br/>')
}
const sugChartOpt = computed(() => {
  const rows = sugArr.value
  if (!rows.length) return baseOpt()
  return {
    ...baseOpt(),
    tooltip: { ...baseOpt().tooltip, trigger: 'axis', formatter: sugTooltip },
    xAxis: { ...baseOpt().xAxis, data: rows.map((r) => r.bizDate.slice(5).replace('-', '/')) },
    yAxis: { ...baseOpt().yAxis, name: '元' },
    series: [{
      name: '建议价',
      type: 'line',
      smooth: false,
      symbol: 'circle',
      symbolSize: 7,
      lineStyle: { width: 2 },
      data: rows.map((r) => ({ value: r.suggestedPrice, itemStyle: { color: priceColor(r) } })),
      markLine: {
        silent: true,
        symbol: 'none',
        label: { show: false },
        lineStyle: { type: 'dashed', color: categoryColors.value[4] },
        data: [{ yAxis: Math.round(rows.reduce((a, r) => a + Number(r.suggestedPrice || 0), 0) / rows.length) }]
      }
    }]
  }
})

function tierTagCls(r) {
  const name = r.tierName || ''
  if (name.includes('节假日')) return 'once'
  if (name.includes('周末')) return 'warn'
  return 'ok'
}
const sugColumns = [
  { key: 'bizDate', label: '日期', format: (r) => r.bizDate.slice(5).replace('-', '/'), strong: true },
  { key: 'tierName', label: '档位', tag: (r) => ({ text: r.tierName, cls: tierTagCls(r) }) },
  { key: 'suggestedPrice', label: '建议价(元)', align: 'right' },
  { key: 'occupancyForecast', label: '预测入住率', align: 'right', format: (r) => (r.occupancyForecast == null ? '—' : r.occupancyForecast + '%') },
  { key: 'source', label: '来源', tag: (r) => (r.source === 'manual' ? { text: '锁定', cls: 'warn' } : { text: '引擎', cls: 'ok' }) }
]
function onPriceChange(row, e) {
  const v = Number(e.target.value)
  editingPrice.value[row.bizDate] = e.target.value
  if (!isFinite(v) || v <= 0) { toast.warn('价格需为正数'); return }
  savePrice(row, v)
}
async function savePrice(row, v) {
  try {
    await pricingApi.updateSuggestion(row.bizDate, v)
    toast.success(`${row.bizDate} 已锁定为 ¥${v}`)
    await loadSuggestions()
  } catch (e) { /* toast 已提示 */ }
}

// ---------- 目标倒推卡 ----------
const enabledRooms = ref(10)
const targetRevenue = ref(80000)
const targetOcc = ref(85)
const targetRooms = ref(10)
const targetCalc = ref(null)
const calcLoading = ref(false)
const scenarioName = ref('')
const calcScenarios = ref([])
const savingCalc = ref(false)
let calcTimer = null

async function runCalc() {
  calcTimer = null
  calcLoading.value = true
  try {
    targetCalc.value = await pricingApi.calcTarget({
      targetRevenue: targetRevenue.value, targetOccupancy: targetOcc.value, roomCount: targetRooms.value
    })
  } catch (e) { targetCalc.value = null } finally { calcLoading.value = false }
}
function scheduleCalc() {
  clearTimeout(calcTimer)
  calcTimer = setTimeout(runCalc, 350)
}
watch([targetRevenue, targetOcc, targetRooms], scheduleCalc)

async function saveScenario() {
  if (!targetCalc.value) return
  savingCalc.value = true
  try {
    await pricingApi.saveCalcScenario({
      name: scenarioName.value.trim(),
      targetRevenue: targetRevenue.value, targetOccupancy: targetOcc.value,
      roomCount: targetRooms.value, targetPrice: targetCalc.value.targetPrice
    })
    toast.success('目标方案已保存')
    scenarioName.value = ''
    await loadCalcScenarios()
  } catch (e) { /* toast 已提示 */ } finally { savingCalc.value = false }
}
async function loadCalcScenarios() {
  try { calcScenarios.value = toArr(await pricingApi.listCalcScenarios()) } catch (e) { calcScenarios.value = [] }
}
function applyCalcScenario(id) {
  const s = calcScenarios.value.find((x) => String(x.id) === String(id))
  if (!s) return
  targetRevenue.value = Number(s.targetRevenue ?? targetRevenue.value)
  targetOcc.value = Number(s.targetOccupancy ?? targetOcc.value)
  targetRooms.value = Number(s.roomCount ?? targetRooms.value)
  runCalc()
}

// ---------- 预测卡 ----------
const predMonth = ref(addMonths(monthStore.month, 1))
const predMetric = ref('revenue')
const prediction = ref(null)
const predLoading = ref(false)
const predError = ref('')     // AI 降级/失败持久提示（预测卡内显示）
const predHistory = ref([])
const monthlyRows = ref([])

const aiItems = computed(() => {
  const p = prediction.value
  return p && p.llmInterpretation ? [{ text: p.llmInterpretation }] : []
})
// C4 兼容：返回对象有 llmAvailable 字段则优先用它（false=LLM 不可用），没有则回退现状 degraded
const predDegraded = computed(() => !!(prediction.value && (prediction.value.llmAvailable === false || prediction.value.degraded)))
// 降级或完全失败均置灰：失败路径 prediction=null 时也提示 AI 不可用
const aiGrey = computed(() => predDegraded.value || !!predError.value)
const aiEmptyText = computed(() => ((predDegraded.value || !!predError.value)
  ? 'AI 服务不可用，以上为纯统计结果。'
  : '生成预测后，这里会给出 AI 经营解读（旁车聚合摘要）。'))

function engineLabel(e) {
  return { statistical: '纯统计', hybrid: '混合 · 统计+LLM', llm: 'LLM' }[e] || e || '—'
}
function fmtPred(v, raw = false) {
  const n = Number(v)
  if (!isFinite(n)) return '—'
  const m = (prediction.value && prediction.value.metric) || predMetric.value
  if (m === 'occupancy_rate') return n.toFixed(1) + '%'
  if (m === 'nights') return (raw ? Math.round(n) + ' 间夜' : Math.round(n))
  return (raw ? '¥' : '¥') + fmtMoney(n)
}

// 预测失败原因映射（50100/50200/50300 或网络失败 → 中文降级文案）
function predReason(e) {
  if (e && (e.code === 50100 || e.code === 50200 || e.code === 50300)) return '智能服务异常'
  if (e && (e.code === 'NETWORK' || e.code === 'ECONNABORTED')) return '网络异常，请检查后端服务'
  return (e && e.message) || '服务异常'
}

async function generatePrediction() {
  predLoading.value = true
  predError.value = ''
  try {
    prediction.value = await predictionApi.generate(predMonth.value, predMetric.value)
    if (predDegraded.value) {
      toast.degrade('AI 服务暂不可用，以上为纯统计结果')
      predError.value = 'AI 服务暂不可用，已降级为纯统计结果（旁车/LLM 暂不可用）'
    }
    await loadPredHistory()
  } catch (e) {
    prediction.value = null
    predError.value = 'AI 服务暂不可用，已降级为纯统计结果（' + predReason(e) + '）'
  } finally { predLoading.value = false }
}
async function loadPredHistory() {
  try { predHistory.value = toArr(await predictionApi.results(predMonth.value)) } catch (e) { predHistory.value = [] }
}

// ---------- 顶卡 4：重点提示 ----------
const alertValue = computed(() => {
  if (!sugArr.value.length) return '—'
  return `${holidayN.value} 天节假日 · ${lockN.value} 天锁定`
})
const alertHint = computed(() => {
  if (holidayN.value) return '临近日命中国庆档，建议提前锁房'
  if (lockN.value) return '手改价已锁定，重新生成不覆盖'
  return '临近日全部自动定价'
})

// ---------- 档位 ----------
const tiers = ref([])
async function loadTiers() {
  try { tiers.value = toArr(await pricingApi.tiers()) } catch (e) { tiers.value = [] }
}

// ---------- 全局月份联动 ----------
async function loadSummary() {
  try { summary.value = await profitApi.summary(monthStore.month) } catch (e) { summary.value = null }
}
async function loadNextOcc() {
  try {
    const d = await predictionApi.results(nextMonth.value, 'occupancy_rate')
    const arr = toArr(d)
    nextOcc.value = arr.length ? arr[0] : null
  } catch (e) { nextOcc.value = null }
}
async function loadMonthlyTrend() {
  try {
    const d = await profitApi.monthly(`${new Date().getFullYear()}-01`, monthStore.month)
    monthlyRows.value = (d && Array.isArray(d.list) ? d.list : [])
  } catch (e) { monthlyRows.value = [] }
}
async function loadRooms() {
  try {
    const d = await roomApi.list({ enabled: '1', pageSize: 100 })
    const n = Number(d && (d.total != null ? d.total : d.list && d.list.length))
    if (n > 0) { enabledRooms.value = n; targetRooms.value = n }
  } catch (e) { /* 保持默认 10 */ }
}
function onGlobalMonthChange() {
  predMonth.value = addMonths(monthStore.month, 1)
  loadSummary()
  loadNextOcc()
  loadMonthlyTrend()
  loadRooms()
}
watch(() => monthStore.month, onGlobalMonthChange)

onMounted(() => {
  loadSummary()
  loadNextOcc()
  loadMonthlyTrend()
  loadRooms()
  loadTiers()
  loadCalcScenarios()
  runCalc()
})
</script>

<template>
  <div>
    <div class="row cards">
      <StatCard label="本月实际均价" :value="adrValue" unit="元" hint="按到手价" />
      <StatCard label="未来14天 · 建议均价" :value="sugAvgLabel" unit="元" :hint="sugAvg == null ? '点击「生成建议价」' : '当前区间均值'" />
      <StatCard label="重点提示" :value="alertValue" :hint="alertHint" />
      <StatCard label="下月预测入住率" :value="nextOccLabel" unit="%" :hint="'下月 ' + monthLabel(nextMonth)" />
    </div>

    <div class="row cards3">
      <div class="card" style="grid-column: span 2">
        <div class="card-head-row">
          <h3>未来14天建议价（分档：平/周末）<span class="card-sub">区间 {{ sugFrom }} ~ {{ sugTo }} · 手改即锁定，重新生成不覆盖</span></h3>
          <div class="head-actions">
            <input type="date" class="tbl-input" v-model="sugFrom" />
            <span class="muted-sm">~</span>
            <input type="date" class="tbl-input" v-model="sugTo" />
            <button class="btn btn-primary btn-sm" :disabled="sugLoading" @click="generate">生成建议价</button>
          </div>
        </div>

        <template v-if="sugArr.length">
          <ChartView class="chart md" :option="sugChartOpt" height="230px" />
          <div class="legend-mini">
            <span><i style="background: var(--s1)"></i>平日档</span>
            <span><i style="background: var(--s4)"></i>周末档</span>
            <span><i style="background: var(--s7)"></i>节假日档</span>
            <span><i style="background: var(--s8)"></i>手动锁定</span>
          </div>
          <DataTable style="margin-top: 12px" :columns="sugColumns" :rows="sugArr" :loading="sugLoading" empty="该区间尚未生成建议价">
            <template #cell:suggestedPrice="{ row }">
              <input
                class="tbl-input num" type="number" min="1" step="1" style="width: 86px"
                :value="editingPrice[row.bizDate] ?? row.suggestedPrice"
                @change="onPriceChange(row, $event)"
              />
            </template>
          </DataTable>
        </template>
        <EmptyState v-else :text="sugLoading ? '正在生成…' : '选择区间后点击「生成建议价」'" />
      </div>

      <div class="card">
        <h3>档位价目表<span class="card-sub">建议价基准 · 档位 CRUD 在设置页</span></h3>
        <TierTable :tiers="tiers" />
      </div>
    </div>

    <div class="row" style="grid-template-columns: 1fr 1fr">
      <div class="card">
        <div class="card-head-row">
          <h3>目标倒推定价<span class="card-sub">均价 = 目标收入 ÷ (房间数 × 月天数 × 入住率)</span></h3>
          <div class="head-actions">
            <input class="tbl-input" v-model="scenarioName" placeholder="方案名（可空）" style="width: 104px" />
            <button class="btn btn-ghost btn-sm" :disabled="savingCalc" @click="saveScenario">保存方案</button>
          </div>
        </div>
        <div class="calc">
          <div>
            <label>目标月收入（元）</label>
            <input type="range" min="40000" max="120000" step="1000" v-model.number="targetRevenue" />
            <div class="num">{{ targetRevenue.toLocaleString() }}</div>
          </div>
          <div>
            <label>目标入住率（%）</label>
            <input type="range" min="50" max="100" step="1" v-model.number="targetOcc" />
            <div class="num">{{ targetOcc }}%</div>
          </div>
          <div>
            <label>可售房间数</label>
            <input type="range" min="4" max="20" step="1" v-model.number="targetRooms" />
            <div class="num">{{ targetRooms }} 间</div>
          </div>
          <div>
            <label>需要达到的均价（元/间夜）</label>
            <div class="out num hl" :class="{ 'is-loading': calcLoading }">
              {{ targetCalc ? '¥' + fmtMoney(targetCalc.targetPrice, { digits: 1 }) : '—' }}
            </div>
            <div class="muted-sm">= {{ targetCalc?.monthly ? Number(targetCalc.monthly.revenue || 0).toLocaleString() : '—' }} ÷ {{ targetCalc?.monthly ? fmtMoney(targetCalc.monthly.nights, { digits: 1 }) : '—' }} 间夜</div>
          </div>
        </div>
        <div class="check-line" style="margin-top: 12px">
          <span class="muted-sm">最近方案：</span>
          <select v-if="calcScenarios.length" class="tbl-input" @change="applyCalcScenario($event.target.value)">
            <option value="">— 选择历史方案 —</option>
            <option v-for="s in calcScenarios" :key="s.id" :value="s.id">{{ s.name }}</option>
          </select>
          <span v-else class="muted-sm">暂无已保存方案</span>
        </div>
      </div>

      <div class="card">
        <div class="card-head-row">
          <h3>AI 解读（统计模型 + 旁车 LLM · 失败自动降级）<span class="card-sub">选择月份与指标后生成预测</span></h3>
          <div class="head-actions">
            <input type="month" class="tbl-input" v-model="predMonth" />
            <select class="tbl-input" v-model="predMetric">
              <option value="revenue">收入</option>
              <option value="nights">间夜</option>
              <option value="occupancy_rate">入住率</option>
              <option value="adr">ADR</option>
              <option value="price">均价</option>
            </select>
            <button class="btn btn-primary btn-sm" :disabled="predLoading" @click="generatePrediction">生成预测</button>
          </div>
        </div>

        <template v-if="prediction">
          <div class="check-line" style="gap: 10px">
            <span class="big num" style="font-size: 30px">{{ fmtPred(prediction.predictedValue) }}</span>
            <span class="tag" :class="predDegraded ? 'warn' : 'ok'">{{ engineLabel(prediction.engine) }}</span>
          </div>
          <div class="muted-sm" style="margin-top: 4px">
            置信区间 {{ fmtPred(prediction.confidenceLow, true) }} ~ {{ fmtPred(prediction.confidenceHigh, true) }} ·
            {{ predMonth }}
          </div>
          <p v-if="predError" class="muted-sm" style="margin-top:8px;color:var(--s8)">{{ predError }}</p>
        </template>
        <template v-else-if="predError">
          <EmptyState :text="predError" />
          <div style="margin-top:10px;text-align:center">
            <button class="btn btn-primary btn-sm" :disabled="predLoading" @click="generatePrediction">重试</button>
          </div>
        </template>
        <EmptyState v-else :text="predLoading ? '预测生成中…' : '选择月份与指标后点击「生成预测」'" />

        <AiBox
          title="✦ 捌宿·智能定价分析"
          :items="aiItems"
          :grey="aiGrey"
          :emptyText="aiEmptyText"
          :loading="predLoading"
        />
      </div>
    </div>
  </div>
</template>
