import { resolve } from 'path'
import { defineConfig, externalizeDepsPlugin } from 'electron-vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  main: {
    plugins: [externalizeDepsPlugin()],
    // The packaged app launched from Finder has no shell environment. Embed a
    // public backend URL at build time; a runtime BACKEND_URL still wins for
    // local development and diagnostics.
    define: {
      __DEFAULT_BACKEND_URL__: JSON.stringify(
        process.env.BACKEND_URL ?? 'http://localhost:8080'
      )
    }
  },
  preload: {
    plugins: [externalizeDepsPlugin()]
  },
  renderer: {
    root: 'src/renderer',
    build: {
      rollupOptions: {
        input: resolve(__dirname, 'src/renderer/index.html')
      }
    },
    plugins: [react()]
  }
})
