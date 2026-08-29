<script setup>
// 设置 FW-08：酒店配置 / 房间管理(可售间数=启用房数) / 档位价目 / 渠道佣金率 / 模板下载
import { ref, computed } from 'vue'
import Modal from '@/components/Modal.vue'
import DataTable from '@/components/DataTable.vue'
import TierTable from '@/components/TierTable.vue'
import NoticeBar from '@/components/NoticeBar.vue'
import { settingsApi, roomApi, channelApi, pricingApi, importApi } from '@/api'
import { useToastStore } from '@/stores/toast'

const toast = useToastStore()
const loading = ref(false)
const loadError = ref(false)
const loadMsg = ref('')
const templateError = ref('')

const hotel = ref({ hotelName: '', city: '', defaultCommissionRate: 0.12, daysPerMonth: 30.4 })
const rooms = ref([])
const channels = ref([])
const tiers = ref([])

async function loadAll() {
  loading.value = true
  loadError.value = false
  loadMsg.value = ''
  try {
    const [h, r, c, t] = await Promise.all([
      settingsApi.hotel(),
      roomApi.list({ pageSize: 200 }),
      channelApi.list(),
      pricingApi.tiers()
    ])
    hotel.value = h
    rooms.value = r.list || []
    channels.value = (c || []).map((ch) => ({ ...ch, _rate: ch.commissionRate }))
    tiers.value = t || []
  } catch (e) {
    loadError.value = true
    loadMsg.value = (e && e.message) || '基础数据加载失败'
  } finally { loading.value = false }
}
loadAll()

const sellable = computed(() => rooms.value.filter((r) => r.enabled).length)
const disabledCount = computed(() => rooms.value.length - sellable.value)

async function saveHotel() {
  try {
    await settingsApi.updateHotel({
      hotelName: hotel.value.hotelName,
      city: hotel.value.city,
      defaultCommissionRate: Number(hotel.value.defaultCommissionRate),
      daysPerMonth: Number(hotel.value.daysPerMonth)
    })
    toast.success('配置已保存')
  } catch (e) { /* 已 toast */ }
}

// ---- 房间 CRUD ----
const roomModal = ref(null) // {mode:'new'|'edit', id?, roomNo, roomType, floor}
function openRoomNew() { roomModal.value = { mode: 'new', roomNo: '', roomType: '', floor: '' } }
function openRoomEdit(r) { roomModal.value = { mode: 'edit', id: r.id, roomNo: r.roomNo, roomType: r.roomType, floor: r.floor } }
async function saveRoom() {
  const m = roomModal.value
  if (!m.roomNo) { toast.warn('房号必填'); return }
  try {
    if (m.mode === 'new') await roomApi.create({ roomNo: m.roomNo, roomType: m.roomType || null, floor: m.floor || null })
    else await roomApi.update(m.id, { roomType: m.roomType || null, floor: m.floor || null })
    toast.success(m.mode === 'new' ? `房号 ${m.roomNo} 已新增` : '房间已更新')
    roomModal.value = null
    await loadAll()
  } catch (e) { /* 40900 等已 toast */ }
}
async function disableRoom(r) {
  if (!window.confirm(`停用房间 ${r.roomNo}？停用后不计入可售房间、矩阵不显示，但历史数据保留。`)) return
  await roomApi.disable(r.id)
  toast.success(`房间 ${r.roomNo} 已停用`)
  await loadAll()
}

const roomCols = [
  { key: 'roomNo', label: '房号', strong: true },
  { key: 'roomType', label: '类型', format: (r) => r.roomType || '—' },
  { key: 'floor', label: '楼层', format: (r) => r.floor || '—' },
  { key: 'enabled', label: '状态', tag: (r) => (r.enabled ? { text: '可售', cls: 'ok' } : { text: '停用', cls: 'muted' }) },
  { key: 'firstSeenFrom', label: '建档来源', format: (r) => (String(r.firstSeenFrom || '').startsWith('import') ? '导入自动建档' : '手动') }
]

// ---- 档位 ----
const tierModal = ref(null)
const DAY_OPTS = [
  { key: 'weekday', label: '平日' },
  { key: 'weekend', label: '周末' },
  { key: 'holiday', label: '节假日' }
]
function openTierNew() { tierModal.value = { name: '', basePrice: '', applyDays: 'weekday', effectiveFrom: '', effectiveTo: '', active: true } }
function openTierEdit(t) {
  tierModal.value = {
    id: t.id, name: t.name, basePrice: t.basePrice, applyDays: t.applyDays,
    effectiveFrom: t.effectiveFrom || '', effectiveTo: t.effectiveTo || '', active: t.active
  }
}
async function saveTier() {
  const m = tierModal.value
  if (!m.name || !m.basePrice) { toast.warn('档位名与价格必填'); return }
  const payload = {
    name: m.name, basePrice: Number(m.basePrice), applyDays: m.applyDays,
    effectiveFrom: m.effectiveFrom || null, effectiveTo: m.effectiveTo || null, active: m.active
  }
  try {
    if (m.id) await pricingApi.updateTier(m.id, payload)
    else await pricingApi.createTier(payload)
    toast.success('档位已保存')
    tierModal.value = null
    await loadAll()
  } catch (e) { /* 已 toast */ }
}
async function deleteTier(t) {
  if (!window.confirm(`删除档位「${t.name}」？历史建议引用将置空。`)) return
  await pricingApi.deleteTier(t.id)
  toast.success('已删除')
  await loadAll()
}

// ---- 渠道佣金率 ----
async function saveRate(ch) {
  const rate = Number(ch._rate)
  if (!(rate >= 0 && rate < 1)) { toast.warn('佣金率需 0 ≤ r < 1'); return }
  try {
    await channelApi.update(ch.id, { commissionRate: rate })
    ch.commissionRate = rate
    toast.success(`${ch.name} 佣金率已更新`)
  } catch (e) { /* 已 toast */ }
}

// ---- 模板下载 ----
async function openTemplate() {
  templateError.value = ''
  try {
    const res = await importApi.template()
    if (res instanceof Blob || (res && res.type)) {
      const url = URL.createObjectURL(res)
      const a = document.createElement('a')
      a.href = url
      a.download = '月度记账模板.xlsx'
      a.click()
      URL.revokeObjectURL(url)
      toast.success('模板已下载')
    }
  } catch (e) {
    // 下载失败：页内持久提示，便于重试
    templateError.value = (e && e.message) || '模板下载失败'
  }
}
</script>

<template>
  <div v-if="loading && !rooms.length" class="page-loading">数据加载中…</div>
  <template v-else>
    <NoticeBar v-if="loadError" :tone="'warn'" title="基础数据加载失败">
      {{ loadMsg }} —— 请确认后端服务正常后重试
      <button class="btn btn-ghost btn-sm" style="margin-left:8px" @click="loadAll">重试</button>
    </NoticeBar>
    <NoticeBar tone="info">
      <b>主数据自动化优先</b>
      费用项、渠道、房号在「月度 Excel 导入」时自动识别建档，无需手工维护；本页只维护导入无法自动获知的信息：酒店配置、房间字典、档位价目、渠道佣金率、导入模板。
    </NoticeBar>

    <!-- 酒店配置 -->
    <div class="row">
      <div class="card">
        <h3>酒店配置<span class="card-sub">全局参数，影响入住率 / 佣金率默认值</span></h3>
        <table>
          <tbody>
            <tr>
              <td style="width:150px">酒店名称</td>
              <td><input class="btn" v-model="hotel.hotelName" style="width:min(360px,100%);text-align:left;font-family:inherit" /></td>
            </tr>
            <tr>
              <td>城市（天气预测）</td>
              <td><input class="btn" v-model="hotel.city" style="width:min(280px,100%);text-align:left;font-family:inherit" /><span class="muted-sm" style="margin-left:8px">可留空：预测自动降级为「日历 + 节假日」</span></td>
            </tr>
            <tr>
              <td>默认线上佣金率</td>
              <td><input class="btn" v-model="hotel.defaultCommissionRate" type="number" step="0.01" min="0" max="0.99" style="width:76px" /> %<span class="muted-sm" style="margin-left:8px">0 ≤ r &lt; 1 · 新渠道默认</span></td>
            </tr>
            <tr>
              <td>每日营业天数常数</td>
              <td><input class="btn" v-model="hotel.daysPerMonth" type="number" step="0.1" style="width:76px" /><span class="muted-sm" style="margin-left:8px">目标倒推：均价 = 目标收入 ÷（房间数 × 天数 × 入住率）</span></td>
            </tr>
          </tbody>
        </table>
        <div style="margin-top:10px"><button class="btn primary" @click="saveHotel">保存配置</button></div>
      </div>
    </div>

    <!-- 房间管理 -->
    <div class="row">
      <div class="card">
        <div class="card-head-row">
          <h3>房间管理（可售房字典 · 具体到每个房号）<span class="card-sub">可售房间数 = 启用房数，自动推导供入住率 / 目标倒推 / 回本共用</span></h3>
          <div class="head-actions">
            <span class="tag ok">可售 {{ sellable }}</span>
            <span v-if="disabledCount" class="tag warn">停用 {{ disabledCount }}</span>
            <button class="btn primary" @click="openRoomNew">＋ 新增房间</button>
          </div>
        </div>
        <DataTable :rows="rooms" :columns="roomCols" empty="暂无房间">
          <template #actions="{ row }">
            <button class="btn btn-ghost btn-sm" @click="openRoomEdit(row)">编辑</button>
            <button v-if="row.enabled" class="btn btn-ghost btn-sm danger" @click="disableRoom(row)">停用</button>
          </template>
        </DataTable>
        <NoticeBar v-if="disabledCount" tone="info">
          已停用 {{ disabledCount }} 间：不入矩阵、不计入可售，但历史入住与成本留存，可随时重新启用。
        </NoticeBar>
      </div>
    </div>

    <!-- 档位价目 -->
    <div class="row">
      <div class="card">
        <div class="card-head-row">
          <h3>档位价目（定价建议的基准 · 需手工维护）<span class="card-sub">平日 / 周末 / 节假日；临近日建议价按基准价 × 预测系数推算</span></h3>
          <button class="btn primary" @click="openTierNew">＋ 新增档位</button>
        </div>
        <TierTable :tiers="tiers" />
        <div v-if="tiers.length" class="row-actions">
          <button v-for="t in tiers" :key="'e' + t.id" class="btn btn-ghost btn-sm" @click="openTierEdit(t)">编辑 {{ t.name }}</button>
          <button class="btn btn-ghost btn-sm danger" @click="deleteTier(tiers[tiers.length - 1])">删除「{{ tiers[tiers.length - 1].name }}」</button>
        </div>
      </div>
    </div>

    <!-- 渠道佣金率 -->
    <div class="row">
      <div class="card">
        <div class="card-head-row">
          <h3>渠道佣金率（渠道本身由导入自动建档 · 此处只调佣金）<span class="card-sub">仅线上渠道可调；线下为 0</span></h3>
        </div>
        <table class="data-table">
          <thead><tr><th>渠道</th><th>类型</th><th>佣金率</th><th class="right">操作</th></tr></thead>
          <tbody>
            <tr v-if="!channels.length">
              <td colspan="4" class="table-empty">暂无渠道（导入时自动建档）—— 上传月度 Excel 后自动识别生成，此处只调佣金率。</td>
            </tr>
            <tr v-for="ch in channels" :key="ch.id">
              <td><strong>{{ ch.name }}</strong></td>
              <td><span class="tag" :class="ch.type === 'online' ? 'online' : 'offline'">{{ ch.type === 'online' ? '线上' : '线下' }}</span></td>
              <td v-if="ch.type === 'online'">
                <input v-model.number="ch._rate" class="tbl-input" type="number" step="0.01" min="0" max="0.99" style="width: 84px" /> ％
              </td>
              <td v-else>0%（线下固定）</td>
              <td class="right">
                <button v-if="ch.type === 'online'" class="btn btn-ghost btn-sm" @click="saveRate(ch)">保存</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 模板下载 -->
    <div class="row">
      <div class="card">
        <div class="card-head-row">
          <h3>月度 Excel 模板下载（用「月度记账模板.xlsx」记账，一键进系统）<span class="card-sub">当月成本 + 当月销售利润两 Sheet；销售页整表粘贴路客云订单导出，房态由订单自动推导</span></h3>
          <button class="btn primary" @click="openTemplate">⬇ 下载模板</button>
        </div>
        <table class="data-table">
          <thead><tr><th>Sheet</th><th>内容</th><th>去向</th></tr></thead>
          <tbody>
            <tr><td><strong>当月成本</strong></td><td>每条 = 费用名 + 金额 + 备注；按固定 / 变动 / 一次性归类</td><td>智能归类 → 月度成本</td></tr>
            <tr><td><strong>当月销售利润</strong></td><td>路客云订单导出 30 列整表粘贴（含「已排房 / 计入统计」，房态由此推导）</td><td>渠道流水 → 对账 / 佣金 / 房态</td></tr>
          </tbody>
        </table>
        <p class="muted-sm" style="margin-top: 10px">模板由主后端实时生成，下载即为最新两 Sheet 结构，可直接使用。</p>
        <NoticeBar v-if="templateError" :tone="'warn'" title="模板下载失败" style="margin-top: 10px">
          {{ templateError }} —— 请稍后重试，或检查后端服务。
        </NoticeBar>
      </div>
    </div>

    <!-- 新增/编辑房间 -->
    <Modal :show="!!roomModal" :title="roomModal?.mode === 'new' ? '新增房间' : '编辑房间'" width="420px" @close="roomModal = null">
      <div v-if="roomModal" class="field">
        <label>房号 *</label>
        <input v-model="roomModal.roomNo" :disabled="roomModal.mode === 'edit'" placeholder="如 301" />
        <label>类型</label>
        <input v-model="roomModal.roomType" placeholder="如：大床房 / 亲子房" />
        <label>楼层</label>
        <input v-model="roomModal.floor" placeholder="如：3F" />
      </div>
      <template v-if="roomModal" #footer>
        <button class="btn btn-ghost" @click="roomModal = null">取消</button>
        <button class="btn btn-primary" @click="saveRoom">保存</button>
      </template>
    </Modal>

    <!-- 新增/编辑档位 -->
    <Modal :show="!!tierModal" :title="tierModal?.id ? '编辑档位' : '新增档位'" width="440px" @close="tierModal = null">
      <div v-if="tierModal" class="field">
        <label>档位名称 *</label>
        <input v-model="tierModal.name" placeholder="如：淡季价" />
        <label>基准价（元/晚）*</label>
        <input v-model="tierModal.basePrice" type="number" step="10" min="0" />
        <label>适用日期</label>
        <div class="chips">
          <button v-for="o in DAY_OPTS" :key="o.key" class="chip" :class="{ 'chip-on': tierModal.applyDays === o.key }" type="button" @click="tierModal.applyDays = o.key">{{ o.label }}</button>
        </div>
        <div class="form-grid">
          <div class="field"><label>生效自</label><input v-model="tierModal.effectiveFrom" type="date" /></div>
          <div class="field"><label>生效至</label><input v-model="tierModal.effectiveTo" type="date" /></div>
        </div>
        <label class="check-line"><input v-model="tierModal.active" type="checkbox" /> 启用该档位</label>
      </div>
      <template v-if="tierModal" #footer>
        <button class="btn btn-ghost" @click="tierModal = null">取消</button>
        <button class="btn btn-primary" @click="saveTier">保存</button>
      </template>
    </Modal>
  </template>
</template>
