<template>
  <!--
    顶部导航栏组件
    功能：固定顶部、滚动吸顶（透明→白色背景）、导航菜单、搜索、登录入口
  -->
  <!-- 导航栏：始终白底+品牌橙点缀，避免透明导致的撞色看不清 -->
  <header
    class="fixed top-0 left-0 right-0 z-[100] h-[60px] flex items-center bg-white shadow-navbar"
  >
    <div class="page-container w-full flex items-center justify-between gap-6">
      <!-- 左侧：Logo -->
      <router-link to="/front/home" class="text-xl font-bold whitespace-nowrap shrink-0 text-primary">
        UniTrade
      </router-link>

      <!-- 中间：导航菜单 -->
      <nav class="hidden md:flex items-center gap-6 flex-1 justify-center">
        <router-link
          v-for="item in navItems"
          :key="item.path"
          :to="item.path"
          class="text-sm text-text-secondary transition-colors duration-200 hover:text-primary"
        >
          {{ item.label }}
        </router-link>
      </nav>

      <!-- 右侧：搜索框 + 操作按钮 -->
      <div class="flex items-center gap-3 shrink-0">
        <!-- 搜索框 + 橙色搜索按钮（PC端显示） -->
        <div class="hidden sm:flex items-center gap-0">
          <el-input
            v-model="keyword"
            size="small"
            placeholder="搜索商品或用户"
            class="w-[170px] lg:w-[210px] search-input-wrapper"
            @keyup.enter="handleSearch"
          />
          <el-button
            type="warning"
            size="small"
            class="search-btn !rounded-l-none !ml-0"
            @click="handleSearch"
          >
            <el-icon :size="16"><Search /></el-icon>
          </el-button>
        </div>

        <!-- 搜索按钮（移动端显示） -->
        <router-link to="/front/search" class="sm:hidden p-1.5 text-text-secondary">
          <el-icon :size="20"><Search /></el-icon>
        </router-link>

        <!-- 登录/注册按钮 -->
        <template v-if="!isLoggedIn">
          <router-link
            to="/login"
            class="text-sm px-4 py-1.5 border border-primary text-primary rounded-card
                   transition-all duration-200 hover:bg-primary hover:text-white"
          >
            登录
          </router-link>
          <router-link
            to="/register"
            class="text-sm px-4 py-1.5 bg-primary text-white rounded-card
                   transition-all duration-200 hover:bg-primary-dark hidden sm:inline-block"
          >
            注册
          </router-link>
        </template>

        <!-- 已登录：头像 + hover 下拉菜单 -->
        <el-popover
          v-else
          trigger="hover"
          :width="140"
          placement="bottom"
        >
          <template #reference>
            <el-avatar :size="30" :src="myAvatar" class="shrink-0 cursor-pointer">
              <el-icon :size="16"><User /></el-icon>
            </el-avatar>
          </template>
          <div class="popover-menu">
            <div class="popover-item" @click="router.push('/front/user?id=' + myUserId)">个人主页</div>
            <div class="popover-item" @click="router.push('/front/profile')">编辑资料</div>
            <div class="popover-divider"></div>
            <div class="popover-item logout" @click="handleLogout">退出登录</div>
          </div>
        </el-popover>
      </div>
    </div>
  </header>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getProfile } from '../api/user'

const router = useRouter()

// 当前用户信息
const myAvatar = ref('')
const myUserId = ref(null)
onMounted(async () => {
  if (localStorage.getItem('token')) {
    try {
      const res = await getProfile()
      myAvatar.value = res.data?.avatarUrl || ''
      myUserId.value = res.data?.id || null
    } catch {}
  }
})

// 退出登录
const handleLogout = () => {
  localStorage.removeItem('token')
  localStorage.removeItem('loginUserId')
  window.location.href = '/front/home'
}

// 导航菜单项
const navItems = [
  { label: '首页', path: '/front/home' },
  { label: 'AI 助手', path: '/front/assistant' },
  { label: '全部分类', path: '/front/search' },
  { label: '发布闲置', path: '/front/publish' },
  { label: '消息通知', path: '/front/chat' },
  { label: '我的', path: '/front/profile' },
]

// 搜索关键词（双向绑定，预留搜索事件）
const keyword = ref('')

// 登录状态判断
const isLoggedIn = ref(!!localStorage.getItem('token'))

// 搜索事件：携带关键词跳转搜索页（支持搜索商品标题和用户昵称）
const handleSearch = () => {
  if (keyword.value.trim()) {
    router.push({ path: '/front/search', query: { keyword: keyword.value.trim() } })
  }
}
</script>

<style scoped>
/* 搜索按钮：品牌橙色 */
.search-btn:deep(.el-button) {
  background-color: #FF7D00 !important;
  border-color: #FF7D00 !important;
}
.search-btn:deep(.el-button:hover) {
  background-color: #E56E00 !important;
  border-color: #E56E00 !important;
}

/* 搜索输入框右侧圆角归零，与按钮无缝拼接 */
.search-input-wrapper :deep(.el-input__wrapper) {
  border-top-right-radius: 0;
  border-bottom-right-radius: 0;
}

/* 头像下拉菜单 */
.popover-menu { padding: 4px 0; }
.popover-item {
  padding: 8px 16px; font-size: 13px; color: #333;
  cursor: pointer; transition: background 0.15s;
}
.popover-item:hover { background: #F5F7FA; }
.popover-item.logout { color: #f56c6c; }
.popover-divider { height: 1px; background: #eee; margin: 4px 0; }
</style>
