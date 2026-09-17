import {defineConfig} from 'vite'
import {svelte} from '@sveltejs/vite-plugin-svelte'
import {svelteTesting} from '@testing-library/svelte/vite'

const backendHost = process.env.BACKEND_HOST ?? 'localhost'
const backendPort = process.env.BACKEND_PORT ?? '8080'
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
    setupFiles: 'src/setup-tests.ts',
    exclude: ['e2e/**', 'node_modules/**'],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html', 'lcov', 'cobertura'],
      include: ['src/**'],
      exclude: ['src/setup-tests.ts', 'src/**/*.test.ts', 'src/router/RouterTest.svelte'],
      thresholds: {
        lines: 80,
        statements: 80,
        functions: 80,
        branches: 80
      }
    }
  }
})
