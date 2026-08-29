<script setup>
// 统计卡片：原型 .card.stat > .lbl + .big + .delta（见 prototype/index.html）
// delta 为小数，如 0.08 → ▲ +8.0%（.up）；负值 → ▼（.down）
import { computed } from 'vue'
import { fmtDelta } from '@/utils/format'

const props = defineProps({
  label: { type: String, required: true },
  value: { type: [String, Number], default: '—' },
  unit: { type: String, default: '' },
  delta: { type: Number, default: NaN },
  deltaLabel: { type: String, default: '较上月' },
  hint: { type: String, default: '' },
  tone: { type: String, default: '' } // '' | 'ok' | 'warn'
})

const deltaHtml = computed(() => {
  const d = fmtDelta(props.delta)
  return d && d.html !== '—' ? d.html : ''
})
</script>

<template>
  <div class="card stat" :class="tone ? `stat-${tone}` : ''">
    <div class="lbl">{{ label }}</div>
    <div class="big">
      {{ value }}<span v-if="unit" class="unit">{{ unit }}</span>
    </div>
    <div class="delta">
      <span v-html="deltaHtml"></span><template v-if="deltaHtml && (hint || deltaLabel)"> · </template>{{ hint || deltaLabel }}
    </div>
  </div>
</template>
