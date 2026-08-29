<script setup>
// 回本测算 FW-11：方案 CRUD + 累计现金流 + 敏感性分析
// 方案/现金流/敏感性全部走后端真实计算；无数据时空态占位，页面不依赖 mock。
import { ref, computed, watch, onMounted } from 'vue'
import { breakevenApi } from '@/api'
import { useToastStore } from '@/stores/toast'
import { usePalette } from '@/composables/usePalette'
import { fmtMoney, fmtYuan } from '@/utils/format'
import StatCard from '@/components/StatCard.vue'
import ChartView from '@/components/ChartView.vue'
import Modal from '@/components/Modal.vue'
import EmptyState from '@/components/EmptyState.vue'

const toast = useToastStore()
const { baseOpt, categoryColors } = usePalette()

const AXIS_KEYS = ['月净流入', '月供', '投资额']
const FACTORS = [0.8, 0.9, 1.0, 1.1, 1.2]

// ---------- 列表 ----------
const scenarios = ref([])
const listLoading = ref(false)
const currentId = ref(null)
const current = ref(null)   // 供统计卡（pickScenario 形态）
const detailLoading = ref(false)
const cashRows = ref([])
const sensitivity = ref(null)

const cur = computed(() => current.value)

// ---------- 统计卡 ----------
const totalInvest = computed(() => (cur.value ? Number(cur.value.investment) : null))
const loanAmount = computed(() => (cur.value ? Number(cur.value.loanAmount != null ? cur.value.loanAmount : cur.value.investment - cur.value.ownCapital) : null))
const ownCapital = computed(() => (cur.value ? Number(cur.value.ownCapital) : null))
const monthlyPayment = computed(() => (cur.value ? Number(cur.value.monthlyPayment) : null))
const beMonth = computed(() => (cur.value ? Number(cur.value.breakEvenMonth) || null : null))
const beYears = computed(() => (beMonth.value ? (beMonth.value / 12).toFixed(1) : '—'))
const ownPct = computed(() => (totalInvest.value ? Math.round((Number(ownCapital.value || 0) / totalInvest.value) * 100) : 0))

// ---------- 详情 ----------
async function loadDetail(id) {
  if (!id) { current.value = null; cashRows.value = []; sensitivity.value = null; return }
  detailLoading.value = true
  try {
    const d = await breakevenApi.cashflow(id)
    current.value = (d && d.scenario) || scenarios.value.find((s) => String(s.id) === String(id)) || null
    cashRows.value = (d && Array.isArray(d.rows) ? d.rows : []).filter((r) => r.runningBalance != null)
  } catch (e) {
    current.value = scenarios.value.find((s) => String(s.id) === String(id)) || null
    cashRows.value = []
  }
  try {
    const s = await breakevenApi.sensitivity(id)
    sensitivity.value = s || null
  } catch (e) { sensitivity.value = null }
  finally { detailLoading.value = false }
}

async function loadScenarios() {
  listLoading.value = true
  try {
    const d = await breakevenApi.scenarios()
    const arr = d && Array.isArray(d.list) ? d.list : (Array.isArray(d) ? d : [])
    scenarios.value = arr
    if (!arr.length) { currentId.value = null; return }
    if (!arr.some((s) => String(s.id) === String(currentId.value))) {
      currentId.value = arr[0].id
    }
  } catch (e) { scenarios.value = [] } finally { listLoading.value = false }
}
watch(currentId, (id) => loadDetail(id))

// ---------- 现金流图表 ----------
const breakRowIndex = computed(() => cashRows.value.findIndex((r) => r.remark === '回本' || r.runningBalance >= 0))
const cashChartOpt = computed(() => {
  if (!cashRows.value.length) return baseOpt()
  const rows = cashRows.value
  const c = categoryColors.value
  const i = breakRowIndex.value
  const labels = rows.map((r) => String(r.monthSeq))
  const values = rows.map((r) => Number(r.runningBalance))
  // 太长的序列抽样显示，避免 x 轴过密
  const step = rows.length > 60 ? Math.ceil(rows.length / 60) : 1
  const sLabels = labels.filter((_, k) => k % step === 0)
  const sValues = values.filter((_, k) => k % step === 0)
  const mk = (i >= 0 && i < rows.length) ? {
    symbol: 'pin', symbolSize: 46,
    label: { color: '#fff', fontSize: 11, formatter: '回本' },
    itemStyle: { color: c[0] },
    data: [{ coord: [labels[i], values[i]] }]
  } : undefined
  return {
    ...baseOpt(),
    tooltip: { ...baseOpt().tooltip, trigger: 'axis', 'valueFormatter': (v) => fmtYuan(v) },
    xAxis: { ...baseOpt().xAxis, data: sLabels },
    yAxis: { ...baseOpt().yAxis, name: '元' },
    series: [{
      name: '累计现金流',
      type: 'line',
      smooth: false,
      symbol: 'none',
      lineStyle: { width: 2 },
      data: sValues,
      itemStyle: { color: c[1] },
      markLine: {
        silent: true, symbol: 'none',
        lineStyle: { type: 'dashed', width: 1, color: c[7] },
        label: { color: '#999', formatter: '回本线 y=0' },
        data: [{ yAxis: 0 }]
      },
      markPoint: mk
    }]
  }
})
const paidTotal = computed(() => {
  const i = breakRowIndex.value
  if (i >= 0 && i < cashRows.value.length) {
    return Number(cashRows.value[i].monthSeq) * (monthlyPayment.value || 0)
  }
  return null
})

// ---------- 贷款还贷结构图（按真实贷款参数等额本息拆分） ----------
const loanChartOpt = computed(() => {
  const principal = Number(loanAmount.value) || 0
  const rate = Number(cur.value && cur.value.loanRate) || 0
  const years = Number(cur.value && cur.value.loanYears) || 0
  const pay = Number(monthlyPayment.value) || 0
  if (!principal || !rate || !years || !pay) return baseOpt()
  const c = categoryColors.value
  const months = years * 12
  const prin = []
  const int = []
  let bal = principal
  for (let m = 0; m < months && bal > 0.5; m++) {
    const interest = bal * (rate / 12)
    const p = Math.min(pay - interest, bal)
    bal -= p
    prin.push(p)
    int.push(interest)
  }
  const labels = []
  const prinY = []
  const intY = []
  for (let y = 0; y * 12 < prin.length; y++) {
    labels.push('第' + (y + 1) + '年')
    prinY.push(Math.round(prin.slice(y * 12, y * 12 + 12).reduce((a, b) => a + b, 0)))
    intY.push(Math.round(int.slice(y * 12, y * 12 + 12).reduce((a, b) => a + b, 0)))
  }
  return {
    ...baseOpt({ legend: true }),
    tooltip: { ...baseOpt().tooltip, trigger: 'axis', valueFormatter: (v) => fmtYuan(v) },
    xAxis: { ...baseOpt().xAxis, data: labels },
    yAxis: { ...baseOpt().yAxis, name: '元' },
    series: [
      { name: '本金', type: 'bar', stack: 'loan', barWidth: 22, itemStyle: { color: c[0] }, data: prinY },
      { name: '利息', type: 'bar', stack: 'loan', barWidth: 22, itemStyle: { color: c[6] }, data: intY }
    ]
  }
})

// ---------- 现金流表格 ----------
const cashColumns = [
  { key: 'monthSeq', label: '期数', strong: true },
  { key: 'inflow', label: '预计月入', align: 'right', format: (r) => fmtMoney(r.inflow) },
  { key: 'outflow', label: '月供', align: 'right', format: (r) => fmtMoney(r.outflow) },
  { key: 'net', label: '月结余', align: 'right', format: (r) => fmtMoney(r.net) },
  { key: 'runningBalance', label: '累计余额', align: 'right', format: (r) => fmtMoney(r.runningBalance) },
  { key: 'remark', label: '进度', tag: (r) => (r.remark === '回本' || r.runningBalance >= 0 ? { text: '回本', cls: 'ok' } : null) }
]

// ---------- 回本测算参数（可调 · 即时应答试算，不落库） ----------
const wfNet = ref(20000)
const wfRate = ref(3.8)
watch(() => cur.value, (d) => {
  if (!d) return
  wfNet.value = Number(d.monthlyNetInflow) || 0
  wfRate.value = Math.round(Number(d.loanRate) * 10000) / 100
}, { immediate: true })
const whatIfPayment = computed(() => {
  const P = Number(loanAmount.value) || 0
  const r = (Number(wfRate.value) || 0) / 100
  const n = (Number(cur.value && cur.value.loanYears) || 1) * 12
  if (!P || !r) return null
  const m = r / 12
  return (P * m) / (1 - Math.pow(1 + m, -n))
})
const whatIfMonth = computed(() => {
  const P = Number(loanAmount.value) || 0
  const net = Number(wfNet.value) || 0
  const pay = Number(whatIfPayment.value) || 0
  if (!P || !pay || net <= pay) return null // ∞ 无法回本
  return Math.ceil(P / (net - pay))
})
const paramRows = computed(() => {
  if (!cur.value) return []
  return [
    ['总投入', fmtYuan(Number(cur.value.investment) || 0)],
    ['自有资金', fmtYuan(Number(cur.value.ownCapital) || 0)],
    ['贷款金额', fmtYuan(Number(loanAmount.value) || 0)],
    ['年利率', (Number(cur.value.loanRate) * 100).toFixed(1) + '%'],
    ['贷款年限', Number(cur.value.loanYears) + ' 年'],
    ['月净流入', fmtYuan(Number(cur.value.monthlyNetInflow) || 0)],
    ['月供（等额本息）', fmtYuan(Number(monthlyPayment.value) || 0)]
  ]
})

// ---------- 敏感性 ----------
const senseRows = computed(() => {
  const rows = sensitivity.value && Array.isArray(sensitivity.value.rows) ? sensitivity.value.rows : []
  return AXIS_KEYS.map((axis) => ({
    axis,
    isBase: !!(sensitivity.value && sensitivity.value.base && sensitivity.value.base.axis === axis),
    cells: FACTORS.map((f) => {
      const it = rows.find((r) => r.axis === axis && Number(r.factor) === f)
      return { factor: f, month: it && it.breakEvenMonth != null ? Number(it.breakEvenMonth) : null }
    })
  }))
})
const senseBaseMonth = computed(() => (sensitivity.value && sensitivity.value.base ? sensitivity.value.base.breakEvenMonth : null))

// ---------- 增改删 ----------
const modalShow = ref(false)
const modalTitle = ref('新建方案')
const saving = ref(false)
const emptyForm = () => ({ name: '', investment: 2000000, ownCapital: 800000, loanRate: 3.8, loanYears: 10, monthlyNetInflow: 20000 })
const form = ref(emptyForm())
const formLoan = computed(() => {
  const inv = Number(form.value.investment) || 0
  const own = Number(form.value.ownCapital) || 0
  return Math.max(0, inv - own)
})

function createScenario() {
  modalTitle.value = '新建方案'
  form.value = emptyForm()
  modalShow.value = true
}
function editScenario() {
  if (!cur.value) return
  modalTitle.value = '编辑方案'
  form.value = {
    name: cur.value.name || '',
    investment: Number(cur.value.investment),
    ownCapital: Number(cur.value.ownCapital),
    loanRate: Math.round(Number(cur.value.loanRate) * 10000) / 100, // 契约 decimal → 表单显示百分数
    loanYears: Number(cur.value.loanYears),
    monthlyNetInflow: Number(cur.value.monthlyNetInflow)
  }
  modalShow.value = true
}
async function saveScenario() {
  const loanRatePct = Number(form.value.loanRate) || 0
  const f = {
    name: form.value.name.trim(),
    investment: Number(form.value.investment) || 0,
    ownCapital: Number(form.value.ownCapital) || 0,
    loanRate: loanRatePct / 100, // 契约 decimal：0.038 = 3.8%
    loanYears: Number(form.value.loanYears) || 1,
    monthlyNetInflow: Number(form.value.monthlyNetInflow) || 0
  }
  if (f.investment <= 0) { toast.warn('总投入需大于 0'); return }
  if (f.ownCapital < 0 || f.ownCapital > f.investment) { toast.warn('自有资金需在 0~总投入之间'); return }
  if (loanRatePct <= 0 || loanRatePct > 40) { toast.warn('年利率需在 0~40% 之间'); return }
  saving.value = true
  const isNew = modalTitle.value === '新建方案'
  try {
    if (isNew) {
      const d = await breakevenApi.createScenario(f)
      await loadScenarios()
      const id = d && (d.scenario ? d.scenario.id : d.id)
      if (id) currentId.value = id
    } else {
      await breakevenApi.updateScenario(currentId.value, f)
      await loadScenarios()
    }
    toast.success(isNew ? '方案已创建' : '方案已保存')
    modalShow.value = false
  } catch (e) { /* toast 已提示 */ } finally { saving.value = false }
}
async function removeScenario() {
  if (!cur.value) return
  if (!window.confirm(`确认删除方案「${cur.value.name || '未命名'}」？其现金流 / 敏感性记录将一并删除。`)) return
  try {
    await breakevenApi.deleteScenario(currentId.value)
    toast.success('方案已删除')
    currentId.value = null
    await loadScenarios()
  } catch (e) { /* toast 已提示 */ }
}

onMounted(() => loadScenarios())
</script>

<template>
  <div>
    <div v-if="!scenarios.length && !listLoading" class="row">
      <div class="card">
        <div class="card-head-row">
          <h3>回本测算<span class="card-sub">无方案 —— 先创建投资方案（默认参数：总投资 200 万 / 自有 80 万 / 贷 120 万 @3.8% · 10年 / 月净流入 2 万）</span></h3>
          <button class="btn btn-primary btn-sm" @click="createScenario">新建方案</button>
        </div>
        <EmptyState text="暂无方案 —— 点击「新建方案」开始测算" />
      </div>
    </div>

    <template v-else>
      <div class="row cards">
        <StatCard label="总投入" :value="fmtMoney(totalInvest)" unit="元" :hint="cur ? `自有 ${ownPct}% · 贷款 ${100 - ownPct}%` : ''" />
        <StatCard
          label="贷款本金" :value="fmtMoney(loanAmount)" unit="元"
          :hint="cur ? `${Number(cur.loanYears)} 年 · ${(Number(cur.loanRate) * 100).toFixed(1)}% · 月供 ${monthlyPayment == null ? '—' : fmtYuan(monthlyPayment)}` : ''"
        />
        <StatCard label="月供（等额本息）" :value="fmtMoney(monthlyPayment)" unit="元/月" :hint="cur ? '随贷款额 / 利率 / 年限' : ''" tone="ok" />
        <StatCard
          label="预计回本" :value="beYears" unit="年"
          :hint="beMonth ? `第 ${beMonth} 期（${cur.breakEvenDate || '—'}）` : '当前参数无法回本'"
          :tone="beMonth && beMonth > 240 ? 'warn' : (beMonth ? 'ok' : 'warn')"
        />
      </div>

      <div class="row cards3">
        <div class="card" style="grid-column: span 2">
          <div class="card-head-row">
            <h3>累计现金流 · 回本点（含贷款月供）<span class="card-sub">首次余额转正即回本</span></h3>
            <div class="head-actions">
              <select class="tbl-input" :value="currentId" :disabled="!scenarios.length" @change="currentId = Number($event.target.value)">
                <option v-for="s in scenarios" :key="s.id" :value="s.id">{{ s.name }}</option>
              </select>
              <button class="btn btn-ghost btn-sm" @click="createScenario">新建</button>
              <button class="btn btn-ghost btn-sm" :disabled="!cur" @click="editScenario">编辑</button>
              <button class="btn btn-ghost btn-sm danger" :disabled="!cur" @click="removeScenario">删除</button>
            </div>
          </div>

          <div v-if="!cur" class="empty">方案出错或已删除 —— 请重新选择或新建</div>
          <template v-else>
            <ChartView class="chart lg" :option="cashChartOpt" height="280px" />
          </template>
        </div>

        <div class="card">
          <h3>贷款还贷结构<span class="card-sub">等额本息 · 按年拆分本金 / 利息</span></h3>
          <ChartView class="chart md" :option="loanChartOpt" height="280px" />
        </div>
      </div>

      <div class="row" style="grid-template-columns: 1.25fr 1fr">
        <div class="card">
          <h3>敏感性表 · 回本年限（月净流入 / 月供 / 投资额）<span class="card-sub">行 = 敏感轴 · 列 = 缩放系数 · 数值 = 回本月份</span></h3>
          <table class="sense-grid">
            <colgroup>
              <col />
              <col />
              <col class="base-col" />
              <col />
              <col />
              <col />
            </colgroup>
            <thead>
              <tr>
                <th>轴 \\ 系数</th>
                <th v-for="f in FACTORS" :key="f">×{{ f.toFixed(1) }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="ax in senseRows" :key="ax.axis">
                <th>{{ ax.axis }}</th>
                <td v-for="cell in ax.cells" :key="cell.factor" :class="{ 'base-cell': ax.isBase && cell.factor === 1 }">
                  {{ cell.month == null ? '∞' : cell.month }}
                </td>
              </tr>
            </tbody>
          </table>
          <div class="notice" style="margin: 12px 0 0">
            <div>💡</div>
            <div>
              <b>敏感轴提示</b>
              基准回本值为 <b>{{ senseBaseMonth != null ? senseBaseMonth : '—' }}</b> 期；系数 ×0.8 起现金流恶化、回本明显拉长 ——
              建议以「月净流入」为第一敏感轴做压力测试。表内 ∞ 表示该参数下无法回本。
            </div>
          </div>
        </div>

        <div class="card">
          <h3>回本测算参数（可调）<span class="card-sub">正式改参请用「编辑」；下方滑块为即时应答试算，不落库</span></h3>
          <table>
            <tbody>
              <tr v-for="p in paramRows" :key="p[0]">
                <td style="width: 130px">{{ p[0] }}</td>
                <td class="num"><b>{{ p[1] }}</b></td>
              </tr>
            </tbody>
          </table>
          <div class="calc" style="margin-top: 10px">
            <div>
              <label>月净流入（元 · 试算）</label>
              <input type="range" min="0" max="60000" step="500" v-model.number="wfNet" />
              <div class="num">{{ wfNet.toLocaleString() }}</div>
            </div>
            <div>
              <label>年利率（% · 试算）</label>
              <input type="range" min="0" max="15" step="0.1" v-model.number="wfRate" />
              <div class="num">{{ wfRate.toFixed(1) }}%</div>
            </div>
            <div>
              <label>试算月供（等额本息）</label>
              <div class="num">{{ whatIfPayment == null ? '—' : fmtYuan(whatIfPayment) }}</div>
            </div>
            <div>
              <label>试算回本</label>
              <div class="out num hl">{{ whatIfMonth == null ? '无法回本（∞）' : `第 ${whatIfMonth} 期 · ${(whatIfMonth / 12).toFixed(1)} 年` }}</div>
            </div>
          </div>
        </div>
      </div>
    </template>

    <Modal :show="modalShow" :title="modalTitle" @close="modalShow = false">
      <div class="form-grid">
        <div class="span2">
          <label>方案名称</label>
          <input class="tbl-input" v-model="form.name" placeholder="如：主楼二期 / 保守现金流" style="width: 100%" />
        </div>
        <div>
          <label>总投入（元）</label>
          <input class="tbl-input" type="number" min="1" step="10000" v-model.number="form.investment" />
        </div>
        <div>
          <label>自有资金（元）</label>
          <input class="tbl-input" type="number" min="0" step="10000" v-model.number="form.ownCapital" />
        </div>
        <div>
          <label>贷款金额（元 · 自动）</label>
          <input class="tbl-input" type="number" :value="formLoan" disabled />
        </div>
        <div>
          <label>年利率（%）</label>
          <input class="tbl-input" type="number" min="0.1" max="40" step="0.1" v-model.number="form.loanRate" />
        </div>
        <div>
          <label>贷款年限</label>
          <input class="tbl-input" type="number" min="1" max="40" step="1" v-model.number="form.loanYears" />
        </div>
        <div>
          <label>月净流入（元）</label>
          <input class="tbl-input" type="number" min="0" step="500" v-model.number="form.monthlyNetInflow" />
        </div>
      </div>
      <template #footer>
        <button class="btn btn-ghost" @click="modalShow = false">取消</button>
        <button class="btn btn-primary" :disabled="saving" @click="saveScenario">保存</button>
      </template>
    </Modal>
  </div>
</template>
