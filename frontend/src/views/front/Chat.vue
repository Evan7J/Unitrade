<template>
  <!--
    Chat - 私聊页面主组件（组件化架构）
    左侧：SideChatList 会话侧边栏
    右侧：聊天主面板（头部 + 商品卡片 + 消息列表 + 输入区）
    使用 useChatWebSocket 管理 WebSocket 连接
  -->
  <div class="chat-page">
    <div class="chat-container">
      <!-- ===== 左侧：会话侧边栏 ===== -->
      <SideChatList
        :conversations="conversations"
        :active-user-id="targetUserId"
        @select="openChat"
        class="chat-side"
      />

      <!-- ===== 右侧：聊天主面板 ===== -->
      <div class="chat-main" v-if="targetUserId">
        <!-- 头部导航 -->
        <div class="chat-header">
          <el-button text class="back-btn" @click="closeChat">
            <el-icon :size="18"><ArrowLeft /></el-icon>
          </el-button>
          <el-avatar :size="36" :src="targetAvatar">
            <el-icon :size="18"><User /></el-icon>
          </el-avatar>
          <div class="header-info">
            <span class="header-name">{{ targetNickname }}</span>
          </div>
        </div>

        <!-- 关联商品卡片 -->
        <router-link
          v-if="currentProductId"
          :to="'/front/goodsDetail?id=' + currentProductId"
          class="product-card-link"
        >
          <div class="product-img-box">
            <img :src="currentProductCover || '/vite.svg'" @error="e => e.target.src = '/vite.svg'" />
          </div>
          <div class="product-info-box">
            <p class="product-name">{{ currentProductTitle }}</p>
            <p class="product-meta">
              <span class="product-price">¥{{ currentProductPrice || '--' }}</span>
              <span class="product-status" :class="currentProductStatus === 1 ? 'st-on' : 'st-off'">
                {{ currentProductStatus === 1 ? '在售' : '已售出' }}
              </span>
            </p>
          </div>
          <el-icon :size="14" color="#ccc"><ArrowRight /></el-icon>
        </router-link>

        <!-- 消息列表滚动容器 -->
        <div class="msg-scroll" ref="msgScroll" @scroll="onScroll">
          <div v-if="loadingHistory" class="loading-hint">加载中...</div>
          <MsgBubble
            v-for="msg in messages"
            :key="msg.msgId"
            :msg="msg"
            :avatar="msg.isSelf ? myAvatar : targetAvatar"
          />
          <div v-if="messages.length === 0 && !loadingHistory" class="empty-hint">
            暂无消息，发送第一条消息打个招呼吧～
          </div>
        </div>

        <!-- 底部输入区 -->
        <MsgInput @send="handleSendMsg" />
      </div>

      <!-- 未选择会话时的空状态 -->
      <div class="chat-empty" v-else>
        <el-icon :size="56" color="#ddd"><ChatDotRound /></el-icon>
        <p>选择一个会话开始聊天</p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, nextTick, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import SideChatList from '../../components/chat/SideChatList.vue'
import MsgBubble from '../../components/chat/MsgBubble.vue'
import MsgInput from '../../components/chat/MsgInput.vue'
import { useChatWebSocket } from '../../composables/useChatWebSocket'
import { useUserStore } from '../../stores/user'
import { getConversations, getHistory, markRead } from '../../api/chat'
import { getProfile } from '../../api/user'
import { getDetail } from '../../api/product'

const route = useRoute()
const userStore = useUserStore()

/*
 * loginUserId：全局唯一用户ID（Pinia 存储，刷新不丢失）
 * 聊天模块中 isSelf 的唯一判断依据：
 *   isSelf = (消息的 senderUserId === loginUserId)
 * 禁止用 senderType、昵称、会话角色等判断左右位置
 */
const loginUserId = computed(() => userStore.loginUserId)

// ===== 会话数据 =====
const conversations = ref([])
const targetUserId = ref(null)
const targetNickname = ref('')
const targetAvatar = ref('')

// ===== 当前用户头像 =====
const myAvatar = ref('')

// ===== 商品信息 =====
const currentProductId = ref('')
const currentProductTitle = ref('')
const currentProductCover = ref('')
const currentProductPrice = ref('')
const currentProductStatus = ref(1)

// ===== 消息 =====
const messages = ref([])
const loadingHistory = ref(false)
const msgScroll = ref(null)

// ===== WebSocket =====
const { isConnected, connect, send, cleanup } = useChatWebSocket()

/*
 * 加载当前用户资料（获取头像）
 */
const loadMyProfile = async () => {
  try {
    const res = await getProfile()
    myAvatar.value = res.data?.avatarUrl || ''
  } catch {}
}

// 加载会话列表
const fetchConversations = async () => {
  try { conversations.value = (await getConversations()).data || [] } catch {}
}

// 加载关联商品详情
const loadProduct = async (pid) => {
  if (!pid) return
  try {
    const d = (await getDetail(pid)).data
    currentProductId.value = d.id
    currentProductTitle.value = d.title
    currentProductCover.value = d.images?.[0] || ''
    currentProductPrice.value = d.price
    currentProductStatus.value = d.status
  } catch {}
}

/*
 * ===== 核心函数：构建消息对象（防止身份错乱的关键） =====
 *
 * 设计思路：
 * 1. isSelf 的唯一判断依据：消息的 senderUserId === 全局 loginUserId
 *    - loginUserId 由 Pinia 持久化存储，刷新页面不丢失
 *    - 关闭窗口重开会话时，历史消息重新从后端拉取，isSelf 重新动态计算
 *    - 不依赖 senderType（后端不存储）、不缓存 isSelf、不本地存储
 * 2. senderType 后端数据库不存储此字段，因此前端不做展示
 *    - 气泡左右位置仅由 isSelf 决定，与买家/卖家身份无关
 *
 * @param {Object} raw - 后端返回的原始消息
 * @returns 标准消息实体
 */
const buildMsg = (raw) => {
  /*
   * isSelf 动态计算（永远实时比对，不缓存、不预存）：
   * 统一转为 String 比较，彻底杜绝 Number vs String 类型不一致导致的误判
   * 只要 senderUserId === loginUserId，就是自己发的消息 → 靠右橙色气泡
   * 否则是对方发的消息 → 靠左灰色气泡
   */
  const uid = loginUserId.value
  const isSelf = uid != null && String(raw.senderId) === String(uid)

  return {
    msgId: String(raw.id || raw.msgId || Date.now()),
    content: raw.content || '',
    msgTime: raw.createTime || raw.msgTime || new Date().toISOString(),
    senderId: String(raw.senderId || ''),
    isSelf,
    readStatus: raw.isRead === 1 ? 'READ' : 'UNREAD',
  }
}

/*
 * 打开会话：加载历史消息 → 标记已读 → 加载商品信息
 */
const openChat = async (conv) => {
  targetUserId.value = conv.userId
  targetNickname.value = conv.nickname
  targetAvatar.value = conv.avatarUrl || ''

  loadingHistory.value = true

  // 标记已读 + 清除红点
  try { await markRead(conv.userId); fetchConversations() } catch {}

  // 加载历史消息（点进聊天框即已读）
  const pid = conv.productId || route.query.productId || ''
  try {
    const res = await getHistory(conv.userId, pid)
    // 历史消息全部标记为已读
    messages.value = (res.data || []).map(raw => buildMsg({ ...raw, isRead: 1 }))
  } catch { messages.value = [] }

  loadingHistory.value = false

  // 加载商品信息
  if (pid) {
    currentProductId.value = pid
    if (conv.productTitle) {
      currentProductTitle.value = conv.productTitle
      currentProductCover.value = conv.productCover || ''
      currentProductPrice.value = conv.productPrice || ''
      currentProductStatus.value = conv.productStatus || 1
    } else {
      loadProduct(pid)
    }
  }

  scrollToBottom()
}

/*
 * 关闭当前会话（移动端返回列表）
 */
const closeChat = () => {
  targetUserId.value = null
  messages.value = []
}

/*
 * 发送消息
 * isSelf 自动赋值 true；senderType 由当前用户身份决定
 */
const handleSendMsg = (content) => {
  if (!targetUserId.value || !content) return

  const success = send(targetUserId.value, content, currentProductId.value || '')
  if (!success) {
    // WebSocket 未连接时的降级处理
  }

  /*
   * 乐观更新：立即追加到消息列表
   * senderId 固定赋值为 loginUserId（全局唯一，Pinia存储）
   * isSelf 自动为 true（自己发的消息）
   */
  const newMsg = {
    msgId: String(Date.now()),
    content,
    msgTime: new Date().toISOString(),
    senderId: String(loginUserId.value),
    isSelf: true,
    readStatus: 'UNREAD',
  }
  messages.value.push(newMsg)
  scrollToBottom()
}

/*
 * 收到 WebSocket 推送：自动追加消息，isSelf 自动判断
 */
const onWsMessage = (data) => {
  // 只处理当前会话的消息
  // 只接收对方发来的消息，自己发的已通过乐观更新展示，避免重复
  if (String(data.senderId) !== String(targetUserId.value)) return
  const msg = buildMsg(data)
  messages.value.push(msg)
  scrollToBottom()
  // 收到新消息刷新会话列表
  fetchConversations()
}

/*
 * 滚动到底部
 */
const scrollToBottom = () => {
  nextTick(() => {
    const el = msgScroll.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

// 上拉加载更多历史（预留）
const onScroll = () => {}

// ===== 初始化 =====
onMounted(async () => {
  loadMyProfile()
  await fetchConversations()
  // 建立 WebSocket 并注册消息回调
  connect(onWsMessage)

  // 如果从商品详情跳转过来（携带 userId）
  if (route.query.userId) {
    const uid = Number(route.query.userId)
    let conv = conversations.value.find(c => c.userId == uid)
    if (!conv) {
      conv = { userId: uid, nickname: route.query.nickname || '用户', avatarUrl: '' }
    }
    if (route.query.productId) {
      conv.productId = route.query.productId
    }
    openChat(conv)
  }
})
</script>

<style scoped>
/* ===== 整体容器 ===== */
.chat-page {
  max-width: 1100px;
  margin: 0 auto;
  height: calc(100vh - 120px);
  padding: 0;
}

.chat-container {
  display: flex;
  height: 100%;
  background: #fff;
  border-radius: 8px;
  overflow: hidden;
  box-shadow: 0 1px 6px rgba(0, 0, 0, 0.06);
}

/* ===== 侧边栏（大屏显示，小屏隐藏） ===== */
.chat-side {
  display: flex;
}

/* ===== 右侧聊天面板 ===== */
.chat-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  background: #EDEDED;
  min-width: 0;
}

/* 头部导航 */
.chat-header {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 16px;
  background: #fff;
  border-bottom: 1px solid #e8e8e8;
  flex-shrink: 0;
}

.back-btn {
  display: none; /* PC端隐藏，移动端显示 */
}

.header-info {
  display: flex;
  align-items: center;
  gap: 6px;
}

.header-name {
  font-weight: 600;
  font-size: 15px;
}

.header-tag {
  font-size: 11px;
  color: #FF7D00;
  background: #FFF0E0;
  padding: 1px 6px;
  border-radius: 3px;
}

/* 商品卡片 */
.product-card-link {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 16px;
  background: #fff;
  border-bottom: 1px solid #eee;
  text-decoration: none;
  flex-shrink: 0;
  transition: background 0.15s;
}
.product-card-link:hover { background: #fafafa; }

.product-img-box {
  width: 44px; height: 44px;
  border-radius: 6px; overflow: hidden;
  background: #f0f0f0; flex-shrink: 0;
}
.product-img-box img { width: 100%; height: 100%; object-fit: cover; }

.product-info-box { flex: 1; min-width: 0; }
.product-name { font-size: 13px; color: #333; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.product-meta { display: flex; align-items: center; gap: 8px; margin-top: 2px; }
.product-price { font-size: 14px; font-weight: 700; color: #FF7D00; }
.product-status { font-size: 11px; padding: 1px 6px; border-radius: 3px; }
.st-on { background: #FFF0E0; color: #FF7D00; }
.st-off { background: #f0f0f0; color: #999; }

/* 消息滚动区 */
.msg-scroll {
  flex: 1;
  overflow-y: auto;
  padding: 16px 20px;
}

.loading-hint, .empty-hint {
  text-align: center;
  color: #bbb;
  font-size: 13px;
  padding: 40px 0;
}

/* 空状态 */
.chat-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: #bbb;
  gap: 12px;
  font-size: 14px;
}

/* ===== 响应式：小屏幕隐藏侧边栏 ===== */
@media (max-width: 768px) {
  .chat-page { height: calc(100vh - 60px); margin: 0 -20px; }
  .chat-container { border-radius: 0; }

  .chat-side {
    display: none;
  }

  .chat-side.show {
    display: flex;
    position: absolute;
    inset: 0;
    z-index: 10;
    width: 100%;
  }

  .back-btn {
    display: inline-flex;
  }
}
</style>
