import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'

import App from '@/App.vue'
import router from '@/router'
import '@/styles/index.css'

/**
 * 应用入口（9.3）。
 *
 * 装配顺序：Pinia → Router → Element Plus。
 * Pinia 必须先于 Router 安装，因为路由全局守卫在导航时会读取 auth store。
 */
const app = createApp(App)

app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })

app.mount('#app')
