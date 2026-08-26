<!--
  ============================================================
  HomeCategory.vue —— 分类标签组件
  ============================================================
  【通俗理解】
  首页中间的那一排分类标签（数码、图书、服饰...），横向可滑动。
  点击某个分类 → 跳转到搜索页，自动按该分类筛选商品。
  分类数据从后端接口获取，不再写死。
  ============================================================
-->
<template>
  <section class="page-container py-8">
    <h2 class="text-lg font-bold text-text-main mb-4">全部分类</h2>

    <!--
      横向滚动容器
      v-for="cat in categories" → 循环渲染分类标签
      每个标签是一个 div，点击触发 handleCategoryClick
    -->
    <div
      ref="scrollContainer"
      class="flex gap-3 overflow-x-auto pb-2 scroll-smooth hide-scrollbar"
      @mousedown="onMouseDown"
      @mousemove="onMouseMove"
      @mouseup="onMouseUp"
      @mouseleave="onMouseUp"
    >
      <!--
        ========================================
        v-for 循环渲染分类标签
        ========================================
        v-for="cat in categories" → 遍历 categories 数组
          cat 是数组里每个元素，有 cat.id 和 cat.name
        :key="cat.id" → 唯一标识
        @click="handleCategoryClick(cat)" → 点击时调用函数，传入当前分类对象
      -->
      <div
        v-for="cat in categories"
        :key="cat.id"
        class="category-card flex-shrink-0 w-[100px] sm:w-[110px] py-4 px-3
               bg-white rounded-card shadow-card cursor-pointer select-none
               flex flex-col items-center gap-2
               transition-all duration-200
               hover:-translate-y-0.5 hover:shadow-card-hover"
        @click="handleCategoryClick(cat)"
      >
        <!-- 图标：Element Plus 的图标组件 -->
        <el-icon :size="24" color="#FF7D00">
          <component :is="cat.icon || 'MoreFilled'" />
        </el-icon>
        <!-- 分类名称 -->
        <span class="text-sm text-text-main font-medium">{{ cat.name }}</span>
      </div>
    </div>
  </section>
</template>

<script setup>
// ============================================================
// 导入依赖
// ============================================================
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'

// 分类接口：从后端获取分类列表
import { getList } from '../api/category'

const router = useRouter()

// ============================================================
// 分类数据
// ============================================================
// 【改造说明】
// 之前分类数据是写死的（hardcoded），现在改为从后端接口获取。
// 好处：后续后台新增/修改分类，前端不用改代码，自动同步。
//
// 每个分类对象的结构：
//   { id: 1, name: "数码产品", sortOrder: 1 }
// 后端 entity/Category.java 定义了这个结构
const categories = ref([])

// ============================================================
// 页面加载时从后端获取分类列表
// ============================================================
onMounted(async () => {
  try {
    // 调用后端接口 GET /api/category/list
    const res = await getList()
    // res.data 是分类数组 [{ id: 1, name: "数码产品" }, ...]
    categories.value = res.data || []
  } catch (error) {
    console.error('获取分类失败:', error)
  }
})

// ============================================================
// 点击分类 → 跳转到搜索页，带上分类ID
// ============================================================
// 【执行流程】
//   1. 用户点击"数码产品"标签
//   2. handleCategoryClick({ id: 1, name: "数码产品" })
//   3. router.push 跳转到 /front/search?categoryId=1&categoryName=数码产品
//   4. 搜索页拿到 URL 参数，自动按 categoryId=1 筛选商品
//
// router.push 的两种写法：
//   方式1（对象）：router.push({ path: '/front/search', query: { categoryId: 1 } })
//      → 跳转 /front/search?categoryId=1
//   方式2（字符串）：router.push('/front/search?categoryId=1')
//      → 效果一样
const handleCategoryClick = (cat) => {
  router.push({
    path: '/front/search',
    query: {
      categoryId: cat.id,        // 分类ID，搜索页用这个值筛选
      categoryName: cat.name     // 分类名称，搜索页显示"当前分类：数码产品"
    }
  })
}

// ===== 横向拖拽滑动逻辑（不重要，保留即可） =====
const scrollContainer = ref(null)
let isDown = false
let startX = 0
let scrollLeftPos = 0

const onMouseDown = (e) => {
  isDown = true
  startX = e.pageX - scrollContainer.value.offsetLeft
  scrollLeftPos = scrollContainer.value.scrollLeft
}

const onMouseUp = () => {
  isDown = false
}

const onMouseMove = (e) => {
  if (!isDown) return
  e.preventDefault()
  const x = e.pageX - scrollContainer.value.offsetLeft
  const walk = (x - startX) * 1.5
  scrollContainer.value.scrollLeft = scrollLeftPos - walk
}
</script>

<style scoped>
/* 隐藏滚动条但保留滚动能力 */
.hide-scrollbar {
  -ms-overflow-style: none;
  scrollbar-width: none;
}
.hide-scrollbar::-webkit-scrollbar {
  display: none;
}
</style>