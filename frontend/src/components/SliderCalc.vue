<script setup>
// 滑杆计算器：拖动价格同步展示佣金/阶梯收益（二期定价页静态实例用）
import { computed } from 'vue'
import { fmtYuan } from '@/utils/format'

const props = defineProps({
  label: { type: String, default: '试算价格' },
  rate: { type: Number, default: 0.12 },
  min: { type: Number, default: 180 },
  max: { type: Number, default: 800 },
  step: { type: Number, default: 10 },
  modelValue: { type: Number, default: 360 }
})
const emit = defineEmits(['update:modelValue'])

const commission = computed(() => props.modelValue * props.rate)
const net = computed(() => props.modelValue - commission.value)
const profit = computed(() => net.value * 0.55) // 示意毛利率
</script>

<template>
  <div class="calc">
    <div class="calc-head">
      <span>{{ label }}：<strong>{{ modelValue }}</strong> 元/晚</span>
      <input
        type="range" :min="min" :max="max" :step="step"
        :value="modelValue"
        @input="emit('update:modelValue', Number($event.target.value))"
      />
    </div>
    <div class="calc-grid">
      <div class="calc-cell"><span>到手价</span><b>{{ fmtYuan(net) }}</b></div>
      <div class="calc-cell"><span>佣金({{ Math.round(rate * 100) }}%)</span><b>{{ fmtYuan(commission) }}</b></div>
      <div class="calc-cell"><span>参考毛利</span><b class="text-ok">{{ fmtYuan(profit) }}</b></div>
    </div>
  </div>
</template>
