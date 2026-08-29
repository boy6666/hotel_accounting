import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { useThemeStore } from './stores/theme'
import './styles/base.css'

const app = createApp(App)
app.use(createPinia())

// 挂载前套用主题（避免深色首屏闪白）
useThemeStore().apply()

app.use(router)
app.mount('#app')
