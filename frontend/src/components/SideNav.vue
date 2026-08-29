<script setup>
// 侧边栏：原型 sidebar/brand/nav 骨架（见 prototype/index.html）
import { useRoute } from 'vue-router'
import { useMonthStore } from '@/stores/month'

const route = useRoute()
const monthStore = useMonthStore()
// 版本号：发版时改这里（当前 v0.0.5 —— 房型识别全链路 + 成本空行修复）
const APP_VERSION = 'v0.0.5'
const NAV = [
  { name: 'dashboard', path: '/', label: '首页看板', ic: '▦' },
  { name: 'costs', path: '/costs', label: '成本分析', ic: '▤' },
  { name: 'channels', path: '/channels', label: '销售渠道', ic: '≡' },
  { name: 'profit', path: '/profit', label: '利润分析', ic: '↗' },
  { name: 'occupancy', path: '/occupancy', label: '房态·入住率', ic: '▥' },
  { name: 'pricing', path: '/pricing', label: '定价·预测', ic: '￥' },
  { name: 'breakeven', path: '/breakeven', label: '回本测算', ic: '⏱' },
  { name: 'settings', path: '/settings', label: '设置·基础数据', ic: '⚙' }
]
</script>

<template>
  <aside class="sidebar">
    <div class="brand">
      <div class="logo">捌</div>
      <b>捌宿·经营系统<small>酒店记账 · 分析 · 定价</small></b>
    </div>
    <nav>
      <router-link
        v-for="n in NAV"
        :key="n.name"
        :to="n.path"
        :class="{ active: route.path === n.path }"
      >
        <span class="ic">{{ n.ic }}</span>{{ n.label }}
      </router-link>
    </nav>
    <div class="sidebar-foot">
      捌宿轻居 · 联机会计系统<br />
      当前经营月：{{ monthStore.month }}<br />
      当前版本：{{ APP_VERSION }}
    </div>
  </aside>
</template>
