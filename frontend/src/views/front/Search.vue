<template>
  <!--
    商品搜索/列表页
    支持：关键词搜索、分类筛选、价格排序、分页
  -->
  <div class="search-page">
    <!-- 排序筛选栏 -->
    <div class="filter-bar">
      <div class="filter-left">
        <span class="filter-label" v-if="route.query.categoryName">分类：{{ route.query.categoryName }}</span>
        <span class="filter-label" v-if="route.query.keyword">搜索："{{ route.query.keyword }}"</span>
        <span v-if="total > 0" class="filter-count">共 {{ total }} 件商品</span>
      </div>
      <el-radio-group v-model="sortBy" @change="fetchData" size="small">
        <el-radio-button value="">综合</el-radio-button>
        <el-radio-button value="newest">最新</el-radio-button>
        <el-radio-button value="price_asc">价格↑</el-radio-button>
        <el-radio-button value="price_desc">价格↓</el-radio-button>
      </el-radio-group>
    </div>

    <!-- 商品网格：复用 GoodsCard 组件 -->
    <div v-if="products.length > 0" class="goods-grid">
      <GoodsCard
        v-for="item in products"
        :key="item.id"
        :goods="item"
      />
    </div>

    <!-- 空状态 -->
    <el-empty v-else description="暂无相关商品">
      <router-link to="/front/publish" class="text-primary text-sm hover:underline">去发布第一件闲置吧</router-link>
    </el-empty>

    <!-- 分页 -->
    <div v-if="total > size" class="pagination-wrap">
      <el-pagination
        v-model:current-page="page"
        :page-size="size"
        :total="total"
        @current-change="fetchData"
        layout="total, prev, pager, next"
        background
      />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { getList } from '../../api/product'
import GoodsCard from '../../components/GoodsCard.vue'

const route = useRoute()
const products = ref([])
const page = ref(1)
const size = ref(8)
const total = ref(0)
const sortBy = ref('')

const fetchData = async () => {
  const params = {
    page: page.value,
    size: size.value,
    sortBy: sortBy.value || 'newest',
  }
  if (route.query.keyword) params.keyword = route.query.keyword
  if (route.query.categoryId) params.categoryId = route.query.categoryId
  try {
    const res = await getList(params)
    products.value = res.data.records || []
    total.value = res.data.total || 0
  } catch {
    products.value = []
  }
}

onMounted(() => {
  window.scrollTo(0, 0)
  fetchData()
})
</script>

<style scoped>
.search-page {
  max-width: 1200px;
  margin: 0 auto;
  padding: 8px 20px 0;
}

/* 筛选栏 */
.filter-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  padding: 8px 0 12px;
}

.filter-left {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.filter-label {
  font-size: 14px;
  color: #666;
}

.filter-count {
  font-size: 12px;
  color: #999;
}

/* 商品网格 */
.goods-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  padding-bottom: 20px;
}

@media (max-width: 1024px) {
  .goods-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 640px) {
  .goods-grid {
    grid-template-columns: 1fr;
  }
}

/* 分页 */
.pagination-wrap {
  display: flex;
  justify-content: center;
  padding: 24px 0 40px;
}
</style>
