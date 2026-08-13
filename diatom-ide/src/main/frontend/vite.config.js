import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [
    vue()
  ],
  base: './',
  resolve: {
    alias: {
      'vue': 'vue/dist/vue.esm-bundler.js'
    }
  },
  server: {
    proxy: {
      '/compile': 'http://localhost:8080',
      '/workspace': 'http://localhost:8080',
      '/ide': 'http://localhost:8080',
      '/java-lsp': {
        target: 'ws://localhost:8080',
        ws: true
      }
    }
  },
  optimizeDeps: {
    include: [
      'vscode-ws-jsonrpc',
      'vscode-jsonrpc'
    ]
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    assetsDir: 'assets',
    rollupOptions: {
      output: {
        manualChunks: {
          'monaco-editor': ['monaco-editor'],
          'vendor': ['vue', 'axios']
        }
      }
    }
  }
})
