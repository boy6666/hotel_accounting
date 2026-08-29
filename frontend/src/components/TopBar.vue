<script setup>
// 顶栏：原型 topbar（h1 + .sub + .top-actions）
import { useRoute, useRouter } from 'vue-router'
import { useMonthStore } from '@/stores/month'
import { useThemeStore } from '@/stores/theme'
import { useImportStore } from '@/stores/import'
import { useAuthStore } from '@/stores/auth'
import { monthLabel } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const month = useMonthStore()
const theme = useThemeStore()
const imp = useImportStore()
const auth = useAuthStore()

const TITLES = {
  dashboard: ['首页看板', '当月经营总览 · 收入 / 成本 / 利润 / 对账'],
  costs: ['成本分析', '月度成本明细 · 固定 / 变动 / 一次性'],
  channels: ['销售渠道', '渠道流水 · 佣金 · 挂牌价推算'],
  profit: ['利润分析', '月度利润 · 趋势 · 同比环比'],
  occupancy: ['房态·入住率', '房间×日期矩阵 · 入住率 · 对账'],
  pricing: ['定价·预测', '智能定价建议 · 档位 / 目标倒推 / 预测'],
  breakeven: ['回本测算', '投资回本 · 现金流与敏感度测算'],
  settings: ['设置·基础数据', '酒店配置 · 房间 · 档位价目 · 渠道佣金']
}
const t = TITLES[route.name] || ['', '']

function switchMonth(e) {
  month.setMonth(e.target.value)
}
function logout() {
  auth.logout()
  router.push({ name: 'login' })
}
</script>

<template>
  <header class="topbar">
    <div>
      <h1>{{ t[0] }}</h1>
      <div class="sub">{{ t[1] }} · {{ monthLabel(month.month) }}</div>
    </div>
    <div class="top-actions">
      <input
        type="month" class="btn" :value="month.month"
        :title="`当前经营月：${monthLabel(month.month)}`"
        style="font-family: inherit"
        @change="switchMonth"
      />
      <button class="btn" @click="theme.toggle()">{{ theme.theme === 'dark' ? '☀ 浅色' : '🌓 暗色' }}</button>
      <button class="btn primary" @click="imp.openDialog('upload', month.month)">⬆ 导入/上月</button>
      <button class="btn ghost" @click="logout">退出</button>
    </div>
  </header>
</template>
