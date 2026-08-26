<template>
  <!--
    订单页 - 闲鱼风格
    顶部Tab切换：我买到的 / 我卖出的
    订单卡片展示：商品信息、价格、交易方、订单状态、操作按钮
  -->
  <div class="orders-page">
    <h2 class="page-title">我的订单</h2>

    <el-tabs v-model="tab" @tab-change="fetchAndCheck" class="order-tabs">
      <el-tab-pane label="我买到的" name="buy" />
      <el-tab-pane label="我卖出的" name="sell" />
    </el-tabs>

    <!-- 订单列表 -->
    <div v-if="orders.length > 0" class="order-list">
      <div v-for="order in orders" :key="order.id" class="order-card">
        <!-- 订单头部：状态标签 + 倒计时 -->
        <div class="order-header">
          <span class="order-time">{{ order.createTime?.substring(0, 16) || '' }}</span>
          <div class="flex items-center gap-2">
            <!-- 未付款超时倒计时 -->
            <span v-if="order.status === 1 && countdownText(order.createTime)" class="countdown-timer">
              <el-icon :size="14"><Clock /></el-icon>
              {{ countdownText(order.createTime) }}
            </span>
            <el-tag :type="statusTagType(order.status)" size="small">{{ order.statusText }}</el-tag>
          </div>
        </div>

        <!-- 订单主体 -->
        <div class="order-body">
          <!-- 商品图 -->
          <div class="order-product-image">
            <img :src="order.productCover || '/vite.svg'" :alt="order.productTitle" @error="e => e.target.src = '/vite.svg'" />
          </div>
          <!-- 商品信息 -->
          <div class="order-product-info">
            <p class="order-product-title">{{ order.productTitle || '商品已删除' }}</p>
            <p class="order-product-price">¥{{ order.productPrice }}</p>
          </div>
          <!-- 交易方信息 -->
          <div class="order-counterparty">
            <p class="counterparty-label">{{ tab === 'buy' ? '卖家' : '买家' }}</p>
            <router-link
              :to="'/front/user?id=' + (tab === 'buy' ? order.sellerId : order.buyerId)"
              class="counterparty-name hover:text-primary transition-colors"
            >
              {{ tab === 'buy' ? (order.sellerNickname || '-') : (order.buyerNickname || '-') }}
            </router-link>
          </div>
        </div>

        <!-- 操作按钮区 -->
        <div class="order-actions">
          <!-- 我买到的 -->
          <template v-if="tab === 'buy'">
            <el-button v-if="order.status === 1" type="warning" size="small" @click="doPay(order.id)">去付款</el-button>
            <el-button v-if="order.status === 3" type="success" size="small" @click="doConfirm(order.id)">确认收货</el-button>
            <el-button v-if="order.status === 4 && !order.reviewed" size="small" type="warning" @click="openReview(order)">评价</el-button>
            <el-tag v-if="order.status === 4 && order.reviewed" size="small" type="info" style="cursor:pointer" @click="viewReview(order)">已评价</el-tag>
            <el-button v-if="order.status === 1" size="small" plain @click="doCancel(order.id)">取消订单</el-button>
            <el-button v-if="order.status === 2 || order.status === 3" size="small" plain @click="doRefund(order.id)">申请退款</el-button>
          </template>

          <!-- 我卖出的 -->
          <template v-if="tab === 'sell'">
            <el-button v-if="order.status === 2" type="primary" size="small" @click="doShip(order.id)">去发货</el-button>
            <el-button v-if="order.status === 4 && !order.reviewed" size="small" type="warning" @click="openReview(order)">评价</el-button>
            <el-tag v-if="order.status === 4 && order.reviewed" size="small" type="info" style="cursor:pointer" @click="viewReview(order)">已评价</el-tag>
            <el-button v-if="order.status === 6" type="success" size="small" @click="doAgreeRefund(order.id)">同意退款</el-button>
            <el-button v-if="order.status === 6" type="danger" size="small" plain @click="doRejectRefund(order.id)">拒绝退款</el-button>
          </template>

          <!-- 联系对方 -->
          <el-button
            v-if="order.status !== 4 && order.status !== 5 && order.status !== 7"
            size="small"
            plain
            @click="goChat(order)"
          >
            <el-icon :size="14"><ChatDotRound /></el-icon>
            联系{{ tab === 'buy' ? '卖家' : '买家' }}
          </el-button>
        </div>
      </div>
    </div>

    <!-- 空状态 -->
    <el-empty v-else description="暂无订单">
      <router-link to="/front/home" class="text-primary text-sm hover:underline">去逛逛</router-link>
    </el-empty>

    <!-- 评价弹窗 -->
    <el-dialog v-model="reviewVisible" :title="isViewMode ? '评价详情' : '评价'" width="420px">
      <div class="review-dialog">
        <p class="review-target">评价对象：{{ reviewTarget }}</p>
        <div class="review-stars">
          <span class="star-label">评分：</span>
          <el-rate v-model="reviewForm.rating" :max="5" :disabled="isViewMode" show-text :texts="['很差','较差','一般','满意','非常满意']" />
        </div>
        <el-input v-model="reviewForm.content" type="textarea" :rows="3" :disabled="isViewMode" :placeholder="isViewMode ? '' : '说说你的交易体验吧（选填）'" maxlength="200" show-word-limit />
      </div>
      <template #footer>
        <el-button @click="reviewVisible = false">{{ isViewMode ? '关闭' : '取消' }}</el-button>
        <el-button v-if="!isViewMode" type="primary" @click="doReview">提交评价</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { myBuy, mySell, pay, ship, confirm, cancel, refund, agreeRefund, rejectRefund } from '../../api/order'
import { save as saveReview, checkReviewed } from '../../api/review'
import { ElMessage, ElMessageBox } from 'element-plus'

const router = useRouter()
const tab = ref('buy')
const orders = ref([])
const now = ref(Date.now())
let timer = null

// 15分钟倒计时文案
const countdownText = (createTime) => {
  if (!createTime) return ''
  const deadline = new Date(createTime).getTime() + 15 * 60 * 1000
  const remain = deadline - now.value
  if (remain <= 0) return '已超时'
  const m = Math.floor(remain / 60000)
  const s = Math.floor((remain % 60000) / 1000)
  return `剩余 ${m}:${String(s).padStart(2, '0')} 自动取消`
}

// 订单状态 → Tag 类型映射
const statusTagType = (status) => {
  const map = { 1: 'warning', 2: 'primary', 3: '', 4: 'success', 5: 'info', 6: 'danger', 7: 'info' }
  return map[status] || 'info'
}

// 加载订单列表
const fetchOrders = async () => {
  try {
    const res = tab.value === 'buy' ? await myBuy() : await mySell()
    orders.value = res.data || []
  } catch { orders.value = [] }
}

// 付款
const doPay = async (id) => {
  try { await pay(id); ElMessage.success('付款成功'); fetchAndCheck() } catch {}
}

// 发货
const doShip = async (id) => {
  try { await ship(id); ElMessage.success('已标记发货'); fetchAndCheck() } catch {}
}

// 确认收货
const doConfirm = async (id) => {
  try {
    await ElMessageBox.confirm('确认已收到商品？确认后钱款将打给卖家。', '确认收货', {
      confirmButtonText: '确认收货',
      cancelButtonText: '再等等',
      type: 'success',
    })
    await confirm(id)
    ElMessage.success('确认收货成功')
    fetchAndCheck()
  } catch {}
}

// 取消订单
const doCancel = async (id) => {
  try {
    const { value } = await ElMessageBox.prompt('请输入取消原因', '取消订单', {
      confirmButtonText: '确认取消',
      cancelButtonText: '再想想',
    })
    await cancel(id, value || '买家取消')
    ElMessage.success('订单已取消')
    fetchAndCheck()
  } catch {}
}

// 申请退款
const doRefund = async (id) => {
  try {
    const { value } = await ElMessageBox.prompt('请说明退款原因', '申请退款', {
      confirmButtonText: '提交申请',
      cancelButtonText: '再想想',
    })
    await refund(id, value || '买家申请退款')
    ElMessage.success('退款申请已提交')
    fetchAndCheck()
  } catch {}
}

// 同意/拒绝退款
const doAgreeRefund = async (id) => {
  try { await agreeRefund(id); ElMessage.success('已同意退款'); fetchAndCheck() } catch {}
}
const doRejectRefund = async (id) => {
  try { await rejectRefund(id); ElMessage.success('已拒绝退款'); fetchAndCheck() } catch {}
}

// 跳转聊天
// 评价
const reviewVisible = ref(false)
const reviewTarget = ref('')
const isViewMode = ref(false)
const reviewForm = ref({ rating: 0, content: '' })
let currentReviewOrder = null

const openReview = (order) => {
  isViewMode.value = false
  currentReviewOrder = order
  reviewTarget.value = order.productTitle || '商品'
  reviewForm.value = {
    orderId: order.id,
    revieweeId: tab.value === 'buy' ? order.sellerId : order.buyerId,
    productId: order.productId,
    rating: 0,
    content: '',
  }
  reviewVisible.value = true
}

const doReview = async () => {
  if (!reviewForm.value.rating) { ElMessage.warning('请先评星'); return }
  try {
    await saveReview(reviewForm.value)
    ElMessage.success('评价成功')
    reviewVisible.value = false
    if (currentReviewOrder) {
      currentReviewOrder.reviewed = true
      currentReviewOrder.reviewData = { rating: reviewForm.value.rating, content: reviewForm.value.content }
    }
  } catch {}
}

// 查看已评价详情
const viewReview = (order) => {
  isViewMode.value = true
  currentReviewOrder = null
  reviewTarget.value = order.productTitle || '商品'
  reviewForm.value = {
    rating: order.reviewData?.rating || 5,
    content: order.reviewData?.content || '好评',
  }
  reviewVisible.value = true
}

// 加载评价状态
const loadReviewStatus = async () => {
  for (const o of orders.value) {
    if (o.status === 4) {
      try {
        const res = await checkReviewed(o.id)
        const d = res.data || {}
        o.reviewed = d.reviewed || false
        if (o.reviewed) o.reviewData = { rating: d.rating, content: d.content }
      } catch { o.reviewed = false }
    }
  }
}

// 在原有 fetchOrders 后追加评价状态加载
const fetchAndCheck = async () => { await fetchOrders(); await loadReviewStatus() }

const goChat = (order) => {
  const userId = tab.value === 'buy' ? order.sellerId : order.buyerId
  const nickname = tab.value === 'buy' ? order.sellerNickname : order.buyerNickname
  router.push(`/front/chat?userId=${userId}&nickname=${encodeURIComponent(nickname || '')}&productId=${order.productId}`)
}

onMounted(() => {
  fetchAndCheck()
  timer = setInterval(() => { now.value = Date.now() }, 1000)
})
onUnmounted(() => clearInterval(timer))
</script>

<style scoped>
.orders-page {
  max-width: 900px;
  margin: 0 auto;
}

.page-title {
  font-size: 20px;
  font-weight: 700;
  color: #333;
  margin-bottom: 16px;
}

.order-tabs {
  margin-bottom: 16px;
}

/* 订单卡片 */
.order-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.order-card {
  background: #fff;
  border-radius: 8px;
  padding: 16px 20px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.04);
}

.order-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.order-time {
  font-size: 12px;
  color: #999;
}

/* 订单主体：商品图 + 信息 + 交易方 */
.order-body {
  display: flex;
  align-items: center;
  gap: 14px;
}

.order-product-image {
  width: 72px;
  height: 72px;
  border-radius: 6px;
  overflow: hidden;
  background: #f5f5f5;
  flex-shrink: 0;
}

.order-product-image img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.order-product-info {
  flex: 1;
  min-width: 0;
}

.order-product-title {
  font-size: 14px;
  color: #333;
  font-weight: 500;
  margin-bottom: 6px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.order-product-price {
  font-size: 18px;
  font-weight: 700;
  color: #FF7D00;
}

.order-counterparty {
  text-align: right;
  flex-shrink: 0;
}

.counterparty-label {
  font-size: 11px;
  color: #999;
  margin-bottom: 2px;
}

.counterparty-name {
  font-size: 13px;
  color: #666;
}

/* 操作按钮区 */
.order-actions {
  display: flex;
  gap: 8px;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid #f5f5f5;
  justify-content: flex-end;
  flex-wrap: wrap;
}

/* 倒计时 */
.countdown-timer {
  font-size: 12px; color: #f56c6c;
  display: flex; align-items: center; gap: 3px;
}

.review-dialog { display: flex; flex-direction: column; gap: 16px; }
.review-target { font-size: 14px; color: #666; }
.review-stars { display: flex; align-items: center; gap: 8px; }
.star-label { font-size: 14px; color: #333; }
</style>
