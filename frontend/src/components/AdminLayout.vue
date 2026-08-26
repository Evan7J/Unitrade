<template>
  <div class="admin-layout">
    <aside class="sidebar">
      <div class="logo">后台管理</div>
      <el-menu :default-active="route.path" router background-color="#304156" text-color="#bfcbd9" active-text-color="#ffd04b">
        <el-menu-item index="/back/dashboard"><el-icon><DataAnalysis /></el-icon><span>后台首页</span></el-menu-item>
        <el-menu-item index="/back/announcement"><el-icon><Bell /></el-icon><span>公告管理</span></el-menu-item>
        <el-menu-item index="/back/banner"><el-icon><Picture /></el-icon><span>轮播图管理</span></el-menu-item>
        <el-menu-item index="/back/category"><el-icon><Grid /></el-icon><span>商品分类管理</span></el-menu-item>
        <el-menu-item index="/back/product"><el-icon><Goods /></el-icon><span>闲置商品管理</span></el-menu-item>
        <el-menu-item index="/back/order"><el-icon><Tickets /></el-icon><span>商品订单管理</span></el-menu-item>
        <el-menu-item index="/back/user"><el-icon><UserFilled /></el-icon><span>系统角色管理</span></el-menu-item>
      </el-menu>
    </aside>
    <main class="content">
      <header class="topbar"><span class="title">二手交易平台</span><el-button @click="logout">退出</el-button></header>
      <router-view />
    </main>
  </div>
</template>

<script setup>
import { useRoute, useRouter } from 'vue-router'
const route = useRoute()
const router = useRouter()
const logout = () => { localStorage.removeItem('token'); router.push('/login') }
</script>

<style scoped>
.admin-layout { display: flex; min-height: 100vh; }
.sidebar { width: 220px; background: #304156; min-height: 100vh; }
.logo { color: #fff; text-align: center; padding: 20px; font-size: 18px; font-weight: bold; }
.content { flex: 1; background: #f0f2f5; }
.topbar { background: #fff; padding: 0 20px; height: 50px; display: flex; align-items: center; justify-content: space-between; box-shadow: 0 1px 4px rgba(0,0,0,.1); }
.title { font-size: 16px; color: #333; }
</style>