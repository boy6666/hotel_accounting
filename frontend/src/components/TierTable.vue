<script setup>
// 档位价目表（只读展示 + 激活状态；CRUD 走设置页）
import { fmtMoney } from '@/utils/format'

defineProps({
  tiers: { type: Array, default: () => [] }
})

const DAY_LABEL = { weekday: '平日', weekend: '周末', holiday: '节假日' }
</script>

<template>
  <table class="data-table">
    <thead>
      <tr>
        <th>档位</th>
        <th>价格</th>
        <th>适用</th>
        <th>生效区间</th>
        <th>状态</th>
      </tr>
    </thead>
    <tbody>
      <tr v-if="!tiers.length">
        <td colspan="5" class="table-empty">暂无档位</td>
      </tr>
      <tr v-for="t in tiers" :key="t.id">
        <td><strong>{{ t.name }}</strong></td>
        <td>¥{{ fmtMoney(t.basePrice) }}</td>
        <td>{{ DAY_LABEL[t.applyDays] || t.applyDays }}</td>
        <td>{{ t.effectiveFrom || '—' }} ~ {{ t.effectiveTo || '长期' }}</td>
        <td>
          <span class="tag" :class="t.active ? 'ok' : 'muted'">{{ t.active ? '启用' : '停用' }}</span>
        </td>
      </tr>
    </tbody>
  </table>
</template>
