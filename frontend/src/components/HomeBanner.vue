<template>
  <!--
    Banner 横幅模块
    暖橙浅渐变纯色背景，左侧宣传文案 + 右侧物品图标
    静态展示，无轮播动效
  -->
  <section class="banner-gradient relative overflow-hidden">
    <div class="page-container flex flex-col md:flex-row items-center justify-between py-12 md:py-16 lg:py-20">
      <!-- 左侧：业务宣传文案 -->
      <div class="flex-1 text-white text-center md:text-left mb-8 md:mb-0 z-10">
        <h1 class="text-[28px] md:text-[36px] lg:text-[42px] font-bold leading-tight mb-4">
          校园闲置<span class="text-white">可信交易平台</span>
        </h1>
        <p class="text-base md:text-lg text-white/85 mb-6 max-w-md">
          同校自提更安心 · 学生之间直接交易 · 让闲置物品重获新生
        </p>
        <div class="flex flex-wrap gap-4 justify-center md:justify-start">
          <router-link
            to="/front/search"
            class="inline-flex items-center gap-1.5 px-6 py-2.5 bg-white text-primary font-medium
                   rounded-card transition-all duration-200 hover:translate-y-[-2px] hover:shadow-lg"
          >
            <el-icon :size="18"><Search /></el-icon>
            立即淘好物
          </router-link>
          <router-link
            to="/front/publish"
            class="inline-flex items-center gap-1.5 px-6 py-2.5 border border-white/60 text-white font-medium
                   rounded-card transition-all duration-200 hover:bg-white/15 hover:translate-y-[-2px]"
          >
            <el-icon :size="18"><Plus /></el-icon>
            发布闲置
          </router-link>
        </div>

        <!-- 核心卖点标签 -->
        <div class="flex flex-wrap gap-3 mt-6 justify-center md:justify-start">
          <span class="text-xs text-white/80 bg-white/15 px-3 py-1 rounded-full">🏫 同校面交自提</span>
          <span class="text-xs text-white/80 bg-white/15 px-3 py-1 rounded-full">💰 学生价更实惠</span>
          <span class="text-xs text-white/80 bg-white/15 px-3 py-1 rounded-full">🔒 实名认证保障</span>
          <span class="text-xs text-white/80 bg-white/15 px-3 py-1 rounded-full">♻️ 环保循环利用</span>
        </div>
      </div>

      <!-- 右侧：后台轮播图 或 默认图标 -->
      <div v-if="banners.length > 0" class="hidden md:flex flex-1 justify-center lg:justify-end z-10">
        <el-carousel :interval="4000" height="380px" class="banner-carousel">
          <el-carousel-item v-for="(b, i) in banners" :key="i">
            <img :src="b.imageUrl" class="banner-img" :style="{ objectPosition: b.objectPosition || 'center' }" @error="e => e.target.style.display='none'" />
          </el-carousel-item>
        </el-carousel>
      </div>
      <div v-else class="hidden md:flex flex-1 justify-center lg:justify-end z-10">
        <div class="grid grid-cols-3 gap-4 lg:gap-6">
          <!-- 使用 Element Plus 线性图标模拟物品分类 -->
          <div
            v-for="icon in displayIcons"
            :key="icon.label"
            class="w-[80px] h-[80px] lg:w-[100px] lg:h-[100px]
                   bg-white/20 backdrop-blur rounded-card
                   flex flex-col items-center justify-center gap-1.5
                   transition-all duration-200 hover:bg-white/30 hover:translate-y-[-2px]"
          >
            <el-icon :size="28" color="#fff"><component :is="icon.component" /></el-icon>
            <span class="text-xs text-white/85">{{ icon.label }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- 底部柔和过渡 -->
    <div class="absolute bottom-0 left-0 right-0 h-8 bg-gradient-to-t from-bg-light to-transparent"></div>
  </section>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { getList } from '../api/banner'

// 后台管理的轮播图
const banners = ref([])
onMounted(async () => {
  try { banners.value = (await getList()).data || [] } catch {}
})

// 如果有后台轮播图，优先展示轮播图；否则展示默认图标
const showBanners = ref(false) // 由 banners 是否为空决定

/*
 * Banner 右侧图标数据
 * 使用 Element Plus 内置线性图标，按分类展示
 */
const displayIcons = [
  { component: 'Iphone', label: '数码电子' },
  { component: 'Reading', label: '图书教材' },
  { component: 'Present', label: '服饰鞋包' },
  { component: 'Box', label: '生活用品' },
  { component: 'Football', label: '运动户外' },
  { component: 'MoreFilled', label: '更多好物' },
]
</script>

<style scoped>
/*
 * Banner 暖橙浅渐变背景
 * 使用 CSS 渐变确保颜色完全可控，不引入其他色系
 */
.banner-gradient {
  background: linear-gradient(135deg, #FF9A3C 0%, #FF7D00 40%, #E56E00 100%);
}

.banner-carousel {
  width: 520px; max-width: 100%; border-radius: 8px; overflow: hidden;
}
.banner-img {
  width: 100%; height: 100%; object-fit: cover; border-radius: 8px;
}
</style>
