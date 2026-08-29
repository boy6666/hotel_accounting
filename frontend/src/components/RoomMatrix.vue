<script setup>
// 房间×日期入住矩阵（FW-07 核心）：行=房间 101~205，列=当月每日
// 已入住格子高亮；点击格子 → 父组件弹 DayRoomEditor（PUT day-rooms）
// 周末列底色弱化（wknd-below 带星期行更直观）
import { computed } from 'vue'
import { monthDates, weekday, isWeekend } from '@/utils/format'

const props = defineProps({
  month: { type: String, required: true },
  rooms: { type: Array, default: () => [] }, // [{roomNo, roomType, occupied:[dates]}]
  loading: { type: Boolean, default: false }
})
const emit = defineEmits(['cell-click'])

// 列数 = 有入住记录的最后一天（空月则显示整月），随补录数据自动扩展，不固定演示列数。
const dates = computed(() => {
  const all = monthDates(props.month)
  let maxDay = 0
  ;(props.rooms || []).forEach((r) => {
    ;(r.occupied || []).forEach((d) => {
      const n = Number(d.slice(8))
      if (n > maxDay) maxDay = n
    })
  })
  return maxDay > 0 ? all.slice(0, maxDay) : all
})
const occupiedByRoom = computed(() => {
  const m = {}
  ;(props.rooms || []).forEach((r) => { m[r.roomNo] = new Set(r.occupied || []) })
  return m
})
const isOcc = (roomNo, date) => !!occupiedByRoom.value[roomNo]?.has(date)
const dayCount = (date) => (props.rooms || []).filter((r) => occupiedByRoom.value[r.roomNo]?.has(date)).length

const WEEK = ['日', '一', '二', '三', '四', '五', '六']
const dayNum = (d) => Number(d.slice(8))
const weekLabel = (d) => '周' + WEEK[weekday(d)]
const wkCls = (d) => ({ wknd: isWeekend(d) })
</script>

<template>
  <div class="rmg-wrap">
    <table class="rmg">
      <thead>
        <tr>
          <th class="rmg-corner">日 / 房</th>
          <th v-for="d in dates" :key="'h' + d" :class="wkCls(d)">{{ dayNum(d) }}</th>
        </tr>
        <tr class="rmg-wk">
          <th></th>
          <th v-for="d in dates" :key="'w' + d" :class="wkCls(d)">{{ weekLabel(d) }}</th>
        </tr>
      </thead>
      <tbody>
        <tr v-if="loading">
          <td :colspan="dates.length + 1" class="table-empty">矩阵加载中…</td>
        </tr>
        <tr v-else-if="!rooms.length">
          <td :colspan="dates.length + 1" class="table-empty">本月暂无房间数据</td>
        </tr>
        <template v-else>
          <tr v-for="r in rooms" :key="r.roomNo">
            <th class="rmg-room">{{ r.roomNo }}<i v-if="r.roomType" class="rmg-type">{{ r.roomType }}</i></th>
            <td
              v-for="d in dates"
              :key="r.roomNo + d"
              class="rmg-cell"
              :class="[isOcc(r.roomNo, d) ? 'rmg-on' : 'rmg-off', wkCls(d)]"
              :title="`${d} ${r.roomNo} ${isOcc(r.roomNo, d) ? '已入住' : '空房'}`"
              @click="emit('cell-click', { date: d, roomNo: r.roomNo, occupied: isOcc(r.roomNo, d) })"
            ></td>
          </tr>
          <tr class="rmg-foot">
            <th class="rmg-corner">入住</th>
            <td
              v-for="d in dates"
              :key="'c' + d"
              :class="[wkCls(d), { 'ft-0': dayCount(d) === 0 }]"
            >{{ dayCount(d) }}</td>
          </tr>
        </template>
      </tbody>
    </table>
    <div class="rmg-legend">
      <span class="rmg-chip on"></span>已入住
      <span class="rmg-chip off"></span>空房
      <span class="rmg-chip wk"></span>周末列
      <span class="rmg-hint">点击任意单元格可修改该日入住房号</span>
    </div>
  </div>
</template>
