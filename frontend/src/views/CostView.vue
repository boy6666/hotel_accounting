<script setup>
// 成本分析 FW-04：4 统计卡 + 固定/变动/一次性趋势 + 类型占比 + 明细表（手录/删除/导入跳转）
// 4 卡口径同原型：总成本 / 固定 / 变动 / 单间成本（含固定+变动）
import { ref, computed } from 'vue'
import { usePageData } from '@/composables/usePageData'
import { usePalette } from '@/composables/usePalette'
import StatCard from '@/components/StatCard.vue'
import ChartView from '@/components/ChartView.vue'
import DataTable from '@/components/DataTable.vue'
import EmptyState from '@/components/EmptyState.vue'
import Modal from '@/components/Modal.vue'
import { costApi, dashboardApi } from '@/api'
import { useToastStore } from '@/stores/toast'
import { useImportStore } from '@/stores/import'
import { fmtMoney, fmtPct, monthLabel } from '@/utils/format'

const toast = useToastStore()
const imp = useImportStore()

const { month, loading, data, error, errorMsg, reload } = usePageData(async (m) => {
  const [sum, rows, trend, ov] = await Promise.all([
    costApi.summary(m),
    costApi.list(m, { pageSize: 100 }),
    costApi.trend(`${new Date().getFullYear()}-01`, m),
    dashboardApi.overview(m)
  ])
  return { sum, rows: rows.list || [], total: rows.total, trend, nights: ov?.nights || 0 }
})

const { baseOpt, categoryColors, hexA } = usePalette()

const trendOpt = computed(() => {
  const t = data.value?.trend
  if (!t || !t.months?.length) return baseOpt({ legend: true })
  const c = categoryColors.value
  return {
    ...baseOpt({ legend: true }),
    tooltip: { ...baseOpt().tooltip, valueFormatter: (v) => fmtMoney(v) + ' 元' },
    xAxis: { ...baseOpt().xAxis, data: t.months.map((m) => monthLabel(m)) },
    series: [
      { name: '固定', type: 'line', smooth: true, symbolSize: 5, data: t.fixed, areaStyle: { color: hexA(c[0], 0.10) }, lineStyle: { width: 2 } },
      { name: '变动', type: 'line', smooth: true, symbolSize: 5, data: t.variable, lineStyle: { width: 2 } },
      { name: '一次性', type: 'line', smooth: true, symbolSize: 5, data: t.oneTime, lineStyle: { width: 2 } }
    ]
  }
})

const typeDonutOpt = computed(() => {
  const s = data.value?.sum
  if (!s || s.total <= 0) return {}
  return {
    ...baseOpt({ tooltip: 'item' }),
    color: categoryColors.value.slice(0, 3),
    tooltip: { ...baseOpt().tooltip, valueFormatter: (v) => fmtMoney(v) + ' 元' },
    series: [{
      type: 'pie', radius: ['52%', '76%'],
      label: { show: true, formatter: '{b}\n{d}%', color: 'inherit', fontSize: 11 },
      labelLine: { length: 8, length2: 6 },
      data: [
        { name: '固定', value: s.fixed },
        { name: '变动', value: s.variable },
        { name: '一次性', value: s.oneTime }
      ]
    }]
  }
})

// 单间成本 = 总成本 ÷ 间夜
const perNightCost = computed(() => {
  const s = data.value?.sum?.total
  const n = data.value?.nights || 0
  return s && n ? s / n : null
})

const oneTimeCount = computed(() => data.value?.rows.filter((r) => r.type === 'one_time').length || 0)

const TYPE_LABEL = { fixed: '固定', variable: '变动', one_time: '一次性' }
const typeCls = (t) => (t === 'fixed' ? 'fixed' : t === 'variable' ? 'var' : 'once')

const columns = [
  { key: 'itemName', label: '费用项', strong: true },
  { key: 'type', label: '归类', tag: (r) => ({ text: TYPE_LABEL[r.type] || r.type, cls: typeCls(r.type) }) },
  { key: 'amount', label: '金额', align: 'right', format: (r) => '¥' + fmtMoney(r.amount) },
  { key: 'note', label: '备注', format: (r) => r.note || '—' },
  { key: 'source', label: '来源', format: (r) => (r.source === 'import' || r.importBatchId ? '导入' : '手录') }
]

// ---- 新增/编辑 ----
const edit = ref(null) // null | {id?, itemName, type, amount, note}
const saving = ref(false)
function openNew() { edit.value = { itemName: '', type: 'variable', amount: '', note: '' } }
function openEdit(row) { edit.value = { id: row.id, itemName: row.itemName, type: row.type, amount: row.amount, note: row.note } }
async function saveEdit() {
  if (!edit.value.itemName || !edit.value.amount) { toast.warn('费用名与金额必填'); return }
  saving.value = true
  try {
    if (edit.value.id) await costApi.update(edit.value.id, { itemName: edit.value.itemName, type: edit.value.type, amount: edit.value.amount, note: edit.value.note })
    else await costApi.create({ month: month.month, itemName: edit.value.itemName, type: edit.value.type, amount: edit.value.amount, note: edit.value.note })
    toast.success(edit.value.id ? '已保存' : '已新增成本')
    edit.value = null
    await reload()
  } catch (e) { /* 已 toast */ } finally { saving.value = false }
}
async function removeRow(row) {
  if (!window.confirm(`删除成本「${row.itemName}」(¥${fmtMoney(row.amount)})？`)) return
  await costApi.remove(row.id)
  toast.success('已删除')
  await reload()
}
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
      <StatCard
        label="本月总成本" :value="fmtMoney(data.sum?.total)" unit="元"
        :hint="data.sum?.total ? `固定/变动/一次性 分类核算` : '较上月'"
      />
      <StatCard label="固定成本" :value="fmtMoney(data.sum?.fixed)" unit="元" :hint="`占比 ${data.sum?.total ? fmtPct(data.sum.fixed / data.sum.total * 100) : '—'}`" />
      <StatCard label="变动成本" :value="fmtMoney(data.sum?.variable)" unit="元" :hint="`占比 ${data.sum?.total ? fmtPct(data.sum.variable / data.sum.total * 100) : '—'}`" />
      <StatCard label="单间成本" :value="perNightCost == null ? '—' : fmtMoney(perNightCost)" unit="元" hint="元 / 间夜（含固定+变动）" />
    </div>

    <div class="row cards3">
      <div class="card" style="grid-column: span 2">
        <h3>月度成本趋势 · 固定 / 变动拆分</h3>
        <ChartView v-if="data.sum && data.sum.total > 0" class="chart lg" :option="trendOpt" height="300px" />
        <EmptyState v-else text="本月暂无成本数据" />
      </div>
      <div class="card">
        <h3>类型占比（{{ monthLabel(month.month) }}）</h3>
        <ChartView v-if="data.sum && data.sum.total > 0" class="chart md" :option="typeDonutOpt" height="250px" />
        <EmptyState v-else text="本月暂无成本数据" />
      </div>
    </div>

    <div class="card">
      <div class="card-head-row">
        <div class="card-title">
          {{ monthLabel(month.month) }}成本明细<span v-if="oneTimeCount" class="tag once">含 {{ oneTimeCount }} 项 新增/偶发</span>
          <span class="card-sub">共 {{ data.total }} 条</span>
        </div>
        <div class="head-actions">
          <button class="btn btn-ghost btn-sm" @click="imp.openDialog('upload', month.month)">⬆ 导入</button>
          <button class="btn btn-primary btn-sm" @click="openNew">＋ 手录成本</button>
        </div>
      </div>
      <DataTable :rows="data.rows" :columns="columns" :loading="loading" empty="本月暂无成本">
        <template #actions="{ row }">
          <button class="btn btn-ghost btn-sm" @click="openEdit(row)">编辑</button>
          <button class="btn btn-ghost btn-sm danger" @click="removeRow(row)">删除</button>
        </template>
      </DataTable>
    </div>
  </template>

  <Modal :show="!!edit" :title="edit?.id ? '编辑成本' : '手录成本'" width="440px" @close="edit = null">
    <div v-if="edit" class="field">
      <label>费用项 *</label>
      <input v-model="edit.itemName" placeholder="如：电费 / 布草洗涤 / 暑假工" />
      <label>归类 *</label>
      <div class="chips">
        <button v-for="(label, key) in TYPE_LABEL" :key="key" class="chip" :class="{ 'chip-on': edit.type === key }" type="button" @click="edit.type = key">
          {{ label }}
        </button>
      </div>
      <label>金额（元）*</label>
      <input v-model="edit.amount" type="number" step="0.01" min="0" placeholder="0.00" />
      <label>备注</label>
      <input v-model="edit.note" placeholder="可选" />
    </div>
    <template v-if="edit" #footer>
      <button class="btn btn-ghost" @click="edit = null">取消</button>
      <button class="btn btn-primary" :disabled="saving" @click="saveEdit">{{ saving ? '保存中…' : '保存' }}</button>
    </template>
  </Modal>
</template>
