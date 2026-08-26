<template>
  <!--
    商品卡片组件
    结构：商品图 → 标题 → 售价（橙色高亮） → 卖家昵称 → 距离标签
    点击跳转商品详情页
  -->
  <div
    class="goods-card bg-white rounded-card shadow-card overflow-hidden cursor-pointer
            transition-all duration-200 hover:-translate-y-0.5 hover:shadow-card-hover"
    @click="handleClick"
  >
    <!-- 商品图片 -->
    <div class="relative w-full pt-[100%] bg-bg-light overflow-hidden">
      <img
        :src="goods.coverImage || defaultCover"
        :alt="goods.title"
        class="absolute inset-0 w-full h-full object-cover transition-transform duration-300 hover:scale-105"
        @error="onImgError"
      />
      <!-- 商品状态标签（如已售出） -->
      <span
        v-if="goods.status === 1"
        class="absolute top-2 right-2 text-xs px-2 py-0.5 bg-text-secondary/70 text-white rounded-sm"
      >
        已售出
      </span>
    </div>

    <!-- 商品信息 -->
    <div class="p-3 flex flex-col gap-2">
      <!-- 标题：最多两行 -->
      <h3 class="text-sm text-text-main leading-snug line-clamp-2 min-h-[2.5em]">
        {{ goods.title }}
      </h3>

      <!-- 售价：橙色高亮 -->
      <div class="flex items-baseline gap-0.5">
        <span class="text-xs text-primary font-medium">¥</span>
        <span class="text-lg font-bold text-primary">{{ goods.price }}</span>
        <span v-if="goods.originalPrice" class="text-xs text-text-muted line-through ml-1.5">
          ¥{{ goods.originalPrice }}
        </span>
      </div>

      <!-- 卖家信息：头像 + 昵称（闲鱼风格） -->
      <div class="flex items-center justify-between mt-auto">
        <div class="flex items-center gap-1.5 min-w-0">
          <el-avatar :size="18" :src="goods.avatarUrl" class="shrink-0">
            <el-icon :size="12"><User /></el-icon>
          </el-avatar>
          <span class="text-xs text-text-secondary truncate">
            {{ goods.nickname || '未知用户' }}
          </span>
        </div>
        <span
          v-if="goods.distance"
          class="text-xs text-text-muted bg-bg-light px-1.5 py-0.5 rounded-sm shrink-0"
        >
          {{ goods.distance }}
        </span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { useRouter } from 'vue-router'

const router = useRouter()

// Props 定义：接收商品数据对象
const props = defineProps({
  goods: {
    type: Object,
    required: true,
    default: () => ({
      id: 0,
      title: '',
      price: 0,
      originalPrice: 0,
      coverImage: '',
      nickname: '',
      avatarUrl: '',
      distance: '',
      status: 0,
    }),
  },
})

// 默认占位图
const defaultCover = '/vite.svg'

// 图片加载失败时使用默认图
const onImgError = (e) => {
  e.target.src = defaultCover
}

/*
 * 点击商品卡片跳转详情页
 * 预留接口：可在此处添加埋点统计等逻辑
 */
const handleClick = () => {
  if (props.goods.id) {
    router.push(`/front/goodsDetail?id=${props.goods.id}`)
  }
}
</script>
