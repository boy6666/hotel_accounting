<script setup>
// 通用数据表
// columns: [{ key, label, align, width, format(row), tag(row)->{text,cls}, strong }]
// rows / loading / empty 文案；可选具名插槽 #actions(row)、#cell:{key}(row)
import EmptyState from './EmptyState.vue'

defineProps({
  columns: { type: Array, required: true },
  rows: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  empty: { type: String, default: '暂无数据' }
})
</script>

<template>
  <div class="table-wrap">
    <table class="data-table">
      <thead>
        <tr>
          <th v-for="c in columns" :key="c.key" :style="{ width: c.width }" :class="{ right: c.align === 'right' }">
            {{ c.label }}
          </th>
          <th v-if="$slots.actions" class="right">操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-if="loading">
          <td :colspan="columns.length + ($slots.actions ? 1 : 0)" class="table-empty">加载中…</td>
        </tr>
        <tr v-else-if="!rows.length">
          <td :colspan="columns.length + ($slots.actions ? 1 : 0)" class="table-empty">
            <EmptyState :text="empty" />
          </td>
        </tr>
        <tr v-for="(row, i) in rows" :key="i">
          <td
            v-for="c in columns"
            :key="c.key"
            :class="{ right: c.align === 'right' }"
          >
            <slot :name="'cell:' + c.key" :row="row">
              <template v-if="c.tag && c.tag(row)">
                <span class="tag" :class="c.tag(row).cls">{{ c.tag(row).text }}</span>
              </template>
              <template v-else-if="c.format">{{ c.format(row) }}</template>
              <strong v-else-if="c.strong">{{ row[c.key] }}</strong>
              <template v-else>{{ row[c.key] }}</template>
            </slot>
          </td>
          <td v-if="$slots.actions" class="right table-actions">
            <slot name="actions" :row="row" />
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
