<template>
  <!-- 我的收藏页 - 商品网格展示 -->
  <div class="favorites-page">
    <h2 class="page-title">我的收藏</h2>

    <div v-if="list.length > 0" class="goods-grid">
      <GoodsCard
        v-for="item in list"
        :key="item.id"
        :goods="item"
      />
    </div>

    <el-empty v-else description="暂无收藏">
      <router-link to="/front/home" class="text-primary text-sm hover:underline">去首页逛逛</router-link>
    </el-empty>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { myList } from '../../api/favorite'
import GoodsCard from '../../components/GoodsCard.vue'

const list = ref([])

onMounted(async () => {
  try { list.value = (await myList()).data || [] } catch { list.value = [] }
})
</script>

<style scoped>
.favorites-page {
  max-width: 1200px;
  margin: 0 auto;
  padding: 0 20px;
}

.page-title {
  font-size: 20px;
  font-weight: 700;
  color: #333;
  margin-bottom: 20px;
}

.goods-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
}

@media (max-width: 1024px) {
  .goods-grid { grid-template-columns: repeat(2, 1fr); }
}

@media (max-width: 640px) {
  .goods-grid { grid-template-columns: 1fr; }
}
</style>
