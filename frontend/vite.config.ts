import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
export default defineConfig(({ mode }) => ({
  plugins: [
    vue(),
    ...(mode === 'test' ? [] : [Components({ resolvers: [ElementPlusResolver()], dts: false })]),
  ],
  server: {
    port: 5173,
    proxy: {
      '/api/v1/classroom-applications': { target: 'http://localhost:8082' },
      '/api/v1/repair-orders': { target: 'http://localhost:8082' },
      '/api': { target: 'http://localhost:8081' },
    },
  },
  test: { environment: 'jsdom', include: ['src/tests/**/*.test.ts'], clearMocks: true },
}))
