<script setup>
// 导入对话框（FW-09）：upload → preview → mapping → confirm；另含历史批次管理
import { ref, computed } from 'vue'
import Modal from './Modal.vue'
import DataTable from './DataTable.vue'
import EmptyState from './EmptyState.vue'
import { useImportStore } from '@/stores/import'
import { useToastStore } from '@/stores/toast'
import { importApi } from '@/api'

const imp = useImportStore()
const toast = useToastStore()
const uploading = ref(false)
const confirming = ref(false)
const history = ref([])
const historyLoading = ref(false)
const fileInput = ref(null)
const file = ref(null)   // 选中的 Excel 文件（File 对象）
// 各步持久错误态：不靠瞬时 toast，防白屏/丢失原因，便于重试
const uploadError = ref('')
const previewLoading = ref(false)
const previewError = ref('')
const mappingLoading = ref(false)
const mappingError = ref('')
const confirmError = ref('')

const MAX_FILE_SIZE = 10 * 1024 * 1024 // 10MB

function onPickFile(e) {
  const f = e.target.files?.[0] || null
  // 清空 input 的 value，保证再次选择同名文件也能触发 change
  const reject = (msg) => { toast.warn(msg); file.value = null; if (e.target) e.target.value = '' }
  if (!f) return
  if (!/\.xlsx$/i.test(f.name || '')) { reject('仅支持 .xlsx 格式'); return }
  if (f.size > MAX_FILE_SIZE) { reject('文件大小需 ≤ 10MB'); return }
  file.value = f
  uploadError.value = ''
}
function pickFile() { fileInput.value?.click() }

const STEP_TITLE = {
  upload: '导入月度数据',
  preview: '预览 · 三表结构',
  mapping: 'AI 归类确认',
  confirm: '导入结果',
  history: '历史导入批次'
}

const step = computed(() => imp.step)
const unknownCosts = computed(() => (imp.preview?.sheets?.costs || []).filter((c) => !c.known).length)
const unknownRooms = computed(() => (imp.preview?.sheets?.occupancy || []).filter((r) => !r.known).length)
const unknownChannels = computed(() => (imp.preview?.sheets?.sales || []).filter((c) => !c.known).length)
const totalUnknown = computed(() => unknownCosts.value + unknownRooms.value + unknownChannels.value)

// ---- upload ----
async function doUpload() {
  if (!file.value) { toast.warn('请先选择 Excel 文件'); return }
  uploading.value = true
  uploadError.value = ''
  imp.reset()
  try {
    const res = await importApi.upload({ month: imp.month, file: file.value })
    imp.batchId = res.batchId
    imp.batch = { batchId: res.batchId, month: imp.month, fileName: file.value.name, totalRows: res.totalRows, rawNameSummary: res.rawNameSummary }
    toast.success('解析完成，进入预览')
    imp.step = 'preview'
    await loadPreview()
  } catch (e) {
    if (e && e.code === 40900) {
      toast.error(e.message || '该月已导入过，请先删除旧批次')
      imp.step = 'history'
      await loadHistory()
    } else {
      // 非 40900：上传失败持久显示在 upload 步，原因不丢、可直接重试
      uploadError.value = '上传失败：' + ((e && e.message) || '请稍后重试')
    }
  } finally { uploading.value = false }
}

async function loadPreview() {
  previewLoading.value = true
  previewError.value = ''
  try {
    const p = await importApi.preview(imp.batchId)
    imp.preview = p
  } catch (e) {
    imp.preview = null
    if (e && (e.code === 50100 || e.code === 50200 || e.code === 50300)) {
      previewError.value = '智能解析服务暂不可用，请稍后重试'
    } else {
      toast.error((e && e.message) || '预览加载失败')
      previewError.value = '预览加载失败，请重试'
    }
  } finally { previewLoading.value = false }
}

// ---- mapping ----
async function loadMapping() {
  mappingLoading.value = true
  mappingError.value = ''
  try {
    const m = await importApi.mapping(imp.batchId)
    imp.mappings = m
  } catch (e) {
    imp.mappings = null
    if (e && (e.code === 50100 || e.code === 50200 || e.code === 50300)) {
      mappingError.value = '智能解析服务暂不可用，请稍后重试'
    } else {
      toast.error((e && e.message) || '归类加载失败')
      mappingError.value = '归类加载失败，请重试'
    }
  } finally { mappingLoading.value = false }
}

// ---- confirm ----
async function doConfirm() {
  confirming.value = true
  confirmError.value = ''
  try {
    const prev = imp.preview
    const mappings = (imp.mappings?.items || []).map((it) => ({
      rawName: it.rawName, costItemId: it.suggestCostItemId, type: it.suggestType
    }))
    const roomSet = {}
    ;(prev?.sheets?.occupancy || []).forEach((r) => { roomSet[r.roomNo] = { roomNo: r.roomNo, roomType: '' } })
    const res = await importApi.confirm(imp.batchId, {
      mappings,
      channelRows: (prev?.sheets?.sales || []).map((c) => ({ rawName: c.rawName, nights: c.nights, revenue: c.revenue })),
      roomRows: Object.values(roomSet),
      occRows: prev?.sheets?.occupancy || []
    })
    imp.confirmed = res
    imp.step = 'confirm'
    toast.success('确认落库完成，看板/对账已刷新')
  } catch (e) {
    // 落库失败：返回 mapping 步并持久提示，可直接重试
    confirmError.value = '落库失败，请重试'
  } finally { confirming.value = false }
}

// ---- history ----
async function loadHistory() {
  historyLoading.value = true
  try {
    const h = await importApi.list(imp.month === '' ? null : imp.month)
    history.value = h.list || []
  } catch (e) { /* 已 toast */ } finally { historyLoading.value = false }
}
async function removeBatch(b) {
  if (!window.confirm(`确认删除批次 #${b.id}（${b.month}）？删除后可重新导入该月。`)) return
  await importApi.remove(b.id)
  toast.success('批次已删除')
  await loadHistory()
}

const typeCls = (t) => (t === 'fixed' ? 'fixed' : t === 'variable' ? 'var' : 'once')
const TYPE_LABEL = { fixed: '固定', variable: '变动', one_time: '一次性' }

function close() { imp.close() }
</script>

<template>
  <Modal :show="imp.open" :title="STEP_TITLE[step] || '导入'" width="640px" @close="close">
    <!-- body：upload -->
    <template v-if="step === 'upload'">
      <div class="field">
        <label>选择月度</label>
        <input v-model="imp.month" type="month" />
      </div>
      <div v-if="uploadError" class="import-error">{{ uploadError }}</div>
      <div class="field">
        <label>数据文件（Excel）</label>
        <div class="file-pick">
          <button type="button" class="btn btn-ghost btn-sm" @click="pickFile">{{ file?.name ? '重新选择…' : '选择文件…' }}</button>
          <span class="file-name" :class="{ empty: !file }">{{ file?.name || '未选择文件' }}</span>
        </div>
        <input ref="fileInput" type="file" class="file-hidden"
               accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
               @change="onPickFile" />
      </div>
      <div class="import-hint">
        <p><b>模板两表</b>：当月成本 · 当月销售利润（路客云订单导出整表粘贴到销售表，表头不变直接用）</p>
        <p class="muted-sm">上传后由旁车解析 → AI 归类建议 → 你确认后落库；新费用项 / 新房号 / 新渠道将自动建档。</p>
      </div>
    </template>

    <!-- body：preview -->
    <template v-else-if="step === 'preview'">
      <div v-if="previewError" class="import-err">
        <EmptyState :text="previewError" />
        <div style="display:flex;gap:8px;justify-content:center">
          <button class="btn btn-primary btn-sm" :disabled="previewLoading" @click="loadPreview">{{ previewLoading ? '重试中…' : '重试' }}</button>
          <button class="btn btn-ghost btn-sm" @click="imp.step = 'upload'">返回上传</button>
        </div>
      </div>
      <template v-else>
        <div class="preview-grid">
          <div class="preview-tile">
            <div class="pt-num">{{ imp.preview?.sheets?.costs?.length || 0 }}</div>
            <div class="pt-name">费用条目</div>
            <div v-if="unknownCosts" class="pt-tag">含 {{ unknownCosts }} 条自动建档</div>
          </div>
          <div class="preview-tile">
            <div class="pt-num">{{ imp.preview?.sheets?.occupancy?.length || 0 }}</div>
            <div class="pt-name">房态明细（行）</div>
            <div v-if="unknownRooms" class="pt-tag">含 {{ unknownRooms }} 个新房号</div>
          </div>
          <div class="preview-tile">
            <div class="pt-num">{{ imp.preview?.sheets?.sales?.length || 0 }}</div>
            <div class="pt-name">渠道流水</div>
            <div v-if="unknownChannels" class="pt-tag">含 {{ unknownChannels }} 个新渠道</div>
          </div>
        </div>
        <p class="preview-desc">
          共 <b>{{ imp.batch?.totalRows }}</b> 行（{{ imp.batch?.rawNameSummary }}）。
          <span v-if="totalUnknown">其中 <span class="tag warn">待确认</span> {{ totalUnknown }} 项将在确认时自动建档。</span>
          <span v-else>未识别到新费用项 / 新房号 / 新渠道，确认后不产生建档变更。</span>
        </p>
      </template>
    </template>

    <!-- body：mapping -->
    <template v-else-if="step === 'mapping'">
      <div v-if="mappingError" class="import-err">
        <EmptyState :text="mappingError" />
        <div style="display:flex;gap:8px;justify-content:center">
          <button class="btn btn-primary btn-sm" :disabled="mappingLoading" @click="loadMapping">{{ mappingLoading ? '重试中…' : '重试' }}</button>
          <button class="btn btn-ghost btn-sm" @click="imp.step = 'preview'">上一步</button>
          <button class="btn btn-ghost btn-sm" @click="imp.step = 'upload'">返回上传</button>
        </div>
      </div>
      <div v-else-if="confirmError" class="import-error" style="margin-bottom:10px">
        {{ confirmError }} —— 请检查后端服务后重试落库。
      </div>
      <template v-else>
        <DataTable
          :rows="imp.mappings?.items || []"
          :loading="mappingLoading"
          :columns="[
            { key: 'rowNo', label: '#' },
            { key: 'rawName', label: '费用名称', strong: true },
            { key: 'suggestType', label: '归类', tag: (r) => ({ text: TYPE_LABEL[r.suggestType] || '—', cls: typeCls(r.suggestType) }) },
            { key: 'confidence', label: '置信度', format: (r) => Math.round(r.confidence * 100) + '%' },
            { key: 'matched', label: '匹配', tag: (r) => (r.matched ? { text: '已匹配', cls: 'ok' } : { text: '将自动建档', cls: 'warn' }) }
          ]"
          empty="无归类项"
        />
        <p class="muted-sm">低置信度项已标黄 —— 可后续在成本页调整；确认采用 UPSERT 幂等，重复导入不会重复建档。</p>
      </template>
    </template>

    <!-- body：confirm -->
    <template v-else-if="step === 'confirm'">
      <div class="confirm-list">
        <div class="confirm-row"><span>自动建档 · 费用项</span><b>{{ imp.confirmed?.createdCostItems?.length || 0 }}</b></div>
        <div class="confirm-row"><span>自动建档 · 渠道</span><b>{{ imp.confirmed?.createdChannels?.length || 0 }}</b></div>
        <div class="confirm-row"><span>自动建档 · 房号</span><b>{{ imp.confirmed?.createdRooms?.length || 0 }}</b></div>
        <div class="confirm-row"><span>本次导入费用（条）</span><b>{{ imp.confirmed?.importedCosts || 0 }}</b></div>
        <div class="confirm-row"><span>累计入住间夜</span><b>{{ imp.confirmed?.importedNights || 0 }}</b></div>
        <div class="confirm-row">
          <span>对账状态</span>
          <b :class="imp.confirmed?.reconcileStatus === 'matched' ? 'text-ok' : 'text-warn'">
            {{ imp.confirmed?.reconcileStatus === 'matched' ? '已对齐（diff 0）' : `差异 ${imp.confirmed?.reconcileDiff ?? '—'}` }}
          </b>
        </div>
      </div>
      <ul v-if="imp.confirmed?.createdRooms?.length" class="mini-list">
        <li v-for="r in imp.confirmed.createdRooms" :key="r">新房号 {{ r }} 已建档并加入可售房间</li>
      </ul>
    </template>

    <!-- body：history -->
    <template v-else-if="step === 'history'">
      <DataTable
        :rows="history"
        :loading="historyLoading"
        :columns="[
          { key: 'id', label: '批次', format: (r) => '#' + r.id },
          { key: 'month', label: '月份' },
          { key: 'fileName', label: '文件' },
          { key: 'status', label: '状态', tag: (r) => (r.status === 'confirmed' ? { text: '已确认', cls: 'ok' } : { text: '映射中', cls: 'warn' }) },
          { key: 'totalRows', label: '总行数' },
          { key: 'createdAt', label: '时间', format: (r) => (r.createdAt || '').slice(0, 16).replace('T', ' ') }
        ]"
        empty="暂无历史批次"
      >
        <template #actions="{ row }">
          <button class="btn btn-ghost btn-sm" @click="removeBatch(row)">删除</button>
        </template>
      </DataTable>
    </template>

    <!-- footer：upload -->
    <template v-if="step === 'upload'" #footer>
      <button class="btn btn-ghost" @click="close">取消</button>
      <button class="btn btn-primary" :disabled="uploading" @click="doUpload">{{ uploading ? '解析中…' : '上传并解析' }}</button>
    </template>
    <!-- footer：preview -->
    <template v-else-if="step === 'preview'" #footer>
      <button class="btn btn-ghost" @click="close">取消</button>
      <button class="btn btn-ghost" @click="imp.step = 'history'; loadHistory()">历史批次</button>
      <button class="btn btn-primary" :disabled="!!previewError" @click="imp.step = 'mapping'; loadMapping()">下一步：AI 归类</button>
    </template>
    <!-- footer：mapping -->
    <template v-else-if="step === 'mapping'" #footer>
      <button class="btn btn-ghost" @click="imp.step = 'preview'">上一步</button>
      <button class="btn btn-ghost" @click="imp.step = 'history'; loadHistory()">历史批次</button>
      <button class="btn btn-primary" :disabled="confirming" @click="doConfirm">{{ confirming ? '确认中…' : '确认导入落库' }}</button>
    </template>
    <!-- footer：confirm -->
    <template v-else-if="step === 'confirm'" #footer>
      <button class="btn btn-primary" @click="close">完成</button>
    </template>
    <!-- footer：history -->
    <template v-else-if="step === 'history'" #footer>
      <button class="btn btn-ghost" @click="imp.step = 'upload'">返回上传</button>
      <button class="btn btn-primary" @click="close">关闭</button>
    </template>
  </Modal>
</template>

<style scoped>
.file-pick { display: flex; align-items: center; gap: 10px; }
.file-name { color: var(--muted); font-size: 12.5px; word-break: break-all; }
.file-name.empty { color: var(--muted); opacity: .7; }
.file-hidden { display: none; }
/* 各步持久错误提示：upload 失败 / preview / mapping / confirm 失败 */
.import-error {
  color: var(--s8);
  font-size: 12.5px;
  background: color-mix(in srgb, var(--s8) 7%, var(--surface-1));
  border: 1px solid color-mix(in srgb, var(--s8) 22%, var(--grid));
  border-radius: 8px;
  padding: 8px 10px;
  margin-bottom: 10px;
}
.import-err { display: flex; flex-direction: column; gap: 8px; }
</style>
