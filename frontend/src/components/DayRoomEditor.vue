<script setup>
// 当日房态编辑器：勾选该日入住房间 → PUT /occupancy/day-rooms
import { ref, watch } from 'vue'
import Modal from './Modal.vue'
import { occApi } from '@/api'
import { useToastStore } from '@/stores/toast'

const props = defineProps({
  show: { type: Boolean, default: false },
  bizDate: { type: String, default: '' },
  rooms: { type: Array, default: () => [] } // 可用房间 [{roomNo, roomType, enabled}]
})
const emit = defineEmits(['close', 'saved'])

const toast = useToastStore()
const selected = ref([])
const note = ref('')
const saving = ref(false)

async function load() {
  if (!props.show || !props.bizDate) return
  selected.value = []
  note.value = ''
  try {
    const list = await occApi.dayRooms(props.bizDate)
    selected.value = list.map((x) => x.roomNo)
  } catch (e) { /* 已 toast */ }
}
watch(() => [props.show, props.bizDate], load, { immediate: true })

function toggle(roomNo) {
  const i = selected.value.indexOf(roomNo)
  if (i >= 0) selected.value.splice(i, 1)
  else selected.value.push(roomNo)
}
async function save() {
  saving.value = true
  try {
    await occApi.updateDayRooms(props.bizDate, selected.value.slice().sort(), note.value || null)
    toast.success(`已保存 ${props.bizDate} · 入住 ${selected.value.length} 间`)
    emit('saved')
    emit('close')
  } catch (e) { /* 已 toast */ } finally { saving.value = false }
}
</script>

<template>
  <Modal :show="show" :title="`当日房态 · ${bizDate}`" width="460px" @close="emit('close')">
    <p class="modal-tip">勾选"已入住"房间；不勾选即空房。保存后矩阵、入住率、对账实时刷新。</p>
    <div class="chips">
      <button
        v-for="r in rooms"
        :key="r.roomNo"
        class="chip"
        :class="{ 'chip-on': selected.includes(r.roomNo), 'chip-off': !r.enabled }"
        type="button"
        @click="toggle(r.roomNo)"
      >
        {{ r.roomNo }}<i v-if="r.roomType" class="chip-type">{{ r.roomType }}</i>
      </button>
    </div>
    <div class="field">
      <label>备注（可选）</label>
      <input v-model="note" placeholder="如：管道维修 / 临时整修等" />
    </div>
    <template #footer>
      <button class="btn btn-ghost" @click="emit('close')">取消</button>
      <button class="btn btn-primary" :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存' }}</button>
    </template>
  </Modal>
</template>
