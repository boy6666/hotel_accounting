import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 契约：前端只调主后端 /api（信封），不直连 MySQL、不直连旁车。
// 开发期主后端可能未启动 → 靠 VITE_USE_MOCK=true 走 mock 数据（见 .env.development）。
// 后端就绪后置 VITE_USE_MOCK=false，即可一键切真（axios 拦截器/接口路径已按 docs/03 写好）。
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    // 5173 被其它 node 进程占用，改用 5174
    port: 5174,
    proxy: {
      '/api': {
        target: 'http://localhost:8081', // 主后端端口（本机约定 8081）
        changeOrigin: true
      }
    }
  }
})
