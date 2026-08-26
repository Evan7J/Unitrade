// ============================================================
// main.js —— Vue 应用的"启动入口"
// ============================================================
// 【通俗理解】
// 这个文件就是整个前端的"总开关"。
// 它做的事情：把 Vue、路由、状态管理、ElementPlus 组件库等
// 全部组装到一起，然后挂载到 index.html 的 <div id="app"> 上。
// 浏览器打开页面时，第一个执行的就是这个文件。
// ============================================================

// 1. 导入 Vue 核心库
//    createApp 就是用来创建一个 Vue 应用的函数
import { createApp } from 'vue'

// 2. 导入 Pinia —— Vue 的状态管理库
//    【大白话】Pinia 就像一个"全局仓库"，用来存登录用户信息、token 等
//    任何页面都能从这个仓库里取数据，不用传来传去
import { createPinia } from 'pinia'

// 3. 导入 Element Plus 组件库（就是按钮、输入框、表格这些 UI 组件）
import ElementPlus from 'element-plus'
//    Element Plus 的 CSS 样式文件，不导入的话按钮都是丑的
import 'element-plus/dist/index.css'
//    中文语言包，让 Element Plus 的组件显示中文（比如分页的"下一页"）
import zhCn from 'element-plus/dist/locale/zh-cn.mjs'

// 4. 导入 Element Plus 的图标库
//    比如 <el-icon><Search /></el-icon> 这种写法
import * as ElementPlusIconsVue from '@element-plus/icons-vue'

// 5. 导入根组件 App.vue（所有页面都是在这个组件里面显示的）
import App from './App.vue'

// 6. 导入路由配置（控制页面跳转的，比如 /login 显示登录页）
import router from './router'

// 7. 导入全局样式（Tailwind CSS 等）
import './style.css'

// ============================================================
// 8. 组装应用
// ============================================================
const app = createApp(App)       // 创建 Vue 应用实例，传入根组件 App.vue

app.use(createPinia())           // 装上 Pinia 全局仓库
app.use(router)                  // 装上路由（页面跳转功能）
app.use(ElementPlus, { locale: zhCn })  // 装上 Element Plus，并设置中文

// 注册所有 Element Plus 图标，这样在任何页面都能直接用 <el-icon><User /></el-icon>
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

// 9. 挂载！把整个 Vue 应用挂到 index.html 里那个 <div id="app"></div> 上
//    挂载后，那个空的 div 就会被 Vue 生成的页面内容替换掉
app.mount('#app')