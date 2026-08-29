<script setup>
import { useToastStore } from '@/stores/toast'
const toast = useToastStore()
</script>

<template>
  <div class="toast-host">
    <div
      v-for="t in toast.items"
      :key="t.id"
      class="toast"
      :class="`toast-${t.type}`"
      @click="toast.dismiss(t.id)"
    >
      {{ t.message }}
    </div>
  </div>
</template>
