<script setup>
// 提示条：对账提示 / 降级提示 / 一般说明
defineProps({
  tone: { type: String, default: 'ok' }, // ok | warn | info
  title: { type: String, default: '' }
})
</script>

<template>
  <div class="notice" :class="`notice-${tone}`">
    <strong v-if="title" class="notice-title">{{ title }}</strong>
    <slot />
  </div>
</template>
