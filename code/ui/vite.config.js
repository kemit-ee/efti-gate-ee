import {defineConfig} from 'vite'
import {svelte} from '@sveltejs/vite-plugin-svelte'
import {svelteTesting} from '@testing-library/svelte/vite'

const backendHost = process.env.BACKEND_HOST ?? 'localhost'
const backendPort = process.env.BACKEND_PORT ?? '8086'
const backendUrl = `http://${backendHost}:${backendPort}`

// https://vite.dev/config/
export default defineConfig({
  plugins: [svelte(), svelteTesting()],
  resolve: {
    alias: {
      src: new URL('src', import.meta.url).pathname,
      i18n: new URL('i18n', import.meta.url).pathname
    },
  },
  server: {
    port: 8000,
    proxy: {
      '/api': backendUrl,
    }
  },
  build: {
    outDir: 'build',
    target: 'es2023',
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: 'src/setup-tests.ts'
  }
})
