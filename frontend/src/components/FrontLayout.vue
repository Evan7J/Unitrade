<template>
  <!--
    FrontLayout - 前台页面布局容器
    包含：顶部导航栏（HomeNavbar）、主内容区、页面底部（HomeFooter）、右侧浮动快捷栏
  -->
  <div class="front-layout min-h-screen bg-bg-light">
    <!-- 顶部固定导航栏 -->
    <HomeNavbar />

    <!-- 公告通知条 -->
    <AnnouncementBar />

    <!-- 主内容区域 -->
    <main class="main-content">
      <!--
        全局返回按钮：非首页时显示，使用浏览器历史返回
        比固定跳转首页更符合用户直觉
      -->
      <div v-if="showBackBtn" class="global-back-bar">
        <el-button text size="small" @click="goBack" class="back-link">
          <el-icon :size="16"><ArrowLeft /></el-icon>
          <span>返回</span>
        </el-button>
      </div>
      <router-view />
    </main>

    <!-- 页面底部 Footer -->
    <HomeFooter />

    <!-- 右侧浮动快捷操作栏 -->
    <div class="float-bar">
      <router-link to="/front/publish" class="float-item publish-item" title="发布闲置">
        <el-icon :size="18"><Plus /></el-icon>
        <span>发布</span>
      </router-link>
      <router-link to="/front/chat" class="float-item" title="消息">
        <el-icon :size="18"><ChatDotRound /></el-icon>
        <span>消息</span>
        <span v-if="unreadCount > 0" class="float-badge">{{ unreadCount > 99 ? '99+' : unreadCount }}</span>
      </router-link>
      <router-link to="/front/orders" class="float-item" title="订单">
        <el-icon :size="18"><Document /></el-icon>
        <span>订单</span>
      </router-link>
      <router-link to="/front/favorites" class="float-item" title="收藏">
        <el-icon :size="18"><Star /></el-icon>
        <span>收藏</span>
      </router-link>
      <router-link to="/front/profile" class="float-item" title="我的">
        <el-icon :size="18"><User /></el-icon>
        <span>我的</span>
      </router-link>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onUnmounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import HomeNavbar from './HomeNavbar.vue'
import HomeFooter from './HomeFooter.vue'
import AnnouncementBar from './AnnouncementBar.vue'
import { getUnread } from '../api/chat'

const router = useRouter()
const route = useRoute()

// 非首页时显示全局返回按钮
const showBackBtn = computed(() => route.path !== '/front/home')

// 使用浏览器历史返回，无历史时回首页
const goBack = () => {
  if (window.history.length > 1) {
    router.back()
  } else {
    router.push('/front/home')
  }
}

// 未读消息数
const unreadCount = ref(0)

// 获取未读消息数
const fetchUnread = () => {
  getUnread().then(res => { unreadCount.value = res.data || 0 }).catch(() => {})
}

// 页面加载时获取，之后每 10 秒轮询
fetchUnread()
const unreadTimer = setInterval(fetchUnread, 10000)

// 组件卸载时清除定时器
onUnmounted(() => clearInterval(unreadTimer))
</script>

<style scoped>
.front-layout {
  min-height: 100vh;
  background: #F5F7FA;
}

.main-content {
  /* 补偿 fixed 导航栏高度，防止内容被遮挡 */
  padding-top: 64px;
  min-height: calc(100vh - 60px);
}

/* 全局返回按钮区域 */
.global-back-bar {
  max-width: 1200px;
  margin: 0 auto;
  padding: 4px 20px 0;
}

.back-link {
  color: #666 !important;
  font-size: 14px;
  padding: 6px 12px;
  border-radius: 6px;
  transition: all 0.2s;
}
.back-link:hover {
  color: #FF7D00 !important;
  background: rgba(255, 125, 0, 0.06);
}

/* ===== 右侧浮动快捷操作栏 ===== */
.float-bar {
  position: fixed;
  right: 16px;
  top: 50%;
  transform: translateY(-50%);
  display: flex;
  flex-direction: column;
  gap: 6px;
  z-index: 99;
}

.float-item {
  position: relative;
  width: 52px;
  height: 52px;
  background: #fff;
  border-radius: 8px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  color: #666;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
  transition: transform 0.2s ease, box-shadow 0.2s ease;
}
.float-item:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.12);
}

/* 发布按钮高亮为橙色 */
.float-item.publish-item {
  background: #FF7D00;
  color: #fff;
  font-weight: 600;
}

/* 未读消息角标 */
.float-badge {
  position: absolute;
  top: -3px;
  right: 4px;
  background: #f56c6c;
  color: #fff;
  border-radius: 10px;
  padding: 0 4px;
  font-size: 9px;
  line-height: 1.4;
  min-width: 16px;
  text-align: center;
}
</style>
