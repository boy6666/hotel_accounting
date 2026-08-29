<script setup>
// AI 建议框：展示旁车聚合建议（非敏感摘要）。定价/预测页复用。
// grey=true → 灰态降级（旁车/LLM 不可用时的纯统计结果）。
defineProps({
  title: { type: String, default: 'AI 建议' },
  loading: { type: Boolean, default: false },
  items: { type: Array, default: () => [] }, // [{text, tag?, tagCls?}]
  emptyText: { type: String, default: '暂无建议 —— 数据完善后这里会给出经营提示（旁车聚合摘要，不涉及敏感明细）' },
  grey: { type: Boolean, default: false }
})
</script>

<template>
  <div class="ai-box" :class="{ 'ai-grey': grey && !items.length && !loading }">
    <div class="ai-head">
      <span class="ai-dot"></span>
      <span>{{ title }}</span>
      <span v-if="loading" class="ai-loading">思考中…</span>
    </div>
    <ul v-if="items.length" class="ai-list">
      <li v-for="(it, i) in items" :key="i">
        <span v-if="it.tag" class="tag" :class="it.tagCls || 'warn'">{{ it.tag }}</span>
        {{ it.text }}
      </li>
    </ul>
    <div v-else class="ai-empty">{{ emptyText }}</div>
  </div>
</template>
