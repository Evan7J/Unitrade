<template>
  <Teleport to="body">
    <Transition name="panel-slide">
      <div v-if="visible" class="chat-panel-wrapper">
        <div class="chat-panel-mask" @click="$emit('close')"></div>
        <div class="chat-panel">
          <!-- 顶部 -->
          <div class="chat-top-bar">
            <div class="flex items-center gap-2.5 min-w-0 flex-1">
              <el-avatar :size="38" :src="targetAvatar"><el-icon :size="20"><User /></el-icon></el-avatar>
              <span class="text-sm font-bold text-text-main truncate">{{ targetNickname || '对方' }}</span>
            </div>
            <el-button :icon="Close" circle size="small" @click="$emit('close')" />
          </div>

          <!-- 商品卡片 -->
          <router-link v-if="productId && productTitle" :to="'/front/goodsDetail?id=' + productId" class="product-card-bar">
            <div class="product-card-img"><img :src="productCover || '/vite.svg'" @error="e => e.target.src = '/vite.svg'" /></div>
            <div class="product-card-info">
              <p class="product-card-title line-clamp-1">{{ productTitle }}</p>
              <div class="product-card-bottom">
                <span class="product-card-price">¥{{ productPrice || '--' }}</span>
                <span class="product-card-status" :class="productStatusClass">{{ productStatusText }}</span>
              </div>
            </div>
            <el-icon :size="16" color="#ccc"><ArrowRight /></el-icon>
          </router-link>

          <!-- 消息列表 -->
          <div class="chat-panel-messages" ref="msgContainer">
            <div v-if="messages.length === 0" class="chat-empty">
              <el-icon :size="40" color="#ddd"><ChatDotRound /></el-icon>
              <p>打个招呼吧，关于「{{ productTitle || '商品' }}」想了解什么？</p>
            </div>
            <template v-for="(msg, i) in messages" :key="msg.id">
              <div v-if="showTimeTag(i, msg)" class="time-tag">{{ formatTime(msg.createTime) }}</div>
              <div class="chat-msg" :class="String(msg.senderId) === String(loginUserId) ? 'sent' : 'received'">
                <template v-if="String(msg.senderId) !== String(loginUserId)">
                  <el-avatar :size="34" :src="targetAvatar" class="msg-avatar"><el-icon :size="16"><User /></el-icon></el-avatar>
                  <div class="msg-main"><div class="msg-bubble received-bubble">{{ msg.content }}</div></div>
                </template>
                <template v-else>
                  <div class="msg-main sent-main">
                    <div class="msg-bubble sent-bubble">{{ msg.content }}</div>
                    <span class="msg-read-tag">{{ msg.isRead === 1 ? '已读' : '未读' }}</span>
                  </div>
                  <el-avatar :size="34" :src="myAvatar" class="msg-avatar"><el-icon :size="16"><User /></el-icon></el-avatar>
                </template>
              </div>
            </template>
          </div>

          <!-- 输入区 -->
          <div class="chat-panel-input">
            <div class="input-row">
              <el-input v-model="inputMsg" placeholder="输入消息..." :rows="1" type="textarea" resize="none" class="msg-input" @keyup.enter.exact="sendMsg" />
              <el-button type="primary" size="small" @click="sendMsg" class="send-btn">发送</el-button>
            </div>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup>
import { ref, watch, nextTick, onUnmounted, computed } from 'vue'
import { Close } from '@element-plus/icons-vue'
import { getHistory } from '../api/chat'
import { getProfile } from '../api/user'
import { useUserStore } from '../stores/user'
import { ElMessage } from 'element-plus'

const userStore = useUserStore()
const loginUserId = computed(() => userStore.loginUserId)

const props = defineProps({
  visible: { type: Boolean, default: false },
  targetUserId: { type: [Number, String], required: true },
  targetNickname: { type: String, default: '' },
  targetAvatar: { type: String, default: '' },
  productId: { type: [Number, String], default: '' },
  productTitle: { type: String, default: '' },
  productCover: { type: String, default: '' },
  productPrice: { type: [Number, String], default: '' },
  productStatus: { type: Number, default: 1 },
})

defineEmits(['close'])

const messages = ref([])
const inputMsg = ref('')
const msgContainer = ref(null)
const ws = ref(null)
const myAvatar = ref('')

const productStatusText = computed(() => ({ 1: '在售', 2: '已售出', 3: '已下架' }[props.productStatus] || '在售'))
const productStatusClass = computed(() => props.productStatus === 1 ? 'status-selling' : 'status-done')

const loadMyProfile = async () => {
  try { myAvatar.value = (await getProfile()).data?.avatarUrl || '' } catch { myAvatar.value = '' }
}

const loadHistory = async () => {
  if (!props.targetUserId) return
  try { const res = await getHistory(props.targetUserId, props.productId); messages.value = (res.data || []).map(m => ({ ...m, isRead: 1 })); scrollToBottom() } catch { messages.value = [] }
}

const connectWs = () => {
  const token = localStorage.getItem('token')
  if (!token) return
  cleanupWs()
  ws.value = new WebSocket('ws://localhost:8080/ws/chat/' + token)
  ws.value.onmessage = (e) => {
    try {
      const data = JSON.parse(e.data)
      if (data.senderId != loginUserId.value && data.senderId == props.targetUserId) {
        messages.value.push({ id: data.id || Date.now(), senderId: data.senderId, content: data.content, createTime: data.createTime || new Date().toISOString() })
        scrollToBottom()
      }
    } catch {}
  }
}

const sendMsg = () => {
  const content = inputMsg.value.trim()
  if (!content || !props.targetUserId) return
  if (!ws.value || ws.value.readyState !== WebSocket.OPEN) { ElMessage.warning('连接已断开'); return }
  ws.value.send(`${props.targetUserId}:${content}:${props.productId || ''}:text`)
  messages.value.push({ id: Date.now(), senderId: loginUserId.value, content, isRead: 0, createTime: new Date().toISOString() })
  inputMsg.value = ''
  scrollToBottom()
}

const scrollToBottom = () => nextTick(() => { if (msgContainer.value) msgContainer.value.scrollTop = msgContainer.value.scrollHeight })
const showTimeTag = (i, msg) => {
  if (i === 0) return true
  const prev = messages.value[i - 1]
  return !prev || Math.abs(new Date(msg.createTime) - new Date(prev.createTime)) > 300000
}
const formatTime = (t) => {
  if (!t) return ''; const d = new Date(t), pad = n => String(n).padStart(2, '0')
  if (d.toDateString() === new Date().toDateString()) return `${pad(d.getHours())}:${pad(d.getMinutes())}`
  if (d.getFullYear() === new Date().getFullYear()) return `${d.getMonth()+1}/${d.getDate()}`
  return `${d.getFullYear()}/${d.getMonth()+1}/${d.getDate()}`
}
const cleanupWs = () => { if (ws.value) { try { ws.value.close() } catch {}; ws.value = null } }

watch(() => [props.visible, props.targetUserId], ([visible, uid]) => {
  if (visible && uid) { loadMyProfile(); loadHistory(); connectWs() } else { cleanupWs() }
})
onUnmounted(cleanupWs)
</script>

<style scoped>
.chat-panel-wrapper { position: fixed; inset: 0; z-index: 200; display: flex; justify-content: flex-end; }
.chat-panel-mask { position: absolute; inset: 0; background: rgba(0,0,0,0.3); }
.chat-panel { position: relative; width: 420px; max-width: 92vw; height: 100%; background: #EDEDED; display: flex; flex-direction: column; box-shadow: -2px 0 20px rgba(0,0,0,0.15); }
.chat-top-bar { display: flex; align-items: center; justify-content: space-between; padding: 12px 16px; background: #fff; border-bottom: 1px solid #e5e5e5; flex-shrink: 0; }
.product-card-bar { display: flex; align-items: center; gap: 10px; padding: 10px 16px; background: #fff; border-bottom: 1px solid #eee; text-decoration: none; flex-shrink: 0; }
.product-card-bar:hover { background: #fafafa; }
.product-card-img { width: 48px; height: 48px; border-radius: 6px; overflow: hidden; background: #f0f0f0; flex-shrink: 0; }
.product-card-img img { width: 100%; height: 100%; object-fit: cover; }
.product-card-info { flex: 1; min-width: 0; }
.product-card-title { font-size: 13px; color: #333; margin-bottom: 4px; }
.product-card-bottom { display: flex; align-items: center; gap: 8px; }
.product-card-price { font-size: 15px; font-weight: 700; color: #FF7D00; }
.product-card-status { font-size: 11px; padding: 1px 6px; border-radius: 3px; }
.status-selling { background: #FFF0E0; color: #FF7D00; }
.status-done { background: #f0f0f0; color: #999; }
.chat-panel-messages { flex: 1; overflow-y: auto; padding: 14px 16px; display: flex; flex-direction: column; gap: 6px; }
.chat-empty { text-align: center; color: #bbb; padding-top: 60px; font-size: 13px; }
.time-tag { text-align: center; font-size: 11px; color: #b0b0b0; padding: 8px 0; }
.chat-msg { display: flex; align-items: flex-start; gap: 8px; max-width: 90%; }
.chat-msg.sent { align-self: flex-end; }
.chat-msg.received { align-self: flex-start; }
.msg-avatar { flex-shrink: 0; border: 1px solid #e0e0e0; margin-top: 2px; }
.msg-main { max-width: 75%; }
.msg-bubble { padding: 9px 13px; border-radius: 8px; font-size: 14px; line-height: 1.55; word-break: break-word; }
.received-bubble { background: #fff; color: #333; border-top-left-radius: 2px; }
.sent-bubble { background: #95EC69; color: #000; border-top-right-radius: 2px; }
.msg-read-tag { font-size: 10px; color: #c0c0c0; text-align: right; margin-top: 2px; }
.chat-panel-input { padding: 10px 12px; background: #f7f7f7; border-top: 1px solid #e0e0e0; flex-shrink: 0; }
.input-row { display: flex; gap: 8px; align-items: flex-end; }
.msg-input :deep(.el-textarea__inner) { border-radius: 6px; background: #fff; font-size: 14px; padding: 8px 12px; line-height: 1.4; }
.send-btn { flex-shrink: 0; height: 36px; width: 60px; background: #FF7D00; border-color: #FF7D00; }
.panel-slide-enter-active, .panel-slide-leave-active { transition: all 0.25s ease; }
.panel-slide-enter-active .chat-panel, .panel-slide-leave-active .chat-panel { transition: transform 0.25s ease; }
.panel-slide-enter-from .chat-panel, .panel-slide-leave-to .chat-panel { transform: translateX(100%); }
.panel-slide-enter-active .chat-panel-mask, .panel-slide-leave-active .chat-panel-mask { transition: opacity 0.25s ease; }
.panel-slide-enter-from .chat-panel-mask, .panel-slide-leave-to .chat-panel-mask { opacity: 0; }
</style>
