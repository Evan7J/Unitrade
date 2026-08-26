<template>
  <!--
    商品详情页 - 闲鱼风格布局
    两栏布局：左侧图片区 + 右侧商品信息区
    底部固定操作栏：聊一聊 + 立即购买
  -->
  <div class="detail-page" v-if="detail">
    <!-- 面包屑导航 -->
    <div class="breadcrumb">
      <router-link to="/front/home" class="text-text-muted hover:text-primary">首页</router-link>
      <span class="text-text-muted mx-1">/</span>
      <router-link to="/front/search" class="text-text-muted hover:text-primary">全部商品</router-link>
      <span class="text-text-muted mx-1">/</span>
      <span class="text-text-secondary">{{ detail.categoryName || '商品详情' }}</span>
    </div>

    <!-- 主体内容：两栏布局 -->
    <div class="detail-main">
      <!-- ===== 左侧：图片展示区 ===== -->
      <div class="detail-gallery">
        <!-- 主图 -->
        <div class="main-image-wrapper">
          <img
            :src="mainImage || '/vite.svg'"
            :alt="detail.title"
            class="main-image"
            @error="e => e.target.src = '/vite.svg'"
          />
          <!-- 图片计数角标 -->
          <span v-if="detail.images && detail.images.length > 1" class="image-count">
            {{ currentImgIndex + 1 }} / {{ detail.images.length }}
          </span>
        </div>

        <!-- 缩略图列表 -->
        <div class="thumb-list" v-if="detail.images && detail.images.length > 1">
          <div
            v-for="(img, i) in detail.images"
            :key="i"
            class="thumb-item"
            :class="{ active: mainImage === img }"
            @click="mainImage = img; currentImgIndex = i"
          >
            <img :src="img" :alt="`图片${i + 1}`" @error="e => e.target.src = '/vite.svg'" />
          </div>
        </div>
      </div>

      <!-- ===== 右侧：商品信息区 ===== -->
      <div class="detail-info">
        <!-- 价格区 -->
        <div class="price-section">
          <span class="price-symbol">¥</span>
          <span class="price-value">{{ detail.price }}</span>
          <span v-if="detail.originalPrice && detail.originalPrice > detail.price" class="price-original">
            ¥{{ detail.originalPrice }}
          </span>
          <span v-if="detail.status !== 1" class="sold-tag">已售出</span>
        </div>

        <!-- 商品标题 -->
        <h1 class="product-title">{{ detail.title }}</h1>

        <!-- 商品元信息行 -->
        <div class="meta-row">
          <span class="meta-item">
            <el-icon :size="14"><Clock /></el-icon>
            {{ formatPublishTime(detail.createTime) }}
          </span>
          <span class="meta-item">
            <el-icon :size="14"><View /></el-icon>
            {{ detail.viewCount || 0 }} 次浏览
          </span>
          <span class="meta-item">
            <el-icon :size="14"><Box /></el-icon>
            {{ conditionText(detail.productCondition) }}
          </span>
          <span class="meta-item">
            <el-icon :size="14"><Folder /></el-icon>
            {{ detail.categoryName || '未分类' }}
          </span>
        </div>

        <!-- 分隔线 -->
        <el-divider class="!my-3" />

        <!-- 卖家信息卡片：点击跳转卖家主页 -->
        <div class="seller-card">
          <router-link :to="'/front/user?id=' + detail.userId" class="seller-info">
            <el-avatar :size="44" :src="detail.avatarUrl">
              <el-icon :size="24"><User /></el-icon>
            </el-avatar>
            <div class="seller-detail">
              <p class="seller-name">{{ detail.nickname || '匿名用户' }}</p>
              <p class="seller-extra" v-if="sellerSchool">{{ sellerSchool }}</p>
            </div>
          </router-link>
          <el-button v-if="!isSelfProduct" type="warning" plain size="small" @click="showChatPanel = true" class="chat-btn">
            <el-icon :size="14"><ChatDotRound /></el-icon>
            聊一聊
          </el-button>
        </div>

        <!-- 分隔线 -->
        <el-divider class="!my-3" />

        <!-- 交易方式 -->
        <div class="shipping-section">
          <h3 class="section-title">交易方式</h3>
          <div class="shipping-info">
            <el-icon :size="18" color="#FF7D00"><component :is="shippingIcon" /></el-icon>
            <span class="shipping-text">{{ shippingText }}</span>
            <span v-if="detail.shippingType === 2 && detail.shippingFee" class="shipping-fee">
              邮费：¥{{ detail.shippingFee }}
            </span>
          </div>
        </div>

        <el-divider class="!my-3" />

        <!-- 商品描述 -->
        <div class="desc-section">
          <h3 class="section-title">商品描述</h3>
          <p class="desc-content">{{ detail.description || '卖家很懒，没有留下描述～' }}</p>
        </div>

        <!-- 交易提示 -->
        <div class="trade-tips">
          <el-icon :size="14"><InfoFilled /></el-icon>
          <span>建议校内公共场所面交，当面验货更放心。贵重物品请保留交易凭证。</span>
        </div>
      </div>
    </div>

    <!-- ===== 底部固定操作栏（闲鱼风格） ===== -->
    <div class="bottom-bar">
      <div class="bottom-bar-inner">
        <!-- 左侧：收藏 + 其他操作 -->
        <div class="bottom-left">
          <div class="action-btn" @click="toggleFavorite">
            <el-icon :size="20" :color="detail.isFavorite ? '#FF7D00' : '#999'">
              <StarFilled v-if="detail.isFavorite" />
              <Star v-else />
            </el-icon>
            <span :class="detail.isFavorite ? 'text-primary' : 'text-text-muted'">
              {{ detail.isFavorite ? '已收藏' : '收藏' }}
            </span>
          </div>
        </div>

        <!-- 右侧：主要操作按钮 -->
        <div class="bottom-right">
          <el-button
            v-if="!isSelfProduct"
            size="large"
            class="chat-action-btn"
            @click="showChatPanel = true"
            :disabled="detail.status !== 1"
          >
            <el-icon :size="18"><ChatDotRound /></el-icon>
            聊一聊
          </el-button>
          <el-button
            type="warning"
            size="large"
            class="buy-action-btn"
            @click="handleBuy"
            :loading="buyLoading"
            :disabled="detail.status !== 1 || isSelfProduct"
          >
            {{ detail.status !== 1 ? '已售出' : '立即购买' }}
          </el-button>
        </div>
      </div>
    </div>

    <!-- ===== 聊天面板（右侧滑入） ===== -->
    <ChatPanel
      :visible="showChatPanel"
      :target-user-id="detail.userId"
      :target-nickname="detail.nickname"
      :target-avatar="detail.avatarUrl"
      :product-id="detail.id"
      :product-title="detail.title"
      :product-cover="detail.images?.[0] || ''"
      :product-price="detail.price"
      :product-status="detail.status"
      @close="showChatPanel = false"
    />
  </div>

  <!-- 加载失败 -->
  <div v-else-if="loadError" class="error-state">
    <el-icon :size="48" color="#999"><WarningFilled /></el-icon>
    <p class="text-text-secondary mt-3">商品不存在或已下架</p>
    <router-link to="/front/home" class="text-primary text-sm mt-2 hover:underline">返回首页</router-link>
  </div>

  <!-- 加载中 -->
  <div v-else class="loading-state">
    <el-icon :size="32" class="animate-spin text-primary"><Loading /></el-icon>
    <p class="text-text-muted text-sm mt-3">加载中...</p>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getDetail, getList } from '../../api/product'
import { create } from '../../api/order'
import { add, remove } from '../../api/favorite'
import { getList as getAddressList } from '../../api/address'
import { getUserInfo } from '../../api/user'
import { ElMessage, ElMessageBox } from 'element-plus'
import ChatPanel from '../../components/ChatPanel.vue'

const route = useRoute()
const router = useRouter()

// 商品详情数据
const detail = ref(null)
const mainImage = ref('')
const currentImgIndex = ref(0)
const loadError = ref(false)

// 购买加载状态
const buyLoading = ref(false)

// 聊天面板显示状态
const showChatPanel = ref(false)

// 判断是否为卖家本人（自己不能跟自己聊天）
const isSelfProduct = computed(() => {
  const token = localStorage.getItem('token')
  if (!token || !detail.value) return false
  try {
    const payload = JSON.parse(atob(token.split('.')[1]))
    return Number(payload.sub) === detail.value.userId
  } catch { return false }
})

// 卖家学校（仅当卖家主页设置了学校时才显示）
const sellerSchool = ref('')

const loadSellerInfo = async (userId) => {
  if (!userId) return
  try {
    const res = await getUserInfo(userId)
    sellerSchool.value = res.data?.school || ''
  } catch { sellerSchool.value = '' }
}

// 成色文本映射
const conditionText = (c) => ({ 1: '全新', 2: '几乎全新', 3: '轻微使用痕迹', 4: '明显使用痕迹' }[c] || '未知')

// 发布时间格式化：年月日
const formatPublishTime = (t) => {
  if (!t) return '-'
  const d = new Date(t)
  return `${d.getFullYear()}/${d.getMonth() + 1}/${d.getDate()}`
}

// 物流方式
const shippingText = computed(() => {
  const t = detail.value?.shippingType
  return { 1: '无需邮寄 — 校内面交自提', 2: '付邮邮寄', 3: '卖家包邮' }[t] || '校内面交'
})
const shippingIcon = computed(() => {
  const t = detail.value?.shippingType
  return { 1: 'Place', 2: 'Van', 3: 'Present' }[t] || 'Place'
})

// 加载商品详情
const fetchDetail = async () => {
  try {
    const id = route.query.id
    if (!id) { loadError.value = true; return }
    const res = await getDetail(id)
    detail.value = res.data
    mainImage.value = res.data.images?.[0] || ''
    // 加载卖家学校信息（设置了才显示）
    loadSellerInfo(res.data.userId)
  } catch {
    loadError.value = true
  }
}

// 立即购买：校验地址 → 二次确认 → 创建订单
const handleBuy = async () => {
  if (!detail.value || detail.value.status !== 1) return

  // 需要邮寄时校验收货地址
  const shippingType = detail.value.shippingType || 1
  if (shippingType !== 1) {
    try {
      const addrRes = await getAddressList()
      const addresses = addrRes.data || []
      if (addresses.length === 0) {
        await ElMessageBox.confirm(
          '该商品需要邮寄，您还没有收货地址，请先添加地址。',
          '需要收货地址',
          { confirmButtonText: '去添加地址', cancelButtonText: '取消', type: 'warning' }
        )
        router.push('/front/address')
        return
      }
    } catch { return }
  }

  const shippingNote = shippingType === 1 ? '（校内面交）' : shippingType === 3 ? '（卖家包邮）' : ''
  const shippingFee = (shippingType === 2 && detail.value.shippingFee) ? parseFloat(detail.value.shippingFee) : 0
  const totalPrice = (parseFloat(detail.value.price) + shippingFee).toFixed(2)
  const feeLine = shippingFee > 0 ? `\n邮费：+¥${shippingFee.toFixed(2)}` : ''
  const totalLine = shippingFee > 0 ? `\n合计：¥${totalPrice}` : ''

  try {
    await ElMessageBox.confirm(
      `确认购买「${detail.value.title}」？\n\n价格：¥${detail.value.price}${feeLine}${totalLine}${shippingNote}\n\n下单后请在15分钟内完成付款，超时自动取消。`,
      '确认下单',
      { confirmButtonText: '确认下单', cancelButtonText: '再想想', type: 'warning' }
    )
  } catch {
    return
  }

  buyLoading.value = true
  try {
    await create({ productId: detail.value.id })
    ElMessage.success('下单成功！请在订单页面完成付款')
    router.push('/front/orders')
  } catch {}
  buyLoading.value = false
}

// 收藏 / 取消收藏
const toggleFavorite = async () => {
  if (!detail.value) return
  try {
    if (detail.value.isFavorite) {
      await remove(detail.value.id)
      detail.value.isFavorite = false
      ElMessage.success('已取消收藏')
    } else {
      await add(detail.value.id)
      detail.value.isFavorite = true
      ElMessage.success('收藏成功')
    }
  } catch { /* 错误已由拦截器处理 */ }
}

onMounted(fetchDetail)
</script>

<style scoped>
/* ===== 整体布局 ===== */
.detail-page {
  max-width: 1200px;
  margin: 0 auto;
  padding-bottom: 100px; /* 为底部固定栏留足空间，避免遮挡内容 */
}

/* 面包屑 */
.breadcrumb {
  padding: 12px 0;
  font-size: 13px;
}

/* 两栏主体 */
.detail-main {
  display: flex;
  gap: 28px;
}

/* ===== 左侧：图片展示区 ===== */
.detail-gallery {
  width: 480px;
  flex-shrink: 0;
}

.main-image-wrapper {
  position: relative;
  width: 100%;
  aspect-ratio: 1 / 1;
  background: #f0f0f0;
  border-radius: 8px;
  overflow: hidden;
}

.main-image {
  width: 100%;
  height: 100%;
  object-fit: contain;
  cursor: zoom-in;
}

.image-count {
  position: absolute;
  bottom: 10px;
  right: 12px;
  background: rgba(0, 0, 0, 0.5);
  color: #fff;
  font-size: 12px;
  padding: 2px 8px;
  border-radius: 10px;
}

/* 缩略图 */
.thumb-list {
  display: flex;
  gap: 8px;
  margin-top: 10px;
  overflow-x: auto;
}

.thumb-item {
  width: 68px;
  height: 68px;
  border-radius: 6px;
  overflow: hidden;
  border: 2px solid transparent;
  cursor: pointer;
  flex-shrink: 0;
  transition: border-color 0.2s;
}

.thumb-item.active {
  border-color: #FF7D00;
}

.thumb-item img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

/* ===== 右侧：商品信息区 ===== */
.detail-info {
  flex: 1;
  min-width: 0;
}

/* 价格区 */
.price-section {
  display: flex;
  align-items: baseline;
  gap: 4px;
  margin-bottom: 10px;
}

.price-symbol {
  font-size: 16px;
  font-weight: 700;
  color: #FF7D00;
}

.price-value {
  font-size: 32px;
  font-weight: 700;
  color: #FF7D00;
  line-height: 1;
}

.price-original {
  font-size: 14px;
  color: #999;
  text-decoration: line-through;
  margin-left: 8px;
}

.sold-tag {
  font-size: 12px;
  background: #999;
  color: #fff;
  padding: 1px 8px;
  border-radius: 4px;
  margin-left: 8px;
}

/* 商品标题 */
.product-title {
  font-size: 18px;
  font-weight: 600;
  color: #333;
  line-height: 1.5;
  margin-bottom: 12px;
  word-break: break-word;
}

/* 元信息行 */
.meta-row {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
}

.meta-item {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  color: #999;
}

/* 卖家信息卡片 */
.seller-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4px 0;
}

.seller-info {
  display: flex;
  align-items: center;
  gap: 12px;
  flex: 1;
  cursor: pointer;
  border-radius: 8px;
  padding: 4px;
  transition: background 0.2s;
}
.seller-info:hover {
  background: #FFF8F0;
}

.seller-detail {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.seller-name {
  font-size: 15px;
  font-weight: 600;
  color: #333;
  display: flex;
  align-items: center;
  gap: 6px;
}

.seller-extra {
  font-size: 12px;
  color: #999;
  margin-top: 1px;
}

.chat-btn {
  flex-shrink: 0;
}

/* 交易方式 */
.shipping-section { margin: 4px 0; }
.shipping-info {
  display: flex; align-items: center; gap: 8px;
  padding: 10px 14px; background: #FFF8F0;
  border-radius: 8px; font-size: 14px; color: #666;
}
.shipping-text { flex: 1; }
.shipping-fee {
  font-weight: 600; color: #FF7D00; font-size: 13px;
}

/* 描述区 */
.section-title {
  font-size: 15px;
  font-weight: 600;
  color: #333;
  margin-bottom: 8px;
}

.desc-content {
  font-size: 14px;
  color: #666;
  line-height: 1.8;
  white-space: pre-wrap;
  word-break: break-word;
}

/* 交易提示 */
.trade-tips {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  margin-top: 16px;
  padding: 10px 12px;
  background: #FFF8F0;
  border-radius: 6px;
  font-size: 12px;
  color: #B07A3D;
  line-height: 1.6;
}

/* ===== 底部固定操作栏 ===== */
.bottom-bar {
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  background: #fff;
  border-top: 1px solid #eee;
  box-shadow: 0 -2px 8px rgba(0, 0, 0, 0.06);
  z-index: 50;
  padding: 12px 0;
  height: 72px;
  box-sizing: border-box;
}

.bottom-bar-inner {
  max-width: 1200px;
  margin: 0 auto;
  padding: 0 20px;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.bottom-left {
  display: flex;
  gap: 8px;
}

.action-btn {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  cursor: pointer;
  padding: 4px 12px;
  border-radius: 8px;
  transition: background 0.2s;
  font-size: 11px;
}
.action-btn:hover {
  background: #F5F7FA;
}

.bottom-right {
  display: flex;
  gap: 12px;
}

.chat-action-btn {
  min-width: 120px;
  border-color: #FF7D00 !important;
  color: #FF7D00 !important;
}
.chat-action-btn:hover {
  background: #FFF8F0 !important;
}

.buy-action-btn {
  min-width: 160px;
  background: #FF7D00 !important;
  border-color: #FF7D00 !important;
  font-weight: 600;
  font-size: 16px;
}
.buy-action-btn:hover {
  background: #E56E00 !important;
}

/* 加载动画 */
.animate-spin {
  animation: spin 1s linear infinite;
}
@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

/* 加载/错误状态 */
.loading-state,
.error-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 80px 0;
}

/* ===== 响应式：平板和移动端 ===== */
@media (max-width: 900px) {
  .detail-main {
    flex-direction: column;
    gap: 20px;
  }

  .detail-gallery {
    width: 100%;
  }

  .main-image-wrapper {
    border-radius: 0;
    margin: 0 -20px;
    width: calc(100% + 40px);
  }

  .thumb-list {
    padding: 0 20px;
  }

  .detail-info {
    padding: 0;
  }

  .price-value {
    font-size: 26px;
  }

  .bottom-bar-inner {
    padding: 0 12px;
  }

  .chat-action-btn {
    min-width: 100px;
  }

  .buy-action-btn {
    min-width: 130px;
    font-size: 15px;
  }
}
</style>
