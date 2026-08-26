import { createRouter, createWebHistory } from 'vue-router'
import { ElMessage } from 'element-plus'

// ============================================================
// 路由配置
// ============================================================
const routes = [
  // 公开页面（不需要登录）
  { path: '/login', name: 'Login', component: () => import('../views/Login.vue') },
  { path: '/register', name: 'Register', component: () => import('../views/Register.vue') },

  // 前台页面（普通用户 aaa）
  {
    path: '/front',
    component: () => import('../components/FrontLayout.vue'),
    meta: { role: 'user' },  // ← 标记：只有 user 角色能访问
    children: [
      { path: 'home', name: 'Home', component: () => import('../views/front/Home.vue') },
      { path: 'assistant', name: 'Assistant', component: () => import('../views/front/Assistant.vue') },
      { path: 'search', name: 'Search', component: () => import('../views/front/Search.vue') },
      { path: 'publish', name: 'Publish', component: () => import('../views/front/Publish.vue') },
      { path: 'editProduct', name: 'EditProduct', component: () => import('../views/front/EditProduct.vue') },
      { path: 'goodsDetail', name: 'GoodsDetail', component: () => import('../views/front/GoodsDetail.vue') },
      { path: 'chat', name: 'Chat', component: () => import('../views/front/Chat.vue') },
      { path: 'orders', name: 'Orders', component: () => import('../views/front/Orders.vue') },
      { path: 'favorites', name: 'Favorites', component: () => import('../views/front/Favorites.vue') },
      { path: 'profile', name: 'Profile', component: () => import('../views/front/Profile.vue') },
      { path: 'address', name: 'AddressManage', component: () => import('../views/front/AddressManage.vue') },
      { path: 'user', name: 'UserProfile', component: () => import('../views/front/UserProfile.vue') },
    ]
  },

  // 后台页面（管理员 aaa）
  {
    path: '/back',
    component: () => import('../components/AdminLayout.vue'),
    meta: { role: 'admin' },  // ← 标记：只有 admin 角色能访问
    children: [
      { path: 'dashboard', name: 'Dashboard', component: () => import('../views/back/Dashboard.vue') },
      { path: 'announcement', name: 'AdminAnnouncement', component: () => import('../views/back/Announcement.vue') },
      { path: 'banner', name: 'AdminBanner', component: () => import('../views/back/Banner.vue') },
      { path: 'category', name: 'AdminCategory', component: () => import('../views/back/Category.vue') },
      { path: 'product', name: 'AdminProduct', component: () => import('../views/back/Product.vue') },
      { path: 'order', name: 'AdminOrder', component: () => import('../views/back/Order.vue') },
      { path: 'user', name: 'AdminUser', component: () => import('../views/back/User.vue') },
    ]
  },

  { path: '/', redirect: '/front/home' }
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior() {
    return { top: 0 }
  },
})

// ============================================================
// 路由守卫（全局前置守卫 beforeEach）
// ============================================================
// 【通俗理解】
// router.beforeEach 就像学校门口的保安：
//   每次有人想进校门（跳转页面），保安先拦住问：
//     1. 你是学生吗？（登录了吗？）
//     2. 你是几年级的？（角色对不对？）
//   答不上来 → 不让进（跳转登录页）
//   答对了    → 放行（next()）
//
// 【参数说明】
//   to：要去的页面（包含 path、meta 等信息）
//   from：从哪来的页面
//   next：放行函数，调用 next() 就放行，next('/login') 就跳转到登录页
// ============================================================
router.beforeEach((to, from, next) => {
  // ============================================================
  // 第一关：公开页面直接放行
  // ============================================================
  // /login 和 /register 是人人可访问的，不需要登录
  if (to.path === '/login' || to.path === '/register') {
    return next()
  }

  // ============================================================
  // 第二关：检查是否登录
  // ============================================================
  const token = localStorage.getItem('token')
  if (!token) {
    // 没登录就想去其他页面？踢回登录页
    ElMessage.warning('请先登录')
    return next('/login')
  }

  // ============================================================
  // 第三关：检查角色权限
  // ============================================================
  // 从 localStorage 取出当前用户的角色
  const role = localStorage.getItem('role')

  // 从路由配置中取出该页面要求的角色
  // to.matched 是当前路由匹配到的所有路由记录（包括父路由和子路由）
  // 找第一个有 role 要求的记录
  const requiredRole = to.matched.find(r => r.meta.role)?.meta?.role

  if (requiredRole && role !== requiredRole) {
    // 角色不匹配！
    // 比如：普通用户想进 /back（后台），或者管理员想进 /front（前台）
    ElMessage.warning('无权访问该页面')

    // 根据实际角色跳转到正确的位置
    if (role === 'admin') {
      return next('/back/dashboard')
    } else if (role === 'user') {
      return next('/front/home')
    } else {
      // role 缺失或异常（旧版本残留、被清掉等）：
      // 直接清空登录态回登录页，避免在 /front/home 和守卫之间死循环
      localStorage.removeItem('token')
      localStorage.removeItem('role')
      localStorage.removeItem('loginUserId')
      return next('/login')
    }
  }

  // ============================================================
  // 全部通过，放行！
  // ============================================================
  next()
})

export default router