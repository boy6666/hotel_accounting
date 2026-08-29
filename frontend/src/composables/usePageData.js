import { ref, watch, onMounted } from 'vue'
import { useMonthStore } from '@/stores/month'

// 页面数据加载组合式：进入 & 切换月份时调用 loader(month)，统一 loading / error 态
// error/errorMsg 供各页渲染「加载失败 + 重试」占位，根治请求失败时的白屏。
export function usePageData(loader) {
  const month = useMonthStore()
  const loading = ref(false)
  const data = ref(null)
  const error = ref(false)
  const errorMsg = ref('')

  async function load() {
    loading.value = true
    error.value = false
    errorMsg.value = ''
    try {
      data.value = await loader(month.month)
      // 成功：错误态已在上方清空
    } catch (e) {
      error.value = true
      errorMsg.value = e?.message || '数据加载失败'
    } finally {
      loading.value = false
    }
  }

  onMounted(load)
  watch(() => month.month, load)
  return { month, loading, data, error, errorMsg, reload: load }
}
