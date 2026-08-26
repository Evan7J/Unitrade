// ============================================================
// request.js —— 前端统一请求工具（封装axios）
// ============================================================
// 【通俗理解】
// 这个文件就是"电话机"，以后所有页面想给后端发请求，都用这个电话机来拨号。
// 好处：
//   1. 不用每次写完整的 http://localhost:8080 地址
//   2. 自动带上 token（登录后存的身份凭证）
//   3. 统一处理成功/失败，不用每个页面重复写判断逻辑
// ============================================================

// 1. 导入 axios 库（就是那个"电话机"的零件）
//    axios 是一个专门用来发 HTTP 请求的 JS 库
import axios from 'axios'

// 2. 导入 Element Plus 的消息提示组件
//    ElMessage 用来在页面上弹出提示框（成功/失败的时候弹一下）
import { ElMessage } from 'element-plus'

// 3. 导入路由实例，用于 401 时跳转登录页（不刷新页面）
import router from '../router'

// ============================================================
// 4. 创建 axios 实例 —— 相当于组装好一部电话机
//    axios.create() 创建一个"定制版"的 axios
//    配置项说明：
//      baseURL: '/api'  —— 所有请求的地址前面自动加上 /api
//                比如后面写 /user/login，实际请求就是 /api/user/login
//      timeout: 10000   —— 超时时间，10秒内后端没响应就自动放弃
// ============================================================
const request = axios.create({
  baseURL: '/api',      // 请求地址的统一前缀
  timeout: 10000        // 10秒超时（单位：毫秒）
})

// ============================================================
// 5. 【请求拦截器】—— 每次发请求之前，自动执行这里的代码
//    "拦截器"的意思是：在请求发出去之前，先拦截下来，做一些预处理
//
//    这里做的事情：从浏览器 localStorage 里取出 token，
//    然后塞到请求头 Authorization 里，后端就能识别你是谁
//
//    【大白话】：
//    就像你打电话给客服之前，电话机自动帮你报上你的会员号
//    后端收到请求后，从请求头里拿到 token，就知道是哪个用户了
// ============================================================
request.interceptors.request.use(
  config => {
    // 从浏览器本地存储里取出 token
    // localStorage 是浏览器自带的小仓库，数据存进去后关掉浏览器也不会丢
    // 登录成功后，我们会把 token 存到这里
    const token = localStorage.getItem('token')

    // 如果 token 存在，就把它塞到请求头里
    // 格式：Authorization: Bearer xxxxxxx
    // 后端 JwtInterceptor 会从请求头里读取这个字段来验证身份
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }

    // 返回处理后的 config，axios 拿到这个 config 才会真正发出请求
    return config
  },
  // 第二个参数是请求出错时的处理
  error => {
    return Promise.reject(error)
  }
)

// ============================================================
// 6. 401 防重复跳转标记
// ============================================================
// 如果多个请求同时返回 401（比如首页同时请求了 banner、商品列表等），
// 每个请求都会触发跳转登录页，导致多次跳转、多次弹提示。
// 用一个标记位来防止重复处理。
let isRedirectingToLogin = false

// ============================================================
// 7. 【响应拦截器】—— 每次收到后端返回的数据后，自动执行这里的代码
// ============================================================
request.interceptors.response.use(
  // 第一个参数：请求成功（HTTP 200）时的处理
  response => {
    // response.data 是后端返回的 JSON 数据
    // 后端统一返回格式：{ code: 200, msg: "操作成功", data: {...} }
    const res = response.data

    // 判断业务状态码
    // code !== 200 说明后端业务处理失败了（比如用户名密码错误）
    if (res.code !== 200) {
      ElMessage.error(res.msg || '请求失败')
      return Promise.reject(new Error(res.msg))
    }

    // code === 200，成功，直接返回后端数据
    return res
  },

  // 第二个参数：HTTP 请求本身失败（网络错误、超时、401等）时的处理
  error => {
    // ============================================================
    // 401 处理：Token 过期或无效
    // ============================================================
    if (error.response && error.response.status === 401) {
      // 防重复跳转：如果已经在跳转登录页的路上了，就不重复处理
      if (!isRedirectingToLogin) {
        isRedirectingToLogin = true

        // 清除所有登录相关数据
        localStorage.removeItem('token')
        localStorage.removeItem('role')
        localStorage.removeItem('loginUserId')

        ElMessage.warning('登录已过期，请重新登录')

        // 使用 router.push 跳转（SPA 内部跳转，不刷新页面）
        // 注意：如果当前已经在登录页，就不跳了（避免死循环）
        if (router.currentRoute.value.path !== '/login') {
          router.push('/login').then(() => {
            isRedirectingToLogin = false  // 跳转完成后重置标记
          })
        } else {
          isRedirectingToLogin = false
        }
      }
      return Promise.reject(error)
    }

    // 其他 HTTP 错误（500、403 等）
    ElMessage.error(error.message || '网络错误')
    return Promise.reject(error)
  }
)

// ============================================================
// 8. 导出这个 request 实例
//    这样其他文件就可以 import 它来发请求了
//    比如：import request from '@/utils/request'
// ============================================================
export default request