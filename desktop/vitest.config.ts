import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// Renderer unit/UI tests run in jsdom. The Electron main/preload code is excluded;
// components talk only to a mocked window.api, so no IPC or backend is involved.
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./vitest.setup.ts'],
    include: ['src/renderer/**/*.test.{ts,tsx}']
  }
})
