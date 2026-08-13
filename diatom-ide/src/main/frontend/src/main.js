import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)

// 全局错误处理 (Issue 6.5)
app.config.errorHandler = (err, instance, info) => {
  console.error('[Global Error]', err, info)
}

// 未捕获的 Promise 错误
window.addEventListener('unhandledrejection', (event) => {
  console.error('[Unhandled Promise Rejection]', event.reason)
})

app.mount('#app')
