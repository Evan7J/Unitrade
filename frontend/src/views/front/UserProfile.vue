<template>
  <!--
    用户主页（公开）
    展示：头像、昵称、学校、个人简介、在售商品、评价
    非本人时仅展示，本人可跳转编辑页
  -->
  <div class="user-profile-page" v-if="user">
    <!-- 用户信息卡片 -->
    <div class="user-card">
      <div class="user-card-inner">
        <el-avatar :size="72" :src="user.avatarUrl" class="user-avatar">
          <el-icon :size="36"><User /></el-icon>
        </el-avatar>
        <div class="user-info">
          <div class="user-name-row">
            <h2 class="user-name">{{ user.nickname || '匿名用户' }}</h2>
            <span class="user-badge">已认证</span>
          </div>
          <p class="user-school" v-if="user.school">
            <el-icon :size="14"><School /></el-icon>
            {{ user.school }}
          </p>
          <p class="user-bio">{{ user.bio || '这个人很懒，什么都没写~' }}</p>
          <div class="user-stats">
            <span class="stat-item">在售 <strong>{{ productCount }}</strong> 件</span>
            <span class="stat-item">好评率 <strong>{{ goodRate }}</strong></span>
          </div>
        </div>
        <!-- 如果是本人，显示编辑按钮 -->
        <router-link v-if="isSelf" to="/front/profile" class="edit-profile-btn">
          <el-button size="small" plain>编辑资料</el-button>
        </router-link>
      </div>
    </div>

    <!-- 在售商品 -->
    <div class="section">
      <div class="section-header">
        <h3 class="section-title">在售商品（{{ productCount }}）</h3>
        <router-link v-if="isSelf" to="/front/publish" class="text-sm text-primary hover:text-primary-dark">
          + 发布新商品
        </router-link>
      </div>
      <div v-if="products.length > 0" class="goods-grid">
        <div v-for="item in products" :key="item.id" class="product-wrapper">
          <GoodsCard :goods="item" />
          <!-- 自己看自己主页时，显示编辑/下架操作 -->
          <div v-if="isSelf" class="product-actions">
            <el-button size="small" text type="primary" @click="router.push('/front/editProduct?id=' + item.id)">
              <el-icon :size="14"><Edit /></el-icon> 编辑
            </el-button>
            <el-button size="small" text type="danger" @click="handleOffline(item)">
              下架
            </el-button>
          </div>
        </div>
      </div>
      <el-empty v-else description="暂无在售商品" :image-size="80">
        <router-link v-if="isSelf" to="/front/publish" class="text-primary text-sm hover:underline">
          去发布第一件商品
        </router-link>
      </el-empty>
    </div>

    <!-- 收到的评价 -->
    <div class="section">
      <h3 class="section-title">他人评价</h3>
      <div class="reviews-list">
        <div v-if="reviews.length > 0">
          <div v-for="r in reviews" :key="r.id" class="review-item">
            <el-avatar :size="32" :src="r.avatarUrl" />
            <div class="review-content">
              <div class="review-header">
                <span class="review-name">{{ r.nickname }}</span>
                <el-rate :model-value="r.rating" :max="5" disabled size="small" />
                <span class="review-time">{{ r.createTime?.substring(0, 10) }}</span>
              </div>
              <p class="review-text">{{ r.content || '好评' }}</p>
            </div>
          </div>
        </div>
        <div v-else class="empty-reviews">
          <el-icon :size="36" color="#ddd"><ChatLineSquare /></el-icon>
          <p class="text-text-muted text-sm mt-2">暂无评价</p>
        </div>
      </div>
    </div>
  </div>

  <!-- 加载失败 -->
  <div v-else-if="loadError" class="error-state">
    <el-icon :size="48" color="#999"><WarningFilled /></el-icon>
    <p class="text-text-secondary mt-3">用户不存在</p>
    <router-link to="/front/home" class="text-primary text-sm mt-2 hover:underline">返回首页</router-link>
  </div>

  <!-- 加载中 -->
  <div v-else class="loading-state">
    <el-icon :size="32" class="animate-spin text-primary"><Loading /></el-icon>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { useRouter } from 'vue-router'
import { getUserInfo } from '../../api/user'
import { getList, offline as offlineProduct } from '../../api/product'
import { getUserReviews } from '../../api/review'
import GoodsCard from '../../components/GoodsCard.vue'
import { ElMessage, ElMessageBox } from 'element-plus'

const route = useRoute()
const router = useRouter()

const user = ref(null)
const products = ref([])
const reviews = ref([]) // 预留评价数据
const productCount = ref(0)
const loadError = ref(false)

// 好评率计算
const goodRate = computed(() => {
  if (reviews.value.length === 0) return '--'
  const good = reviews.value.filter(r => r.rating >= 4).length
  return Math.round(good / reviews.value.length * 100) + '%'
})

// 判断是否为自己主页
const isSelf = computed(() => {
  const token = localStorage.getItem('token')
  if (!token || !user.value) return false
  try {
    const payload = JSON.parse(atob(token.split('.')[1]))
    return Number(payload.sub) === user.value.id
  } catch { return false }
})

// 加载用户信息
const loadUser = async () => {
  const uid = route.query.id
  if (!uid) { loadError.value = true; return }
  try {
    const res = await getUserInfo(uid)
    user.value = res.data
  } catch { loadError.value = true }
}

// 加载评价
const loadReviews = async (uid) => {
  if (!uid) return
  try { reviews.value = (await getUserReviews(uid)).data || [] } catch { reviews.value = [] }
}

// 下架商品
const handleOffline = async (item) => {
  try {
    await ElMessageBox.confirm(`确认下架「${item.title}」？`, '确认下架', {
      confirmButtonText: '确认下架', cancelButtonText: '取消', type: 'warning',
    })
  } catch { return }
  try {
    await offlineProduct(item.id)
    ElMessage.success('已下架')
    loadProducts()
  } catch {}
}

// 加载该用户的在售商品
const loadProducts = async () => {
  const uid = route.query.id
  if (!uid) return
  try {
    const res = await getList({ userId: uid, page: 1, size: 8, status: 1 })
    products.value = res.data?.records || []
    productCount.value = res.data?.total || products.value.length
  } catch { products.value = [] }
}

onMounted(async () => {
  await loadUser()
  await loadProducts()
  loadReviews(route.query.id)
})
</script>

<style scoped>
.user-profile-page {
  max-width: 1000px;
  margin: 0 auto;
  padding: 0 20px;
}

/* 用户信息卡片 */
.user-card {
  background: #fff;
  border-radius: 8px;
  padding: 28px 24px;
  margin-bottom: 24px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.04);
}

.user-card-inner {
  display: flex;
  align-items: flex-start;
  gap: 20px;
}

.user-avatar {
  flex-shrink: 0;
  border: 2px solid #f0f0f0;
}

.user-info {
  flex: 1;
  min-width: 0;
}

.user-name-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}

.user-name {
  font-size: 20px;
  font-weight: 700;
  color: #333;
}

.user-badge {
  font-size: 11px;
  background: #FF7D00;
  color: #fff;
  padding: 1px 8px;
  border-radius: 3px;
}

.user-school {
  font-size: 13px;
  color: #666;
  display: flex;
  align-items: center;
  gap: 4px;
  margin-bottom: 6px;
}

.user-bio {
  font-size: 14px;
  color: #666;
  line-height: 1.6;
  margin-bottom: 8px;
}

.user-stats {
  display: flex;
  gap: 20px;
}

.stat-item {
  font-size: 13px;
  color: #999;
}

.stat-item strong {
  color: #FF7D00;
  font-size: 15px;
}

.edit-profile-btn {
  flex-shrink: 0;
}

/* 分区 */
.section {
  margin-bottom: 24px;
}

.section-title {
  font-size: 16px;
  font-weight: 600;
  color: #333;
  margin-bottom: 14px;
}

.section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 14px;
}

/* 商品网格 */
.product-wrapper {
  position: relative;
}

.product-actions {
  display: flex;
  gap: 4px;
  margin-top: 6px;
  justify-content: center;
}

.goods-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
}

@media (max-width: 1024px) { .goods-grid { grid-template-columns: repeat(2, 1fr); } }
@media (max-width: 640px) { .goods-grid { grid-template-columns: 1fr; } }

/* 评价列表 */
.reviews-list {
  background: #fff;
  border-radius: 8px;
  padding: 20px;
}

.review-item {
  display: flex;
  gap: 12px;
  padding: 12px 0;
  border-bottom: 1px solid #f5f5f5;
}

.review-item:last-child { border-bottom: none; }

.review-header {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 4px;
}

.review-name {
  font-size: 14px;
  font-weight: 500;
  color: #333;
}

.review-time {
  font-size: 12px;
  color: #999;
}

.review-text {
  font-size: 14px;
  color: #666;
  line-height: 1.5;
}

.empty-reviews {
  text-align: center;
  padding: 24px 0;
}

/* 加载/错误 */
.loading-state, .error-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 80px 0;
}

.animate-spin { animation: spin 1s linear infinite; }
@keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
</style>
