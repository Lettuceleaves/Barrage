import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import path from 'path'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src')
    }
  },
  server: {
    port: 5173,
    host: true,
    // 将 /api/* 请求代理到后端 API 服务器（barrage-api，端口 9090）
    proxy: {
      '/api': {
        target: 'http://localhost:9090',
        changeOrigin: true,
        // 可选：若后端路径不含 /api 前缀，则需要 rewrite；目前后端路径与前端一致，无需 rewrite
        // rewrite: (path) => path.replace(/^\/api/, '')
      }
    }
  }
})
