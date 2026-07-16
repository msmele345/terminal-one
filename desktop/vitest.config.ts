import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// Renderer unit/UI tests run in jsdom; components talk only to a mocked
// window.api, so no IPC or backend is involved. Pure main-process modules
// (no electron imports, e.g. the EOD notification watcher) are unit-tested too.
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./vitest.setup.ts'],
    include: ['src/renderer/**/*.test.{ts,tsx}', 'src/main/**/*.test.ts']
  }
})
