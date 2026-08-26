<!--
  ============================================================
  Home.vue —— 首页
  ============================================================
  【通俗理解】
  首页是用户打开网站看到的第一个页面，类似淘宝首页。
  由三个子组件拼成：Banner横幅 → 分类标签 → 商品卡片网格。
  页面加载时自动调用后端接口获取商品数据，然后用 v-for 循环渲染卡片。
  ============================================================
-->
<template>
  <div class="home-page">
    <!--
      ========================================
      第一块：Banner 横幅
      ========================================
      HomeBanner 是子组件，定义在 components/HomeBanner.vue
      父组件引入子组件：import HomeBanner from '...'
      然后直接在模板里 <HomeBanner /> 就能用
    -->
    <HomeBanner />

    <!--
      ========================================
      第二块：分类标签
      ========================================
      HomeCategory 是子组件，显示横向可滑动的分类标签
      点击某个分类会跳转到搜索结果页，带上分类ID参数
    -->
    <HomeCategory />

    <!--
      ========================================
      第三块：商品列表区域
      ========================================
    -->
    <section class="page-container py-6">
      <!-- 标题行：左边"精选好物"，右边"查看更多"链接 -->
      <div class="flex items-center justify-between mb-4">
        <h2 class="text-lg font-bold text-text-main">精选好物</h2>
        <router-link
          to="/front/search"
          class="text-sm text-primary hover:text-primary-dark transition-colors flex items-center gap-1"
        >
          查看更多
          <el-icon :size="14"><ArrowRight /></el-icon>
        </router-link>
      </div>

      <!--
        ========================================
        加载状态：loading = true 时显示
        ========================================
        v-if="loading" → Vue 的条件渲染指令
          当 loading 为 true 时，显示这个 div
          当 loading 为 false 时，隐藏这个 div
        animate-spin → 自定义的旋转动画 CSS
      -->
      <div v-if="loading" class="flex justify-center py-16">
        <el-icon class="animate-spin text-primary" :size="32"><Loading /></el-icon>
      </div>

      <!--
        ========================================
        商品网格：有数据时显示
        ========================================
        v-else-if="goodsList.length > 0" → 如果 loading 是 false 且数组不为空

        【核心：v-for 循环渲染】
        v-for="item in goodsList" → 遍历 goodsList 数组
          每次循环取出一个元素，存到 item 变量里
          然后把这个 item 传给 GoodsCard 子组件显示

        类比 Java 的 for 循环：
          for (ProductListVO item : goodsList) {
              // 渲染一个 GoodsCard，传入 item
          }

        :key="item.id" → 每个循环项必须有一个唯一的 key
          作用：Vue 用 key 来追踪每个元素，提高渲染性能
          通常用数据的 id 作为 key
      -->
      <div v-else-if="goodsList.length > 0" class="goods-grid">
        <!--
          GoodsCard 是商品卡片子组件
          :goods="item" → 把当前循环的 item 传给 GoodsCard 组件的 goods 属性
            = 号前面的冒号表示"动态绑定"，goods 是子组件定义的 props 名称
            等号后面是 JS 表达式，item 是 v-for 循环出来的当前项
        -->
        <GoodsCard
          v-for="item in goodsList"
          :key="item.id"
          :goods="item"
        />
      </div>

      <!--
        ========================================
        空状态：没有数据时显示
        ========================================
        v-else → 前面两个条件都不满足时显示
      -->
      <div v-else class="flex flex-col items-center justify-center py-16 text-text-muted">
        <el-icon :size="48"><Box /></el-icon>
        <p class="mt-3 text-sm">暂无商品，快去发布第一件闲置吧～</p>
        <router-link
          to="/front/publish"
          class="mt-3 text-sm text-primary hover:text-primary-dark transition-colors"
        >
          发布闲置 →
        </router-link>
      </div>
    </section>
  </div>
</template>

<script setup>
// ============================================================
// 导入依赖
// ============================================================
import { ref, onMounted } from 'vue'

// 子组件
import HomeBanner from '../../components/HomeBanner.vue'
import HomeCategory from '../../components/HomeCategory.vue'
import GoodsCard from '../../components/GoodsCard.vue'

// 接口方法：获取商品列表
import { getGoodsList } from '../../api/goods'

// ============================================================
// 响应式数据
// ============================================================
// goodsList：商品列表数组，初始为空数组
//   ref([]) 创建一个响应式的空数组
//   后面调用接口拿到数据后，goodsList.value = 新数据
//   Vue 检测到 goodsList.value 变了，自动重新渲染页面
const goodsList = ref([])

// loading：加载状态，初始为 false
const loading = ref(false)

// ============================================================
// 获取商品列表
// ============================================================
// 【核心函数：fetchGoodsList】
// 执行流程：
//   1. loading = true  → 页面显示转圈
//   2. 调用 getGoodsList({ page: 1, size: 8 }) → 发 GET 请求到后端
//   3. 后端返回 { code: 200, data: { records: [...], total: 50 } }
//   4. 取 res.data.records 赋值给 goodsList
//   5. loading = false → 页面显示商品卡片
const fetchGoodsList = async () => {
  // 第一步：开启加载状态
  loading.value = true

  try {
    // 第二步：调用后端接口
    //   传入参数：{ page: 1, size: 8 } → 第1页，每页8条
    //   axios 自动拼成：GET /api/product/list?page=1&size=8
    const res = await getGoodsList({ page: 1, size: 8 })

    // 第三步：取数据
    //   res.data 是后端返回的 Page 对象
    //   res.data.records 是商品数组（这一页的数据）
    //   res.data.total 是总条数
    //   || [] 是兜底：如果 records 是 undefined，就用空数组，避免报错
    goodsList.value = res.data?.records || res.data || []
  } catch (error) {
    // 第四步：出错处理
    console.error('获取商品列表失败:', error)
    goodsList.value = []
  } finally {
    // 第五步：不管成功还是失败，都关闭加载状态
    loading.value = false
  }
}

// ============================================================
// 生命周期钩子：onMounted
// ============================================================
// 【通俗理解】
// onMounted 是 Vue 的"生命周期钩子"。
// 当页面加载完成（组件挂载到 DOM 上）后，自动执行里面的代码。
// 类比：Java 的 @PostConstruct 注解，对象创建后自动执行。
//
// 这里用来在页面加载时自动获取商品列表数据。
onMounted(() => {
  fetchGoodsList()
})
</script>

<style scoped>
/* 整体容器 */
.home-page {
  /* 预留 */
}

/* ===== 响应式商品网格 ===== */
/* CSS Grid 布局：默认4列 */
.goods-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);  /* 4列，每列等宽 */
  gap: 16px;                               /* 卡片间距 */
}

/* 平板：2列（屏幕宽度 ≤ 1024px 时生效） */
@media (max-width: 1024px) {
  .goods-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

/* 移动端：1列（屏幕宽度 ≤ 640px 时生效） */
@media (max-width: 640px) {
  .goods-grid {
    grid-template-columns: 1fr;
  }
}

/* 加载动画：无限旋转 */
.animate-spin {
  animation: spin 1s linear infinite;
}
@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}
</style>